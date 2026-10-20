package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.feature.interview.InterviewConstants

/** 화면의 상태 변경 원인. 비동기 작업이나 사용자 입력 객체를 보관하지 않는다. */
internal sealed interface InterviewMutation {
    sealed interface Preparation : InterviewMutation

    sealed interface Timing : InterviewMutation

    sealed interface Turn : InterviewMutation

    sealed interface Finalization : InterviewMutation

    data class CameraPermissionDenied(
        val permanentlyDenied: Boolean,
    ) : Preparation

    data class MicrophonePermissionDenied(
        val permanentlyDenied: Boolean,
    ) : Preparation

    data object SpeechStarted : Turn

    data class FinishConfirmationChanged(
        val visible: Boolean,
    ) : Turn

    data class EarlyExitWarningChanged(
        val visible: Boolean,
    ) : Turn

    data class MeteredUploadConfirmationChanged(
        val visible: Boolean,
    ) : Finalization

    data object UploadHandoffFailed : Finalization

    data class SessionLoaded(
        val sessionId: Long,
    ) : Preparation

    data class ElapsedTimeUpdated(
        val elapsedMillis: Long,
    ) : Timing

    data class QuestionPrepared(
        val questionId: Long,
    ) : Preparation

    data object CameraPermissionGranted : Preparation

    data object CameraReady : Preparation

    data object MicrophoneReady : Preparation

    data class StorageChecked(
        val availableBytes: Long,
    ) : Preparation

    data object QuestionPreparationStarted : Preparation

    data object StartGuideReady : Preparation

    data object InterviewStarted : Timing

    data object QuestionPlaybackStarted : Turn

    data object QuestionPlaybackRetryRequired : Turn

    data object FinalizationFailureCleared : Finalization

    data object AnswerRecordingStarted : Turn

    data object AnswerSubmissionStarted : Turn

    data object AnswerSubmissionReleased : Turn

    data class NextQuestionReceived(
        val questionId: Long,
    ) : Turn

    data object QuestionRecordingStarted : Turn

    data object EndDialogsClosed : Turn

    data class FinishingStarted(
        val reportGenerating: Boolean,
    ) : Finalization

    data object UploadPermissionRequested : Finalization

    data object UploadHandoffStarted : Finalization

    data object UploadEnqueued : Finalization

    data object RecordingFinalizationTimedOut : Finalization

    data object RecordingFailed : Finalization

    data object FinalOutcomeEmitted : Finalization
}

/** 면접 UI 상태를 계산하는 유일한 순수 변경 경로다. */
internal object InterviewReducer : MviReducer<InterviewState, InterviewMutation> {
    override fun reduce(
        state: InterviewState,
        mutation: InterviewMutation,
    ): InterviewState =
        when (mutation) {
            is InterviewMutation.Preparation -> reducePreparation(state, mutation)
            is InterviewMutation.Timing -> reduceTiming(state, mutation)
            is InterviewMutation.Turn -> reduceTurn(state, mutation)
            is InterviewMutation.Finalization -> reduceFinalization(state, mutation)
        }

    private fun reducePreparation(
        state: InterviewState,
        mutation: InterviewMutation.Preparation,
    ): InterviewState =
        when (mutation) {
            is InterviewMutation.CameraPermissionDenied -> {
                state.copy(
                    isCameraPermissionGranted = false,
                    isCameraReady = false,
                    permanentlyDeniedPermission =
                        InterviewPermission.CAMERA.takeIf { mutation.permanentlyDenied },
                )
            }

            is InterviewMutation.MicrophonePermissionDenied -> {
                state.copy(
                    isMicrophoneReady = false,
                    permanentlyDeniedPermission =
                        InterviewPermission.MICROPHONE.takeIf { mutation.permanentlyDenied },
                )
            }

            is InterviewMutation.SessionLoaded -> {
                state.copy(sessionId = mutation.sessionId)
            }

            is InterviewMutation.QuestionPrepared -> {
                state.copy(
                    questionId = mutation.questionId,
                    isServerReady = true,
                )
            }

            InterviewMutation.CameraPermissionGranted -> {
                state.copy(
                    isCameraPermissionGranted = true,
                    permanentlyDeniedPermission = null,
                )
            }

            InterviewMutation.CameraReady -> {
                state.copy(isCameraPermissionGranted = true, isCameraReady = true)
            }

            InterviewMutation.MicrophoneReady -> {
                state.copy(isMicrophoneReady = true, permanentlyDeniedPermission = null)
            }

            is InterviewMutation.StorageChecked -> {
                state.copy(
                    availableStorageBytes = mutation.availableBytes,
                    hasEnoughStorage =
                        mutation.availableBytes >= InterviewConstants.REQUIRED_STORAGE_BYTES,
                )
            }

            InterviewMutation.QuestionPreparationStarted -> {
                state.copy(screenState = InterviewScreenState.QUESTION_PREPARING)
            }

            InterviewMutation.StartGuideReady -> {
                state.copy(screenState = InterviewScreenState.START_GUIDE)
            }
        }

