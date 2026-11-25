package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.model.InterviewUploadTaskStatus
import com.dminus14.app.domain.repository.InterviewLocalRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RetainInterviewSessionForCleanupUseCaseTest {
    @Test
    fun `미디어가 있는 종료 세션은 업로드하지 않는 정리 대상으로 인계한다`() =
        runTest {
            val progress = progress()
            val manifest = InterviewMediaManifest(sessionId = progress.sessionId)
            var handedOffTask: InterviewUploadTask? = null
            val repository =
                repositoryDouble { name, args ->
                    when (name) {
                        "getProgress" -> {
                            progress
                        }

                        "getManifest" -> {
                            manifest
                        }

                        "getUploadTasks" -> {
                            emptyList<InterviewUploadTask>()
                        }

                        "handoffUploadTask" -> {
                            true.also {
                                handedOffTask = args[0] as InterviewUploadTask
                            }
                        }

                        else -> {
                            error("사용하지 않는 테스트 호출: $name")
                        }
                    }
                }

            assertTrue(RetainInterviewSessionForCleanupUseCase(repository)(progress.sessionId))

            assertEquals(InterviewUploadTaskStatus.RETAINED, handedOffTask?.status)
            assertEquals(progress.sessionId, handedOffTask?.sessionId)
            assertEquals(
                progress.retentionDeadlineEpochMillis,
                handedOffTask?.retentionDeadlineEpochMillis,
            )
        }

    @Test
    fun `미디어가 없는 종료 세션은 진행 상태만 제거한다`() =
        runTest {
            val progress = progress()
            var clearedProgress: InterviewProgress? = progress
            val repository =
                repositoryDouble { name, args ->
                    when (name) {
                        "getProgress" -> {
                            progress
                        }

                        "getUploadTasks" -> {
                            emptyList<InterviewUploadTask>()
                        }

                        "getManifest" -> {
                            null
                        }

                        "clearProgressIfMatching" -> {
                            assertEquals(progress.sessionId, args[0])
                            true.also { clearedProgress = null }
                        }

                        else -> {
                            error("사용하지 않는 테스트 호출: $name")
                        }
                    }
                }

            assertTrue(RetainInterviewSessionForCleanupUseCase(repository)(progress.sessionId))

            assertNull(clearedProgress)
        }

    @Test
    fun `미디어 인계 재시도는 기존 정리 작업으로 이어서 처리한다`() =
        runTest {
            val progress = progress()
            val existingTask =
                InterviewUploadTask(
                    uploadTaskId = "retained-task",
                    sessionId = progress.sessionId,
                    retentionDeadlineEpochMillis = progress.retentionDeadlineEpochMillis,
                    status = InterviewUploadTaskStatus.RETAINED,
                )
            val repository =
                repositoryDouble { name, args ->
                    when (name) {
                        "getProgress" -> {
                            progress
                        }

                        "getUploadTasks" -> {
                            listOf(existingTask)
                        }

                        "handoffUploadTask" -> {
                            assertEquals(existingTask, args[0])
                            true
                        }

                        else -> {
                            error("사용하지 않는 테스트 호출: $name")
                        }
                    }
                }

            assertTrue(RetainInterviewSessionForCleanupUseCase(repository)(progress.sessionId))
        }

    private fun progress() =
        InterviewProgress(
            sessionId = 145L,
            retentionDeadlineEpochMillis = 86_400_000L,
            retentionRemainingAtCheckpointMillis = 86_400_000L,
            retentionCheckpointElapsedRealtimeMillis = 0L,
            timerStartedAtEpochMillis = 0L,
            elapsedAtCheckpointMillis = 0L,
            checkpointedAtEpochMillis = 0L,
            elapsedCheckpointElapsedRealtimeMillis = 0L,
        )
}

private fun repositoryDouble(
    handler: (name: String, args: Array<out Any?>) -> Any?,
): InterviewLocalRepository =
    Proxy.newProxyInstance(
        InterviewLocalRepository::class.java.classLoader,
        arrayOf(InterviewLocalRepository::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "InterviewLocalRepositoryTestDouble"
            else -> handler(method.name, args.orEmpty())
        }
    } as InterviewLocalRepository
