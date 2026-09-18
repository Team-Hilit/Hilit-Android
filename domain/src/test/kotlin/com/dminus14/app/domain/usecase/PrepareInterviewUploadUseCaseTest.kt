package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.repository.InterviewLocalRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PrepareInterviewUploadUseCaseTest {
    @Test
    fun `작업 메타데이터 기록 실패를 재시도하면 같은 업로드 작업 식별자를 사용한다`() =
        runTest {
            val fixture = Fixture(failBeforeTaskSaved = true)
            val useCase = PrepareInterviewUploadUseCase(fixture.repository)

            runCatching { useCase(SESSION_ID, InterviewUploadNetworkPolicy.UNMETERED) }
            val task = useCase(SESSION_ID, InterviewUploadNetworkPolicy.CONNECTED)

            assertEquals(2, fixture.attemptedIds.size)
            assertEquals(fixture.attemptedIds.first(), fixture.attemptedIds.last())
            assertEquals(InterviewUploadNetworkPolicy.UNMETERED, task.networkPolicy)
        }

    @Test
    fun `부분 인계 뒤 재시도하면 기존 작업으로 남은 인계를 이어간다`() =
        runTest {
            val fixture = Fixture(failAfterTaskSaved = true)
            val useCase = PrepareInterviewUploadUseCase(fixture.repository)

            runCatching { useCase(SESSION_ID, InterviewUploadNetworkPolicy.UNMETERED) }
            val task = useCase(SESSION_ID, InterviewUploadNetworkPolicy.CONNECTED)

            assertEquals(1, fixture.tasks.size)
            assertEquals(fixture.attemptedIds.first(), task.uploadTaskId)
            assertEquals(InterviewUploadNetworkPolicy.UNMETERED, task.networkPolicy)
            assertTrue(fixture.handoffCompleted)
        }

    @Test
    fun `인계 완료 뒤 등록 재시도는 중복 작업을 만들지 않는다`() =
        runTest {
            val fixture = Fixture()
            val useCase = PrepareInterviewUploadUseCase(fixture.repository)

            val first = useCase(SESSION_ID, InterviewUploadNetworkPolicy.UNMETERED)
            val retried = useCase(SESSION_ID, InterviewUploadNetworkPolicy.CONNECTED)

            assertEquals(first.uploadTaskId, retried.uploadTaskId)
            assertEquals(1, fixture.tasks.size)
            assertEquals(InterviewUploadNetworkPolicy.UNMETERED, retried.networkPolicy)
        }

    @Test
    fun `진행 상태 삭제 실패를 재시도하면 완료된 기존 인계를 재사용한다`() =
        runTest {
            val fixture = Fixture(failBeforeProgressCleared = true)
            val useCase = PrepareInterviewUploadUseCase(fixture.repository)

            runCatching { useCase(SESSION_ID, InterviewUploadNetworkPolicy.UNMETERED) }
            val retried = useCase(SESSION_ID, InterviewUploadNetworkPolicy.CONNECTED)

            assertEquals(1, fixture.tasks.size)
            assertEquals(fixture.attemptedIds.first(), retried.uploadTaskId)
            assertTrue(fixture.handoffCompleted)
        }

    private class Fixture(
        private var failBeforeTaskSaved: Boolean = false,
        private var failAfterTaskSaved: Boolean = false,
        private var failBeforeProgressCleared: Boolean = false,
    ) {
        val tasks = linkedMapOf<String, InterviewUploadTask>()
        val attemptedIds = mutableListOf<String>()
        var handoffCompleted = false

        private var progress: InterviewProgress? =
            InterviewProgress(
                sessionId = SESSION_ID,
                retentionDeadlineEpochMillis = 86_400_000L,
                retentionRemainingAtCheckpointMillis = 86_400_000L,
                retentionCheckpointElapsedRealtimeMillis = 0L,
                timerStartedAtEpochMillis = null,
                elapsedAtCheckpointMillis = null,
                checkpointedAtEpochMillis = null,
                elapsedCheckpointElapsedRealtimeMillis = null,
            )

        val repository: InterviewLocalRepository =
            Proxy.newProxyInstance(
                InterviewLocalRepository::class.java.classLoader,
                arrayOf(InterviewLocalRepository::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> {
                        proxy === args?.firstOrNull()
                    }

                    "hashCode" -> {
                        System.identityHashCode(proxy)
                    }

                    "toString" -> {
                        "InterviewLocalRepositoryTestDouble"
                    }

                    "getProgress" -> {
                        progress
                    }

                    "getUploadTasks" -> {
                        tasks.values.toList()
                    }

                    "handoffUploadTask" -> {
                        val task = args!![0] as InterviewUploadTask
                        attemptedIds += task.uploadTaskId
                        if (failBeforeTaskSaved) {
                            failBeforeTaskSaved = false
                            error("metadata write failed")
                        }
                        tasks[task.uploadTaskId] = task
                        if (failAfterTaskSaved) {
                            failAfterTaskSaved = false
                            error("partial handoff failed")
                        }
                        handoffCompleted = true
                        if (failBeforeProgressCleared) {
                            failBeforeProgressCleared = false
                            error("progress clear failed")
                        }
                        progress = null
                        Unit
                    }

                    else -> {
                        error("사용하지 않는 테스트 호출: ${method.name}")
                    }
                }
            } as InterviewLocalRepository
    }

    private companion object {
        const val SESSION_ID = 145L
    }
}
