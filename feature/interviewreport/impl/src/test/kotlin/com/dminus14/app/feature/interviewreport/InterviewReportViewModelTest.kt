package com.dminus14.app.feature.interviewreport

import com.dminus14.app.domain.model.GuestFeedbackItem
import com.dminus14.app.domain.model.GuestFeedbackSummary
import com.dminus14.app.domain.model.InterviewAbandon
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.model.InterviewReport
import com.dminus14.app.domain.model.InterviewReportList
import com.dminus14.app.domain.model.InterviewReportStatus
import com.dminus14.app.domain.model.InterviewReportVideo
import com.dminus14.app.domain.model.InterviewResumeConfirm
import com.dminus14.app.domain.model.InterviewResumeStatus
import com.dminus14.app.domain.model.InterviewSessionRequest
import com.dminus14.app.domain.model.InterviewSessionResult
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewVideoExpiry
import com.dminus14.app.domain.model.InterviewVideoUploadUrl
import com.dminus14.app.domain.model.JdValidationResult
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.model.UploadInterviewVideoCommand
import com.dminus14.app.domain.repository.InterviewRepository
import com.dminus14.app.domain.usecase.GetVideoExpiryUseCase
import com.dminus14.app.domain.usecase.ObserveInterviewReportUseCase
import com.dminus14.app.feature.interviewreport.InterviewReportState.Phase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewReportViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `READY 응답을 받으면 Ready 로 전이한다`() =
        runTest {
            val viewModel = viewModel(FakeInterviewRepository(listOf(readyReport())))

            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            val phase = viewModel.state.value.phase
            assertTrue("expected Ready but was $phase", phase is Phase.Ready)
        }

    @Test
    fun `INSUFFICIENT_ANALYSIS 응답은 InsufficientAnalysis 로 전이한다`() =
        runTest {
            val insufficient =
                readyReport().copy(
                    status = InterviewReportStatus.INSUFFICIENT_ANALYSIS,
                )
            val viewModel = viewModel(FakeInterviewRepository(listOf(insufficient)))

            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            val phase = viewModel.state.value.phase
            assertTrue(
                "expected InsufficientAnalysis but was $phase",
                phase is Phase.InsufficientAnalysis,
            )
        }

    @Test
    fun `getReport 가 예외로 종료되면 Failed 로 전이한다`() =
        runTest {
            val viewModel = viewModel(ThrowingInterviewRepository())

            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            assertEquals(Phase.Failed, viewModel.state.value.phase)
        }

    @Test
    fun `status UNKNOWN 응답은 Loading 이 아니라 Failed 로 전이한다`() =
        runTest {
            // ObserveInterviewReportUseCase 는 GENERATING 이 아니면(UNKNOWN 포함) 폴링을 멈추고
            // flow 를 종료하므로, UNKNOWN 을 Loading 으로 매핑하면 다음 emit 이 오지 않아 스피너에
            // 영구 고착된다.
            val unknown = readyReport().copy(status = InterviewReportStatus.UNKNOWN)
            val viewModel = viewModel(FakeInterviewRepository(listOf(unknown)))

            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            assertEquals(Phase.Failed, viewModel.state.value.phase)
        }

    @Test
    fun `sessionId 가 바인딩되지 않았으면 Loading 이 아니라 Failed 로 전이한다`() =
        runTest {
            // 유효하지 않은 sessionId(0L)로 진입한 상태. 초기 phase 가
            // Loading 이라 load() 가 조용히 리턴하면 스피너에 영구 고착된다.
            val viewModel = viewModel(FakeInterviewRepository(listOf(readyReport())))

            viewModel.onIntent(InterviewReportIntent.Load(0L))
            advanceUntilIdle()

            assertEquals(Phase.Failed, viewModel.state.value.phase)
        }

    @Test
    fun `하이라이트 선택 후 해제하면 selectedHighlight 가 초기화된다`() =
        runTest {
            val viewModel = viewModel(FakeInterviewRepository(listOf(readyReport())))
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            viewModel.onIntent(InterviewReportIntent.ClickHighlight(0, 1))
            assertEquals(
                InterviewReportState.SelectedHighlight(0, 1),
                viewModel.state.value.selectedHighlight,
            )

            viewModel.onIntent(InterviewReportIntent.DismissHighlight)
            assertNull(viewModel.state.value.selectedHighlight)
        }

    @Test
    fun `영상이 만료됐으면 ClickWatchVideo 는 Toast 를 발행하고 Player 로 이동하지 않는다`() =
        runTest {
            val expired =
                readyReport().copy(
                    video = InterviewReportVideo(url = null, expired = true, expiresAt = null),
                )
            val viewModel = viewModel(FakeInterviewRepository(listOf(expired)))
            val effects = mutableListOf<InterviewReportEffect>()
            val job = TestScope(dispatcher).launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()
            viewModel.onIntent(InterviewReportIntent.ClickWatchVideo)
            advanceUntilIdle()

            assertTrue(effects.any { it is InterviewReportEffect.ShowToast })
            assertTrue(effects.none { it is InterviewReportEffect.NavigateToPlayer })
            job.cancel()
        }

    @Test
    fun `영상이 유효하면 ClickWatchVideo 는 Player 로 이동한다`() =
        runTest {
            val available =
                readyReport().copy(
                    video =
                        InterviewReportVideo(
                            url = "https://cdn/x.mp4",
                            expired = false,
                            expiresAt = null,
                        ),
                )
            val viewModel = viewModel(FakeInterviewRepository(listOf(available)))
            val effects = mutableListOf<InterviewReportEffect>()
            val job = TestScope(dispatcher).launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(InterviewReportIntent.Load(5L))
            advanceUntilIdle()
            viewModel.onIntent(InterviewReportIntent.ClickWatchVideo)
            advanceUntilIdle()

            val navigate =
                effects
                    .filterIsInstance<InterviewReportEffect.NavigateToPlayer>()
                    .single()
            assertEquals(5L, navigate.sessionId)
            assertNull(navigate.startSec)
            job.cancel()
        }

    @Test
    fun `InsufficientAnalysis 상태에서도 영상이 유효하면 ClickWatchVideo 는 Player 로 이동한다`() =
        runTest {
            val insufficientWithVideo =
                readyReport().copy(
                    status = InterviewReportStatus.INSUFFICIENT_ANALYSIS,
                    video =
                        InterviewReportVideo(
                            url = "https://cdn/x.mp4",
                            expired = false,
                            expiresAt = null,
                        ),
                )
            val viewModel = viewModel(FakeInterviewRepository(listOf(insufficientWithVideo)))
            val effects = mutableListOf<InterviewReportEffect>()
            val job = TestScope(dispatcher).launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()
            viewModel.onIntent(InterviewReportIntent.ClickWatchVideo)
            advanceUntilIdle()

            assertTrue(effects.any { it is InterviewReportEffect.NavigateToPlayer })
            job.cancel()
        }

    @Test
    fun `SelectCard 인텐트는 selectedCardIndex 를 갱신한다`() =
        runTest {
            val viewModel = viewModel(FakeInterviewRepository(listOf(readyReport())))
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            viewModel.onIntent(InterviewReportIntent.SelectCard(2))

            assertEquals(2, viewModel.state.value.selectedCardIndex)
        }

    @Test
    fun `SelectGuestFeedback 인텐트는 selectedGuestFeedbackIndex 를 갱신한다`() =
        runTest {
            val viewModel = viewModel(FakeInterviewRepository(listOf(readyReport())))
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            viewModel.onIntent(InterviewReportIntent.SelectGuestFeedback(1))

            assertEquals(1, viewModel.state.value.selectedGuestFeedbackIndex)
        }

    @Test
    fun `만료 전이면 videoExpirySeconds 를 잔여 초로 세팅한다`() =
        runTest {
            val repository =
                FakeInterviewRepository(
                    responses = listOf(readyReport()),
                    expiry = InterviewVideoExpiry(expiresInSeconds = 3600L, expired = false),
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            assertEquals(3600L, viewModel.state.value.videoExpirySeconds)
        }

    @Test
    fun `이미 만료면 videoExpirySeconds 는 null 로 둔다`() =
        runTest {
            val repository =
                FakeInterviewRepository(
                    responses = listOf(readyReport()),
                    expiry = InterviewVideoExpiry(expiresInSeconds = 0L, expired = true),
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()

            assertNull(viewModel.state.value.videoExpirySeconds)
        }

    @Test
    fun `새로고침은 기존 리포트를 유지하고 중복 요청 없이 최신 피드백을 반영한다`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                FakeInterviewRepository(
                    listOf(
                        readyReport(),
                        readyReport().copy(
                            guestFeedback =
                                GuestFeedbackSummary(
                                    participantCount = 1,
                                    guests = listOf(GuestFeedbackItem("합성 지인", emptyList())),
                                ),
                        ),
                    ),
                    refreshGate = gate,
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()
            val before = viewModel.state.value.phase
            viewModel.onIntent(InterviewReportIntent.SelectGuestFeedback(2))
            repository.expiry = InterviewVideoExpiry(expiresInSeconds = 7200L, expired = false)
            viewModel.onIntent(InterviewReportIntent.RefreshReport)
            viewModel.onIntent(InterviewReportIntent.RefreshReport)
            assertEquals(2, viewModel.state.value.selectedGuestFeedbackIndex)
            assertTrue(viewModel.state.value.isRefreshing)
            assertEquals(before, viewModel.state.value.phase)
            assertEquals(2, repository.reportCalls)
            gate.complete(Unit)
            advanceUntilIdle()
            val report = (viewModel.state.value.phase as Phase.Ready).report
            assertEquals(1, report.guestFeedback?.participantCount)
            assertEquals(false, viewModel.state.value.isRefreshing)
            assertEquals(2, repository.expiryCalls)
            assertEquals(7200L, viewModel.state.value.videoExpirySeconds)
            assertEquals(0, viewModel.state.value.selectedGuestFeedbackIndex)
        }

    @Test
    fun `새로고침의 실패 응답은 기존 분석 부족 리포트를 유지하고 표시를 종료한다`() =
        runTest {
            val repository =
                FakeInterviewRepository(
                    listOf(
                        readyReport().copy(status = InterviewReportStatus.INSUFFICIENT_ANALYSIS),
                        readyReport().copy(status = InterviewReportStatus.FAILED),
                    ),
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()
            val before = viewModel.state.value.phase
            viewModel.onIntent(InterviewReportIntent.RefreshReport)
            advanceUntilIdle()
            assertEquals(before, viewModel.state.value.phase)
            assertEquals(false, viewModel.state.value.isRefreshing)
        }

    @Test
    fun `새로고침 중 예외가 발생해도 기존 리포트를 보존하고 다시 요청할 수 있다`() =
        runTest {
            val repository = FakeInterviewRepository(listOf(readyReport()))
            val viewModel = viewModel(repository)
            viewModel.onIntent(InterviewReportIntent.Load(1L))
            advanceUntilIdle()
            val before = viewModel.state.value.phase
            repository.refreshError = IllegalStateException("합성 조회 오류")
            viewModel.onIntent(InterviewReportIntent.RefreshReport)
            advanceUntilIdle()
            assertEquals(before, viewModel.state.value.phase)
            assertEquals(false, viewModel.state.value.isRefreshing)
            repository.refreshError = null
            viewModel.onIntent(InterviewReportIntent.RefreshReport)
            advanceUntilIdle()
            assertEquals(3, repository.reportCalls)
            assertEquals(false, viewModel.state.value.isRefreshing)
        }

    private fun readyReport(): InterviewReport =
        InterviewReport(
            status = InterviewReportStatus.READY,
            headline = "요약",
            video = null,
            cards = emptyList(),
            script = null,
            guestFeedback = null,
        )

    private fun viewModel(repository: InterviewRepository): InterviewReportViewModel =
        InterviewReportViewModel(
            ObserveInterviewReportUseCase(repository),
            GetVideoExpiryUseCase(repository),
        )
}

private class FakeInterviewRepository(
    private val responses: List<InterviewReport>,
    var expiry: InterviewVideoExpiry? = null,
    var refreshError: Throwable? = null,
    private val refreshGate: CompletableDeferred<Unit>? = null,
) : InterviewRepository {
    private var index: Int = 0
    var reportCalls = 0
    var expiryCalls = 0

    override suspend fun getReport(sessionId: Long): InterviewReport {
        reportCalls += 1
        if (reportCalls > 1) {
            refreshGate?.await()
            refreshError?.let { throw it }
        }
        val current = index.coerceAtMost(responses.lastIndex)
        index += 1
        return responses[current]
    }

    override suspend fun validateJdUrl(jdUrl: String): JdValidationResult = error("사용하지 않음")

    override suspend fun createInterviewSession(
        request: InterviewSessionRequest,
    ): InterviewSessionResult = error("사용하지 않음")

    override suspend fun getInterviewSession(sessionId: Long): InterviewSessionStatus =
        error("사용하지 않음")

    override suspend fun getInterviewSessionStatus(sessionId: Long): InterviewSessionStatus =
        error("사용하지 않음")

    override suspend fun getReportList(): InterviewReportList = error("사용하지 않음")

    override suspend fun submitAnswer(command: SubmitInterviewAnswerCommand): SubmitAnswerResult =
        error("사용하지 않음")

    override fun getAudioStreamUrl(
        sessionId: Long,
        questionId: Long,
    ): String = error("사용하지 않음")

    override suspend fun getResume(sessionId: Long): InterviewResumeStatus = error("사용하지 않음")

    override suspend fun confirmResume(sessionId: Long): InterviewResumeConfirm = error("사용하지 않음")

    override suspend fun abandon(
        sessionId: Long,
        cause: InterviewAbandonRequestCause,
    ): InterviewAbandon = error("사용하지 않음")

    override suspend fun issueUploadUrl(sessionId: Long): InterviewVideoUploadUrl = error("사용하지 않음")

    override suspend fun uploadVideo(command: UploadInterviewVideoCommand) = error("사용하지 않음")

    override suspend fun completeUpload(
        sessionId: Long,
        wrapUpStartSec: Float?,
        wrapUpEndSec: Float?,
    ) = error("사용하지 않음")

    override suspend fun getExpiry(sessionId: Long): InterviewVideoExpiry {
        expiryCalls += 1
        return expiry ?: error("사용하지 않음")
    }
}

private class ThrowingInterviewRepository : InterviewRepository {
    override suspend fun getReport(sessionId: Long): InterviewReport = error("네트워크 오류")

    override suspend fun validateJdUrl(jdUrl: String): JdValidationResult = error("사용하지 않음")

    override suspend fun createInterviewSession(
        request: InterviewSessionRequest,
    ): InterviewSessionResult = error("사용하지 않음")

    override suspend fun getInterviewSession(sessionId: Long): InterviewSessionStatus =
        error("사용하지 않음")

    override suspend fun getInterviewSessionStatus(sessionId: Long): InterviewSessionStatus =
        error("사용하지 않음")

    override suspend fun getReportList(): InterviewReportList = error("사용하지 않음")

    override suspend fun submitAnswer(command: SubmitInterviewAnswerCommand): SubmitAnswerResult =
        error("사용하지 않음")

    override fun getAudioStreamUrl(
        sessionId: Long,
        questionId: Long,
    ): String = error("사용하지 않음")

    override suspend fun getResume(sessionId: Long): InterviewResumeStatus = error("사용하지 않음")

    override suspend fun confirmResume(sessionId: Long): InterviewResumeConfirm = error("사용하지 않음")

    override suspend fun abandon(
        sessionId: Long,
        cause: InterviewAbandonRequestCause,
    ): InterviewAbandon = error("사용하지 않음")

    override suspend fun issueUploadUrl(sessionId: Long): InterviewVideoUploadUrl = error("사용하지 않음")

    override suspend fun uploadVideo(command: UploadInterviewVideoCommand) = error("사용하지 않음")

    override suspend fun completeUpload(
        sessionId: Long,
        wrapUpStartSec: Float?,
        wrapUpEndSec: Float?,
    ) = error("사용하지 않음")

    override suspend fun getExpiry(sessionId: Long): InterviewVideoExpiry = error("사용하지 않음")
}
