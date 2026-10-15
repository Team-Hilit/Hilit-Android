package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.domain.model.InterviewAnswerEndRequest
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand

/** Handler 간 의존 없이 ViewModel에서 실행 순서를 조정하는 내부 사건이다. */
internal sealed interface InterviewWorkflow {
    sealed interface Timing : InterviewWorkflow

    sealed interface Turn : InterviewWorkflow

    sealed interface Submission : InterviewWorkflow

    sealed interface Recovery : InterviewWorkflow

    sealed interface Finalization : InterviewWorkflow

    data class SubmitAnswer(
        val command: SubmitInterviewAnswerCommand,
    ) : Submission

    data class CreateEndSubmission(
        val request: InterviewAnswerEndRequest,
    ) : Submission

    data class FinishAnswer(
        val requireSpeech: Boolean,
    ) : Turn

    data object CheckRestoredHardCap : Timing

    data object ConsumeRecoveryResult : Recovery

    data object TryApplyPendingRecovery : Recovery

    data class RestoreTimer(
        val elapsedMillis: Long,
    ) : Timing

    data object StartTicker : Timing

    data object StopTicker : Timing

    data class PlayCurrentQuestion(
        val resetRetry: Boolean,
    ) : Turn

    data class RequestEnd(
        val request: InterviewAnswerEndRequest,
    ) : Submission

    data class ClearBeforeFirstQuestion(
        val completionEffect: InterviewEffect? = null,
    ) : InterviewWorkflow

    data class BeginFinishing(
        val payload: String?,
        val reportGenerating: Boolean,
    ) : Finalization

    data object FatalExit : Recovery

    data object ClearSubmissionBusyState : Submission

    data object ResetTimeline : Turn

    data object StartQuestionSegmentAndPlayback : Turn

    data class CompletionReasonChanged(
        val reason: InterviewCompletionReason,
    ) : Finalization

    data object WrapUpSegmentFinalized : Finalization

    data class GlobalError(
        val event: GlobalAppEvent,
    ) : InterviewWorkflow
}
