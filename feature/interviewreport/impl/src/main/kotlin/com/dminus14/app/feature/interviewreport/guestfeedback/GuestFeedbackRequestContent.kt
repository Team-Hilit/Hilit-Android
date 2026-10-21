package com.dminus14.app.feature.interviewreport.guestfeedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.designsystem.component.button.HilitButtonType
import com.dminus14.designsystem.component.button.HilitFixedBottomButton
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButton
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButtonType
import com.dminus14.designsystem.component.icon.HilitIcon
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.toggle.HilitToggle
import com.dminus14.designsystem.theme.HilitTheme

/** 시안(443:8088) 순서에 맞춘 평가 항목 행 정의. 표시명은 [label] 을 공유한다. */
private data class AxisRow(
    val axis: GuestFeedbackAxisCode,
    val icon: HilitIconAsset,
)

private val AXIS_ROWS =
    listOf(
        AxisRow(GuestFeedbackAxisCode.GAZE, HilitIconAsset.Eyes),
        AxisRow(GuestFeedbackAxisCode.EXPRESSION, HilitIconAsset.Face),
        AxisRow(GuestFeedbackAxisCode.POSTURE, HilitIconAsset.Body),
        AxisRow(GuestFeedbackAxisCode.GESTURE, HilitIconAsset.Hand),
        AxisRow(GuestFeedbackAxisCode.VOICE, HilitIconAsset.Voice),
    )

/**
 * 지인 피드백 항목선택 화면 순수 UI (Figma Node: 443:8006). State 만 받아 렌더한다.
 */
@Composable
@Suppress("LongMethod")
internal fun GuestFeedbackRequestContent(
    state: GuestFeedbackRequestState,
    onIntent: (GuestFeedbackRequestIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HilitTheme.colors
    Box(modifier = modifier.fillMaxSize().background(colors.hilitBlack900)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HilitIcon(
                    asset = HilitIconAsset.Cancel,
                    contentDescription = "닫기",
                    tint = colors.hilitWhite,
                    modifier =
                        Modifier.clickable {
                            onIntent(
                                GuestFeedbackRequestIntent.ClickClose,
                            )
                        },
                )
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "지인에게 어떤 항목을\n평가받을까요?",
                    style = HilitTheme.typography.sub4,
                    color = colors.hilitWhite,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "지인은 면접 태도를 중심으로 평가합니다.\n평가받고 싶은 항목을 선택해 주세요.",
                    style = HilitTheme.typography.body7,
                    color = colors.gray300,
                )
                Spacer(Modifier.height(24.dp))
                AXIS_ROWS.forEachIndexed { index, row ->
                    AxisToggleRow(
                        row = row,
                        checked = row.axis in state.selectedAxes,
                        onToggle = { onIntent(GuestFeedbackRequestIntent.ToggleAxis(row.axis)) },
                    )
                    if (index != AXIS_ROWS.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .height(1.dp)
                                .background(colors.gray800),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            HilitFixedBottomButton(
                // 생성 후에는 서버를 거치지 않고 저장된 링크를 바로 복사한다(#219).
                // 기기에 기록이 없어 링크를 꺼낼 수 없는 복구 상태에서만 종료 버튼이 된다.
                text =
                    when {
                        state.endOnlyShare -> "피드백 종료하기"
                        state.hasActiveShare -> "피드백 링크 복사하기"
                        else -> "피드백 링크 생성"
                    },
                // Figma 443:8044 실제 버튼은 짙은 회색(gray900) 배경 + 흰 글자로, Dark 타입(흰 배경)이
                // 아니라 Light 타입(활성 시 hilitBlack800 배경, 누를 때 gray900)과 일치한다. 이 앱의
                // 다른 화면 CTA 버튼도 전부 Light 를 쓴다 — Dark 타입은 이 파일에서만 잘못 쓰이고 있었음.
                type = HilitButtonType.Light,
                enabled =
                    !state.submitting &&
                        (
                            state.endOnlyShare ||
                                state.hasActiveShare ||
                                state.selectedAxes.isNotEmpty()
                        ),
                onClick = { onIntent(GuestFeedbackRequestIntent.ClickSubmit) },
            )
        }

        if (state.shareLink != null) {
            LinkCreatedModal(
                message = buildGuestFeedbackShareMessage(state.messageAxes, state.shareLink),
                onCopy = { onIntent(GuestFeedbackRequestIntent.ClickCopyMessage) },
                onShare = { onIntent(GuestFeedbackRequestIntent.ClickShareMessage) },
            )
        }
    }
}

@Composable
private fun AxisToggleRow(
    row: AxisRow,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = HilitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HilitIcon(
            asset = row.icon,
            contentDescription = null,
            tint = colors.gray50,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = row.axis.label,
            style = HilitTheme.typography.sub7,
            color = colors.hilitWhite,
            modifier = Modifier.weight(1f),
        )
        HilitToggle(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * 링크 생성 완료 모달. [message] 는 복사·공유에 실제로 쓰이는 전문이며, 사용자가 무엇을
 * 보내는지 먼저 확인할 수 있도록 버튼 위에 그대로 노출한다.
 */
@Composable
private fun LinkCreatedModal(
    message: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    // 복사·공유를 마치기 전에 닫히지 않도록 X 버튼과 배경 탭 닫기를 두지 않는다(#219).
    // 복사하면 2초 뒤 자동으로 닫힌다.
    ModalScrim {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .background(HilitTheme.colors.hilitWhite),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                HilitIcon(
                    asset = HilitIconAsset.Link,
                    contentDescription = null,
                    modifier = Modifier.size(74.dp),
                )
                Text(
                    text = "링크 생성 완료!\n지인에게 보내보세요.",
                    style = HilitTheme.typography.sub4,
                    // TODO(designsystem): Figma #262A30 대응 토큰 확정 필요, 임시 gray900.
                    color = HilitTheme.colors.gray900,
                    textAlign = TextAlign.Center,
                )
                ShareMessagePreview(message = message)
            }
            HilitFixedBottomDualButton(
                modifier = Modifier.fillMaxWidth(),
                leftText = "복사하기",
                rightText = "공유하기",
                type = HilitFixedBottomDualButtonType.TwoColor,
                onLeftClick = onCopy,
                onRightClick = onShare,
            )
        }
    }
}

/** 클립보드·공유 시트로 나갈 전문을 그대로 보여주는 프리뷰. */
@Composable
private fun ShareMessagePreview(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        style = HilitTheme.typography.body7,
        // TODO(designsystem): Figma #262A30 대응 토큰 확정 필요, 임시 gray900.
        color = HilitTheme.colors.gray900,
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = HilitTheme.colors.gray50,
                    shape = RoundedCornerShape(12.dp),
                ).padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun ModalScrim(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                // 배경 탭으로는 닫지 않는다(#219). 다만 클릭을 여기서 소비해 뒤에 깔린 화면
                // (항목선택 리스트 등)의 클릭 요소로 뚫고 들어가지 않게 막는다.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // 카드 자체를 탭했을 때는 스크림의 dismiss 클릭으로 전파되지 않도록 별도로 소비한다.
            modifier =
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            content()
        }
    }
}
