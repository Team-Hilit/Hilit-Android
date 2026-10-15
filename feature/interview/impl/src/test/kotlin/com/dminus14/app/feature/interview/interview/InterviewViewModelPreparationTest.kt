package com.dminus14.app.feature.interview.interview

import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.model.QuestionTurn
import com.dminus14.app.domain.model.SummaryQuestion
import com.dminus14.app.feature.interview.InterviewConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewViewModelPreparationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `준비가 즉시 완료되어도 각 준비 화면을 3초씩 유지한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = readyFixture()
            val viewModel = fixture.createViewModel()

            viewModel.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            viewModel.completeClientPreparation()
            runCurrent()

            assertEquals(InterviewScreenState.DEVICE_CHECK, viewModel.state.value.screenState)
            assertEquals(1L, viewModel.state.value.questionId)
            assertTrue(viewModel.state.value.isReadyToStart)

            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS - 1L)
            runCurrent()
            assertEquals(InterviewScreenState.DEVICE_CHECK, viewModel.state.value.screenState)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(InterviewScreenState.QUESTION_PREPARING, viewModel.state.value.screenState)

            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS - 1L)
            runCurrent()
            assertEquals(InterviewScreenState.QUESTION_PREPARING, viewModel.state.value.screenState)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(InterviewScreenState.START_GUIDE, viewModel.state.value.screenState)
        }

    @Test
    fun `준비 조건이 늦게 충족되면 준비 중 경과 시간을 다시 기다리지 않는다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = readyFixture()
            val viewModel = fixture.createViewModel()

            viewModel.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
            runCurrent()

            assertEquals(InterviewScreenState.DEVICE_CHECK, viewModel.state.value.screenState)

            viewModel.completeClientPreparation()
            runCurrent()
            assertEquals(InterviewScreenState.QUESTION_PREPARING, viewModel.state.value.screenState)

            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS - 1L)
            runCurrent()
            assertEquals(InterviewScreenState.QUESTION_PREPARING, viewModel.state.value.screenState)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(InterviewScreenState.START_GUIDE, viewModel.state.value.screenState)
        }

    @Test
    fun `준비 완료 화면에서 준비 조건이 해제되면 시작 안내로 전환하지 않는다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = readyFixture().createViewModel()
            viewModel.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            viewModel.completeClientPreparation()
            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
            runCurrent()
            assertEquals(InterviewScreenState.QUESTION_PREPARING, viewModel.state.value.screenState)

            viewModel.onIntent(InterviewIntent.Preparation.ReportStorageAvailability(0L))
            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
            runCurrent()
            assertEquals(InterviewScreenState.QUESTION_PREPARING, viewModel.state.value.screenState)

            viewModel.onIntent(
                InterviewIntent.Preparation.ReportStorageAvailability(Long.MAX_VALUE),
            )
            runCurrent()
            assertEquals(InterviewScreenState.START_GUIDE, viewModel.state.value.screenState)
        }

    @Test
    fun `백그라운드에 머문 시간도 준비 화면 유지 시간에 포함한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = readyFixture().createViewModel()
            viewModel.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            viewModel.completeClientPreparation()

            advanceTimeBy(1_000L)
            viewModel.onIntent(InterviewIntent.Recovery.ReportAppBackgrounded)
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(InterviewScreenState.QUESTION_PREPARING, viewModel.state.value.screenState)

            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
            runCurrent()
            assertEquals(InterviewScreenState.START_GUIDE, viewModel.state.value.screenState)
        }

    @Test
    fun `새 ViewModel은 준비 화면 유지 시간을 처음부터 측정한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val firstViewModel = readyFixture().createViewModel()
            firstViewModel.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            firstViewModel.completeClientPreparation()
            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
            runCurrent()
            assertEquals(
                InterviewScreenState.QUESTION_PREPARING,
                firstViewModel.state.value.screenState,
            )

            val recreatedViewModel = readyFixture().createViewModel()
            recreatedViewModel.onIntent(InterviewIntent.Preparation.LoadInterview)
            runCurrent()
            recreatedViewModel.completeClientPreparation()
            runCurrent()

            assertEquals(
                InterviewScreenState.DEVICE_CHECK,
                recreatedViewModel.state.value.screenState,
            )

            advanceTimeBy(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS - 1L)
            runCurrent()
            assertEquals(
                InterviewScreenState.DEVICE_CHECK,
                recreatedViewModel.state.value.screenState,
            )

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(
                InterviewScreenState.QUESTION_PREPARING,
                recreatedViewModel.state.value.screenState,
            )
        }

    private fun readyFixture(): InterviewViewModelTestFixture =
        InterviewViewModelTestFixture().apply {
            sessionStatus =
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
        }

    private fun InterviewViewModel.completeClientPreparation() {
        onIntent(InterviewIntent.Preparation.ReportCameraPermissionGranted)
        onIntent(InterviewIntent.Preparation.ReportCameraReady)
        onIntent(InterviewIntent.Preparation.ReportMicrophoneReady)
        onIntent(InterviewIntent.Preparation.ReportStorageAvailability(Long.MAX_VALUE))
    }
}
