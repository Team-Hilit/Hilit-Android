package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaSegment
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.feature.interview.InterviewConstants
import com.dminus14.app.feature.interview.api.InterviewErrorType

/** 현재 화면에 표시할 발언 주체다. */
enum class InterviewSpeaker { AI, USER }

/** 면접 화면이 갖는 일곱 개의 지속 단계다. */
enum class InterviewScreenState {
    DEVICE_CHECK,
    QUESTION_PREPARING,
    START_GUIDE,
    QUESTION_PLAYING,
    ANSWER_RECORDING,
    ANSWER_SUBMITTING,
    FINISHING,
}

enum class InterviewPermission { CAMERA, MICROPHONE }

/**
 * 서버 종료 방식이 아니라 후속 화면 조립을 위한 Feature 종료 결과다.
 *
 * [COMPLETED]는 BackExit을 포함해 A4가 면접 종료를 확정한 흐름이고,
 * [ABANDONED]는 오류 화면에서 A7 중단이 확정된 흐름이다.
 */
enum class InterviewCompletionReason { COMPLETED, ABANDONED }

/** 서버 종료 뒤 로컬 마무리 단계에서 사용자가 복구할 수 있는 실패 유형이다. */
enum class InterviewFinalizationFailure {
    RECORDING_FINALIZATION_TIMEOUT,
    RECORDING_FAILURE,
    UPLOAD_HANDOFF_FAILURE,
}

sealed interface InterviewIntent : MviIntent {
    data object LoadInterview : InterviewIntent

    data object CheckCameraPermission : InterviewIntent

    data object ClickRetryDeviceCheck : InterviewIntent

    data object ClickOpenSettings : InterviewIntent

    data object ReportCameraPermissionGranted : InterviewIntent

    data object ReportCameraReady : InterviewIntent

    data class ReportCameraPermissionDenied(
        val permanentlyDenied: Boolean,
    ) : InterviewIntent

    data object ReportCameraBindingFailure : InterviewIntent

    data object ReportMicrophoneReady : InterviewIntent

    data class ReportMicrophonePermissionDenied(
        val permanentlyDenied: Boolean,
    ) : InterviewIntent

    data object ReportMicrophoneFailure : InterviewIntent

    data class ReportStorageAvailability(
        val availableBytes: Long,
    ) : InterviewIntent

    data object ClickPermissionDeniedBack : InterviewIntent

    data object StartInterview : InterviewIntent

    data object ReportAppBackgrounded : InterviewIntent

    data object ReportAppForegrounded : InterviewIntent

    data class UpdateElapsedTime(
        val elapsedMillis: Long,
    ) : InterviewIntent

    data object ReportHardCapReached : InterviewIntent

    data object ReportQuestionPlaybackStarted : InterviewIntent

    data object ReportQuestionPlaybackCompleted : InterviewIntent

    data object ReportQuestionPlaybackFailure : InterviewIntent

    data object ClickRetryQuestionAudio : InterviewIntent

    data object ReportAnswerSpeechStarted : InterviewIntent

    data object ReportAnswerSilenceElapsed : InterviewIntent

    data object ClickFinishAnswer : InterviewIntent

    data class ReportRecordingSegmentFinalized(
        val segment: InterviewMediaSegment,
    ) : InterviewIntent

    data class ReportAnswerRecordingCompleted(
        val audioSegment: InterviewMediaSegment,
    ) : InterviewIntent

    data object ReportAnswerAudioMergeFailure : InterviewIntent

    data object ReportRecordingFailure : InterviewIntent

    data object ClickFinishInterview : InterviewIntent

    data object ConfirmFinishInterview : InterviewIntent

    data object DismissFinishInterview : InterviewIntent

    data object ClickExitInterview : InterviewIntent

    data object ConfirmEarlyExit : InterviewIntent

    data object DismissEarlyExit : InterviewIntent

    data object ConfirmMeteredUpload : InterviewIntent

    data object DismissMeteredUpload : InterviewIntent

    data class ReportUploadNotificationPermission(
        val isGranted: Boolean,
    ) : InterviewIntent

    data class ReportUploadNetworkMetered(
        val isMetered: Boolean,
    ) : InterviewIntent

    data object ReportVideoUploadEnqueued : InterviewIntent

    data object ReportVideoUploadEnqueueFailure : InterviewIntent

    data object ClickRetryFinalization : InterviewIntent

    data object ClickExitFinalization : InterviewIntent

