package com.dminus14.app.feature.home

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.InterviewSessionAlreadyEndedException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.model.InterviewResumeState
import com.dminus14.app.domain.usecase.AbandonInterviewUseCase
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewResumeUseCase
import com.dminus14.app.domain.usecase.RetainInterviewSessionForCleanupUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 로컬 세션 확인, 재개와 재시작 작업을 ViewModel 수명 안에서 수행한다. */
internal class HomeSessionHandler(
    private val context: MviContext<HomeState, HomeMutation, HomeEffect>,
    private val getInterviewResumeUseCase: GetInterviewResumeUseCase,
    private val getInterviewProgressUseCase: GetInterviewProgressUseCase,
    private val getInterviewElapsedTimeUseCase: GetInterviewElapsedTimeUseCase,
    private val abandonInterviewUseCase: AbandonInterviewUseCase,
    private val retainInterviewSessionForCleanupUseCase: RetainInterviewSessionForCleanupUseCase,
) {
    private var pendingSessionId: Long? = null
    private var checkJob: Job? = null

    fun checkSession() {
        checkJob?.cancel()
        checkJob =
            context.launch {
                val sessionId = getInterviewProgressUseCase()?.sessionId
                currentCoroutineContext().ensureActive()
                pendingSessionId = sessionId
                if (sessionId == null) {
                    context.dispatch(HomeMutation.SessionAbsent)
                    return@launch
                }
                getInterviewResumeUseCase(sessionId).rethrowHomeCancellation().fold(
                    onSuccess = { resume ->
                        when (resume.resumeState) {
                            InterviewResumeState.Resumable -> {
                                val elapsed = getInterviewElapsedTimeUseCase()
                                currentCoroutineContext().ensureActive()
                                context.dispatch(HomeMutation.SessionResumable(elapsed))
                            }

                            InterviewResumeState.Ended -> {
                                context.dispatch(HomeMutation.SessionAbsent)
                            }

                            is InterviewResumeState.Unknown -> {
                                Unit
                            }
                        }
                    },
                    onFailure = { context.handleHomeFailure(it) },
                )
            }
    }

    fun startSession() {
        when (context.state.value.sessionStartOverlay) {
            is HomeSessionStartOverlayState.InProgress -> {
                context.dispatch(HomeMutation.RestartRequested)
            }

            HomeSessionStartOverlayState.ConfirmRestart -> {
                confirmRestart()
            }

            else -> {
                if ((context.state.value.remainingTicketCount ?: 0) > 0) {
                    context.sendEffect(HomeEffect.GoToOnboardingInterviewRequested)
                    dismissOverlay()
                } else {
                    context.dispatch(HomeMutation.TicketsExhausted)
                }
            }
        }
    }

    fun resumeSession() {
        context.sendEffect(HomeEffect.GoToInterviewRequested)
        dismissOverlay()
    }

    fun dismissOverlay() {
        checkJob?.cancel()
        context.dispatch(HomeMutation.OverlayDismissed)
        context.sendEffect(HomeEffect.ReportSheetResetRequested)
    }

    /** 중단이 이미 완료된 응답도 성공으로 보고 기존 정리 예약 정책을 유지한다. */
    private fun confirmRestart() {
        val sessionId = pendingSessionId ?: return
        if (context.state.value.isLoading) return
        context.dispatch(HomeMutation.LoadStarted)
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
