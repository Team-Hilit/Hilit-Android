package com.dminus14.app.feature.interview.interview

import com.dminus14.app.domain.model.InterviewEndType
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaFinalizeState
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaOwnerType
import com.dminus14.app.domain.model.InterviewMediaSegment
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.model.QuestionTurn
import com.dminus14.app.domain.model.SummaryQuestion
import com.dminus14.app.domain.model.WrapUpMessage
import com.dminus14.app.feature.interview.InterviewConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewViewModelFinalizationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `마무리 녹화가 15초 안에 확정되면 실패 Modal을 표시하지 않는다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val segment = wrapUpSegment()
            fixture.manifests[InterviewViewModelTestFixture.SESSION_ID] = manifest(segment)
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }

            enterFinalization(viewModel, fixture, reportGenerating = false)
            viewModel.onIntent(InterviewIntent.ReportRecordingSegmentFinalized(segment))
            runCurrent()
            advanceTimeBy(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
            runCurrent()

            assertNull(viewModel.state.value.finalizationFailure)
            assertEquals(1, effects.filterIsInstance<InterviewEffect.InterviewEnded>().size)
        }

    @Test
    fun `마무리 녹화 확정 지연을 재시도하면 기존 확정을 기다린 뒤 새 세그먼트를 시작한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val segment = wrapUpSegment()
            fixture.manifests[InterviewViewModelTestFixture.SESSION_ID] = manifest(segment)
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }

            enterFinalization(viewModel, fixture, reportGenerating = false)
            advanceTimeBy(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
            runCurrent()

            assertEquals(
                InterviewFinalizationFailure.RECORDING_FINALIZATION_TIMEOUT,
                viewModel.state.value.finalizationFailure,
            )
            val stopCount = effects.count { it == InterviewEffect.StopRecordingSegment }
            val startCount = effects.filterIsInstance<InterviewEffect.StartRecordingSegment>().size

            viewModel.onIntent(InterviewIntent.ClickRetryFinalization)
            runCurrent()

            assertNull(viewModel.state.value.finalizationFailure)
            assertEquals(stopCount, effects.count { it == InterviewEffect.StopRecordingSegment })
            assertEquals(
                startCount,
                effects.filterIsInstance<InterviewEffect.StartRecordingSegment>().size,
            )

            viewModel.onIntent(InterviewIntent.ReportRecordingSegmentFinalized(segment))
            runCurrent()

            assertEquals(
                startCount + 1,
                effects.filterIsInstance<InterviewEffect.StartRecordingSegment>().size,
            )
            assertEquals(2, effects.filterIsInstance<InterviewEffect.PlayWrapUpMessage>().size)
            assertEquals(0, effects.filterIsInstance<InterviewEffect.InterviewEnded>().size)

            advanceTimeBy(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
            runCurrent()

            assertEquals(
                InterviewFinalizationFailure.RECORDING_FINALIZATION_TIMEOUT,
                viewModel.state.value.finalizationFailure,
            )
        }

    @Test
    fun `마무리 녹화 확정 지연을 재시도하면 기존 실패를 기다린 뒤 새 세그먼트를 시작한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }

            enterFinalization(viewModel, fixture, reportGenerating = false)
            advanceTimeBy(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
            runCurrent()
            val stopCount = effects.count { it == InterviewEffect.StopRecordingSegment }

            viewModel.onIntent(InterviewIntent.ClickRetryFinalization)
            viewModel.onIntent(InterviewIntent.ReportRecordingFailure)
            runCurrent()

            assertNull(viewModel.state.value.finalizationFailure)
            assertEquals(stopCount, effects.count { it == InterviewEffect.StopRecordingSegment })
            assertEquals(
                2,
                effects.filterIsInstance<InterviewEffect.StartRecordingSegment>().size,
            )
            assertEquals(2, effects.filterIsInstance<InterviewEffect.PlayWrapUpMessage>().size)
        }

    @Test
    fun `마무리 녹화 실패를 재시도하면 저장한 음성으로 새 세그먼트를 시작한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }

            enterFinalization(viewModel, fixture, reportGenerating = false)
            viewModel.onIntent(InterviewIntent.ReportRecordingFailure)
            viewModel.onIntent(InterviewIntent.ClickRetryFinalization)
            runCurrent()

            assertEquals(
                2,
                effects.filterIsInstance<InterviewEffect.StartRecordingSegment>().size,
            )
            assertEquals(2, effects.filterIsInstance<InterviewEffect.PlayWrapUpMessage>().size)
            assertNull(viewModel.state.value.finalizationFailure)
        }

    @Test
    fun `마무리 범위 저장 실패는 녹화를 중지하고 치명적 이탈로 전환한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val segment = wrapUpSegment()
            fixture.manifests[InterviewViewModelTestFixture.SESSION_ID] = manifest(segment)
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }

            enterFinalization(viewModel, fixture, reportGenerating = false)
            fixture.saveManifestFailure = IllegalStateException("synthetic save failure")
            viewModel.onIntent(InterviewIntent.ReportWrapUpPlaybackCompleted)
            runCurrent()

            assertEquals(1, fixture.fatalPromptCount)
            assertEquals(1, effects.count { it == InterviewEffect.StopRecordingSegment })
            assertEquals(1, effects.count { it == InterviewEffect.FatalExitConfirmed })

            fixture.saveManifestFailure = null
            viewModel.onIntent(InterviewIntent.ReportRecordingSegmentFinalized(segment))
            runCurrent()

            assertEquals(0, effects.filterIsInstance<InterviewEffect.InterviewEnded>().size)
        }

    @Test
    fun `업로드 인계 실패를 재시도하면 마지막 네트워크 정책을 다시 사용한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }

            enterFinalization(
                viewModel = viewModel,
                fixture = fixture,
                reportGenerating = true,
                wrapUpPayload = null,
            )
            viewModel.onIntent(InterviewIntent.ReportUploadNotificationPermission(true))
            viewModel.onIntent(InterviewIntent.ReportUploadNetworkMetered(false))
            viewModel.onIntent(InterviewIntent.ReportVideoUploadEnqueueFailure)
            viewModel.onIntent(InterviewIntent.ClickRetryFinalization)
            runCurrent()

            val enqueueEffects = effects.filterIsInstance<InterviewEffect.EnqueueVideoUpload>()
            assertEquals(2, enqueueEffects.size)
            assertEquals(
                InterviewUploadNetworkPolicy.UNMETERED,
                enqueueEffects.last().networkPolicy,
            )
        }

    @Test
    fun `홈 이동 뒤 늦은 녹화 성공이 와도 종료 Effect는 한 번만 발생한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val segment = wrapUpSegment()
            fixture.manifests[InterviewViewModelTestFixture.SESSION_ID] = manifest(segment)
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }

            enterFinalization(viewModel, fixture, reportGenerating = false)
            advanceTimeBy(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
            runCurrent()
            viewModel.onIntent(InterviewIntent.ClickExitFinalization)
            viewModel.onIntent(InterviewIntent.ReportRecordingSegmentFinalized(segment))
            runCurrent()

            val completionEffects =
                effects.count {
                    it == InterviewEffect.FinalizationExitConfirmed ||
                        it is InterviewEffect.InterviewEnded
                }
            assertEquals(1, completionEffects)
        }

    private fun TestScope.enterFinalization(
        viewModel: InterviewViewModel,
        fixture: InterviewViewModelTestFixture,
        reportGenerating: Boolean,
        wrapUpPayload: String? = "synthetic-wrap-up",
    ) {
        fixture.sessionStatus =
            InterviewSessionStatus(
                status = InterviewSessionStatusType.READY,
                startedAt = null,
                summaryQuestion =
                    SummaryQuestion(
                        questionId = 1L,
                        ttsAudio = null,
                        turn = QuestionTurn(turnLevel = 1, depthLevel = 0),
                    ),
            )
        viewModel.onIntent(InterviewIntent.LoadInterview)
        runCurrent()
        viewModel.onIntent(InterviewIntent.ReportCameraPermissionGranted)
        viewModel.onIntent(InterviewIntent.ReportCameraReady)
        viewModel.onIntent(InterviewIntent.ReportMicrophoneReady)
        viewModel.onIntent(InterviewIntent.ReportStorageAvailability(Long.MAX_VALUE))
        fixture.recoveryStore.publish(
            InterviewRecoveryStore.Result(
                nextQuestion = null,
                sessionEnded = true,
                wrapUpMessage = wrapUpPayload?.let(::WrapUpMessage),
                endType = InterviewEndType.NormalEnd,
                reportGenerating = reportGenerating,
            ),
        )
        viewModel.onIntent(InterviewIntent.ConsumeRecoveryResult)
        runCurrent()
    }

    private fun wrapUpSegment() =
        InterviewMediaSegment(
            sequence = 0,
            type = InterviewMediaSegmentType.QUESTION_VIDEO,
            mediaRef =
                InterviewMediaFileRef(
                    value = UUID.nameUUIDFromBytes("wrap-up".toByteArray()).toString(),
                    ownerType = InterviewMediaOwnerType.SESSION,
                    ownerId = InterviewViewModelTestFixture.SESSION_ID.toString(),
                    segmentType = InterviewMediaSegmentType.QUESTION_VIDEO,
                ),
            questionId = null,
            startedAtMillis = 0L,
            endedAtMillis = null,
            gapBeforeMillis = 0L,
            finalizeState = InterviewMediaFinalizeState.WRITING,
        )

    private fun manifest(segment: InterviewMediaSegment) =
        InterviewMediaManifest(
            sessionId = InterviewViewModelTestFixture.SESSION_ID,
            nextSequence = 1,
            currentQuestionId = null,
            segments = listOf(segment),
        )
}
