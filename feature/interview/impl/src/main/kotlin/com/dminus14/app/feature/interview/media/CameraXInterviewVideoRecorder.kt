package com.dminus14.app.feature.interview.media

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.camera.video.AudioStats
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class CameraXInterviewVideoRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : InterviewVideoRecorder {
        private val recorder =
            Recorder
                .Builder()
                .setQualitySelector(QualitySelector.from(InterviewVideoRecordingSpec.QUALITY))
                .setTargetVideoEncodingBitRate(InterviewVideoRecordingSpec.TARGET_VIDEO_BITRATE)
                .build()

        override val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

        private var recording: Recording? = null

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun start(
            outputFile: File,
            onEvent: (InterviewRecordingEvent) -> Unit,
        ) {
            check(recording == null) { "Interview recording is already active" }
            outputFile.parentFile?.mkdirs()
            val outputOptions = FileOutputOptions.Builder(outputFile).build()
            val pendingRecording = recorder.prepareRecording(context, outputOptions)
            recording =
                pendingRecording
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        handleVideoRecordEvent(event, onEvent)
                    }
        }

        private fun handleVideoRecordEvent(
            event: VideoRecordEvent,
            onEvent: (InterviewRecordingEvent) -> Unit,
        ) {
            when (event) {
                is VideoRecordEvent.Start -> {
                    onEvent(InterviewRecordingEvent.Started)
                }

                is VideoRecordEvent.Status -> {
                    onEvent(
                        InterviewRecordingEvent.AudioLevel(
                            event.recordingStats.audioStats.toLevelSample(),
                        ),
                    )
                }

                is VideoRecordEvent.Finalize -> {
                    recording = null
                    val sourceStoppedWithData =
                        event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE &&
                            event.recordingStats.numBytesRecorded > 0
                    if (event.hasError() && !sourceStoppedWithData) {
                        val cause = event.cause ?: IllegalStateException("Recording failed")
                        onEvent(InterviewRecordingEvent.Failed(cause))
                    } else {
                        onEvent(InterviewRecordingEvent.Finalized)
                    }
                }
            }
        }

        override fun pause() {
            recording?.pause()
        }

        override fun resume() {
            recording?.resume()
        }

        override fun stop() {
            recording?.stop()
        }

        private fun AudioStats.toLevelSample(): InterviewAudioLevelSample =
            InterviewAudioLevelSample(
                timestampMillis = SystemClock.elapsedRealtime(),
                amplitude = audioAmplitude.coerceIn(0.0, 1.0),
                state =
                    when {
                        hasError() -> {
                            InterviewAudioState.ERROR
                        }

                        audioState == AudioStats.AUDIO_STATE_ACTIVE -> {
                            InterviewAudioState.ACTIVE
                        }

                        else -> {
                            InterviewAudioState.INACTIVE
                        }
                    },
            )
    }
