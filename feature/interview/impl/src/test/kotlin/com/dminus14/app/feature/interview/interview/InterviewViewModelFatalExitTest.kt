package com.dminus14.app.feature.interview.interview

import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaFinalizeState
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaOwnerType
import com.dminus14.app.domain.model.InterviewMediaSegment
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.model.NextQuestion
import com.dminus14.app.domain.model.QuestionTurn
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SummaryQuestion
import com.dminus14.app.feature.interview.InterviewConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewViewModelFatalExitTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `로컬 제출 저장 실패는 busy를 해제하고 세션을 보존한 채 홈 Effect를 보낸다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = configuredFixture()
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }
            val answerAudio = prepareRecordedAnswer(viewModel, fixture)
            fixture.saveManifestFailure = IllegalStateException("synthetic save failure")
            fixture.successfulManifestSavesBeforeFailure = 1

            viewModel.onIntent(InterviewIntent.ReportAnswerRecordingCompleted(answerAudio))
            runCurrent()
            viewModel.onIntent(InterviewIntent.ReportAppBackgrounded)

            assertFalse(viewModel.state.value.isRequestInFlight)
            assertEquals(1, fixture.fatalPromptCount)
            assertEquals(1, effects.count { it == InterviewEffect.FatalExitConfirmed })
            assertNotNull(fixture.progress)
            assertNotNull(fixture.manifests[InterviewViewModelTestFixture.SESSION_ID])
        }

    @Test
    fun `다음 질문이 없는 비종료 응답은 추가 제출 없이 치명적 이탈로 전환한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture =
                configuredFixture().apply {
                    submitResult =
                        SubmitAnswerResult(
                            answerId = 10L,
                            nextQuestion = null,
                            sessionEnded = false,
                            wrapUpMessage = null,
                            endType = null,
                        )
                }
            val viewModel = fixture.createViewModel()
            val effects = mutableListOf<InterviewEffect>()
            backgroundScope.launch { viewModel.effect.toList(effects) }
            val answerAudio = prepareRecordedAnswer(viewModel, fixture)

            viewModel.onIntent(InterviewIntent.ReportAnswerRecordingCompleted(answerAudio))
            runCurrent()
            viewModel.onIntent(InterviewIntent.ReportAppBackgrounded)

            assertFalse(viewModel.state.value.isRequestInFlight)
            assertEquals(1, fixture.submittedCommands.size)
            assertEquals(1, fixture.fatalPromptCount)
            assertEquals(1, effects.count { it == InterviewEffect.FatalExitConfirmed })
            assertNotNull(fixture.progress)
        }

    @Test
    fun `강제 종료를 수락하면 열려 있던 종료 Modal을 모두 닫는다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = configuredFixture()
            val viewModel = fixture.createViewModel()
            prepareRecordedAnswer(viewModel, fixture)

            viewModel.onIntent(InterviewIntent.ClickFinishInterview)
            viewModel.onIntent(InterviewIntent.ClickExitInterview)
            assertEquals(true, viewModel.state.value.showFinishConfirmation)
            assertEquals(true, viewModel.state.value.showEarlyExitWarning)

            viewModel.onIntent(InterviewIntent.ReportHardCapReached)
            viewModel.onIntent(InterviewIntent.ReportAppBackgrounded)

            assertFalse(viewModel.state.value.showFinishConfirmation)
            assertFalse(viewModel.state.value.showEarlyExitWarning)
        }

    @Test
    fun `종료 요청을 서버가 계속시키면 같은 종료 요청을 반복하지 않고 이탈한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture =
                configuredFixture().apply {
                    submitResult =
                        SubmitAnswerResult(
                            answerId = 10L,
                            nextQuestion =
                                NextQuestion(
                                    questionId = 2L,
                                    isLast = false,
                                    turn = QuestionTurn(turnLevel = 1, depthLevel = 1),
                                ),
                            sessionEnded = false,
                            wrapUpMessage = null,
                            endType = null,
                        )
                }
            val viewModel = fixture.createViewModel()
            val answerAudio = prepareRecordedAnswer(viewModel, fixture)
            viewModel.onIntent(InterviewIntent.ReportHardCapReached)

            viewModel.onIntent(InterviewIntent.ReportAnswerRecordingCompleted(answerAudio))
            runCurrent()
            viewModel.onIntent(InterviewIntent.ReportAppBackgrounded)

            assertEquals(1, fixture.submittedCommands.size)
            assertEquals(
                com.dminus14.app.domain.model.InterviewAnswerEndRequest.HardCap,
                fixture.submittedCommands.single().endType,
            )
            assertEquals(1, fixture.fatalPromptCount)
        }

    private fun configuredFixture() =
        InterviewViewModelTestFixture().apply {
            sessionStatus =
                InterviewSessionStatus(
                    status = InterviewSessionStatusType.READY,
                    startedAt = null,
                    summaryQuestion =
                        SummaryQuestion(
                            questionId = QUESTION_ID,
                            ttsAudio = null,
                            turn = QuestionTurn(turnLevel = 1, depthLevel = 0),
                        ),
                )
        }

    private fun TestScope.prepareRecordedAnswer(
        viewModel: InterviewViewModel,
        fixture: InterviewViewModelTestFixture,
    ): InterviewMediaSegment {
        val questionSegment = segment(0, InterviewMediaSegmentType.QUESTION_VIDEO)
        fixture.manifests[InterviewViewModelTestFixture.SESSION_ID] =
            InterviewMediaManifest(
                sessionId = InterviewViewModelTestFixture.SESSION_ID,
                nextSequence = 1,
                currentQuestionId = QUESTION_ID,
                segments = listOf(questionSegment),
            )
        viewModel.onIntent(InterviewIntent.LoadInterview)
        runCurrent()
        viewModel.onIntent(InterviewIntent.ReportCameraPermissionGranted)
        viewModel.onIntent(InterviewIntent.ReportCameraReady)
        viewModel.onIntent(InterviewIntent.ReportMicrophoneReady)
        viewModel.onIntent(InterviewIntent.ReportStorageAvailability(Long.MAX_VALUE))
        advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS * 2L)
        runCurrent()
        viewModel.onIntent(InterviewIntent.StartInterview)
        runCurrent()
        viewModel.onIntent(InterviewIntent.ReportQuestionPlaybackCompleted)
        viewModel.onIntent(InterviewIntent.ReportRecordingSegmentFinalized(questionSegment))
        runCurrent()

        val answerSegment = segment(1, InterviewMediaSegmentType.ANSWER_VIDEO)
        val manifest = fixture.manifests.getValue(InterviewViewModelTestFixture.SESSION_ID)
        fixture.manifests[InterviewViewModelTestFixture.SESSION_ID] =
            manifest.copy(nextSequence = 2, segments = manifest.segments + answerSegment)
        viewModel.onIntent(InterviewIntent.ReportAnswerSpeechStarted)
        viewModel.onIntent(InterviewIntent.ClickFinishAnswer)
        viewModel.onIntent(InterviewIntent.ReportRecordingSegmentFinalized(answerSegment))
        runCurrent()
        return fixture.manifests
            .getValue(InterviewViewModelTestFixture.SESSION_ID)
            .segments
            .single { it.type == InterviewMediaSegmentType.ANSWER_AUDIO }
    }

    private fun segment(
        sequence: Int,
        type: InterviewMediaSegmentType,
    ) = InterviewMediaSegment(
        sequence = sequence,
        type = type,
        mediaRef = mediaRef(sequence, type),
        questionId = QUESTION_ID,
        startedAtMillis = 0L,
        endedAtMillis = null,
        gapBeforeMillis = 0L,
        finalizeState = InterviewMediaFinalizeState.WRITING,
    )

    private fun mediaRef(
        sequence: Int,
        type: InterviewMediaSegmentType,
    ) = InterviewMediaFileRef(
        value = UUID.nameUUIDFromBytes("$type-$sequence".toByteArray()).toString(),
        ownerType = InterviewMediaOwnerType.SESSION,
        ownerId = InterviewViewModelTestFixture.SESSION_ID.toString(),
        segmentType = type,
    )

    private companion object {
        const val QUESTION_ID = 1L
    }
}
