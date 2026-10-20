package com.dminus14.app.feature.interview.error.handler

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.InterviewSessionAlreadyEndedException
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.usecase.AbandonInterviewUseCase
import com.dminus14.app.domain.usecase.RetainInterviewSessionForCleanupUseCase
import com.dminus14.app.feature.interview.api.InterviewErrorType
import com.dminus14.app.feature.interview.error.InterviewErrorEffect
import com.dminus14.app.feature.interview.error.InterviewErrorMutation
import com.dminus14.app.feature.interview.error.InterviewErrorOperation
import com.dminus14.app.feature.interview.error.InterviewErrorState
import com.dminus14.app.feature.interview.interview.InterviewCompletionReason
import com.dminus14.app.feature.interview.interview.InterviewRecoveryStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 중단 요청과 보존 기한 정리 인계를 담당한다. */
internal class InterviewErrorAbortHandler(
    private val context:
        MviContext<InterviewErrorState, InterviewErrorMutation, InterviewErrorEffect>,
    private val abandonInterview: AbandonInterviewUseCase,
    private val retainSessionForCleanup: RetainInterviewSessionForCleanupUseCase,
    private val recoveryStore: InterviewRecoveryStore,
    private val operation: InterviewErrorOperation,
    private val onFailure: (Throwable) -> Unit,
) {
    private val state get() = context.state

    fun abort(sessionId: Long?) {
        if (operation.isRunning || operation.isCompleted) return
        val currentSessionId = sessionId
        if (currentSessionId == null) {
            operation.complete()
            context.sendEffect(InterviewErrorEffect.InterviewAbandonCompleted)
            return
        }
        when (state.value.errorType) {
            InterviewErrorType.MIC_DEVICE -> {
                retainAndFinish(sessionId, InterviewErrorEffect.InterviewAbandonCompleted)
            }

            InterviewErrorType.STT -> {
                retainAndFinish(sessionId, InterviewErrorEffect.SttFailureAcknowledged)
            }

            InterviewErrorType.NETWORK -> {
                abandon(currentSessionId, InterviewAbandonRequestCause.NetworkDisconnect)
            }

            InterviewErrorType.SERVER_TEMPORARY -> {
                abandon(currentSessionId, InterviewAbandonRequestCause.UserExit)
            }
        }
    }

    private fun abandon(
        sessionId: Long,
        cause: InterviewAbandonRequestCause,
    ) {
        context.dispatch(InterviewErrorMutation.AbandonStarted)
        operation.launch(context) {
            abandonInterview(sessionId, cause)
                .onSuccess { result ->
                    currentCoroutineContext().ensureActive()
                    if (result.reportGenerating) {
                        operation.complete()
                        recoveryStore.publish(
                            InterviewRecoveryStore.Result(
                                nextQuestion = null,
                                sessionEnded = true,
                                wrapUpMessage = null,
                                endType = null,
                                reportGenerating = true,
                                completionReason = InterviewCompletionReason.ABANDONED,
                            ),
                        )
                        context.sendEffect(InterviewErrorEffect.InterviewResumeConfirmed)
                    } else {
                        retainAndComplete(sessionId, InterviewErrorEffect.InterviewAbandonCompleted)
                    }
                }.onFailure { error ->
                    currentCoroutineContext().ensureActive()
                    if (error is InterviewSessionAlreadyEndedException) {
                        retainAndComplete(sessionId, InterviewErrorEffect.InterviewAbandonCompleted)
                    } else {
                        onFailure(error)
                    }
                }
        }
    }

    fun retainAndFinish(
        currentSessionId: Long?,
        effect: InterviewErrorEffect,
    ) {
        operation.launch(context) { retainAndComplete(currentSessionId, effect) }
    }

    private suspend fun retainAndComplete(
        currentSessionId: Long?,
        effect: InterviewErrorEffect,
    ) {
        if (currentSessionId != null) retainSessionForCleanup(currentSessionId)
        currentCoroutineContext().ensureActive()
        operation.complete()
        context.sendEffect(effect)
    }
}
