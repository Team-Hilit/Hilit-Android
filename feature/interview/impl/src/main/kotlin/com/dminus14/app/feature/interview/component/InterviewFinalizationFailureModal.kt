package com.dminus14.app.feature.interview.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButton
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButtonType
import com.dminus14.designsystem.component.modal.HilitModal
import com.dminus14.designsystem.component.modal.HilitModalPreviewHost
import com.dminus14.designsystem.component.modal.HilitModalType

/** 면접 종료 후 녹화 확정 또는 업로드 인계 실패를 복구하는 Modal이다. */
@Composable
fun InterviewFinalizationFailureModal(
    onExitClick: () -> Unit,
    onRetryClick: () -> Unit,
) {
    HilitModal(
        type = HilitModalType.Default,
        title = "면접 마무리를 완료하지 못했어요",
        subtitle = "다시 시도하거나 홈으로 이동해주세요. 영상은 최대 24시간 보관 후 삭제돼요.",
        dismissible = false,
        buttons = {
            HilitFixedBottomDualButton(
                leftText = "홈으로 이동",
                rightText = "다시 시도",
                type = HilitFixedBottomDualButtonType.TwoColor,
                onLeftClick = onExitClick,
                onRightClick = onRetryClick,
            )
        },
    )
}

@Preview
@Composable
private fun InterviewFinalizationFailureModalPreview() {
    HilitModalPreviewHost {
        InterviewFinalizationFailureModal(onExitClick = {}, onRetryClick = {})
    }
}
