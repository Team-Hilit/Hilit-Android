package com.dminus14.app.feature.interview.interview

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.repository.InterviewWorkController
import com.dminus14.app.domain.usecase.PrepareInterviewUploadUseCase
import com.dminus14.app.feature.interview.device.InterviewStorageChecker
import com.dminus14.app.feature.interview.media.InterviewAudioPlaybackRequest
import com.dminus14.app.feature.interview.media.InterviewAudioPlayer
import com.dminus14.app.feature.interview.media.InterviewMediaSessionManager
import com.dminus14.app.feature.interview.media.InterviewRecordingEvent
import com.dminus14.app.feature.interview.media.InterviewSpeechDetectionEvent
import com.dminus14.app.feature.interview.media.InterviewVideoRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Screen에서 Android 미디어·파일·Work 실행을 한 곳으로 모은다. */
@Suppress("LongParameterList", "TooManyFunctions")
class InterviewScreenEffectHandler
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val storageChecker: InterviewStorageChecker,
        private val mediaSessionManager: InterviewMediaSessionManager,
        private val audioPlayer: InterviewAudioPlayer,
        private val videoRecorder: InterviewVideoRecorder,
        private val prepareUpload: PrepareInterviewUploadUseCase,
        private val workController: InterviewWorkController,
    ) {
        private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        private val countdownTone = ToneGenerator(AudioManager.STREAM_MUSIC, COUNTDOWN_TONE_VOLUME)
        private var lastSegmentFinalizedAtMillis: Long? = null

        val videoCapture: VideoCapture<Recorder>
            get() = videoRecorder.videoCapture

        fun checkStorage(onIntent: (InterviewIntent) -> Unit) {
            val status = storageChecker.check()
            onIntent(InterviewIntent.ReportStorageAvailability(status.availableBytes))
        }

        @SuppressLint("MissingPermission")
        fun isUploadNetworkMetered(): Boolean = connectivityManager.isActiveNetworkMetered

        fun playCountdownTone() {
            countdownTone.startTone(ToneGenerator.TONE_PROP_BEEP, COUNTDOWN_TONE_DURATION_MILLIS)
        }

        /** Screen이 컴포지션에서 제거될 때 전용 네이티브 오디오 자원을 해제한다. */
        fun release() {
            countdownTone.release()
        }

        suspend fun startRecording(
            effect: InterviewEffect.StartRecordingSegment,
            onIntent: (InterviewIntent) -> Unit,
        ) {
            runCatching {
                mediaSessionManager.startSegment(
                    sessionId = effect.sessionId,
                    type = effect.type,
                    questionId = effect.questionId,
                    startedAtMillis = effect.startedAtMillis,
                    gapBeforeMillis =
                        lastSegmentFinalizedAtMillis
                            ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                            ?: 0L,
                    listener = listener(onIntent),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                onIntent(InterviewIntent.ReportRecordingFailure)
            }
        }

        fun stopRecording() = mediaSessionManager.stop()

        fun pauseRecording() = mediaSessionManager.pause()

        fun resumeRecording() = mediaSessionManager.resume()

        fun playQuestion(
            url: String,
            onIntent: (InterviewIntent) -> Unit,
        ) {
            audioPlayer.play(
                InterviewAudioPlaybackRequest.AuthenticatedQuestionStream(url),
                playbackListener(
                    onStarted = { onIntent(InterviewIntent.ReportQuestionPlaybackStarted) },
                    onCompleted = { onIntent(InterviewIntent.ReportQuestionPlaybackCompleted) },
                    onFailure = { onIntent(InterviewIntent.ReportQuestionPlaybackFailure) },
                ),
            )
        }

        fun playWrapUp(
            payload: String,
            onIntent: (InterviewIntent) -> Unit,
        ) {
            audioPlayer.play(
                InterviewAudioPlaybackRequest.Base64WrapUpMp3(payload),
                playbackListener(
                    onStarted = {},
                    onCompleted = { onIntent(InterviewIntent.ReportWrapUpPlaybackCompleted) },
                    onFailure = { onIntent(InterviewIntent.ReportWrapUpPlaybackFailure) },
                ),
            )
        }

        suspend fun exportAnswerAudio(
            effect: InterviewEffect.ExportAnswerAudio,
            onIntent: (InterviewIntent) -> Unit,
        ) {
            runCatching {
                mediaSessionManager.exportAnswerAudio(
                    effect.inputRefs,
                    effect.outputSegment.mediaRef,
                )
            }.onSuccess {
                onIntent(InterviewIntent.ReportAnswerRecordingCompleted(effect.outputSegment))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                onIntent(InterviewIntent.ReportAnswerAudioMergeFailure)
            }
        }

        suspend fun enqueueUpload(
            sessionId: Long,
            policy: InterviewUploadNetworkPolicy,
        ) {
            val task = prepareUpload(sessionId, policy)
            workController.enqueueUpload(task.uploadTaskId, task.networkPolicy)
        }

        private fun listener(onIntent: (InterviewIntent) -> Unit) =
            object : InterviewMediaSessionManager.Listener {
                override fun onRecordingStarted() = Unit

                override fun onSpeechEvent(event: InterviewSpeechDetectionEvent) {
                    when (event) {
                        InterviewSpeechDetectionEvent.SpeechStarted -> {
                            onIntent(InterviewIntent.ReportAnswerSpeechStarted)
                        }

                        InterviewSpeechDetectionEvent.SilenceElapsed -> {
                            onIntent(InterviewIntent.ReportAnswerSilenceElapsed)
                        }

                        InterviewSpeechDetectionEvent.MicrophoneFailure -> {
                            onIntent(InterviewIntent.ReportMicrophoneFailure)
                        }
                    }
                }

                override fun onSegmentFinalized(
                    segment: com.dminus14.app.domain.model.InterviewMediaSegment,
                ) {
                    lastSegmentFinalizedAtMillis = SystemClock.elapsedRealtime()
                    onIntent(InterviewIntent.ReportRecordingSegmentFinalized(segment))
                }

                override fun onFinalizeCheckpointRequested(
                    sessionId: Long,
                    sequence: Int,
                ) = Unit

                override fun onFailure(cause: Throwable) {
                    onIntent(InterviewIntent.ReportRecordingFailure)
                }
            }

        private fun playbackListener(
            onStarted: () -> Unit,
            onCompleted: () -> Unit,
            onFailure: () -> Unit,
        ) = object : InterviewAudioPlayer.Listener {
            override fun onStarted() = onStarted.invoke()

            override fun onCompleted() = onCompleted.invoke()

            override fun onFailure(cause: Throwable) = onFailure.invoke()
        }

        private companion object {
            const val COUNTDOWN_TONE_VOLUME = 60
            const val COUNTDOWN_TONE_DURATION_MILLIS = 150
        }
    }
