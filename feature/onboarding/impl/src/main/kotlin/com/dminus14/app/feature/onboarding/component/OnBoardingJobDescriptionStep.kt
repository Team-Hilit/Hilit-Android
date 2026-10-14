package com.dminus14.app.feature.onboarding.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.dminus14.app.feature.onboarding.JOB_DESCRIPTION_TEXT_MIN_LENGTH
import com.dminus14.app.feature.onboarding.JdLinkStatus
import com.dminus14.app.feature.onboarding.JobDescriptionTab
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent
import com.dminus14.designsystem.component.tab.HilitTabItem
import com.dminus14.designsystem.component.tab.HilitTabRow
import com.dminus14.designsystem.component.tag.TagColorType
import com.dminus14.designsystem.component.text.withHilitTextHighlight
import com.dminus14.designsystem.component.textfield.HilitAsyncTextField
import com.dminus14.designsystem.component.textfield.HilitAsyncTextFieldType
import com.dminus14.designsystem.component.textfield.HilitJDTextField

private val SubtitleToTabSpacing = 34.dp
private val TabToFieldSpacing = 16.dp
private const val JOB_DESCRIPTION_TEXT_MAX_LENGTH = 3000
private const val JOB_DESCRIPTION_TEXT_VALIDATION_ERROR = "공고 내용은 200자 이상으로 입력해 주세요"
private const val JOB_DESCRIPTION_TEXT_VALIDATION_SUCCESS = "공고 내용을 확인했어요"

/**
 * Figma Node: 3759:14839
 *
 * 선택 입력 안내는 입력란 아래에 표시하고 오류·검증 메시지가 있으면 숨긴다.
 */
@Composable
@Suppress("LongParameterList")
fun OnBoardingJobDescriptionStep(
    tab: JobDescriptionTab,
    link: String,
    linkStatus: JdLinkStatus,
    linkSubText: String,
    text: String,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OnBoardingStepHeader(
            tagText = "선택",
            tagColorType = TagColorType.Gray,
            title =
                buildAnnotatedString {
                    withHilitTextHighlight { append("채용공고 링크") }
                    append("를\n업로드해 주세요.")
                },
            subtitle = "채용 페이지에 직접 올라온 공고 링크를 넣어주세요.",
        )

        HilitTabRow(
            items =
                listOf(
                    HilitTabItem(text = "링크 붙여넣기"),
                    HilitTabItem(text = "직접 입력하기"),
                ),
            selectedIndex = tab.ordinal,
            onTabSelected = { onIntent(OnBoardingInterviewIntent.JobDescriptionTabChange(it)) },
            modifier = Modifier.padding(top = SubtitleToTabSpacing),
        )

        OnBoardingJobDescriptionField(
            tab = tab,
            link = link,
            linkStatus = linkStatus,
            linkSubText = linkSubText,
            text = text,
            onIntent = onIntent,
            modifier = Modifier.padding(top = TabToFieldSpacing),
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun OnBoardingJobDescriptionField(
    tab: JobDescriptionTab,
    link: String,
    linkStatus: JdLinkStatus,
    linkSubText: String,
    text: String,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        JobDescriptionTab.Link -> {
            HilitAsyncTextField(
                value = link,
                onValueChange = {
                    onIntent(
                        OnBoardingInterviewIntent.JobDescriptionLinkChange(it),
                    )
                },
                type = link.toLinkFieldType(linkStatus),
                placeholder = "https://www.hilit.com/",
                processingText = "분석 중",
                subText = linkSubText,
                descriptionText = "채용공고 없이도 계속 진행할 수 있어요",
                onClearClick = { onIntent(OnBoardingInterviewIntent.JobDescriptionLinkChange("")) },
                modifier = modifier,
            )
        }

        JobDescriptionTab.Text -> {
            HilitJDTextField(
                value = text,
                onValueChange = {
                    onIntent(
                        OnBoardingInterviewIntent.JobDescriptionTextChange(it),
                    )
                },
                descriptionText = "채용공고 없이도 계속 진행할 수 있어요",
                maxLength = JOB_DESCRIPTION_TEXT_MAX_LENGTH,
                minLength = JOB_DESCRIPTION_TEXT_MIN_LENGTH,
                validationErrorText = JOB_DESCRIPTION_TEXT_VALIDATION_ERROR,
                validationSuccessText = JOB_DESCRIPTION_TEXT_VALIDATION_SUCCESS,
                modifier = modifier,
            )
        }
    }
}

private fun String.toLinkFieldType(status: JdLinkStatus): HilitAsyncTextFieldType =
    when {
        isEmpty() -> HilitAsyncTextFieldType.Ready
        status == JdLinkStatus.Validating -> HilitAsyncTextFieldType.Processing
        status == JdLinkStatus.Valid -> HilitAsyncTextFieldType.Complete
        status == JdLinkStatus.Invalid -> HilitAsyncTextFieldType.Error
        else -> HilitAsyncTextFieldType.Edit
    }