    private fun reduceTiming(
        state: InterviewState,
        mutation: InterviewMutation.Timing,
    ): InterviewState =
        when (mutation) {
            is InterviewMutation.ElapsedTimeUpdated -> {
                state.copy(elapsedMillis = mutation.elapsedMillis)
            }

            InterviewMutation.InterviewStarted -> {
                state.copy(
                    screenState = InterviewScreenState.QUESTION_PLAYING,
                    isRequestInFlight = false,
                )
            }
        }

    private fun reduceTurn(
        state: InterviewState,
        mutation: InterviewMutation.Turn,
    ): InterviewState =
        when (mutation) {
            InterviewMutation.SpeechStarted -> {
                state.copy(hasSpeechStarted = true)
            }

            is InterviewMutation.FinishConfirmationChanged -> {
                state.copy(showFinishConfirmation = mutation.visible)
            }

            is InterviewMutation.EarlyExitWarningChanged -> {
                state.copy(showEarlyExitWarning = mutation.visible)
            }

            InterviewMutation.AnswerRecordingStarted -> {
                state.copy(
                    screenState = InterviewScreenState.ANSWER_RECORDING,
                    hasSpeechStarted = false,
                )
            }

            InterviewMutation.AnswerSubmissionStarted -> {
                state.copy(
                    screenState = InterviewScreenState.ANSWER_SUBMITTING,
                    isRequestInFlight = true,
                )
            }

            InterviewMutation.AnswerSubmissionReleased -> {
                state.copy(isRequestInFlight = false)
            }

            is InterviewMutation.NextQuestionReceived -> {
                state.copy(
                    questionId = mutation.questionId,
                    isRequestInFlight = false,
                )
            }

            InterviewMutation.QuestionRecordingStarted -> {
                state.copy(
                    screenState = InterviewScreenState.QUESTION_PLAYING,
                    hasSpeechStarted = false,
                )
            }

            InterviewMutation.EndDialogsClosed -> {
                state.copy(
                    showFinishConfirmation = false,
                    showEarlyExitWarning = false,
                )
            }

            InterviewMutation.QuestionPlaybackStarted -> {
                state.copy(
                    screenState = InterviewScreenState.QUESTION_PLAYING,
                    isQuestionAudioRetryVisible = false,
                    hasSpeechStarted = false,
                )
            }

            InterviewMutation.QuestionPlaybackRetryRequired -> {
                state.copy(isQuestionAudioRetryVisible = true)
            }
        }

    private fun reduceFinalization(
        state: InterviewState,
        mutation: InterviewMutation.Finalization,
    ): InterviewState =
        when (mutation) {
            is InterviewMutation.MeteredUploadConfirmationChanged -> {
                state.copy(showMeteredUploadConfirmation = mutation.visible)
            }

            InterviewMutation.UploadHandoffFailed -> {
                state.copy(
                    isUploadHandoffInProgress = false,
                    finalizationFailure =
                        InterviewFinalizationFailure.UPLOAD_HANDOFF_FAILURE,
                )
            }

            InterviewMutation.FinalizationFailureCleared -> {
                state.copy(finalizationFailure = null)
            }

            is InterviewMutation.FinishingStarted -> {
                state.copy(
                    screenState = InterviewScreenState.FINISHING,
                    isRequestInFlight = false,
                    reportGenerating = mutation.reportGenerating,
                    showFinishConfirmation = false,
                    showEarlyExitWarning = false,
                    finalizationFailure = null,
                )
            }

            InterviewMutation.UploadPermissionRequested -> {
                state.copy(isUploadHandoffInProgress = true)
            }

            InterviewMutation.UploadHandoffStarted -> {
                state.copy(
                    isUploadHandoffInProgress = true,
                    finalizationFailure = null,
                )
            }

            InterviewMutation.UploadEnqueued -> {
                state.copy(
                    isUploadHandoffInProgress = false,
                    isUploadEnqueued = true,
                    finalizationFailure = null,
                )
            }

            InterviewMutation.RecordingFinalizationTimedOut -> {
                state.copy(
                    finalizationFailure =
                        InterviewFinalizationFailure.RECORDING_FINALIZATION_TIMEOUT,
                )
            }

            InterviewMutation.RecordingFailed -> {
                state.copy(finalizationFailure = InterviewFinalizationFailure.RECORDING_FAILURE)
            }

            InterviewMutation.FinalOutcomeEmitted -> {
                state.copy(
                    isUploadHandoffInProgress = false,
                    finalizationFailure = null,
                )
            }
        }
}
