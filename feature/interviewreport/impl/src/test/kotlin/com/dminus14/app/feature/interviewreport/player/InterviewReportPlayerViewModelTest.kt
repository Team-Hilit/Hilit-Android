package com.dminus14.app.feature.interviewreport.player

import com.dminus14.app.domain.model.HighlightReason
import com.dminus14.app.domain.model.HighlightSpan
import com.dminus14.app.domain.model.HighlightTone
import com.dminus14.app.domain.model.InterviewAbandon
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.model.InterviewReport
import com.dminus14.app.domain.model.InterviewReportCard
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
import com.dminus14.app.domain.model.ReportScript
import com.dminus14.app.domain.model.ScriptRole
import com.dminus14.app.domain.model.ScriptSegment
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.model.UploadInterviewVideoCommand
import com.dminus14.app.domain.repository.InterviewRepository
import com.dminus14.app.domain.usecase.GetInterviewReportUseCase
import com.dminus14.app.feature.interviewreport.player.InterviewReportPlayerState.Phase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewReportPlayerViewModelTest {
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
    fun `sessionId 가 바인딩되지 않았으면 Loading 이 아니라 Failed 로 전이한다`() =
        runTest {
            // 유효하지 않은 sessionId(0L)로 진입한 상태. 초기 phase 가
            // Loading 이라 load() 가 조용히 리턴하면, 닫기 아이콘이 Ready(PlayerReady) 안에만
            // 있어서 사용자가 시스템 백 외에는 빠져나갈 수단이 없는 화면에 갇힌다.
            val viewModel = viewModel(FakeInterviewRepository(readyReport()))

            viewModel.onIntent(InterviewReportPlayerIntent.Load(0L))
            advanceUntilIdle()

            assertEquals(Phase.Failed, viewModel.state.value.phase)
        }

    @Test
    fun `getReport 가 예외로 종료되면 Failed 로 전이한다`() =
        runTest {
            val viewModel = viewModel(ThrowingInterviewRepository())

            viewModel.onIntent(InterviewReportPlayerIntent.Load(1L))
            advanceUntilIdle()

            assertEquals(Phase.Failed, viewModel.state.value.phase)
        }

    @Test
    fun `READY 응답을 받으면 세그먼트와 대본 라인을 매핑해 Ready 로 전이한다`() =
        runTest {
            val viewModel = viewModel(FakeInterviewRepository(readyReport()))

            viewModel.onIntent(InterviewReportPlayerIntent.Load(1L))
            advanceUntilIdle()

            val phase = viewModel.state.value.phase
            assertTrue("expected Ready but was $phase", phase is Phase.Ready)
            val content = (phase as Phase.Ready).content

            assertEquals("https://cdn/x.mp4", content.videoUrl)

            val segment = content.segments.single()
            assertEquals("질문 1-1", segment.label)
            assertEquals(1_000L, segment.startMs)
            assertEquals(3_000L, segment.endMs)

            // 최상위 script 라인(1.0~3.0초)이 highlight.startSec(1.5초 → 1500ms)를 포함하므로
            // 그 라인에 highlightRef 가 매칭돼야 한다.
            val line = content.scriptLines.single()
            assertEquals("대본 내용", line.text)
            assertEquals(1_000L, line.startMs)
            assertEquals(3_000L, line.endMs)
            assertNotNull(line.highlightRef)
            assertEquals("잘한 점", line.highlightRef?.highlight?.title)
        }

    @Test
    fun `ToggleTranscript 인텐트는 transcriptVisible 을 토글한다`() =
        runTest {
            val viewModel = viewModel(FakeInterviewRepository(readyReport()))

            assertEquals(false, viewModel.state.value.transcriptVisible)
            viewModel.onIntent(InterviewReportPlayerIntent.ToggleTranscript)
            assertEquals(true, viewModel.state.value.transcriptVisible)
            viewModel.onIntent(InterviewReportPlayerIntent.ToggleTranscript)
            assertEquals(false, viewModel.state.value.transcriptVisible)
        }

    @Test
    fun `리포트 응답이 늦게 도착해도 로딩 중 켠 대본 표시를 유지한다`() {
        runTest {
            val response = CompletableDeferred<InterviewReport>()
            val repository =
                FakeInterviewRepository(
                    response = readyReport(),
                    awaitResponse = { response.await() },
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(InterviewReportPlayerIntent.Load(1L))
            viewModel.onIntent(InterviewReportPlayerIntent.ToggleTranscript)

            response.complete(readyReport())
            advanceUntilIdle()

            assertTrue(viewModel.state.value.phase is Phase.Ready)
            assertTrue(viewModel.state.value.transcriptVisible)
        }
    }

    private fun readyReport(): InterviewReport =
        InterviewReport(
            status = InterviewReportStatus.READY,
            headline = "요약",
            video =
                InterviewReportVideo(
                    url = "https://cdn/x.mp4",
                    expired = false,
                    expiresAt = null,
                ),
            cards = listOf(card()),
            script =
                listOf(
                    ReportScript(
                        role = ScriptRole.INTERVIEWEE,
                        text = "대본 내용",
                        startSec = 1.0f,
                        endSec = 3.0f,
                    ),
                ),
            guestFeedback = null,
        )

    private fun card(): InterviewReportCard =
        InterviewReportCard(
            axisOrder = 1,
            depthLevel = 1,
            questionText = "질문",
            transcript = "대본 내용",
            highlightSpans = listOf(highlightSpan()),
            resolutionNotice = null,
            cardRedFlagNotices = null,
            questionIntentTitle = null,
            questionIntent = null,
            scriptSegments =
                listOf(
                    ScriptSegment(
                        role = ScriptRole.INTERVIEWEE,
                        text = "대본 내용",
                        startIndex = 0,
                        endIndex = 4,
                        startSec = 1.0f,
                        endSec = 3.0f,
                    ),
                ),
        )

    private fun highlightSpan(): HighlightSpan =
        HighlightSpan(
            startIndex = 0,
            endIndex = 4,
            tone = HighlightTone.GOOD,
            reason = HighlightReason.SUFFICIENT,
            title = "잘한 점",
            analysis = "분석",
            followUpQuestions = emptyList(),
            startSec = 1.5f,
            answerTopicTitle = null,
            questionIntentTitle = null,
            questionIntent = null,
        )

    private fun viewModel(repository: InterviewRepository): InterviewReportPlayerViewModel =
        InterviewReportPlayerViewModel(GetInterviewReportUseCase(repository))
}

private class FakeInterviewRepository(
    private val response: InterviewReport,
    private val awaitResponse: (suspend () -> InterviewReport)? = null,
) : InterviewRepository {
    override suspend fun getReport(sessionId: Long): InterviewReport =
        awaitResponse?.invoke() ?: response

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
