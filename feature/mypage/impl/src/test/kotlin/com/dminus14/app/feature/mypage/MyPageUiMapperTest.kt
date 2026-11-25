package com.dminus14.app.feature.mypage

import com.dminus14.app.domain.model.InterviewReportListItem
import com.dminus14.app.domain.model.InterviewReportStatus
import com.dminus14.app.domain.model.Portfolio
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyPageUiMapperTest {
    @Test
    fun `연차가 있으면 년차 문구로 없으면 내용 없음으로 표시한다`() {
        val withYears = sampleProfile(careerYears = 3)
        val withoutYears = sampleProfile(careerYears = null)

        assertEquals("3년차", withYears.toProfileUiModel().experience)
        assertEquals("내용 없음", withoutYears.toProfileUiModel().experience)
    }

    @Test
    fun `직군 라벨은 서버 jobRoleLabel을 우선한다`() {
        val profile = sampleProfile(jobRole = "BACKEND", jobRoleLabel = "백엔드")
        assertEquals("백엔드", profile.toProfileUiModel().role)
    }

    @Test
    fun `jobRoleLabel이 없으면 jobRole 원문을 그대로 쓰고 둘 다 없으면 내용 없음이다`() {
        val onlyRawRole = sampleProfile(jobRole = "BACKEND", jobRoleLabel = null)
        val noRole = sampleProfile(jobRole = null, jobRoleLabel = null)

        assertEquals("BACKEND", onlyRawRole.toProfileUiModel().role)
        assertEquals("내용 없음", noRole.toProfileUiModel().role)
    }

    @Test
    fun `이메일 로컬 파트 길이별 마스킹 경계값을 확인한다`() {
        assertEquals("sam****@kakao.com", maskEmail("sample@kakao.com"))
        assertEquals("a****@kakao.com", maskEmail("ab@kakao.com"))
        assertEquals("****@kakao.com", maskEmail("a@kakao.com"))
        assertEquals("내용 없음", maskEmail(null))
        assertEquals("내용 없음", maskEmail(""))
        assertEquals("내용 없음", maskEmail("invalid-email"))
    }

    @Test
    fun `카카오 제공자만 로고 플래그가 true다`() {
        val kakao = sampleProfile(provider = "KAKAO")
        val other = sampleProfile(provider = "APPLE")

        assertEquals(true, kakao.toSocialAccountUiModel()?.isKakaoProvider)
        assertEquals(false, other.toSocialAccountUiModel()?.isKakaoProvider)
    }

    @Test
    fun `제공자가 없으면 소셜 계정 UI 모델도 없다`() {
        assertNull(sampleProfile(provider = null).toSocialAccountUiModel())
    }

    @Test
    fun `파일 용량은 1MB 미만이면 KB, 이상이면 MB로 표기한다`() {
        assertEquals("500.0KB", formatFileSize(500 * 1024L))
        assertEquals("2.0MB", formatFileSize(2 * 1024 * 1024L))
    }

    @Test
    fun `업로드 일시는 날짜만 남기고 점으로 구분하며 null이면 빈 문자열이다`() {
        assertEquals("2026.07.01", formatUploadedAt("2026-07-01T10:00:00"))
        assertEquals("", formatUploadedAt(null))
    }

    @Test
    fun `포트폴리오 상태 5종을 화면 상태로 매핑한다`() {
        assertEquals(
            MyPagePortfolioState.Empty,
            samplePortfolio(PortfolioStatus.UNKNOWN).toPortfolioState(MyPagePortfolioState.Empty),
        )
        assertEquals(
            MyPagePortfolioState.Uploading(fileName = "resume.pdf", portfolioId = "portfolio-1"),
            samplePortfolio(
                PortfolioStatus.PROCESSING,
            ).toPortfolioState(MyPagePortfolioState.Empty),
        )
        assertEquals(
            MyPagePortfolioState.Failed(fileName = "resume.pdf", portfolioId = "portfolio-1"),
            samplePortfolio(
                PortfolioStatus.FAILED_FILE,
            ).toPortfolioState(MyPagePortfolioState.Empty),
        )
        assertEquals(
            MyPagePortfolioState.Failed(fileName = "resume.pdf", portfolioId = "portfolio-1"),
            samplePortfolio(
                PortfolioStatus.FAILED_SYSTEM,
            ).toPortfolioState(MyPagePortfolioState.Empty),
        )
        val ready =
            samplePortfolio(
                PortfolioStatus.READY,
            ).toPortfolioState(MyPagePortfolioState.Empty)
        assertEquals(true, ready is MyPagePortfolioState.Uploaded)
    }

    @Test
    fun `이전 화면 상태가 Completed면 READY도 Completed로 유지한다`() {
        val previouslyCompleted = MyPagePortfolioState.Completed("resume.pdf")
        val result = samplePortfolio(PortfolioStatus.READY).toPortfolioState(previouslyCompleted)

        assertEquals(MyPagePortfolioState.Completed("resume.pdf"), result)
    }

    @Test
    fun `리포트 상태 5종이 도메인 열거형과 1대1로 매핑된다`() {
        InterviewReportStatus.entries.forEach { domainStatus ->
            val item = sampleReportItem(status = domainStatus)
            assertEquals(domainStatus.name, item.toReportUiModel().status.name)
        }
    }

    @Test
    fun `feedbackAvailable이 isFeedbackAvailable로 그대로 반영된다`() {
        assertEquals(
            true,
            sampleReportItem(feedbackAvailable = true).toReportUiModel().isFeedbackAvailable,
        )
        assertEquals(
            false,
            sampleReportItem(feedbackAvailable = false).toReportUiModel().isFeedbackAvailable,
        )
    }

    @Test
    fun `jdUrl이 없으면 JD 직접 입력으로 표시한다`() {
        assertEquals("JD 직접 입력", sampleReportItem(jdUrl = null).toReportUiModel().jobDescription)
        assertEquals(
            "https://careers.example.com/jobs/1",
            sampleReportItem(
                jdUrl = "https://careers.example.com/jobs/1",
            ).toReportUiModel().jobDescription,
        )
    }

    @Test
    fun `포트폴리오가 삭제되면 삭제 상태가 true이고 헤더 제목과 상세에 파일명을 표시한다`() {
        val uiModel =
            sampleReportItem(
                portfolioFileName = "deleted_portfolio.pdf",
                portfolioDeleted = true,
            ).toReportUiModel()

        assertTrue(uiModel.isPortfolioDeleted)
        assertEquals("deleted_portfolio.pdf", uiModel.title)
        assertEquals("deleted_portfolio.pdf", uiModel.portfolioFileName)
    }

    @Test
    fun `포트폴리오가 삭제되었지만 파일명이 비어 있으면 직군과 연차 조합으로 헤더 제목을 대체한다`() {
        val uiModel =
            sampleReportItem(
                portfolioFileName = null,
                portfolioDeleted = true,
            ).toReportUiModel()

        assertTrue(uiModel.isPortfolioDeleted)
        assertEquals("백엔드 · 3년차 면접", uiModel.title)
        assertEquals("내용 없음", uiModel.portfolioFileName)
    }

    @Test
    fun `리포트 생성이 실패하면 포트폴리오가 삭제되지 않아도 헤더 제목에 파일명을 표시한다`() {
        val uiModel =
            sampleReportItem(
                status = InterviewReportStatus.FAILED,
                portfolioFileName = "failed_report_portfolio.pdf",
                portfolioDeleted = false,
            ).toReportUiModel()

        assertFalse(uiModel.isPortfolioDeleted)
        assertEquals("failed_report_portfolio.pdf", uiModel.title)
    }

    @Test
    fun `정상 리포트이고 포트폴리오가 삭제되지 않으면 직군과 연차 조합을 헤더 제목으로 사용한다`() {
        val uiModel =
            sampleReportItem(
                status = InterviewReportStatus.READY,
                portfolioDeleted = false,
            ).toReportUiModel()

        assertFalse(uiModel.isPortfolioDeleted)
        assertEquals("백엔드 · 3년차 면접", uiModel.title)
        assertEquals("resume.pdf", uiModel.portfolioFileName)
    }

    @Test
    fun `연차나 직군 정보가 일부 없으면 누락된 항목을 제외하고 헤더 제목을 구성한다`() {
        val base = sampleReportItem()
        val onlyRole =
            base
                .copy(
                    jobType = "ANDROID",
                    jobTypeLabel = "Android",
                    careerYears = null,
                ).toReportUiModel()
        val onlyYears =
            base
                .copy(
                    jobType = null,
                    jobTypeLabel = null,
                    careerYears = 2,
                ).toReportUiModel()
        val neither =
            base
                .copy(
                    jobType = null,
                    jobTypeLabel = null,
                    careerYears = null,
                ).toReportUiModel()

        assertEquals("Android 면접", onlyRole.title)
        assertEquals("2년차 면접", onlyYears.title)
        assertEquals("면접 리포트", neither.title)
    }

    private fun sampleProfile(
        careerYears: Int? = 3,
        jobRole: String? = "BACKEND",
        jobRoleLabel: String? = "백엔드",
        provider: String? = "KAKAO",
    ) = UserProfile(
        name = "홍길동",
        email = "sample@kakao.com",
        provider = provider,
        jobRole = jobRole,
        jobRoleLabel = jobRoleLabel,
        careerYears = careerYears,
        remainingTicketCount = 3,
    )

    private fun samplePortfolio(status: PortfolioStatus) =
        Portfolio(
            portfolioId = "portfolio-1",
            fileName = "resume.pdf",
            fileSize = 1_024L,
            pageCount = 3,
            status = status,
            uploadedAt = "2026-08-04T00:00:00",
            isInterviewInProgress = false,
        )

    private fun sampleReportItem(
        status: InterviewReportStatus = InterviewReportStatus.READY,
        feedbackAvailable: Boolean = true,
        jdUrl: String? = "https://careers.example.com/jobs/1",
        portfolioFileName: String? = "resume.pdf",
        portfolioDeleted: Boolean = false,
    ) = InterviewReportListItem(
        sessionId = 1L,
        jobType = "BACKEND",
        jobTypeLabel = "백엔드",
        careerYears = 3,
        interviewedAt = "2026-08-04T00:00:00",
        portfolioFileName = portfolioFileName,
        portfolioDeleted = portfolioDeleted,
        jdUrl = jdUrl,
        reportStatus = status,
        feedbackAvailable = feedbackAvailable,
    )
}
