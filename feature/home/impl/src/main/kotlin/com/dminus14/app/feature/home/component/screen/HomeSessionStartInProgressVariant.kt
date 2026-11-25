package com.dminus14.app.feature.home.component.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dminus14.app.feature.home.HomeSessionStartCallbacks
import com.dminus14.app.feature.home.HomeSessionStartOverlayState
import com.dminus14.app.feature.home.component.HomeSessionStartCard
import com.dminus14.app.feature.home.component.HomeSessionStartScaffold
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButton
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButtonType
import com.dminus14.designsystem.component.icon.HilitIcon
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.theme.HilitTheme

private val IconSize = 74.dp
private val LabelToValueSpacing = 8.dp
private val IconToTextSpacing = 16.dp

/**
 * Figma 443:5890 — 진행 중인 면접이 있는 상태에서 이어서 진행 여부를 묻는 오버레이.
 *
 * "말풍선+물음표" 아이콘이 designsystem에 없어 임시로 `HilitIconAsset.Script`로 표시한다.
 */
@Composable
internal fun HomeSessionStartInProgressVariant(
    state: HomeSessionStartOverlayState.InProgress,
    callbacks: HomeSessionStartCallbacks,
    modifier: Modifier = Modifier,
) {
    HomeSessionStartScaffold(
        title = "${state.userName}님,\n진행 중인\n면접이 있어요",
        onCloseClick = callbacks.onCloseClick,
        card = {
            HomeSessionStartCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HilitIcon(
                        asset = HilitIconAsset.Opp,
                        contentDescription = null,
                        tint = HilitTheme.colors.hilitGreen800,
                        modifier = Modifier.size(IconSize),
                    )
                    Text(
                        text = "진행 시간 ${state.elapsedSeconds / 60}분 ${state.elapsedSeconds % 60}초",
                        style = HilitTheme.typography.body9,
                        color = HilitTheme.colors.gray500,
                        modifier = Modifier.padding(top = IconToTextSpacing),
                    )
                    Text(
                        text = "${state.remainingQuestionCount}개의 질문이 남았어요",
                        style = HilitTheme.typography.sub7,
                        color = HilitTheme.colors.hilitBlack800,
                        modifier = Modifier.padding(top = LabelToValueSpacing),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        bottomButton = {
            HilitFixedBottomDualButton(
                leftText = "처음부터 시작",
                rightText = "이어서 진행",
                type = HilitFixedBottomDualButtonType.Default,
                onLeftClick = callbacks.onRestartClick,
                onRightClick = callbacks.onResumeClick,
            )
        },
        modifier = modifier,
    )
}

@Preview(name = "InProgress variant", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun HomeSessionStartInProgressVariantPreview() {
    HilitTheme {
        HomeSessionStartInProgressVariant(
            state =
                HomeSessionStartOverlayState.InProgress(
                    userName = "재원",
                    remainingQuestionCount = 2,
                ),
            callbacks = HomeSessionStartCallbacks(),
        )
    }
}
