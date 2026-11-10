package com.dminus14.app.feature.interviewreport.guestfeedback

import com.dminus14.app.domain.model.GuestFeedbackAxisCode

/** 항목선택 리스트와 공유 멘트가 함께 쓰는 평가 항목 표시명. */
internal val GuestFeedbackAxisCode.label: String
    get() =
        when (this) {
            GuestFeedbackAxisCode.GAZE -> "시선"
            GuestFeedbackAxisCode.EXPRESSION -> "표정"
            GuestFeedbackAxisCode.POSTURE -> "자세"
            GuestFeedbackAxisCode.GESTURE -> "손동작"
            GuestFeedbackAxisCode.VOICE -> "목소리"
        }

/** 멘트 마지막에 링크와 함께 붙는 서비스 소개 한 줄. */
private const val SERVICE_INTRO = "AI와 함께 개발자 면접을 대비하세요 - Hilit"

/**
 * 지인에게 붙여넣을 피드백 요청 멘트를 조립한다.
 *
 * 요청 문구를 직접 쓰는 부담을 덜기 위한 초안이다. 사용자 본인이 보내는 말이므로 1인칭을
 * 유지하고, 서비스 소개 한 줄과 링크는 본문 뒤에서 빈 줄로 분리한다. "10분"은 지인이 보는
 * 온보딩 화면(`FeedbackOnboardingScreen`)의 안내와 같은 값이어야 한다.
 *
 * 말투는 존댓말로 통일한다. 받는 사람이 친구일 수도, 선배나 직무 전문가일 수도 있어 존댓말이
 * 모든 상대에게 보낼 수 있는 기본값이고, 마지막 줄의 서비스 소개와도 말투가 어긋나지 않는다.
 *
 * 사용자 이름이나 세션 정보 같은 식별 정보는 넣지 않는다. 클립보드와 외부 앱으로 그대로
 * 나가는 텍스트이기 때문이다.
 */
internal fun buildGuestFeedbackShareMessage(
    selectedAxes: Set<GuestFeedbackAxisCode>,
    shareLink: String,
): String =
    buildString {
        appendLine("면접 연습 영상을 찍고 리포트까지 받아봤는데, 사람 눈에는 어떨지가 제일 궁금해요 😅")
        appendLine("${axesPhrase(selectedAxes)} 영상 보면서 고르기만 하면 되고 10분이면 끝나요.")
        appendLine()
        appendLine(SERVICE_INTRO)
        appendLine()
        append(shareLink)
    }

/** 선택 항목이 전부면 "면접 태도"로 뭉치고, 일부면 항목선택 리스트 순서대로 이어 붙인다. */
private fun axesPhrase(selectedAxes: Set<GuestFeedbackAxisCode>): String {
    val isEveryAxis = selectedAxes.containsAll(GuestFeedbackAxisCode.entries)
    if (selectedAxes.isEmpty() || isEveryAxis) return "면접 태도가 어땠는지 알려주실 수 있나요?"

    val labels =
        GuestFeedbackAxisCode.entries
            .filter { it in selectedAxes }
            .joinToString(separator = "·") { it.label }
    return "${labels}만 보고 알려주실 수 있나요?"
}