    data object ReportWrapUpPlaybackCompleted : InterviewIntent

    data object ReportWrapUpPlaybackFailure : InterviewIntent

    data object ReportNetworkDisconnected : InterviewIntent

    data object ReportNetworkRestored : InterviewIntent

    data object ConsumeRecoveryResult : InterviewIntent
}

data class InterviewState(
    val screenState: InterviewScreenState = InterviewScreenState.DEVICE_CHECK,
    val sessionId: Long? = null,
    val questionId: Long? = null,
    val isCameraPermissionGranted: Boolean = false,
    val isCameraReady: Boolean = false,
    val isMicrophoneReady: Boolean = false,
    val permanentlyDeniedPermission: InterviewPermission? = null,
    val isServerReady: Boolean = false,
    val availableStorageBytes: Long = 0L,
    val hasEnoughStorage: Boolean = false,
    val elapsedMillis: Long = 0L,
    val hasSpeechStarted: Boolean = false,
    val isQuestionAudioRetryVisible: Boolean = false,
    val isRequestInFlight: Boolean = false,
    val showFinishConfirmation: Boolean = false,
    val showEarlyExitWarning: Boolean = false,
    val showMeteredUploadConfirmation: Boolean = false,
    val isUploadHandoffInProgress: Boolean = false,
    val isUploadEnqueued: Boolean = false,
    val reportGenerating: Boolean = false,
    val finalizationFailure: InterviewFinalizationFailure? = null,
) : MviState {
    val elapsedSeconds: Int
        get() = (elapsedMillis / 1_000L).toInt()

    val canFinishEarly: Boolean
        get() = elapsedSeconds >= InterviewConstants.CAN_FINISH_INTERVIEW_SECONDS

    val isWrapUp: Boolean
        get() = elapsedSeconds >= InterviewConstants.WRAP_UP_SECONDS

    val countdownSeconds: Int?
        get() =
            if (elapsedSeconds >= InterviewConstants.COUNTDOWN_START_SECONDS) {
                (InterviewConstants.MAX_INTERVIEW_SECONDS - elapsedSeconds).coerceIn(0, 10)
            } else {
                null
            }

    val isReadyToStart: Boolean
        get() =
            isCameraPermissionGranted && isCameraReady && isMicrophoneReady &&
                isServerReady && hasEnoughStorage

    val speaker: InterviewSpeaker
        get() =
            if (screenState == InterviewScreenState.ANSWER_RECORDING) {
                InterviewSpeaker.USER
            } else {
                InterviewSpeaker.AI
            }
}

sealed interface InterviewEffect : MviEffect {
    data object RequestCameraPermission : InterviewEffect

    data object RequestMicrophonePermission : InterviewEffect

    data class OpenAppSettings(
        val permission: InterviewPermission,
    ) : InterviewEffect

    data object CheckStorageAvailability : InterviewEffect

    data class StartRecordingSegment(
        val sessionId: Long,
        val type: InterviewMediaSegmentType,
        val questionId: Long?,
        val startedAtMillis: Long,
    ) : InterviewEffect

    data object StopRecordingSegment : InterviewEffect

    data object PauseRecording : InterviewEffect

    data object ResumeRecording : InterviewEffect

    data class PlayQuestionAudio(
        val url: String,
    ) : InterviewEffect

    data class PlayWrapUpMessage(
        val payload: String,
    ) : InterviewEffect

    data class ExportAnswerAudio(
        val inputRefs: List<InterviewMediaFileRef>,
        val outputSegment: InterviewMediaSegment,
    ) : InterviewEffect

    data object ShowEarlyFinishAvailable : InterviewEffect

    data object PlayFinalCountdown : InterviewEffect

    data object RequestUploadNotificationPermission : InterviewEffect

    data object ShowUploadNotificationPermissionDenied : InterviewEffect

    data object CheckUploadNetwork : InterviewEffect

    data class EnqueueVideoUpload(
        val sessionId: Long,
        val networkPolicy: InterviewUploadNetworkPolicy,
    ) : InterviewEffect

    data class NavigateToError(
        val errorType: InterviewErrorType,
    ) : InterviewEffect

    data object PermissionDeniedExitRequested : InterviewEffect

    data object PrerequisiteMissing : InterviewEffect

    data object FatalExitConfirmed : InterviewEffect

    data object FinalizationExitConfirmed : InterviewEffect

    data class InterviewEnded(
        val reason: InterviewCompletionReason,
        val sessionId: Long,
    ) : InterviewEffect
}
