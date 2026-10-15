package com.dminus14.app.feature.interview.error

import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.feature.interview.api.InterviewErrorType

internal sealed interface InterviewErrorMutation {
    data class LoadStarted(
        val errorType: InterviewErrorType,
    ) : InterviewErrorMutation

    data class PendingAnswerChecked(
        val canRetry: Boolean,
    ) : InterviewErrorMutation

    data object ResumeAvailable : InterviewErrorMutation

    data object SessionInvalid : InterviewErrorMutation

    data object ResumeCheckStarted : InterviewErrorMutation

    data object AnswerRetryUnavailable : InterviewErrorMutation

    data object ResumeRequested : InterviewErrorMutation

    data object PendingAnswerMissing : InterviewErrorMutation

    data object AnswerRetryFailed : InterviewErrorMutation

    data object AbandonStarted : InterviewErrorMutation

    data object NetworkRetryRequired : InterviewErrorMutation

    data object UnknownErrorReported : InterviewErrorMutation
}

internal object InterviewErrorReducer : MviReducer<InterviewErrorState, InterviewErrorMutation> {
    override fun reduce(
        state: InterviewErrorState,
        mutation: InterviewErrorMutation,
    ): InterviewErrorState =
        when (mutation) {
            is InterviewErrorMutation.LoadStarted -> {
                state.copy(
                    errorType = mutation.errorType,
                    isLoading = mutation.errorType == InterviewErrorType.NETWORK,
                    canResume = mutation.errorType != InterviewErrorType.NETWORK,
                    canRetryAnswerSubmission = false,
                    failureMessage = null,
                )
            }

            is InterviewErrorMutation.PendingAnswerChecked -> {
                state.copy(canRetryAnswerSubmission = mutation.canRetry)
            }

            InterviewErrorMutation.ResumeAvailable -> {
                state.copy(isLoading = false, canResume = true, failureMessage = null)
            }

            InterviewErrorMutation.SessionInvalid -> {
                state.copy(errorType = InterviewErrorType.STT, isLoading = false, canResume = false)
            }

            InterviewErrorMutation.ResumeCheckStarted,
            InterviewErrorMutation.AbandonStarted,
            -> {
                state.copy(isLoading = true)
            }

            InterviewErrorMutation.AnswerRetryUnavailable -> {
                state.copy(failureMessage = InterviewErrorMessages.MISSING_PENDING_ANSWER)
            }

            InterviewErrorMutation.ResumeRequested -> {
                state.copy(isLoading = true, failureMessage = null)
            }

            InterviewErrorMutation.PendingAnswerMissing -> {
                state.copy(
                    isLoading = false,
                    canRetryAnswerSubmission = false,
                    failureMessage = InterviewErrorMessages.MISSING_PENDING_ANSWER,
                )
            }

            InterviewErrorMutation.AnswerRetryFailed -> {
                state.copy(
                    isLoading = false,
                    canRetryAnswerSubmission = true,
                    failureMessage = InterviewErrorMessages.RETRY_LATER,
                )
            }

            InterviewErrorMutation.NetworkRetryRequired -> {
                state.copy(
                    isLoading = false,
                    canResume = false,
                    failureMessage = InterviewErrorMessages.NETWORK_RETRY,
                )
            }

            InterviewErrorMutation.UnknownErrorReported -> {
                state.copy(isLoading = false)
            }
        }
}

internal object InterviewErrorMessages {
    const val NETWORK_RETRY = "네트워크 연결을 확인한 뒤 다시 시도해주세요."
    const val RETRY_LATER = "잠시 후 같은 답변을 다시 시도해주세요."
    const val MISSING_PENDING_ANSWER = "재시도할 답변 정보를 찾지 못했어요."
}
