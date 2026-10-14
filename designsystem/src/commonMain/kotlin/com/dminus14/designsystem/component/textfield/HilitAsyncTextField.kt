package com.dminus14.designsystem.component.textfield

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dminus14.designsystem.component.icon.HilitIcon
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.subtext.HilitSubText
import com.dminus14.designsystem.component.subtext.HilitSubTextType
import com.dminus14.designsystem.theme.HilitTheme

/** 비동기 처리 텍스트 필드의 시각·상호작용 상태. */
enum class HilitAsyncTextFieldType {
    /** 비활성(미포커스). 전체 보더, Ready 서브텍스트 가능 */
    Ready,

    /** 포커스. 하단 초록 indicator */
    Focus,

    /** 입력 중. 하단 초록 indicator + 클리어 버튼 */
    Edit,

    /** 처리 중. 입력 비활성, 처리 상태 라벨, 애니메이션 indicator */
    Processing,

    /** 완료. 하단 초록 indicator + 클리어 + Success 서브텍스트 */
    Complete,

    /** 오류. 하단 빨간 indicator + 클리어 + Error 서브텍스트 */
    Error,
}

private val FieldShape = RectangleShape
private val FieldBorderWidth = 1.dp
private val FieldHorizontalPadding = 16.dp
private val FieldVerticalPadding = 14.dp
private val IndicatorHeight = 4.dp
private val ClearIconSize = 16.dp
private val TrailingGap = 8.dp
private val SubTextSpacing = 8.dp
private const val PROCESSING_SEGMENT_FRACTION = 0.3f

/**
 * Figma Node: 2044:1801, 3759:14839
 *
 * 범용 비동기 처리 텍스트 필드. 상태에 따라 하단 indicator·클리어 버튼·서브텍스트가 달라진다.
 *
 * URL 확인, 중복 체크, 데이터 조회 등 값 입력 후 비동기 처리가 필요한 다양한 유즈케이스에
 * 재사용한다.
 *
 * Figma: text-field (`2044:1801`)
 *
 * @param value 현재 입력 값
 * @param onValueChange 입력이 바뀔 때 호출된다. [HilitAsyncTextFieldType.Processing]이면 입력이 비활성이다
 * @param type 필드 상태. UI(indicator·클리어·서브텍스트)를 결정한다
 * @param modifier 외부 레이아웃 Modifier
 * @param placeholder 값이 비어 있을 때 표시할 placeholder
 * @param processingText [HilitAsyncTextFieldType.Processing]일 때 우측에 노출할 상태 문구
 * @param subText Ready/Complete/Error일 때만 [HilitSubText]로 노출된다. 빈 문자열이면 숨긴다
 * @param descriptionText 상태 메시지가 없을 때 표시할 안내. Processing에서는 숨긴다
 * @param onClearClick Edit/Complete/Error의 클리어 버튼 클릭 콜백. null이면 클릭해도 동작하지 않는다
 */
@Composable
fun HilitAsyncTextField(
    value: String,
    onValueChange: (String) -> Unit,
    type: HilitAsyncTextFieldType,
    modifier: Modifier = Modifier,
    placeholder: String = "텍스트를 입력해주세요",
    processingText: String = "처리 중",
    subText: String = "",
    onClearClick: (() -> Unit)? = null,
    descriptionText: String = "",
) {
    val editable = type != HilitAsyncTextFieldType.Processing
    val showClear =
        type == HilitAsyncTextFieldType.Edit ||
            type == HilitAsyncTextFieldType.Complete ||
            type == HilitAsyncTextFieldType.Error
    val showProcessingLabel = type == HilitAsyncTextFieldType.Processing
    val footer = resolveAsyncTextFieldSubText(type, subText, descriptionText)
    val showDescription =
        subText.isEmpty() || type == HilitAsyncTextFieldType.Focus ||
            type == HilitAsyncTextFieldType.Edit

    val surfaceColor =
        if (type == HilitAsyncTextFieldType.Processing) {
            HilitTheme.colors.gray100
        } else {
            HilitTheme.colors.hilitWhite
        }
    val borderColor = HilitTheme.colors.gray100
    val textColor = HilitTheme.colors.hilitBlack800
    val placeholderColor = HilitTheme.colors.gray500
    val textStyle = HilitTheme.typography.body4

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = surfaceColor,
                        shape = FieldShape,
                    ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = FieldHorizontalPadding,
                                vertical = FieldVerticalPadding,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = editable,
                        singleLine = true,
                        textStyle = textStyle.copy(color = textColor),
                        cursorBrush = SolidColor(textColor),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) {
                                    Text(
                                        text = placeholder,
                                        style = textStyle,
                                        color = placeholderColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    if (showProcessingLabel) {
                        Spacer(modifier = Modifier.width(TrailingGap))
                        Text(
                            text = processingText,
                            style = HilitTheme.typography.body9,
                            color = HilitTheme.colors.gray400,
                        )
                    }

                    if (showClear) {
                        Spacer(modifier = Modifier.width(TrailingGap))
                        HilitIcon(
                            asset = HilitIconAsset.Delete,
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier =
                                Modifier
                                    .size(ClearIconSize)
                                    .clickable { onClearClick?.invoke() },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(IndicatorHeight))
            }

            // border를 전체 overlay로 그린 뒤, indicator로 하단 stroke를 덮는다.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .border(
                            width = FieldBorderWidth,
                            color = borderColor,
                            shape = FieldShape,
                        ),
            )

            AsyncTextFieldIndicator(
                type = type,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(IndicatorHeight),
            )
        }

        if (footer != null) {
            HilitSubText(
                text = footer.first,
                type = footer.second,
                maxLines = if (showDescription) Int.MAX_VALUE else 1,
                modifier = Modifier.padding(top = SubTextSpacing),
            )
        }
    }
}

