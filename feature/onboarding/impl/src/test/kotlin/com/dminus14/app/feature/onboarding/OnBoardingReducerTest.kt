package com.dminus14.app.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.dminus14.app.feature.onboarding.OnBoardingMutation.JobDescription as JdMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Portfolio as PortfolioMutation

class OnBoardingReducerTest {
    @Test
    fun `공고 입력 변경은 검증 오류만 지우고 다른 단계 입력을 보존한다`() {
        val state = OnBoardingInterviewState(mainProjectText = "가상 프로젝트", jdTextError = "오류")
        val actual =
            OnBoardingReducer.reduce(
                state,
                JdMutation.JdTextChanged("새 공고"),
            )
        assertEquals(state.copy(jobDescriptionText = "새 공고", jdTextError = null), actual)
        assertEquals(
            actual,
            OnBoardingReducer.reduce(
                state,
                JdMutation.JdTextChanged("새 공고"),
            ),
        )
    }

    @Test
    fun `포트폴리오 삭제 성공은 안내와 진행률을 초기화하고 공고 입력을 보존한다`() {
        val state =
            OnBoardingInterviewState(
                portfolioFileName = "sample.pdf",
                portfolioUploadProgress = 100,
                hasShownExistingPortfolioNotice = true,
                isPortfolioProcessing = true,
                existingPortfolioModalPhase = ExistingPortfolioModalPhase.ConfirmContinue,
                jobDescriptionText = "가상 공고",
            )
        val actual = OnBoardingReducer.reduce(state, PortfolioMutation.PortfolioDeleted)
        assertEquals(
            state.copy(
                portfolioFileName = null,
                portfolioUploadProgress = 0,
                hasShownExistingPortfolioNotice = false,
                isPortfolioProcessing = false,
                existingPortfolioModalPhase = ExistingPortfolioModalPhase.None,
            ),
            actual,
        )
        assertFalse(actual.isPortfolioProcessing)
    }
}
