package com.dminus14.app.feature.mypage

import org.junit.Assert.assertEquals
import org.junit.Test

class MyPageContractTest {
    @Test
    fun `직군과 연차로 리포트의 표시 문자열을 계산한다`() {
        val report =
            MyPageReportUiModel(
                id = "synthetic-report",
                title = "iOS · 2년차 면접",
                jobRole = MyPageJobRole.IOS,
                jobRoleLabel = MyPageJobRole.IOS.label,
                experienceYears = 2,
                createdAt = "2026.08.04",
                status = MyPageReportStatus.READY,
                portfolioFileName = "sample_portfolio.pdf",
                isPortfolioDeleted = false,
                jobDescription = "JD 직접 입력",
                isFeedbackAvailable = true,
            )

        assertEquals("iOS", MyPageJobRole.IOS.label)
        assertEquals("iOS · 2년", report.jobRoleAndExperience)
    }

    @Test
    fun `서버 목록 밖 직군 코드는 fromRaw에서 null을 반환한다`() {
        assertEquals(null, MyPageJobRole.fromRaw("UNKNOWN_ROLE"))
        assertEquals(MyPageJobRole.BACKEND, MyPageJobRole.fromRaw("BACKEND"))
    }
}
