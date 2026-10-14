package com.dminus14.designsystem.component.textfield

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dminus14.designsystem.component.subtext.HilitSubText
import com.dminus14.designsystem.component.subtext.HilitSubTextType
import com.dminus14.designsystem.theme.HilitTheme

private val HilitJDTextFieldShape = RectangleShape
private val HilitJDTextFieldBorderWidth = 1.dp
private val HilitJDTextFieldHorizontalPadding = 16.dp
private val HilitJDTextFieldVerticalPadding = 14.dp
private val HilitJDTextFieldHeight = 158.dp
private val HilitJDTextFieldFooterSpacing = 8.dp
private const val DEFAULT_MAX_LENGTH = 300
private const val DEFAULT_PLACEHOLDER = "텍스트를 입력해주세요"

/**
 * Figma Node: 2091:806, 1320:6624, 3759:14839
 *
 * JD 입력용 멀티라인 텍스트 필드. 하단에 조건 검증 서브텍스트·글자 수 카운터를 표시한다.
 *
 * Figma: text-field status=large (`2091:806`, `1320:6624`)
 *
 * @param value 현재 입력 값
 * @param onValueChange 입력이 바뀔 때 호출된다. [maxLength]를 넘는 값은 초과분이 잘려 반영된다
 * @param modifier 외부 레이아웃 Modifier
 * @param placeholder 값이 비어 있을 때 표시할 placeholder
 * @param maxLength 최대 입력 글자 수. 카운터에도 사용된다. 음수는 0으로 보정한다
 * @param minLength 최소 글자 수 조건. null이면 검증 서브텍스트를 숨긴다
 * @param validationErrorText [minLength] 미만일 때 노출할 Error 서브텍스트
 * @param validationSuccessText [minLength] 이상일 때 노출할 Success 서브텍스트
 * @param descriptionText 명시적 오류와 길이 검증 메시지가 없을 때 표시할 안내
 * @param explicitErrorText null이 아니면 length 조건과 무관하게 이 텍스트를 Error 서브텍스트로
 *   강제 노출한다. 서버 검증 실패(예: 연관성 부족)처럼 글자 수와 무관한 오류에 사용.
 */
@Composable
fun HilitJDTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = DEFAULT_PLACEHOLDER,
    maxLength: Int = DEFAULT_MAX_LENGTH,
    minLength: Int? = null,
    validationErrorText: String = "",
    validationSuccessText: String = "",
    explicitErrorText: String? = null,
    descriptionText: String = "",
) {
    val safeMaxLength = maxLength.coerceAtLeast(0)
    val safeMinLength = minLength?.coerceAtLeast(0)
    val scrollState = rememberScrollState()
    val textStyle = HilitTheme.typography.body4
    val counterStyle = HilitTheme.typography.body9
    val textColor = HilitTheme.colors.hilitBlack800
    val placeholderColor = HilitTheme.colors.gray500
    val borderColor = HilitTheme.colors.gray100
    val surfaceColor = HilitTheme.colors.hilitWhite
    val validationSubText =
        resolveJDTextFieldSubText(
            value = value,
            minLength = safeMinLength,
            validationErrorText = validationErrorText,
            validationSuccessText = validationSuccessText,
            explicitErrorText = explicitErrorText,
            descriptionText = descriptionText,
        )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(HilitJDTextFieldHeight)
                    .background(
                        color = surfaceColor,
                        shape = HilitJDTextFieldShape,
                    ).border(
                        width = HilitJDTextFieldBorderWidth,
                        color = borderColor,
                        shape = HilitJDTextFieldShape,
                    ).clip(HilitJDTextFieldShape)
                    .padding(
                        horizontal = HilitJDTextFieldHorizontalPadding,
                        vertical = HilitJDTextFieldVerticalPadding,
                    ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { newValue ->
                    // 붙여넣기 등으로 maxLength를 초과해 들어와도 잘라서 반영해야
                    // 사용자가 백스페이스로 지울 수 없는 락 상태에 빠지지 않는다.
                    onValueChange(newValue.take(safeMaxLength))
                },
                textStyle = textStyle.copy(color = textColor),
                cursorBrush = SolidColor(textColor),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (value.isEmpty()) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = placeholder,
                                style = textStyle,
                                color = placeholderColor,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = HilitJDTextFieldFooterSpacing),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (validationSubText != null) {
                HilitSubText(
                    text = validationSubText.first,
                    type = validationSubText.second,
                    // 서버 오류 등 길이가 가변인 문구가 잘리지 않게 wrap 을 허용한다.
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Text(
                text = "${value.length}/$safeMaxLength",
                style = counterStyle,
                color = placeholderColor,
            )
        }
    }
}

