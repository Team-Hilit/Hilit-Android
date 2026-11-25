package com.dminus14.app.feature.interview.interview

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import com.dminus14.app.domain.model.InterviewMediaFinalizeState
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.NextQuestion
import com.dminus14.app.domain.model.QuestionTurn
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.repository.InterviewRepository
import com.dminus14.app.domain.time.InterviewTimeCalculator
import com.dminus14.app.domain.usecase.CreateInterviewMediaSegmentUseCase
import com.dminus14.app.feature.interview.api.InterviewErrorType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewBackgroundTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val fixture = InterviewViewModelTestFixture()
    private val sessionId = InterviewViewModelTestFixture.SESSION_ID
    private val effects = mutableListOf<InterviewEffect>()

    @Test
    fun `열린 질문 세그먼트를 마감한 뒤 한 번만 동결하고 복원 시 홈으로 이동한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = startConfirmedInterview()
            val segment = createSegment(InterviewMediaSegmentType.QUESTION_VIDEO)
            fixture.clockDeltaMillis = 60_000L
            repeat(2) { vm.onIntent(InterviewIntent.Recovery.ReportAppBackgrounded) }
            vm.onIntent(InterviewIntent.Turn.ReportQuestionPlaybackCompleted)
            vm.onIntent(InterviewIntent.Recovery.ReportAppForegrounded)
            runCurrent()
            assertEquals(listOf(InterviewEffect.FreezeMedia), effects)
            assertFalse(checkNotNull(fixture.progress).isFrozen)

            fixture.clockDeltaMillis += 60_000L
            vm.onIntent(InterviewIntent.Turn.ReportRecordingSegmentFinalized(segment))
            repeat(2) { vm.onIntent(InterviewIntent.Recovery.BackgroundRecordingStopped) }
            runCurrent()
            assertEquals(1, effects.count { it == InterviewEffect.InterviewFrozen })
            val saved = checkNotNull(fixture.progress)
            assertTrue(saved.isFrozen)
            assertEquals(60_000L, saved.elapsedAtCheckpointMillis)
            assertEquals(
                InterviewMediaFinalizeState.FINALIZED,
                fixture.manifests
                    .getValue(sessionId)
                    .segments
                    .single()
                    .finalizeState,
            )
            assertTrue(fixture.submittedCommands.isEmpty())
            assertEquals(0, fixture.abandonCallCount)

            val recreated = fixture.createViewModel()
            val restoredEffects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { recreated.effect.toList(restoredEffects) }
            recreated.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            assertEquals(listOf(InterviewEffect.InterviewFrozen), restoredEffects)

            val resumed = startConfirmedInterview(questionId = 22L)
            assertEquals(22L, resumed.state.value.questionId)
            assertEquals(60_000L, resumed.state.value.elapsedMillis)
            assertEquals(
                saved.retentionDeadlineEpochMillis,
                fixture.progress?.retentionDeadlineEpochMillis,
            )
            assertFalse(checkNotNull(fixture.progress).isFrozen)
            resumed.onIntent(InterviewIntent.Recovery.ReportAppBackgrounded)
        }

    @Test
    fun `답변 녹화 마감 저장에 실패하면 홈에 동결 성공을 알리지 않는다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = startConfirmedInterview()
            vm.onIntent(
                InterviewIntent.Turn.ReportRecordingSegmentFinalized(
                    createSegment(InterviewMediaSegmentType.QUESTION_VIDEO),
                ),
            )
            runCurrent()
            assertEquals(InterviewScreenState.ANSWER_RECORDING, vm.state.value.screenState)
            val answer = createSegment(InterviewMediaSegmentType.ANSWER_VIDEO)
            fixture.saveManifestFailure = IllegalStateException()
            vm.onIntent(InterviewIntent.Recovery.ReportAppBackgrounded)
            vm.onIntent(InterviewIntent.Turn.ReportRecordingSegmentFinalized(answer))
            vm.onIntent(InterviewIntent.Recovery.BackgroundRecordingStopped)
            runCurrent()
            assertFalse(checkNotNull(fixture.progress).isFrozen)
            assertFalse(InterviewEffect.InterviewFrozen in effects)
            assertEquals(1, fixture.fatalPromptCount)
        }

    @Test
    fun `제출 중 열린 녹화가 없으면 즉시 동결하고 늦은 서버 응답을 무시한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gate = CompletableDeferred<Unit>()
            var submissions = 0
            val repo =
                object : InterviewRepository by fixture.interviewRepository {
                    override suspend fun submitAnswer(
                        command: SubmitInterviewAnswerCommand,
                    ): SubmitAnswerResult {
                        submissions++
                        withContext(NonCancellable) { gate.await() }
                        return SubmitAnswerResult(
                            1L,
                            NextQuestion(33L, false, QuestionTurn(1, 0)),
                            false,
                            null,
                            null,
                        )
                    }
                }
            val vm = startConfirmedInterview(submissions = repo)
            fixture.manifests[sessionId] = InterviewMediaManifest(sessionId = sessionId)
            vm.onIntent(InterviewIntent.Timing.ReportHardCapReached)
            runCurrent()
            assertEquals(InterviewScreenState.ANSWER_SUBMITTING, vm.state.value.screenState)
            vm.onIntent(InterviewIntent.Recovery.ReportAppBackgrounded)
            vm.onIntent(InterviewIntent.Recovery.BackgroundRecordingStopped)
            runCurrent()
            assertTrue(checkNotNull(fixture.progress).isFrozen)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, submissions)
            assertEquals(1L, vm.state.value.questionId)
            assertFalse(
                effects.any {
                    it is InterviewEffect.NavigateToError ||
                        it is InterviewEffect.EnqueueVideoUpload
                },
            )
        }

    @Test
    fun `화면 회전과 일시 정지는 동결을 일으키지 않고 실제 연결 단절은 네트워크 복구로 이동한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            assertFalse(
                isInterviewBackgroundStop(Lifecycle.Event.ON_STOP, changingConfigurations = true),
            )
            assertFalse(
                isInterviewBackgroundStop(Lifecycle.Event.ON_PAUSE, changingConfigurations = false),
            )
            assertTrue(
                isInterviewBackgroundStop(Lifecycle.Event.ON_STOP, changingConfigurations = false),
            )
            val vm = startConfirmedInterview()
            vm.onIntent(InterviewIntent.Recovery.ReportNetworkDisconnected)
            runCurrent()
            assertTrue(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK) in effects)
            assertFalse(checkNotNull(fixture.progress).isFrozen)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `동결된 면접도 최초 저장 후 하루가 지나면 삭제한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            fixture.progress = checkNotNull(fixture.progress).copy(isFrozen = true)
            fixture.clockDeltaMillis = InterviewTimeCalculator.RETENTION_MILLIS
            val vm = fixture.createViewModel()
            vm.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            assertNull(fixture.progress)
        }

    @Test
    fun `동결 표식이 없는 기존 타이머는 저장된 시각부터 계속 흐른다`() {
        val oldProgress =
            checkNotNull(fixture.progress).copy(
                timerStartedAtEpochMillis = 1_000L,
                elapsedAtCheckpointMillis = 60_000L,
                checkpointedAtEpochMillis = 61_000L,
                elapsedCheckpointElapsedRealtimeMillis = 61_000L,
            )
        assertEquals(
            120_000L,
            InterviewTimeCalculator().elapsedMillis(oldProgress, 121_000L, 121_000L),
        )
    }

    private suspend fun createSegment(type: InterviewMediaSegmentType) =
        CreateInterviewMediaSegmentUseCase(
            fixture.localRepository,
        )(sessionId, type, 1L, 0L, 0L, "mp4")

    private fun TestScope.startConfirmedInterview(
        questionId: Long = 1L,
        submissions: InterviewRepository = fixture.interviewRepository,
    ): InterviewViewModel {
        val vm = fixture.createViewModel(submissions)
        backgroundScope.launch { vm.effect.toList(effects) }
        vm.onIntent(InterviewIntent.Preparation.LoadConfirmedResume(sessionId, questionId))
        runCurrent()
        vm.onIntent(InterviewIntent.Preparation.ReportCameraReady)
        vm.onIntent(InterviewIntent.Preparation.ReportMicrophoneReady)
        vm.onIntent(InterviewIntent.Preparation.ReportStorageAvailability(Long.MAX_VALUE))
        runCurrent()
        assertEquals(InterviewScreenState.QUESTION_PLAYING, vm.state.value.screenState)
        effects.clear()
        return vm
    }
}
