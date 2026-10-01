package com.dminus14.app.feature.onboarding

/** [OnBoardingInterviewViewModel]의 [JD_TEXT_MIN_LENGTH]와 동일. */
internal const val JOB_DESCRIPTION_TEXT_MIN_LENGTH = 200

/** [OnBoardingInterviewViewModel]의 [FREETEXT_MIN_LENGTH]와 동일. */
internal const val MAIN_PROJECT_MIN_LENGTH = 10

internal fun OnBoardingInterviewState.isBottomBarEnabled(): Boolean =
    existingPortfolioModalPhase == ExistingPortfolioModalPhase.None

/**
 * 계속하기 버튼 활성 여부를 스텝별로 파생한다.
 * - JobDescription: 링크 탭은 검증 중에만 비활성, 직접 입력 탭은 200자 이상일 때만 활성.
 * - Portfolio: 업로드가 끝나 완료 카드(`PdfUploadType.Completed`)로 표시되는 상태와 같은 조건
 *   (`!isProcessing && fileName != null`)에서만 활성.
 * - MainProject: 선택 입력(빈 값이면 건너뛰기)이거나 최소 10자 이상 입력됐을 때만 활성.
 *   버튼이 눌린 이후의 서버 검증(연관성 등) 오류는 [OnBoardingInterviewState.mainProjectError]로
 *   전달돼 [OnBoardingMainProjectStep]에서 노출한다.
 * - Preload: 진행 중 화면이라 활성 여부와 무관하지만 파생 규칙은 true 로 유지.
 */
internal fun OnBoardingInterviewState.isContinueEnabled(): Boolean =
    when (step) {
        OnBoardingInterviewStep.JobDescription -> {
            isJobDescriptionContinueEnabled()
        }

        OnBoardingInterviewStep.Portfolio -> {
            !isPortfolioProcessing && portfolioFileName != null
        }

        OnBoardingInterviewStep.MainProject -> {
            mainProjectText.isEmpty() || mainProjectText.length >= MAIN_PROJECT_MIN_LENGTH
        }

        OnBoardingInterviewStep.Preload -> {
            true
        }
    }

/** [OnBoardingInterviewViewModel.submitJobDescription]과 동일한 진행 가능 조건. */
private fun OnBoardingInterviewState.isJobDescriptionContinueEnabled(): Boolean =
    when (jobDescriptionTab) {
        JobDescriptionTab.Link -> {
            jdLinkStatus != JdLinkStatus.Validating
        }

        JobDescriptionTab.Text -> {
            jobDescriptionText.length >= JOB_DESCRIPTION_TEXT_MIN_LENGTH
        }
    }

internal fun OnBoardingInterviewStep.toProgressStep(): Int =
    when (this) {
        OnBoardingInterviewStep.JobDescription -> 1

        OnBoardingInterviewStep.Portfolio -> 2

        OnBoardingInterviewStep.MainProject,
        OnBoardingInterviewStep.Preload,
        -> PROGRESS_MAX_STEP
    }

internal const val PROGRESS_MAX_STEP = 3
