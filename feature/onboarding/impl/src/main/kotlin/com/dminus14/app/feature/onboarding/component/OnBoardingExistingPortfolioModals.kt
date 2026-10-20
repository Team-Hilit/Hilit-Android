package com.dminus14.app.feature.onboarding.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.dminus14.app.feature.onboarding.ExistingPortfolioModalPhase
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent
import com.dminus14.app.feature.onboarding.OnBoardingInterviewState
import com.dminus14.designsystem.component.button.HilitFixedBottomButton
import com.dminus14.designsystem.component.modal.HilitBookIllustration
import com.dminus14.designsystem.component.modal.HilitModal
import com.dminus14.designsystem.component.modal.HilitModalType
import kotlinx.coroutines.delay
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Portfolio as PortfolioIntent

internal const val AUTO_DISMISS_NOTICE_MS = 3_000L

@Composable
internal fun OnBoardingExistingPortfolioModals(
    state: OnBoardingInterviewState,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
) {
    when (state.existingPortfolioModalPhase) {
        ExistingPortfolioModalPhase.ConfirmContinue -> {
            OnBoardingExistingPortfolioConfirmModal(onIntent = onIntent)
        }

        ExistingPortfolioModalPhase.AutoDismissNotice -> {
            OnBoardingUseExistingPortfolioNotice(
                remainingDeleteCount = if (state.existingDeleteAvailable) 1 else 0,
                onIntent = onIntent,
            )
        }

        ExistingPortfolioModalPhase.None -> {
            Unit
        }
    }
}

/** Case1: 기존 포트폴리오 재사용 안내. */
@Composable
internal fun OnBoardingExistingPortfolioConfirmModal(
    onIntent: (OnBoardingInterviewIntent) -> Unit,
) {
    HilitModal(
        type = HilitModalType.InvisibleInfo,
        title = "기존에 있는 포트폴리오로\n다시 진행할 수 있어요",
        dismissible = false,
        graphic = { HilitBookIllustration() },
        buttons = {
            HilitFixedBottomButton(
                text = "확인",
                onClick = {
                    onIntent(
                        PortfolioIntent.ClickExistingPortfolioConfirm,
                    )
                },
            )
        },
    )
}

/**
 * Case2: 기존 포트폴리오 자동 사용 안내(3초 후 또는 배경 탭 시 dismiss).
 *
 * @param remainingDeleteCount 이번 달 남은 삭제 기회(0 또는 1). 안내 문구의 잔여 횟수를 결정한다.
 */
@Composable
internal fun OnBoardingUseExistingPortfolioNotice(
    remainingDeleteCount: Int,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(AUTO_DISMISS_NOTICE_MS)
        onIntent(PortfolioIntent.AutoDismissNoticeTimedOut)
    }

    HilitModal(
        type = HilitModalType.InvisibleIcon,
        title = "가장 최근에 업로드한 포트폴리오를\n이번 면접에 그대로 사용합니다.",
        infoText =
            "이번 달 남은 삭제 기회 ${remainingDeleteCount}번이라\n" +
                "새로운 포트폴리오 업로드가 불가능해요.",
        dismissible = true,
        onDismiss = { onIntent(PortfolioIntent.ClickAutoDismissNotice) },
        buttons = {},
    )
}