@Composable
private fun AsyncTextFieldIndicator(
    type: HilitAsyncTextFieldType,
    modifier: Modifier = Modifier,
) {
    when (type) {
        HilitAsyncTextFieldType.Ready -> {
            Spacer(modifier = modifier)
        }

        HilitAsyncTextFieldType.Focus,
        HilitAsyncTextFieldType.Edit,
        HilitAsyncTextFieldType.Complete,
        -> {
            Box(
                modifier = modifier.background(HilitTheme.colors.hilitGreen500),
            )
        }

        HilitAsyncTextFieldType.Error -> {
            Box(
                modifier = modifier.background(HilitTheme.colors.error500),
            )
        }

        HilitAsyncTextFieldType.Processing -> {
            ProcessingIndicator(modifier = modifier)
        }
    }
}

@Composable
private fun ProcessingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hilitAsyncTextFieldProcessing")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "hilitAsyncTextFieldProcessingProgress",
    )

    BoxWithConstraints(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(HilitTheme.colors.gray200),
        )
        val segmentWidth = maxWidth * PROCESSING_SEGMENT_FRACTION
        val maxOffset = maxWidth - segmentWidth
        Box(
            modifier =
                Modifier
                    .offset(x = maxOffset * progress)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .background(HilitTheme.colors.hilitGreen500),
        )
    }
}

/** 기존 상태 메시지를 우선하고, 처리 중에는 하단 안내를 숨긴다. */
internal fun resolveAsyncTextFieldSubText(
    type: HilitAsyncTextFieldType,
    subText: String,
    descriptionText: String = "",
): Pair<String, HilitSubTextType>? =
    when {
        type == HilitAsyncTextFieldType.Processing -> {
            null
        }

        subText.isNotEmpty() && type == HilitAsyncTextFieldType.Ready -> {
            subText to
                HilitSubTextType.Default
        }

        subText.isNotEmpty() && type == HilitAsyncTextFieldType.Complete -> {
            subText to
                HilitSubTextType.Success
        }

        subText.isNotEmpty() && type == HilitAsyncTextFieldType.Error -> {
            subText to
                HilitSubTextType.Error
        }

        descriptionText.isNotEmpty() -> {
            descriptionText to HilitSubTextType.Default
        }

        else -> {
            null
        }
    }

@Preview(
    name = "HilitAsyncTextField",
    showBackground = true,
    backgroundColor = 0xFF4A4B50,
    widthDp = 360,
    heightDp = 720,
)
@Composable
private fun HilitAsyncTextFieldPreview() {
    HilitTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(HilitTheme.colors.gray700)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HilitAsyncTextFieldType.entries.forEach { type ->
                HilitAsyncTextField(
                    value =
                        when (type) {
                            HilitAsyncTextFieldType.Edit,
                            HilitAsyncTextFieldType.Processing,
                            HilitAsyncTextFieldType.Complete,
                            HilitAsyncTextFieldType.Error,
                            -> "https://company.com/jobs/123"

                            else -> ""
                        },
                    onValueChange = {},
                    type = type,
                    onClearClick = {},
                    subText = "서브 텍스트를 입력해주세요",
                )
            }
        }
    }
}
