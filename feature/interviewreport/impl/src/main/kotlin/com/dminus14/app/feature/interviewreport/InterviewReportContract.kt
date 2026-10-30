package com.dminus14.app.feature.interviewreport

import androidx.compose.runtime.Immutable
import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState
import com.dminus14.app.feature.interviewreport.model.ReportUiModel

sealed interface InterviewReportIntent : MviIntent {
    data class Load(
        val sessionId: Long,
    ) : InterviewReportIntent

    data object ClickClose : InterviewReportIntent

    data object RefreshReport : InterviewReportIntent

    data object ClickRetry : InterviewReportIntent

    data object ClickWatchVideo : InterviewReportIntent

    data class ClickWatchScene(
        val startSec: Float,
    ) : InterviewReportIntent

    data class ClickHighlight(
        val cardIndex: Int,
        val spanIndex: Int,
    ) : InterviewReportIntent

    data object DismissHighlight : InterviewReportIntent

    data object ClickGuestFeedbackInvite : InterviewReportIntent

    /** 상세 리포트에서 질문 탭을 선택한다. */
    data class SelectCard(
        val cardIndex: Int,
    ) : InterviewReportIntent

    /** 지인 피드백 섹션에서 지인 탭을 선택한다. */
    data class SelectGuestFeedback(
        val guestIndex: Int,
    ) : InterviewReportIntent
}

@Immutable
data class InterviewReportState(
    val phase: Phase = Phase.Loading,
    val sessionId: Long = 0L,
    val isRefreshing: Boolean = false,
    val selectedHighlight: SelectedHighlight? = null,
    /** 상세 리포트에서 현재 선택된 질문 탭 index. */
    val selectedCardIndex: Int = 0,
    /** 지인 피드백 섹션에서 현재 선택된 지인 탭 index. */
    val selectedGuestFeedbackIndex: Int = 0,
    /**
     * 영상 만료까지 남은 초. 리포트 응답이 아니라 `/video/expiry`(GetVideoExpiryUseCase)에서 온다.
     * 카운트다운 UI 의 시드 값이며, 조회 실패·만료 시 null 이면 카운트다운 영역을 숨긴다.
     */
    val videoExpirySeconds: Long? = null,
) : MviState {
    sealed interface Phase {
        data object Loading : Phase

        data object Failed : Phase

        /**
         * 분석 부족 상태. 채점된 범위의 부분 리포트를 그대로 렌더한다(헤드라인이 분석 부족 톤).
         */
        data class InsufficientAnalysis(
            val report: ReportUiModel,
        ) : Phase

        /** 리포트가 준비된 뒤 UI 렌더에 사용하는 최종 모델. */
        data class Ready(
            val report: ReportUiModel,
        ) : Phase
    }

    @Immutable
    data class SelectedHighlight(
        val cardIndex: Int,
        val spanIndex: Int,
    )
}

sealed interface InterviewReportEffect : MviEffect {
    data object NavigateBack : InterviewReportEffect

    data class NavigateToPlayer(
        val sessionId: Long,
        val startSec: Float?,
    ) : InterviewReportEffect

    data class NavigateToGuestFeedback(
        val sessionId: Long,
    ) : InterviewReportEffect

    data class ShowToast(
        val message: String,
    ) : InterviewReportEffect
}
