package com.dminus14.app.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.dminus14.app.feature.onboarding.component.OnBoardingExistingPortfolioConfirmModal
import com.dminus14.app.feature.onboarding.component.OnBoardingUseExistingPortfolioNotice
import com.dminus14.designsystem.theme.HilitTheme

@Preview(name = "JobDescription", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingInterviewJobDescriptionPreview() {
    HilitTheme {
        OnBoardingInterviewContent(
            state = OnBoardingInterviewState(step = OnBoardingInterviewStep.JobDescription),
            onIntent = {},
        )
    }
}

@Preview(name = "Portfolio", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingInterviewPortfolioPreview() {
    HilitTheme {
        OnBoardingInterviewContent(
            state =
                OnBoardingInterviewState(
                    step = OnBoardingInterviewStep.Portfolio,
                    portfolioFileName = "포트폴리오.pdf",
                    portfolioUploadProgress = 100,
                ),
            onIntent = {},
        )
    }
}

@Preview(
    name = "Portfolio - Case1 ConfirmContinue",
    showBackground = true,
    widthDp = 375,
    heightDp = 812,
)
@Composable
private fun OnBoardingInterviewPortfolioCase1ConfirmContinuePreview() {
    HilitTheme {
        OnBoardingInterviewContent(
            state =
                OnBoardingInterviewState(
                    step = OnBoardingInterviewStep.Portfolio,
                    portfolioFileName = "포트폴리오.pdf",
                    portfolioUploadProgress = 100,
                    existingPortfolioModalPhase = ExistingPortfolioModalPhase.ConfirmContinue,
                ),
            onIntent = {},
        )
    }
}

@Preview(
    name = "Portfolio - Case2 AutoDismiss",
    showBackground = true,
    widthDp = 375,
    heightDp = 812,
)
@Composable
private fun OnBoardingInterviewPortfolioCase2AutoDismissPreview() {
    HilitTheme {
        OnBoardingInterviewContent(
            state =
                OnBoardingInterviewState(
                    step = OnBoardingInterviewStep.Portfolio,
                    portfolioFileName = "포트폴리오.pdf",
                    portfolioUploadProgress = 100,
                    existingPortfolioModalPhase = ExistingPortfolioModalPhase.AutoDismissNotice,
                ),
            onIntent = {},
        )
    }
}

@Preview(name = "MainProject", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingInterviewMainProjectPreview() {
    HilitTheme {
        OnBoardingInterviewContent(
            state = OnBoardingInterviewState(step = OnBoardingInterviewStep.MainProject),
            onIntent = {},
        )
    }
}

@Preview(name = "Preload", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingInterviewPreloadPreview() {
    HilitTheme {
        OnBoardingInterviewContent(
            state = OnBoardingInterviewState(step = OnBoardingInterviewStep.Preload),
            onIntent = {},
        )
    }
}

@Preview(name = "Modal - ConfirmContinue", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingExistingPortfolioConfirmModalPreview() {
    HilitTheme {
        OnBoardingExistingPortfolioConfirmModal(onIntent = {})
    }
}

@Preview(name = "Modal - AutoDismissNotice", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingUseExistingPortfolioNoticePreview() {
    HilitTheme {
        OnBoardingUseExistingPortfolioNotice(remainingDeleteCount = 0, onIntent = {})
    }
}
