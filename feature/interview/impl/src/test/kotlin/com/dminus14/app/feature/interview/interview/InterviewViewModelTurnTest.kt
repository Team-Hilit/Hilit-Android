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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewViewModelTurnTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `답변 녹화를 확정하면 같은 질문을 제출하고 다음 질문을 재생한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = configuredFixture()
            val questionSegment = segment(0, InterviewMediaSegmentType.QUESTION_VIDEO, 1L)
            fixture.manifests[InterviewViewModelTestFixture.SESSION_ID] =
                InterviewMediaManifest(
                    sessionId = InterviewViewModelTestFixture.SESSION_ID,
                    nextSequence = 1,
                    currentQuestionId = 1L,
                    segments = listOf(questionSegment),
                )
            val viewModel = fixture.createViewModel()

            viewModel.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            viewModel.onIntent(InterviewIntent.Preparation.ReportCameraPermissionGranted)
            viewModel.onIntent(InterviewIntent.Preparation.ReportCameraReady)
            viewModel.onIntent(InterviewIntent.Preparation.ReportMicrophoneReady)
            viewModel.onIntent(
                InterviewIntent.Preparation.ReportStorageAvailability(Long.MAX_VALUE),
            )
            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS * 2L)
            runCurrent()
            viewModel.onIntent(InterviewIntent.Timing.StartInterview)
            runCurrent()
            viewModel.onIntent(InterviewIntent.Turn.ReportQuestionPlaybackStarted)
            viewModel.onIntent(InterviewIntent.Turn.ReportQuestionPlaybackCompleted)
            viewModel.onIntent(
                InterviewIntent.Turn.ReportRecordingSegmentFinalized(questionSegment),
            )
            runCurrent()

            val answerSegment = segment(1, InterviewMediaSegmentType.ANSWER_VIDEO, 1L)
            fixture.manifests.computeValue { manifest ->
                manifest.copy(nextSequence = 2, segments = manifest.segments + answerSegment)
            }
            viewModel.onIntent(InterviewIntent.Turn.ReportAnswerSpeechStarted)
            viewModel.onIntent(InterviewIntent.Turn.ClickFinishAnswer)
            viewModel.onIntent(InterviewIntent.Turn.ReportRecordingSegmentFinalized(answerSegment))
            runCurrent()

            val answerAudio =
                fixture.manifests
                    .getValue(InterviewViewModelTestFixture.SESSION_ID)
                    .segments
                    .single { it.type == InterviewMediaSegmentType.ANSWER_AUDIO }
            viewModel.onIntent(InterviewIntent.Turn.ReportAnswerRecordingCompleted(answerAudio))
            runCurrent()

            assertEquals(1, fixture.submittedCommands.size)
            assertEquals(1L, fixture.submittedCommands.single().questionId)
            assertEquals(2L, viewModel.state.value.questionId)
            assertEquals(InterviewScreenState.QUESTION_PLAYING, viewModel.state.value.screenState)
            assertFalse(viewModel.state.value.isRequestInFlight)

            viewModel.onIntent(InterviewIntent.Recovery.ReportAppBackgrounded)
            runCurrent()
        }

    private fun configuredFixture() =
        InterviewViewModelTestFixture().apply {
            sessionStatus = readyStatus(questionId = 1L)
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

    private fun readyStatus(questionId: Long) =
        InterviewSessionStatus(
            status = InterviewSessionStatusType.READY,
            startedAt = null,
            summaryQuestion =
                SummaryQuestion(
                    questionId = questionId,
                    ttsAudio = null,
                    turn = QuestionTurn(turnLevel = 1, depthLevel = 0),
                ),
        )

    private fun segment(
        sequence: Int,
        type: InterviewMediaSegmentType,
        questionId: Long,
    ) = InterviewMediaSegment(
        sequence = sequence,
        type = type,
        mediaRef =
            InterviewMediaFileRef(
                value = UUID.nameUUIDFromBytes("$type-$sequence".toByteArray()).toString(),
                ownerType = InterviewMediaOwnerType.SESSION,
                ownerId = InterviewViewModelTestFixture.SESSION_ID.toString(),
                segmentType = type,
            ),
        questionId = questionId,
        startedAtMillis = 0L,
        endedAtMillis = null,
        gapBeforeMillis = 0L,
        finalizeState = InterviewMediaFinalizeState.WRITING,
    )
}

private fun MutableMap<Long, InterviewMediaManifest>.computeValue(
    transform: (InterviewMediaManifest) -> InterviewMediaManifest,
) {
    val sessionId = InterviewViewModelTestFixture.SESSION_ID
    this[sessionId] = transform(getValue(sessionId))
}
