package com.dminus14.app.feature.onboarding.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent
import com.dminus14.designsystem.component.tag.TagColorType
import com.dminus14.designsystem.component.text.withHilitTextHighlight
import com.dminus14.designsystem.component.textfield.HilitJDTextField
import com.dminus14.designsystem.theme.HilitTheme
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Flow as FlowIntent

private val SubtitleToLabelSpacing = 30.dp
private val LabelToFieldSpacing = 10.dp
private const val MAIN_PROJECT_MAX_LENGTH = 300
private const val MAIN_PROJECT_PLACEHOLDER =
    "프로젝트에서 담당하신 주요 내용을 담아 10글자 이상 작성해 주세요. " +
        "AI가 프로젝트를 더 좋은 면접 질문을 만들 수 있어요."

/**
 * Figma Node: 3759:14839
 *
 * 선택 입력 안내는 입력란 아래에 표시하고 오류·검증 메시지가 있으면 숨긴다.
 */
@Composable
fun OnBoardingMainProjectStep(
    text: String,
    error: String?,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OnBoardingStepHeader(
            tagText = "선택",
            tagColorType = TagColorType.Gray,
            title =
                buildAnnotatedString {
                    append("집중적으로 보고싶은\n")
                    withHilitTextHighlight { append("프로젝트 내용") }
                    append("을 알려주세요")
                },
            subtitle = "해당 프로젝트를 중점으로 면접이 진행돼요.",
        )

        Column(modifier = Modifier.padding(top = SubtitleToLabelSpacing)) {
            Text(
                text = "프로젝트 내용 입력",
                style = HilitTheme.typography.body2,
                color = HilitTheme.colors.hilitBlack800,
                modifier = Modifier.fillMaxWidth(),
            )

            HilitJDTextField(
                value = text,
                onValueChange = {
                    onIntent(
                        FlowIntent.MainProjectTextChange(it),
                    )
                },
                placeholder = MAIN_PROJECT_PLACEHOLDER,
                maxLength = MAIN_PROJECT_MAX_LENGTH,
                // 서버 연관성 판단(FREETEXT_NOT_RELEVANT) 등 필드 밖 오류를 subtext 슬롯에 노출.
                // 로컬 최소 길이 위반은 계속하기 버튼 자체가 비활성이라 이 자리로 오지 않는다.
                explicitErrorText = error,
                descriptionText = "작성하지 않아도 면접을 시작할 수 있어요",
                modifier = Modifier.padding(top = LabelToFieldSpacing),
            )
        }
    }
}

@Preview(name = "Empty with description", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingMainProjectStepPreview() {
    HilitTheme {
        OnBoardingMainProjectStep(
            text = "",
            error = null,
            onIntent = {},
        )
    }
}

@Preview(name = "Filled with description", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingMainProjectStepFilledPreview() {
    HilitTheme {
        OnBoardingMainProjectStep(
            text = "결제 시스템 리뉴얼 프로젝트에서 백엔드 아키텍처 설계와 API 개발을 담당했습니다.",
            error = null,
            onIntent = {},
        )
    }
}

@Preview(name = "Server error", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingMainProjectStepErrorPreview() {
    HilitTheme {
        OnBoardingMainProjectStep(
            text = "결제 시스템 리뉴얼 프로젝트에서 백엔드 아키텍처 설계와 API 개발을 담당했습니다.",
            error = "포트폴리오에서 그 내용을 찾지 못했어요.",
            onIntent = {},
        )
    }
}
