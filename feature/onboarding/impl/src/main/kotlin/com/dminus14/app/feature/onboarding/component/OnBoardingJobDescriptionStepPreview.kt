package com.dminus14.app.feature.onboarding.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.dminus14.app.feature.onboarding.JdLinkStatus
import com.dminus14.app.feature.onboarding.JobDescriptionTab
import com.dminus14.designsystem.theme.HilitTheme

@Preview(
    name = "Link - Empty with description",
    showBackground = true,
    widthDp = 375,
    heightDp = 812,
)
@Composable
private fun OnBoardingJobDescriptionStepLinkPreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Link,
            link = "",
            linkStatus = JdLinkStatus.Idle,
            linkSubText = "",
            text = "",
            onIntent = {},
        )
    }
}

@Preview(name = "Link - Filled", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingJobDescriptionStepLinkFilledPreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Link,
            link = "https://www.hilit.com/jobs/123",
            linkStatus = JdLinkStatus.Idle,
            linkSubText = "",
            text = "",
            onIntent = {},
        )
    }
}

@Preview(name = "Link - Processing", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingJobDescriptionStepLinkProcessingPreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Link,
            link = "https://www.hilit.com/jobs/123",
            linkStatus = JdLinkStatus.Validating,
            linkSubText = "",
            text = "",
            onIntent = {},
        )
    }
}

@Preview(name = "Link - Complete", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingJobDescriptionStepLinkCompletePreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Link,
            link = "https://www.hilit.com/jobs/123",
            linkStatus = JdLinkStatus.Valid,
            linkSubText = "링크를 확인했어요",
            text = "",
            onIntent = {},
        )
    }
}

@Preview(name = "Link - Error", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingJobDescriptionStepLinkErrorPreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Link,
            link = "http://www.hilit.com/jobs/123",
            linkStatus = JdLinkStatus.Invalid,
            linkSubText = "https:// 형식의 링크를 입력해주세요",
            text = "",
            onIntent = {},
        )
    }
}

@Preview(
    name = "Text - Empty with description",
    showBackground = true,
    widthDp = 375,
    heightDp = 812,
)
@Composable
private fun OnBoardingJobDescriptionStepTextPreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Text,
            link = "",
            linkStatus = JdLinkStatus.Idle,
            linkSubText = "",
            text = "",
            onIntent = {},
        )
    }
}

@Preview(name = "Text - Filled (under min)", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingJobDescriptionStepTextFilledPreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Text,
            link = "",
            linkStatus = JdLinkStatus.Idle,
            linkSubText = "",
            text = "당사 서비스의 백엔드 API를 설계하고 운영하실 시니어 개발자를 찾고 있습니다.",
            onIntent = {},
        )
    }
}

@Preview(name = "Text - Valid", showBackground = true, widthDp = 375, heightDp = 812)
@Composable
private fun OnBoardingJobDescriptionStepTextValidPreview() {
    HilitTheme {
        OnBoardingJobDescriptionStep(
            tab = JobDescriptionTab.Text,
            link = "",
            linkStatus = JdLinkStatus.Idle,
            linkSubText = "",
            text =
                "당사 서비스의 백엔드 API를 설계하고 운영하실 시니어 개발자를 찾고 있습니다. " +
                    "Kotlin, Spring Boot 기반 마이크로서비스 경험이 필요하며, " +
                    "대용량 트래픽 처리와 데이터 파이프라인 구축 경험을 우대합니다. " +
                    "협업과 코드 리뷰 문화를 중시하는 팀에서 함께 성장할 분을 기다립니다. " +
                    "서비스 품질을 개선하고 안정적인 배포를 위해 자동화 테스트와 모니터링을 함께 구축합니다.",
            onIntent = {},
        )
    }
}