/** 명시적 오류, 길이 검증 메시지, 기본 안내 순으로 하단 문구를 선택한다. */
internal fun resolveJDTextFieldSubText(
    value: String,
    minLength: Int?,
    validationErrorText: String,
    validationSuccessText: String,
    explicitErrorText: String?,
    descriptionText: String = "",
): Pair<String, HilitSubTextType>? {
    // 명시적 error 가 있으면 length 조건과 무관하게 우선 노출한다.
    if (!explicitErrorText.isNullOrEmpty()) {
        return explicitErrorText to HilitSubTextType.Error
    }

    if (minLength == null || value.isEmpty()) {
        return descriptionText.takeIf { it.isNotEmpty() }?.let { it to HilitSubTextType.Default }
    }

    val validationMessage =
        if (value.length >= minLength.coerceAtLeast(0)) {
            validationSuccessText.takeIf { it.isNotEmpty() }?.let { it to HilitSubTextType.Success }
        } else {
            validationErrorText.takeIf { it.isNotEmpty() }?.let { it to HilitSubTextType.Error }
        }
    return validationMessage
        ?: descriptionText.takeIf { it.isNotEmpty() }?.let { it to HilitSubTextType.Default }
}

@Preview(
    name = "HilitJDTextField - Empty",
    showBackground = true,
    widthDp = 360,
)
@Composable
private fun HilitJDTextFieldEmptyPreview() {
    HilitTheme {
        HilitJDTextField(
            value = "",
            onValueChange = {},
            modifier = Modifier.padding(16.dp),
            minLength = 200,
            validationErrorText = "공고 내용은 200자 이상으로 입력해 주세요",
            validationSuccessText = "공고 내용을 확인했어요",
        )
    }
}

@Preview(
    name = "HilitJDTextField - Validation Error",
    showBackground = true,
    widthDp = 360,
)
@Composable
private fun HilitJDTextFieldValidationErrorPreview() {
    HilitTheme {
        HilitJDTextField(
            value = "당사는 백엔드 개발자를 모집합니다.",
            onValueChange = {},
            modifier = Modifier.padding(16.dp),
            minLength = 200,
            validationErrorText = "공고 내용은 200자 이상으로 입력해 주세요",
            validationSuccessText = "공고 내용을 확인했어요",
        )
    }
}

@Preview(
    name = "HilitJDTextField - Validation Success",
    showBackground = true,
    widthDp = 360,
)
@Composable
private fun HilitJDTextFieldValidationSuccessPreview() {
    HilitTheme {
        HilitJDTextField(
            value =
                "당사 서비스의 백엔드 API를 설계하고 운영하실 시니어 개발자를 찾고 있습니다. " +
                    "Kotlin, Spring Boot 기반 마이크로서비스 경험이 필요하며, " +
                    "대용량 트래픽 처리와 데이터 파이프라인 구축 경험을 우대합니다. " +
                    "협업과 코드 리뷰 문화를 중시하는 팀에서 함께 성장할 분을 기다립니다. " +
                    "서비스 품질을 개선하고 안정적인 배포를 위해 자동화 테스트와 모니터링을 함께 구축합니다.",
            onValueChange = {},
            modifier = Modifier.padding(16.dp),
            maxLength = 3000,
            minLength = 200,
            validationErrorText = "공고 내용은 200자 이상으로 입력해 주세요",
            validationSuccessText = "공고 내용을 확인했어요",
        )
    }
}
