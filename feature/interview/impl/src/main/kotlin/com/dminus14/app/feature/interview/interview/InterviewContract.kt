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
    sealed interface Preparation : InterviewIntent {
        data object LoadInterview : Preparation

        data object CheckCameraPermission : Preparation

        data object ClickRetryDeviceCheck : Preparation

        data object ClickOpenSettings : Preparation

        data object ReportCameraPermissionGranted : Preparation

        data object ReportCameraReady : Preparation

        data class ReportCameraPermissionDenied(
            val permanentlyDenied: Boolean,
        ) : Preparation

        data object ReportCameraBindingFailure : Preparation

        data object ReportMicrophoneReady : Preparation

        data class ReportMicrophonePermissionDenied(
            val permanentlyDenied: Boolean,
        ) : Preparation

        data object ReportMicrophoneFailure : Preparation

        data class ReportStorageAvailability(
            val availableBytes: Long,
        ) : Preparation

        data object ClickPermissionDeniedBack : Preparation
    }

    sealed interface Timing : InterviewIntent {
        data object StartInterview : Timing

        data class UpdateElapsedTime(
            val elapsedMillis: Long,
        ) : Timing

        data object ReportHardCapReached : Timing
    }

    sealed interface Turn : InterviewIntent {
        data object ReportQuestionPlaybackStarted : Turn

        data object ReportQuestionPlaybackCompleted : Turn

        data object ReportQuestionPlaybackFailure : Turn

        data object ClickRetryQuestionAudio : Turn

        data object ReportAnswerSpeechStarted : Turn

        data object ReportAnswerSilenceElapsed : Turn

        data object ClickFinishAnswer : Turn

        data class ReportRecordingSegmentFinalized(
            val segment: InterviewMediaSegment,
        ) : Turn

        data class ReportAnswerRecordingCompleted(
            val audioSegment: InterviewMediaSegment,
        ) : Turn

        data object ReportAnswerAudioMergeFailure : Turn

        data object ClickFinishInterview : Turn

        data object ConfirmFinishInterview : Turn

        data object DismissFinishInterview : Turn

        data object ClickExitInterview : Turn

        data object ConfirmEarlyExit : Turn

        data object DismissEarlyExit : Turn
    }

    sealed interface Finalization : InterviewIntent {
        data object ReportRecordingFailure : Finalization

        data object ConfirmMeteredUpload : Finalization

        data object DismissMeteredUpload : Finalization

        data class ReportUploadNotificationPermission(
            val isGranted: Boolean,
        ) : Finalization

        data class ReportUploadNetworkMetered(
            val isMetered: Boolean,
        ) : Finalization

        data object ReportVideoUploadEnqueued : Finalization

        data object ReportVideoUploadEnqueueFailure : Finalization

        data object ClickRetryFinalization : Finalization

        data object ClickExitFinalization : Finalization

        data object ReportWrapUpPlaybackCompleted : Finalization

        data object ReportWrapUpPlaybackFailure : Finalization
    }

    sealed interface Recovery : InterviewIntent {
        data object ReportAppBackgrounded : Recovery

        data object ReportAppForegrounded : Recovery

        data object ReportNetworkDisconnected : Recovery

        data object ReportNetworkRestored : Recovery

        data object ConsumeRecoveryResult : Recovery
    }
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
