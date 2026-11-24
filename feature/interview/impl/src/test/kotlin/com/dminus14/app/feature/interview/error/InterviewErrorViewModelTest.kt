package com.dminus14.app.feature.interview.error

import com.dminus14.app.domain.model.InterviewAbandon
import com.dminus14.app.domain.model.InterviewAbandonCause
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewResumeConfirm
import com.dminus14.app.domain.model.InterviewResumeState
import com.dminus14.app.domain.model.InterviewResumeStatus
import com.dminus14.app.domain.model.InterviewTerminalStatus
import com.dminus14.app.domain.model.InterviewTicketOutcome
import com.dminus14.app.domain.model.InterviewUploadTaskStatus
import com.dminus14.app.domain.usecase.AbandonInterviewUseCase
import com.dminus14.app.domain.usecase.ConfirmInterviewResumeUseCase
import com.dminus14.app.domain.usecase.DeleteInterviewSessionUseCase
import com.dminus14.app.domain.usecase.GetInterviewMediaManifestUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewResumeUseCase
import com.dminus14.app.domain.usecase.RetainInterviewSessionForCleanupUseCase
import com.dminus14.app.domain.usecase.SavePendingInterviewAnswerUseCase
import com.dminus14.app.domain.usecase.SubmitAnswerUseCase
import com.dminus14.app.feature.interview.api.InterviewErrorType
import com.dminus14.app.feature.interview.interview.InterviewRecoveryStore
import com.dminus14.app.feature.interview.interview.InterviewViewModelTestFixture
import com.dminus14.app.feature.interview.interview.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewErrorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `중단 응답이 리포트를 만들지 않아도 미디어를 보존 기한 정리 대상으로 남긴다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val sessionId = InterviewViewModelTestFixture.SESSION_ID
            fixture.manifests[sessionId] = InterviewMediaManifest(sessionId = sessionId)
            fixture.abandonResult =
                InterviewAbandon(
                    sessionId = sessionId,
                    status = InterviewTerminalStatus.Abandoned,
                    abandonCause = InterviewAbandonCause.UserExit,
                    endedAt = "",
                    ticketOutcome = InterviewTicketOutcome.Committed,
                    reportGenerating = false,
                )
            val viewModel = createViewModel(fixture)

            viewModel.onIntent(InterviewErrorIntent.Load(InterviewErrorType.SERVER_TEMPORARY))
            runCurrent()
            viewModel.onIntent(InterviewErrorIntent.ClickAbort)
            runCurrent()

            assertNull(fixture.progress)
            assertNotNull(fixture.manifests[sessionId])
            assertEquals(
                InterviewUploadTaskStatus.RETAINED,
                fixture.uploadTasks.values
                    .single()
                    .status,
            )
        }

    @Test
    fun `연속 중단 입력은 서버 요청과 종료 결과를 한 번만 만든다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = InterviewViewModelTestFixture()
            val sessionId = InterviewViewModelTestFixture.SESSION_ID
            fixture.manifests[sessionId] = InterviewMediaManifest(sessionId = sessionId)
            fixture.abandonResult =
                InterviewAbandon(
                    sessionId = sessionId,
                    status = InterviewTerminalStatus.Abandoned,
                    abandonCause = InterviewAbandonCause.UserExit,
                    endedAt = "",
                    ticketOutcome = InterviewTicketOutcome.Committed,
                    reportGenerating = false,
                )
            val viewModel = createViewModel(fixture)
            val effects = mutableListOf<InterviewErrorEffect>()
            backgroundScope.launch { viewModel.effect.collect { effects += it } }
            viewModel.onIntent(InterviewErrorIntent.Load(InterviewErrorType.SERVER_TEMPORARY))
            runCurrent()

            viewModel.onIntent(InterviewErrorIntent.ClickAbort)
            viewModel.onIntent(InterviewErrorIntent.ClickAbort)
            runCurrent()
            viewModel.onIntent(InterviewErrorIntent.ClickAbort)
            runCurrent()

            assertEquals(1, fixture.abandonCallCount)
            assertEquals(listOf(InterviewErrorEffect.InterviewAbandonCompleted), effects)
        }

    @Test
    fun `재개 확정이 홀드 만료로 종료를 반환하면 로컬 데이터를 즉시 삭제하고 종료 결과를 전달한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = createResumeFixture(InterviewAbandonCause.HoldExpired)
            val recoveryStore = InterviewRecoveryStore()
            val viewModel = createViewModel(fixture, recoveryStore)
            val effects = mutableListOf<InterviewErrorEffect>()
            backgroundScope.launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(InterviewErrorIntent.Load(InterviewErrorType.NETWORK))
            runCurrent()
            viewModel.onIntent(InterviewErrorIntent.ClickResume)
            runCurrent()

            assertNull(fixture.progress)
            assertNull(fixture.manifests[InterviewViewModelTestFixture.SESSION_ID])
            assertEquals(0, fixture.uploadTasks.size)
            assertNull(recoveryStore.consume())
            assertEquals(listOf(InterviewErrorEffect.InterviewAbandonCompleted), effects)
        }

    @Test
    fun `재개 확정의 종료 원인을 알 수 없으면 로컬 데이터를 보존하고 화면에 머문다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = createResumeFixture(InterviewAbandonCause.Unknown("NEW_CAUSE"))
            val viewModel = createViewModel(fixture)
            val effects = mutableListOf<InterviewErrorEffect>()
            backgroundScope.launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(InterviewErrorIntent.Load(InterviewErrorType.NETWORK))
            runCurrent()
            viewModel.onIntent(InterviewErrorIntent.ClickResume)
            runCurrent()

            assertNotNull(fixture.progress)
            assertNotNull(fixture.manifests[InterviewViewModelTestFixture.SESSION_ID])
            assertEquals(emptyList<InterviewErrorEffect>(), effects)
        }

    @Test
    fun `재개 확정의 종료 원인이 홀드 만료로 확정되지 않으면 미디어를 보존하고 종료 결과를 전달한다`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = createResumeFixture(cause = null)
            val recoveryStore = InterviewRecoveryStore()
            val viewModel = createViewModel(fixture, recoveryStore)
            val effects = mutableListOf<InterviewErrorEffect>()
            backgroundScope.launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(InterviewErrorIntent.Load(InterviewErrorType.NETWORK))
            runCurrent()
            viewModel.onIntent(InterviewErrorIntent.ClickResume)
            runCurrent()

            assertNull(fixture.progress)
            assertNotNull(fixture.manifests[InterviewViewModelTestFixture.SESSION_ID])
            assertNull(recoveryStore.consume())
            assertEquals(listOf(InterviewErrorEffect.InterviewAbandonCompleted), effects)
        }

    private fun createResumeFixture(cause: InterviewAbandonCause?) =
        InterviewViewModelTestFixture().apply {
            val sessionId = InterviewViewModelTestFixture.SESSION_ID
            manifests[sessionId] = InterviewMediaManifest(sessionId = sessionId)
            resumeStatus =
                InterviewResumeStatus(
                    resumeState = InterviewResumeState.Resumable,
                    startedAt = null,
                    elapsedSeconds = null,
                    status = null,
                )
            resumeConfirm =
                InterviewResumeConfirm(
                    nextQuestion = null,
                    sessionEnded = true,
                    wrapUpMessage = null,
                    endType = null,
                    status = InterviewTerminalStatus.Abandoned,
                    abandonCause = cause,
                    endedAt = null,
                )
        }

    private fun createViewModel(
        fixture: InterviewViewModelTestFixture,
        recoveryStore: InterviewRecoveryStore = InterviewRecoveryStore(),
    ) = InterviewErrorViewModel(
        getProgress = GetInterviewProgressUseCase(fixture.localRepository),
        getManifest = GetInterviewMediaManifestUseCase(fixture.localRepository),
        getResume = GetInterviewResumeUseCase(fixture.interviewRepository),
        confirmResume = ConfirmInterviewResumeUseCase(fixture.interviewRepository),
        deleteSession = DeleteInterviewSessionUseCase(fixture.localRepository),
        abandonInterview = AbandonInterviewUseCase(fixture.interviewRepository),
        submitAnswer = SubmitAnswerUseCase(fixture.interviewRepository),
        savePendingAnswer = SavePendingInterviewAnswerUseCase(fixture.localRepository),
        retainSessionForCleanup =
            RetainInterviewSessionForCleanupUseCase(fixture.localRepository),
        recoveryStore = recoveryStore,
    )
}
