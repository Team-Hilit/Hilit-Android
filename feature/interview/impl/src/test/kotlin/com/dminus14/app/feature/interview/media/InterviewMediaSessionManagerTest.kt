package com.dminus14.app.feature.interview.media

import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaOwnerType
import com.dminus14.app.domain.model.InterviewMediaSegment
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.usecase.CreateInterviewMediaSegmentUseCase
import com.dminus14.app.domain.usecase.DeleteInterviewMediaFileUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InterviewMediaSessionManagerTest {
    @Test
    fun `resolve 실패 시 생성된 WRITING 세그먼트 파일을 삭제하고 재시도를 허용한다`() =
        runTest {
            val repository = FakeInterviewLocalRepository()
            val manager =
                newManager(
                    repository = repository,
                    resolve = { throw IllegalStateException("resolve 실패") },
                )

            assertTrue(
                runCatching { manager.startSegment(SESSION_ID, TYPE, null, 0L, 0L, listener) }
                    .isFailure,
            )

            assertEquals(1, repository.deleteCallCount)
        }

    @Test
    fun `recorder start 실패 시 생성된 WRITING 세그먼트 파일을 삭제한다`() =
        runTest {
            val repository = FakeInterviewLocalRepository()
            val recorder =
                FakeInterviewVideoRecorder(
                    onStart = { throw IllegalStateException("recorder 실패") },
                )
            val manager = newManager(repository = repository, recorder = recorder)

            assertTrue(
                runCatching { manager.startSegment(SESSION_ID, TYPE, null, 0L, 0L, listener) }
                    .isFailure,
            )

            assertEquals(1, repository.deleteCallCount)
        }

    @Test
    fun `녹화 Failed 이벤트는 활성 세그먼트 파일을 정리한다`() =
        runTest {
            val repository = FakeInterviewLocalRepository()
            val latch = CountDownLatch(1)
            repository.onDelete = { latch.countDown() }
            val recorder = FakeInterviewVideoRecorder()
            val manager = newManager(repository = repository, recorder = recorder)

            manager.startSegment(SESSION_ID, TYPE, null, 0L, 0L, listener)
            recorder.emit(InterviewRecordingEvent.Failed(IllegalStateException("녹화 실패")))

            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(1, repository.deleteCallCount)
        }

    private fun newManager(
        repository: InterviewLocalRepository,
        recorder: FakeInterviewVideoRecorder = FakeInterviewVideoRecorder(),
        resolve: suspend (
            InterviewMediaFileRef,
        ) -> File = { File.createTempFile("segment", ".mp4") },
    ): InterviewMediaSessionManager =
        InterviewMediaSessionManager(
            recorder = recorder,
            speechDetector = InterviewSpeechDetector(),
            transformer = FakeInterviewMediaTransformer(),
            createSegment = CreateInterviewMediaSegmentUseCase(repository),
            deleteMediaFile = DeleteInterviewMediaFileUseCase(repository),
            mediaFileResolver = InterviewMediaFileResolver { ref -> resolve(ref) },
        )

    private val listener =
        object : InterviewMediaSessionManager.Listener {
            override fun onRecordingStarted() = Unit

            override fun onSpeechEvent(event: InterviewSpeechDetectionEvent) = Unit

            override fun onSegmentFinalized(segment: InterviewMediaSegment) = Unit

            override fun onFinalizeCheckpointRequested(
                sessionId: Long,
                sequence: Int,
            ) = Unit

            override fun onFailure(cause: Throwable) = Unit
        }

    private class FakeInterviewVideoRecorder(
        private val onStart: (File) -> Unit = {},
    ) : InterviewVideoRecorder {
        private var listener: ((InterviewRecordingEvent) -> Unit)? = null

        override val videoCapture: VideoCapture<Recorder>
            get() = error("사용하지 않음")

        override fun start(
            outputFile: File,
            onEvent: (InterviewRecordingEvent) -> Unit,
        ) {
            listener = onEvent
            onStart(outputFile)
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop() = Unit

        fun emit(event: InterviewRecordingEvent) {
            listener?.invoke(event)
        }
    }

    private class FakeInterviewMediaTransformer : InterviewMediaTransformer {
        override suspend fun export(
            inputFiles: List<File>,
            outputFile: File,
            audioOnly: Boolean,
        ) = Unit
    }

    private class FakeInterviewLocalRepository : InterviewLocalRepository {
        private var manifest: InterviewMediaManifest? = null
        var deleteCallCount = 0
            private set
        var onDelete: () -> Unit = {}

        override suspend fun getProgress(): InterviewProgress? = null

        override suspend fun saveProgress(progress: InterviewProgress) = Unit

        override suspend fun updateProgress(
            transform: (InterviewProgress) -> InterviewProgress,
        ): InterviewProgress? = null

        override suspend fun clearProgress() = Unit

        override suspend fun clearProgressIfMatching(sessionId: Long) = false

        override suspend fun getManifest(sessionId: Long): InterviewMediaManifest? = manifest

        override suspend fun getUploadManifest(uploadTaskId: String): InterviewMediaManifest? = null

        override suspend fun saveManifest(manifest: InterviewMediaManifest) {
            this.manifest = manifest
        }

        override suspend fun createMediaFile(
            sessionId: Long,
            type: InterviewMediaSegmentType,
            extension: String,
        ): InterviewMediaFileRef =
            InterviewMediaFileRef(
                value = "${sessionId}_${type}_${System.nanoTime()}",
                ownerType = InterviewMediaOwnerType.SESSION,
                ownerId = sessionId.toString(),
                segmentType = type,
            )

        override suspend fun createUploadMediaFile(
            uploadTaskId: String,
            extension: String,
        ): InterviewMediaFileRef = error("사용하지 않음")

        override suspend fun deleteMediaFile(ref: InterviewMediaFileRef) {
            deleteCallCount += 1
            onDelete()
        }

        override suspend fun handoffUploadTask(task: InterviewUploadTask) = false

        override suspend fun getUploadTask(uploadTaskId: String): InterviewUploadTask? = null

        override suspend fun saveUploadTask(task: InterviewUploadTask) = Unit

        override suspend fun getUploadTasks(): List<InterviewUploadTask> = emptyList()

        override suspend fun deleteUploadTask(uploadTaskId: String) = Unit

        override suspend fun deleteSession(sessionId: Long) = Unit

        override suspend fun clearAll() = Unit

        override suspend fun isCleanupPending(): Boolean = false

        override suspend fun setCleanupPending(isPending: Boolean) = Unit
    }

    private companion object {
        const val SESSION_ID = 1L
        val TYPE = InterviewMediaSegmentType.ANSWER_VIDEO
    }
}
