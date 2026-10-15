package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.usecase.SaveInterviewWrapUpRangeUseCase
import com.dminus14.app.feature.interview.InterviewConstants
import com.dminus14.app.feature.interview.api.InterviewErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** 마무리 녹화 확정과 업로드 인계, 종료 결과를 관리한다. */
@Suppress("TooManyFunctions")
internal class InterviewFinalizationHandler(
    private val context: MviContext<InterviewState, InterviewMutation, InterviewEffect>,
    private val saveWrapUpRange: SaveInterviewWrapUpRangeUseCase,
    private val workflow: (InterviewWorkflow) -> Unit,
) {
    private val state get() = context.state
    private var completionReason = InterviewCompletionReason.COMPLETED
    private var wrapUpStartedAtMillis: Long? = null
    private var isWrapUpRecording = false
    private var isWrapUpRetryPending = false
    private var finalizationWatchdogJob: Job? = null
    private var wrapUpPayload: String? = null
    private var lastUploadNetworkPolicy: InterviewUploadNetworkPolicy? = null
    private var isFinalOutcomeEmitted = false

    fun handle(intent: InterviewIntent.Finalization) {
        when (intent) {
            InterviewIntent.Finalization.ReportRecordingFailure -> {
                onRecordingFailure()
            }

            InterviewIntent.Finalization.ConfirmMeteredUpload -> {
                context.dispatch(InterviewMutation.MeteredUploadConfirmationChanged(false))
                enqueueUpload(InterviewUploadNetworkPolicy.CONNECTED)
            }

            InterviewIntent.Finalization.DismissMeteredUpload -> {
                context.dispatch(InterviewMutation.MeteredUploadConfirmationChanged(false))
                enqueueUpload(InterviewUploadNetworkPolicy.UNMETERED)
            }

            is InterviewIntent.Finalization.ReportUploadNotificationPermission -> {
                if (!intent.isGranted) {
                    context.sendEffect(InterviewEffect.ShowUploadNotificationPermissionDenied)
                }
                context.sendEffect(InterviewEffect.CheckUploadNetwork)
            }

            is InterviewIntent.Finalization.ReportUploadNetworkMetered -> {
                if (intent.isMetered) {
                    context.dispatch(InterviewMutation.MeteredUploadConfirmationChanged(true))
                } else {
                    enqueueUpload(InterviewUploadNetworkPolicy.UNMETERED)
                }
            }

            InterviewIntent.Finalization.ReportVideoUploadEnqueued -> {
                finishUploadHandoff()
            }

            InterviewIntent.Finalization.ReportVideoUploadEnqueueFailure -> {
                if (!isFinalOutcomeEmitted) {
                    context.dispatch(InterviewMutation.UploadHandoffFailed)
                }
            }

            InterviewIntent.Finalization.ClickRetryFinalization -> {
                retryFinalization()
            }

            InterviewIntent.Finalization.ClickExitFinalization -> {
                exitFinalization()
            }

            InterviewIntent.Finalization.ReportWrapUpPlaybackCompleted,
            InterviewIntent.Finalization.ReportWrapUpPlaybackFailure,
            -> {
                finishWrapUpPlayback()
            }
        }
    }

    fun beginFinishing(
        wrapUpPayload: String?,
        reportGenerating: Boolean,
    ) {
        this.wrapUpPayload = wrapUpPayload
        context.dispatch(InterviewMutation.FinishingStarted(reportGenerating))
        if (wrapUpPayload.isNullOrBlank()) {
            completeInterview()
        } else {
            startWrapUpRecording(wrapUpPayload)
        }
    }

    private fun startWrapUpRecording(payload: String) {
        val sessionId = state.value.sessionId ?: return
        wrapUpStartedAtMillis = state.value.elapsedMillis
        isWrapUpRecording = true
        startFinalizationWatchdog()
        context.sendEffect(
            InterviewEffect.StartRecordingSegment(
                sessionId = sessionId,
                type = InterviewMediaSegmentType.QUESTION_VIDEO,
                questionId = null,
                startedAtMillis = state.value.elapsedMillis,
            ),
        )
        context.sendEffect(InterviewEffect.PlayWrapUpMessage(payload))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun finishWrapUpPlayback() {
        val sessionId = state.value.sessionId ?: return
        val startMillis = wrapUpStartedAtMillis ?: state.value.elapsedMillis
        context.launch {
            try {
                saveWrapUpRange(sessionId, startMillis, state.value.elapsedMillis)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                isWrapUpRecording = false
                finalizationWatchdogJob?.cancel()
                finalizationWatchdogJob = null
                workflow(InterviewWorkflow.FatalExit)
            } finally {
                context.sendEffect(InterviewEffect.StopRecordingSegment)
            }
        }
    }

    private fun completeInterview() {
        if (isFinalOutcomeEmitted) return
        workflow(InterviewWorkflow.StopTicker)
        val sessionId = state.value.sessionId ?: return
        if (state.value.reportGenerating) {
            context.dispatch(InterviewMutation.UploadPermissionRequested)
            context.sendEffect(InterviewEffect.RequestUploadNotificationPermission)
        } else {
            emitFinalOutcome(InterviewEffect.InterviewEnded(completionReason, sessionId))
        }
    }

    private fun enqueueUpload(policy: InterviewUploadNetworkPolicy) {
        if (isFinalOutcomeEmitted) return
        val sessionId = state.value.sessionId ?: return
        lastUploadNetworkPolicy = policy
        context.dispatch(InterviewMutation.UploadHandoffStarted)
        context.sendEffect(InterviewEffect.EnqueueVideoUpload(sessionId, policy))
    }

    private fun finishUploadHandoff() {
        if (isFinalOutcomeEmitted) return
        val sessionId = state.value.sessionId ?: return
        context.dispatch(InterviewMutation.UploadEnqueued)
        emitFinalOutcome(InterviewEffect.InterviewEnded(completionReason, sessionId))
    }

    private fun startFinalizationWatchdog() {
        finalizationWatchdogJob?.cancel()
        finalizationWatchdogJob =
            context.launch {
                delay(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
                if (!isFinalOutcomeEmitted && isWrapUpRecording) {
                    context.dispatch(InterviewMutation.RecordingFinalizationTimedOut)
                    context.sendEffect(InterviewEffect.StopRecordingSegment)
                }
            }
    }

    @Suppress("ReturnCount")
    private fun onRecordingFailure() {
        if (state.value.screenState != InterviewScreenState.FINISHING) {
            context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
            return
        }
        if (isFinalOutcomeEmitted) return
        isWrapUpRecording = false
        finalizationWatchdogJob?.cancel()
        finalizationWatchdogJob = null
        if (restartPendingWrapUpRecording()) return
        context.dispatch(InterviewMutation.RecordingFailed)
    }

    @Suppress("ReturnCount")
    private fun retryFinalization() {
        if (isFinalOutcomeEmitted) return
        when (state.value.finalizationFailure) {
            InterviewFinalizationFailure.RECORDING_FINALIZATION_TIMEOUT -> {
                isWrapUpRetryPending = true
                context.dispatch(InterviewMutation.FinalizationFailureCleared)
            }

            InterviewFinalizationFailure.RECORDING_FAILURE -> {
                val payload = wrapUpPayload ?: return
                context.dispatch(InterviewMutation.FinalizationFailureCleared)
                startWrapUpRecording(payload)
            }

            InterviewFinalizationFailure.UPLOAD_HANDOFF_FAILURE -> {
                val policy = lastUploadNetworkPolicy ?: return
                enqueueUpload(policy)
            }

            null -> {
                Unit
            }
        }
    }

    @Suppress("ReturnCount")
    private fun restartPendingWrapUpRecording(): Boolean {
        if (!isWrapUpRetryPending) return false
        isWrapUpRetryPending = false
        val payload = wrapUpPayload ?: return false
        startWrapUpRecording(payload)
        return true
    }

    private fun exitFinalization() {
        if (state.value.finalizationFailure == null || isFinalOutcomeEmitted) return
        emitFinalOutcome(InterviewEffect.FinalizationExitConfirmed)
    }

    private fun emitFinalOutcome(effect: InterviewEffect) {
        if (isFinalOutcomeEmitted) return
        isFinalOutcomeEmitted = true
        finalizationWatchdogJob?.cancel()
        finalizationWatchdogJob = null
        context.dispatch(InterviewMutation.FinalOutcomeEmitted)
        context.sendEffect(effect)
    }

    fun onWrapUpSegmentFinalized() {
        if (!isWrapUpRecording) return
        isWrapUpRecording = false
        finalizationWatchdogJob?.cancel()
        finalizationWatchdogJob = null
        context.dispatch(InterviewMutation.FinalizationFailureCleared)
        if (!restartPendingWrapUpRecording()) completeInterview()
    }

    fun updateCompletionReason(reason: InterviewCompletionReason) {
        completionReason = reason
    }
}
