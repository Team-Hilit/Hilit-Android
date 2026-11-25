package com.dminus14.app.feature.interview.media

import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaSegment
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.usecase.CreateInterviewMediaSegmentUseCase
import com.dminus14.app.domain.usecase.DeleteInterviewMediaFileUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

class InterviewMediaSessionManager
    @Inject
    constructor(
        private val recorder: InterviewVideoRecorder,
        private val speechDetector: InterviewSpeechDetector,
        private val transformer: InterviewMediaTransformer,
        private val createSegment: CreateInterviewMediaSegmentUseCase,
        private val deleteMediaFile: DeleteInterviewMediaFileUseCase,
        private val mediaFileResolver: InterviewMediaFileResolver,
    ) {
        private var activeSegment: InterviewMediaSegment? = null
        val hasActiveSegment: Boolean get() = activeSegment != null

        private var listener: Listener? = null
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Suppress("LongParameterList")
        suspend fun startSegment(
            sessionId: Long,
            type: InterviewMediaSegmentType,
            questionId: Long?,
            startedAtMillis: Long,
            gapBeforeMillis: Long,
            listener: Listener,
        ) {
            check(activeSegment == null)
            this.listener = listener
            speechDetector.reset()
            val segment =
                createSegment(
                    sessionId = sessionId,
                    type = type,
                    questionId = questionId,
                    startedAtMillis = startedAtMillis,
                    gapBeforeMillis = gapBeforeMillis,
                    extension = "mp4",
                )
            activeSegment = segment
            runCatching {
                recorder.start(
                    mediaFileResolver.resolve(segment.mediaRef),
                ) { event -> handleRecordingEvent(sessionId, event) }
            }.onFailure { error ->
                activeSegment = null
                deleteMediaFile(segment.mediaRef)
                throw error
            }
        }

        fun pause() = recorder.pause()

        fun resume() = recorder.resume()

        fun stop() = recorder.stop()

        suspend fun exportAnswerAudio(
            inputRefs: List<InterviewMediaFileRef>,
            outputRef: InterviewMediaFileRef,
        ) {
            transformer.export(
                inputFiles = inputRefs.map { mediaFileResolver.resolve(it) },
                outputFile = mediaFileResolver.resolve(outputRef),
                audioOnly = true,
            )
        }

        suspend fun mergeVideo(
            inputRefs: List<InterviewMediaFileRef>,
            outputRef: InterviewMediaFileRef,
        ) {
            transformer.export(
                inputFiles = inputRefs.map { mediaFileResolver.resolve(it) },
                outputFile = mediaFileResolver.resolve(outputRef),
                audioOnly = false,
            )
        }

        private fun handleRecordingEvent(
            sessionId: Long,
            event: InterviewRecordingEvent,
        ) {
            when (event) {
                InterviewRecordingEvent.Started -> {
                    listener?.onRecordingStarted()
                }

                is InterviewRecordingEvent.AudioLevel -> {
                    speechDetector.accept(event.sample)?.let { listener?.onSpeechEvent(it) }
                }

                InterviewRecordingEvent.Finalized -> {
                    val segment = activeSegment ?: return
                    activeSegment = null
                    listener?.onSegmentFinalized(segment)
                    listener?.onFinalizeCheckpointRequested(sessionId, segment.sequence)
                }

                is InterviewRecordingEvent.Failed -> {
                    val segment = activeSegment
                    activeSegment = null
                    if (segment != null) {
                        cleanupScope.launch { deleteMediaFile(segment.mediaRef) }
                    }
                    listener?.onFailure(event.cause)
                }
            }
        }

        interface Listener {
            fun onRecordingStarted()

            fun onSpeechEvent(event: InterviewSpeechDetectionEvent)

            fun onSegmentFinalized(segment: InterviewMediaSegment)

            fun onFinalizeCheckpointRequested(
                sessionId: Long,
                sequence: Int,
            )

            fun onFailure(cause: Throwable)
        }
    }
