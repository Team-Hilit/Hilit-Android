package com.dminus14.app.feature.home

import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState

sealed interface HomeIntent : MviIntent {
    data object Load : HomeIntent

    data object ClickMyPage : HomeIntent

    data class ClickReportExpand(
        val reportId: String,
    ) : HomeIntent

    /** 펼친 리포트 카드의 화살표(">") 버튼. 리포트 상세 화면으로 이동한다. */
    data class ClickReportOpen(
        val reportId: String,
    ) : HomeIntent

    /** 리포트 바텀시트를 하단(Collapsed)까지 내렸을 때. 진행 중 세션 여부를 확인한다. */
    data object ReportSheetCollapsed : HomeIntent

    /**
     * 세션 시작 오버레이의 시작 계열 버튼. 현재 [HomeState.sessionStartOverlay]에 따라 동작이
     * 갈린다.
     * - `Start`(또는 오버레이 없음): 잔여 이용권에 따라 온보딩 인터뷰로 이동하거나 소진(NoTickets)
     *   오버레이를 띄운다.
     * - `InProgress`의 "처음부터 시작": 곧장 시작하지 않고 `ConfirmRestart` 재확인 오버레이로 넘어간다.
     * - `ConfirmRestart`의 "처음부터 시작"(최종 확정): 기존 진행 중 세션을 중단(abandon)한 뒤
     *   온보딩 인터뷰로 이동한다.
     */
    data object ClickSessionStart : HomeIntent

    /**
     * 세션 시작 오버레이의 닫기 계열 버튼(닫기·홈으로·뒤로가기).
     * 오버레이를 닫고 리포트 시트를 중간(Peek)으로 되돌린다.
     */
    data object ClickSessionOverlayDismiss : HomeIntent

    /** 진행 중 면접 "이어서 진행". 서버에 재개를 확정하고 최신 질문으로 이동한다. */
    data object ClickSessionResume : HomeIntent
}

data class HomeReportItem(
    val id: String,
    val date: String,
    val title: String?,
)

data class HomeState(
    val isLoading: Boolean = false,
    val userName: String = "",
    val remainingTicketCount: Int? = null,
    val reports: List<HomeReportItem> = emptyList(),
    val expandedReportIds: Set<String> = emptySet(),
    /**
     * 홈에 페이드인으로 노출할 세션 시작 오버레이. null이면 아무것도 표시하지 않는다.
     * 트리거 로직은 후속에서 인텐트/UseCase를 통해 채우며, 지금은 상태 필드만 준비해 둔다.
     */
    val sessionStartOverlay: HomeSessionStartOverlayState? = null,
    internal val shouldOpenSessionOverlayAfterProfileLoad: Boolean = false,
) : MviState

sealed interface HomeEffect : MviEffect {
    data object GoToMyPageRequested : HomeEffect

    /**
     * 프로필 이름이 비어 있어 온보딩(직무·연차 입력)으로 라우팅해야 함을 알린다.
     * 스플래시가 동일 조건을 [com.dminus14.app.feature.login.splash.SplashEffect.RequireOnboarding]
     * 으로 처리하는 것과 대응한다.
     */
    data object UserNameNotRegistered : HomeEffect

    /**
     * 유저가 존재하지 않아(`UserNotFoundException`) 스플래시로 되돌려야 함을 알린다.
     */
    data object UserNotFound : HomeEffect

    /** 면접 시작을 위해 온보딩 인터뷰 화면으로 이동해야 함을 알린다. */
    data object GoToOnboardingInterviewRequested : HomeEffect

    /** 진행 중 면접을 이어서 진행하기 위해 Interview 화면으로 이동해야 함을 알린다. */
    data class ResumeConfirmed(
        val sessionId: Long,
        val questionId: Long,
    ) : HomeEffect

    data object SessionInvalid : HomeEffect

    /** 세션 오버레이를 닫은 뒤 리포트 시트를 중간(Peek)으로 되돌려야 함을 알린다. */
    data object ReportSheetResetRequested : HomeEffect

    /**
     * 펼친 리포트 카드의 화살표(">") 클릭 시 해당 리포트 상세 화면으로 이동해야 함을 알린다.
     * 후속 구현: ReportScreen 화면 연동 대기.
     */
    data class GoToReportRequested(
        val reportId: String,
    ) : HomeEffect
}

/** [HomeContent]에 주입하는 콜백·시트 리셋 신호 묶음. */
data class HomeContentCallbacks(
    val onReportExpandClick: (String) -> Unit,
    val onReportActionClick: (String) -> Unit,
    val onReportSheetCollapsed: () -> Unit = {},
    val onSessionStartClick: () -> Unit = {},
    val onSessionOverlayDismiss: () -> Unit = {},
    val onSessionResumeClick: () -> Unit = {},
    val onMyPageClick: () -> Unit = {},
    val peekResetSignal: Int = 0,
)

internal val PreviewHomeReports =
    listOf(
        HomeReportItem(
            id = "1",
            date = "7월 11일 월",
            title = "캐시 도입 결정의 이유와 한계까지 구체적인 수치로 설명해 주셨어요",
        ),
        HomeReportItem(
            id = "2",
            date = "7월 10일 월",
            title = "서비스 아키텍처 변경 배경을 명확히 설명해 주셨어요",
        ),
        HomeReportItem(
            id = "3",
            date = "7월 10일 월",
            title = "팀 협업 경험을 구체적인 사례로 설명해 주셨어요",
        ),
        HomeReportItem(
            id = "4",
            date = "7월 10일 월",
            title = "문제 해결 과정을 단계별로 설명해 주셨어요",
        ),
        HomeReportItem(
            id = "5",
            date = "7월 10일 월",
            title = "기술 선택 근거를 비교 관점에서 설명해 주셨어요",
        ),
    )
