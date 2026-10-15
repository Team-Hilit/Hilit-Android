package com.dminus14.app.feature.onboarding

import androidx.compose.runtime.Composable
import com.dminus14.designsystem.component.button.HilitFixedBottomButton
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButton
import com.dminus14.designsystem.component.modal.HilitModal
import com.dminus14.designsystem.component.modal.HilitModalType
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.SessionPreparation as SessionIntent

/**
 * Preload(세션 생성/준비) 실패 안내 다이얼로그. 그 외 실패는 Preload 화면 아래
 * 로딩 인디케이터 그대로였는데, 다이얼로그로 사용자에게 알리고 이전 스텝으로 복귀시킨다.
 */
@Composable
internal fun OnBoardingPreloadFailureDialog(
    message: String,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
) {
    HilitModal(
        type = HilitModalType.InvisibleInfo,
        title = "면접 준비에 실패했어요",
        subtitle = message,
        dismissible = false,
        buttons = {
            HilitFixedBottomButton(
                text = "확인",
                onClick = {
                    onIntent(
                        SessionIntent.ClickPreloadFailureAcknowledged,
                    )
                },
            )
        },
    )
}

/**
 * S3.5 연관성 판단이 4회 연속 실패했을 때 뜨는 이스케이프 다이얼로그.
 * 스펙: 포트폴리오 다시 올리기 vs 집중 프로젝트 없이 진행.
 */
@Composable
internal fun OnBoardingRelevanceFailDialog(onIntent: (OnBoardingInterviewIntent) -> Unit) {
    HilitModal(
        type = HilitModalType.InvisibleInfo,
        title = "포트폴리오에서\n그 내용을 계속 찾지 못했어요",
        subtitle = "포트폴리오를 다시 올리거나, 집중 프로젝트 없이 진행할 수 있어요.",
        dismissible = false,
        buttons = {
            HilitFixedBottomDualButton(
                leftText = "집중 프로젝트 없이 진행",
                rightText = "포트폴리오 다시 올리기",
                onLeftClick = {
                    onIntent(
                        SessionIntent.ClickRelevanceProceedWithoutMainProject,
                    )
                },
                onRightClick = {
                    onIntent(
                        SessionIntent.ClickRelevanceRetryPortfolio,
                    )
                },
            )
        },
    )
}
