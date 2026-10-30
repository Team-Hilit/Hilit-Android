@file:Suppress("ktlint:standard:filename")

package com.dminus14.app.feature.interviewreport

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.dminus14.app.feature.interviewreport.guestfeedback.GuestFeedbackRequestContent
import com.dminus14.app.feature.interviewreport.guestfeedback.GuestFeedbackRequestState
import com.dminus14.designsystem.theme.HilitTheme

private const val SAMPLE_EXPIRY_SECONDS = 86_373L

@Preview(name = "리포트 - 정상", heightDp = 1100)
@Composable
private fun InterviewReportReadyPreview() {
    HilitTheme {
        InterviewReportContent(
            state =
                InterviewReportState(
                    phase = InterviewReportState.Phase.Ready(PreviewInterviewReport.readyReport),
                    videoExpirySeconds = SAMPLE_EXPIRY_SECONDS,
                ),
            onIntent = {},
        )
    }
}

@Preview(name = "리포트 - 영상 만료", heightDp = 1100)
@Composable
private fun InterviewReportExpiredPreview() {
    HilitTheme {
        InterviewReportContent(
            state =
                InterviewReportState(
                    phase = InterviewReportState.Phase.Ready(PreviewInterviewReport.expiredReport),
                    videoExpirySeconds = null,
                ),
            onIntent = {},
        )
    }
}

@Preview(name = "리포트 - 로딩")
@Composable
private fun InterviewReportLoadingPreview() {
    HilitTheme {
        InterviewReportContent(
            state = InterviewReportState(phase = InterviewReportState.Phase.Loading),
            onIntent = {},
        )
    }
}

@Preview(name = "지인피드백 - 항목선택", heightDp = 812)
@Composable
private fun GuestFeedbackRequestPreview() {
    HilitTheme {
        GuestFeedbackRequestContent(
            state = GuestFeedbackRequestState(),
            onIntent = {},
        )
    }
}

@Preview(name = "지인피드백 - 링크 생성 모달", heightDp = 812)
@Composable
private fun GuestFeedbackLinkCreatedPreview() {
    HilitTheme {
        GuestFeedbackRequestContent(
            state = GuestFeedbackRequestState(shareLink = "https://hilit.app/f/sample-token"),
            onIntent = {},
        )
    }
}

@Preview(name = "리포트 - 새로고침 중", heightDp = 1100)
@Composable
private fun InterviewReportRefreshingPreview() {
    HilitTheme {
        InterviewReportContent(
            state =
                InterviewReportState(
                    phase = InterviewReportState.Phase.Ready(PreviewInterviewReport.readyReport),
                    isRefreshing = true,
                ),
            onIntent = {},
        )
    }
}
