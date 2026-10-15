package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.usecase.CheckpointInterviewProgressUseCase
import com.dminus14.app.feature.interview.api.InterviewErrorType
import kotlinx.coroutines.Job

/** 앱 복귀와 네트워크 중단 체크포인트, 복구 및 종료 안내를 관리한다. */
internal class InterviewRecoveryHandler(
    private val context: MviContext<InterviewState, InterviewMutation, InterviewEffect>,
    private val checkpointProgress: CheckpointInterviewProgressUseCase,
    private val recoveryStore: InterviewRecoveryStore,
    private val fatalExitPrompt: InterviewFatalExitPrompt,
    private val workflow: (InterviewWorkflow) -> Unit,
) {
    private val state get() = context.state
    var connectionInterrupted = false
        private set
    private var wasBackgrounded = false
    private var pendingRecoveryResult: InterviewRecoveryStore.Result? = null
    private var fatalExitJob: Job? = null

    fun handle(intent: InterviewIntent.Recovery) {
        when (intent) {
            InterviewIntent.Recovery.ReportAppBackgrounded -> {
                onBackgrounded()
            }

            InterviewIntent.Recovery.ReportAppForegrounded -> {
                onForegrounded()
            }

            InterviewIntent.Recovery.ReportNetworkDisconnected -> {
                onNetworkDisconnected()
            }

            InterviewIntent.Recovery.ReportNetworkRestored -> {
                Unit
            }

            InterviewIntent.Recovery.ConsumeRecoveryResult -> {
                consumeRecoveryResult()
            }
        }
    }

    fun beginFatalExit() {
        workflow(InterviewWorkflow.ClearSubmissionBusyState)
        context.dispatch(InterviewMutation.EndDialogsClosed)
        if (fatalExitJob != null) return
        fatalExitJob =
            context.launch {
                if (fatalExitPrompt.show() == GlobalModalResult.Confirm) {
                    context.sendEffect(InterviewEffect.FatalExitConfirmed)
                }
            }
    }

    private fun onBackgrounded() {
        if (!state.value.screenState.isActiveInterview) return
        wasBackgrounded = true
        workflow(InterviewWorkflow.StopTicker)
        context.launch { checkpointProgress() }
        context.sendEffect(InterviewEffect.StopRecordingSegment)
    }

    private fun onForegrounded() {
        if (!state.value.screenState.isActiveInterview || !wasBackgrounded) return
        wasBackgrounded = false
        connectionInterrupted = true
        context.launch {
            checkpointProgress()
            context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
        }
        workflow(InterviewWorkflow.StartTicker)
    }

    private fun onNetworkDisconnected() {
        if (!state.value.screenState.isActiveInterview || connectionInterrupted) return
        connectionInterrupted = true
        context.launch {
            checkpointProgress()
            context.sendEffect(InterviewEffect.StopRecordingSegment)
            context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
        }
    }

    fun consumeRecoveryResult() {
        val result = recoveryStore.consume() ?: return
        val current = state.value
        if (!current.isCameraReady || !current.isMicrophoneReady || !current.hasEnoughStorage) {
            pendingRecoveryResult = result
            context.sendEffect(InterviewEffect.RequestCameraPermission)
            context.sendEffect(InterviewEffect.CheckStorageAvailability)
            return
        }
        applyRecoveryResult(result)
    }

    fun tryApplyPendingRecovery() {
        val current = state.value
        val result = pendingRecoveryResult ?: return
        if (!current.isCameraReady || !current.isMicrophoneReady ||
            !current.hasEnoughStorage
        ) {
            return
        }
        pendingRecoveryResult = null
        applyRecoveryResult(result)
    }

    private fun applyRecoveryResult(result: InterviewRecoveryStore.Result) {
        connectionInterrupted = false
        wasBackgrounded = false
        workflow(InterviewWorkflow.CompletionReasonChanged(result.completionReason))
        if (result.sessionEnded) {
            workflow(
                InterviewWorkflow.BeginFinishing(
                    result.wrapUpMessage?.ttsAudio,
                    result.reportGenerating,
                ),
            )
            return
        }
        val nextQuestion = result.nextQuestion ?: return
        context.dispatch(InterviewMutation.NextQuestionReceived(nextQuestion.questionId))
        workflow(InterviewWorkflow.ResetTimeline)
        workflow(InterviewWorkflow.StartQuestionSegmentAndPlayback)
        workflow(InterviewWorkflow.StartTicker)
    }

    private val InterviewScreenState.isActiveInterview: Boolean
        get() =
            this == InterviewScreenState.QUESTION_PLAYING ||
                this == InterviewScreenState.ANSWER_RECORDING ||
                this == InterviewScreenState.ANSWER_SUBMITTING
}
