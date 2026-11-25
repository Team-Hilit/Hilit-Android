package com.dminus14.app.feature.home

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.InterviewSessionAlreadyEndedException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewAbandonCause
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.model.InterviewEndType
import com.dminus14.app.domain.model.InterviewResumeConfirm
import com.dminus14.app.domain.model.InterviewResumeState
import com.dminus14.app.domain.model.InterviewResumeStatus
import com.dminus14.app.domain.model.InterviewTerminalStatus
import com.dminus14.app.domain.usecase.AbandonInterviewUseCase
import com.dminus14.app.domain.usecase.ConfirmInterviewResumeUseCase
import com.dminus14.app.domain.usecase.DeleteInterviewSessionUseCase
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewResumeUseCase
import com.dminus14.app.domain.usecase.RetainInterviewSessionForCleanupUseCase
import com.dminus14.app.domain.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 로컬 세션 확인, 재개와 재시작 작업을 ViewModel 수명 안에서 수행한다. */
@Suppress("LongParameterList")
internal class HomeSessionHandler(
    private val context: MviContext<HomeState, HomeMutation, HomeEffect>,
    private val getInterviewResumeUseCase: GetInterviewResumeUseCase,
    private val getInterviewProgressUseCase: GetInterviewProgressUseCase,
    private val getInterviewElapsedTimeUseCase: GetInterviewElapsedTimeUseCase,
    private val confirmResume: ConfirmInterviewResumeUseCase,
    private val deleteSession: DeleteInterviewSessionUseCase,
    private val abandonInterviewUseCase: AbandonInterviewUseCase,
    private val retainInterviewSessionForCleanupUseCase: RetainInterviewSessionForCleanupUseCase,
    private val refreshHome: () -> Unit,
) {
    private var pendingSessionId: Long? = null
    private var checkJob: Job? = null

    private var actionJob: Job? = null

    fun checkSession(frozenOnly: Boolean = false) {
        val isBusy = if (frozenOnly) checkJob?.isActive == true else context.state.value.isLoading
        if (isBusy || actionJob?.isActive == true) return
        checkJob?.cancel()
        if (!frozenOnly) pendingSessionId = null
        checkJob =
            context.launch {
                val progress = getInterviewProgressUseCase()
                if (frozenOnly && progress?.isFrozen != true) return@launch
                val sessionId = progress?.sessionId
                currentCoroutineContext().ensureActive()
                if (sessionId == null) {
                    context.dispatch(HomeMutation.SessionAbsent)
                    return@launch
                }
                getInterviewResumeUseCase(sessionId).rethrowHomeCancellation().fold(
                    onSuccess = { resume -> handleResumeStatus(sessionId, resume) },
                    onFailure = { context.handleHomeFailure(it) },
                )
            }
    }

    private suspend fun handleResumeStatus(
        sessionId: Long,
        resume: InterviewResumeStatus,
    ) {
        when (resume.resumeState) {
            InterviewResumeState.Resumable -> {
                val elapsed = getInterviewElapsedTimeUseCase()
                currentCoroutineContext().ensureActive()
                pendingSessionId = sessionId
                context.dispatch(HomeMutation.SessionResumable(elapsed))
            }

            InterviewResumeState.Ended -> {
                when (resume.status) {
                    InterviewTerminalStatus.Invalid -> {
                        invalidateSession()
                    }

                    InterviewTerminalStatus.Completed,
                    InterviewTerminalStatus.Abandoned,
                    -> {
                        cleanEndedSession(sessionId)
                    }

                    else -> {
                        context.handleHomeFailure(
                            IllegalStateException("Unknown terminal status"),
                        )
                    }
                }
            }

            is InterviewResumeState.Unknown -> {
                context.handleHomeFailure(
                    IllegalStateException("Unknown resume status"),
                )
            }
        }
    }

    fun startSession() {
        val state = context.state.value
        if (state.isLoading) return
        when (state.sessionStartOverlay) {
            is HomeSessionStartOverlayState.InProgress -> {
                context.dispatch(HomeMutation.RestartRequested)
            }

            HomeSessionStartOverlayState.ConfirmRestart -> {
                confirmRestart()
            }

            else -> {
                if ((state.remainingTicketCount ?: 0) > 0) {
                    context.sendEffect(HomeEffect.GoToOnboardingInterviewRequested)
                    dismissOverlay()
                } else {
                    context.dispatch(HomeMutation.TicketsExhausted)
                }
            }
        }
    }

    fun resumeSession() {
        val sessionId = pendingSessionId ?: return
        if (context.state.value.sessionStartOverlay !is HomeSessionStartOverlayState.InProgress ||
            actionJob?.isActive == true
        ) {
            return
        }
        context.dispatch(HomeMutation.LoadStarted)
        actionJob =
            context.launch {
                if (getInterviewProgressUseCase()?.sessionId != sessionId) {
                    pendingSessionId = null
                    context.dispatch(HomeMutation.LoadStopped)
                    context.dispatch(HomeMutation.SessionAbsent)
                    refreshHome()
                    return@launch
                }
                confirmResume(sessionId).rethrowHomeCancellation().fold(
                    onSuccess = { result -> handleConfirmation(sessionId, result) },
                    onFailure = { error ->
                        if (error is InterviewSessionAlreadyEndedException) {
                            cleanEndedSession(sessionId)
                        } else {
                            context.handleHomeFailure(error)
                        }
                    },
                )
            }
    }

    private suspend fun handleConfirmation(
        sessionId: Long,
        result: InterviewResumeConfirm,
    ) {
        val question = result.nextQuestion
        when {
            !result.sessionEnded && question != null -> {
                pendingSessionId = null
                context.dispatch(HomeMutation.LoadStopped)
                closeOverlay()
                context.sendEffect(HomeEffect.ResumeConfirmed(sessionId, question.questionId))
            }

            !result.sessionEnded -> {
                context.handleHomeFailure(
                    IllegalStateException("Confirmed resume missing next question"),
                )
            }

            result.status == InterviewTerminalStatus.Invalid ||
                result.endType == InterviewEndType.SttReset -> {
                invalidateSession()
            }

            result.hasUnconfirmedEnd -> {
                context.handleHomeFailure(IllegalStateException("Unknown resume completion"))
            }

            else -> {
                cleanEndedSession(
                    sessionId,
                    result.abandonCause == InterviewAbandonCause.HoldExpired,
                )
            }
        }
    }

    private fun invalidateSession() {
        pendingSessionId = null
        context.dispatch(HomeMutation.RestartCompleted)
        context.sendEffect(HomeEffect.SessionInvalid)
    }

    private suspend fun cleanEndedSession(
        sessionId: Long,
        holdExpired: Boolean = false,
    ) {
        runCatchingCancellable {
            if (holdExpired) {
                deleteSession(sessionId)
                true
            } else {
                retainInterviewSessionForCleanupUseCase(sessionId)
            }
        }.rethrowHomeCancellation()
            .onSuccess { cleared ->
                pendingSessionId = null
                if (cleared) {
                    context.dispatch(HomeMutation.SessionEligibilityRefreshStarted)
                    refreshHome()
                } else {
                    context.dispatch(HomeMutation.LoadStopped)
                }
            }.onFailure { context.handleHomeFailure(it) }
    }

    fun dismissOverlay() {
        if (actionJob?.isActive == true) return
        closeOverlay()
    }

    private fun closeOverlay() {
        checkJob?.cancel()
        context.dispatch(HomeMutation.OverlayDismissed)
        context.sendEffect(HomeEffect.ReportSheetResetRequested)
    }

    /** 중단이 이미 완료된 응답도 성공으로 보고 기존 정리 예약 정책을 유지한다. */
    private fun confirmRestart() {
        val sessionId = pendingSessionId ?: return
        context.dispatch(HomeMutation.LoadStarted)
        actionJob =
            context.launch {
                val error =
                    abandonInterviewUseCase(sessionId, InterviewAbandonRequestCause.UserExit)
                        .rethrowHomeCancellation()
                        .exceptionOrNull()
                if (error == null || error is InterviewSessionAlreadyEndedException) {
                    retainInterviewSessionForCleanupUseCase(sessionId)
                    currentCoroutineContext().ensureActive()
                    context.dispatch(HomeMutation.RestartCompleted)
                    context.sendEffect(HomeEffect.GoToOnboardingInterviewRequested)
                } else {
                    context.handleHomeFailure(error)
                }
            }
    }
}

/** 홈에서 기존에 사용하던 전역 오류 경로를 보존한다. */
internal suspend fun MviContext<HomeState, HomeMutation, HomeEffect>.handleHomeFailure(
    error: Throwable,
) {
    if (error is CancellationException) throw error
    dispatch(HomeMutation.LoadStopped)
    GlobalErrorHandler.emit(
        when (error) {
            is NetworkUnavailableException -> GlobalAppEvent.ShowNetworkErrorAndExit
            is ServerException -> GlobalAppEvent.ShowServerErrorAndExit
            else -> GlobalAppEvent.ShowUnknownError
        },
    )
}

internal suspend fun <T> Result<T>.rethrowHomeCancellation(): Result<T> {
    currentCoroutineContext().ensureActive()
    val error = exceptionOrNull()
    if (error is CancellationException) throw error
    return this
}
