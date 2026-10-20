package com.dminus14.app.feature.interview.error.handler

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.AiTemporarilyUnavailableException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewEndType
import com.dminus14.app.domain.model.InterviewResumeState
import com.dminus14.app.domain.model.InterviewTerminalStatus
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.usecase.ConfirmInterviewResumeUseCase
import com.dminus14.app.domain.usecase.GetInterviewMediaManifestUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewResumeUseCase
import com.dminus14.app.domain.usecase.SavePendingInterviewAnswerUseCase
import com.dminus14.app.domain.usecase.SubmitAnswerUseCase
import com.dminus14.app.feature.interview.api.InterviewErrorType
import com.dminus14.app.feature.interview.error.InterviewErrorEffect
import com.dminus14.app.feature.interview.error.InterviewErrorMutation
import com.dminus14.app.feature.interview.error.InterviewErrorOperation
import com.dminus14.app.feature.interview.error.InterviewErrorState
import com.dminus14.app.feature.interview.interview.InterviewRecoveryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 복구 가능 상태 조회, 재개와 보존된 동일 답변 재시도를 담당한다. */
@Suppress("LongParameterList")
internal class InterviewErrorResumeHandler(
    private val context:
        MviContext<InterviewErrorState, InterviewErrorMutation, InterviewErrorEffect>,
    private val getProgress: GetInterviewProgressUseCase,
    private val getManifest: GetInterviewMediaManifestUseCase,
    private val getResume: GetInterviewResumeUseCase,
    private val confirmResume: ConfirmInterviewResumeUseCase,
    private val submitAnswer: SubmitAnswerUseCase,
    private val savePendingAnswer: SavePendingInterviewAnswerUseCase,
    private val recoveryStore: InterviewRecoveryStore,
    private val operation: InterviewErrorOperation,
    private val onGlobalError: (GlobalAppEvent) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onRetain: (Long?, InterviewErrorEffect) -> Unit,
) {
    private val state get() = context.state
    var sessionId: Long? = null
        private set
    private var loadJob: Job? = null

    fun load(errorType: InterviewErrorType) {
        if (operation.isRunning || operation.isCompleted) return
        context.dispatch(InterviewErrorMutation.LoadStarted(errorType))
        loadJob?.cancel()
        loadJob =
            context.launch {
                val progress = getProgress()
                currentCoroutineContext().ensureActive()
                sessionId = progress?.sessionId
                if (progress == null) {
                    operation.complete()
                    context.sendEffect(InterviewErrorEffect.InterviewAbandonCompleted)
                    return@launch
                }
                if (errorType == InterviewErrorType.SERVER_TEMPORARY) {
                    val canRetryAnswerSubmission =
                        getManifest(progress.sessionId)?.pendingAnswer != null
                    currentCoroutineContext().ensureActive()
                    context.dispatch(
                        InterviewErrorMutation.PendingAnswerChecked(canRetryAnswerSubmission),
                    )
                }
                if (errorType == InterviewErrorType.NETWORK) checkResume(progress.sessionId)
            }
    }

    private suspend fun checkResume(sessionId: Long) {
        getResume(sessionId)
            .onSuccess { status ->
                currentCoroutineContext().ensureActive()
                when (status.resumeState) {
                    InterviewResumeState.Resumable -> {
                        context.dispatch(InterviewErrorMutation.ResumeAvailable)
                    }

                    InterviewResumeState.Ended -> {
                        handleTerminalStatus(status.status)
                    }

                    is InterviewResumeState.Unknown -> {
                        onGlobalError(GlobalAppEvent.ShowUnknownError)
                    }
                }
            }.onFailure { error ->
                currentCoroutineContext().ensureActive()
                onFailure(error)
            }
    }

    private fun handleTerminalStatus(status: InterviewTerminalStatus?) {
        when (status) {
            InterviewTerminalStatus.Invalid -> {
                context.dispatch(InterviewErrorMutation.SessionInvalid)
            }

            InterviewTerminalStatus.Completed,
            InterviewTerminalStatus.Abandoned,
            -> {
                onRetain(sessionId, InterviewErrorEffect.InterviewAbandonCompleted)
            }

            is InterviewTerminalStatus.Unknown,
            null,
            -> {
                onGlobalError(GlobalAppEvent.ShowUnknownError)
            }
        }
    }

    fun cancelLoad() {
        loadJob?.cancel()
    }

    @Suppress("ReturnCount")
    fun resume() {
        if (operation.isRunning || operation.isCompleted) return
        if (state.value.isLoading) return
        val currentSessionId = sessionId ?: return
        if (state.value.errorType == InterviewErrorType.NETWORK && !state.value.canResume) {
            context.dispatch(InterviewErrorMutation.ResumeCheckStarted)
            loadJob = context.launch { checkResume(currentSessionId) }
            return
        }
        if (state.value.errorType == InterviewErrorType.SERVER_TEMPORARY &&
            !state.value.canRetryAnswerSubmission
        ) {
            context.dispatch(InterviewErrorMutation.AnswerRetryUnavailable)
            return
        }
        context.dispatch(InterviewErrorMutation.ResumeRequested)
        when (state.value.errorType) {
            InterviewErrorType.NETWORK -> confirmNetworkResume(currentSessionId)

            InterviewErrorType.SERVER_TEMPORARY -> retryPendingAnswer(currentSessionId)

            InterviewErrorType.MIC_DEVICE,
            InterviewErrorType.STT,
            -> Unit
        }
    }

    private fun confirmNetworkResume(sessionId: Long) {
        operation.launch(context) {
            confirmResume(sessionId)
                .onSuccess { result ->
                    currentCoroutineContext().ensureActive()
                    if (result.sessionEnded) {
                        if (result.status == InterviewTerminalStatus.Invalid ||
                            result.endType == InterviewEndType.SttReset
                        ) {
                            context.dispatch(InterviewErrorMutation.SessionInvalid)
                        } else {
                            operation.complete()
                            recoveryStore.publish(
                                InterviewRecoveryStore.Result(
                                    nextQuestion = result.nextQuestion,
                                    sessionEnded = true,
                                    wrapUpMessage = result.wrapUpMessage,
                                    endType = result.endType,
                                    reportGenerating = false,
                                ),
                            )
                            context.sendEffect(InterviewErrorEffect.InterviewResumeConfirmed)
                        }
                    } else {
                        val nextQuestion = result.nextQuestion
                        if (nextQuestion == null) {
                            onGlobalError(GlobalAppEvent.ShowUnknownError)
                        } else {
                            operation.complete()
                            recoveryStore.publish(
                                InterviewRecoveryStore.Result(
                                    nextQuestion = nextQuestion,
                                    sessionEnded = false,
                                    wrapUpMessage = null,
                                    endType = null,
                                    reportGenerating = false,
                                ),
                            )
                            context.sendEffect(InterviewErrorEffect.InterviewResumeConfirmed)
                        }
                    }
                }.onFailure { error ->
                    currentCoroutineContext().ensureActive()
                    onFailure(error)
                }
        }
    }

    private fun retryPendingAnswer(sessionId: Long) {
        operation.launch(context) {
            val command = getManifest(sessionId)?.pendingAnswer
            currentCoroutineContext().ensureActive()
            if (command == null) {
                context.dispatch(InterviewErrorMutation.PendingAnswerMissing)
                return@launch
            }
            submitAnswer(command)
                .onSuccess { result ->
                    currentCoroutineContext().ensureActive()
                    savePendingAnswer(null)
                    currentCoroutineContext().ensureActive()
                    operation.complete()
                    publishAnswerResult(result)
                    context.sendEffect(InterviewErrorEffect.AnswerSubmissionRecovered)
                }.onFailure { error ->
                    currentCoroutineContext().ensureActive()
                    when (error) {
                        is CancellationException -> {
                            throw error
                        }

                        is AiTemporarilyUnavailableException,
                        is NetworkUnavailableException,
                        -> {
                            context.dispatch(InterviewErrorMutation.AnswerRetryFailed)
                        }

                        is ServerException -> {
                            onGlobalError(GlobalAppEvent.ShowServerErrorAndExit)
                        }

                        else -> {
                            onGlobalError(GlobalAppEvent.ShowUnknownError)
                        }
                    }
                }
        }
    }

    private fun publishAnswerResult(result: SubmitAnswerResult) {
        operation.complete()
        recoveryStore.publish(
            InterviewRecoveryStore.Result(
                nextQuestion = result.nextQuestion,
                sessionEnded = result.sessionEnded,
                wrapUpMessage = result.wrapUpMessage,
                endType = result.endType,
                reportGenerating = result.reportGenerating,
            ),
        )
    }
}
