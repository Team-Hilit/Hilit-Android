package com.dminus14.app.feature.interviewreport.guestfeedback

import androidx.compose.runtime.Immutable
import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState
import com.dminus14.app.domain.model.GuestFeedbackAxisCode

sealed interface GuestFeedbackRequestIntent : MviIntent {
    /** 화면 진입 시 sessionId 에 저장된 공유 링크 token 이 있는지 확인한다. */
    data class Load(
        val sessionId: Long,
    ) : GuestFeedbackRequestIntent

    data class ToggleAxis(
        val axis: GuestFeedbackAxisCode,
    ) : GuestFeedbackRequestIntent

    data object ClickClose : GuestFeedbackRequestIntent

    /** 하단 버튼 클릭. [GuestFeedbackRequestState.hasActiveShare] 에 따라 생성/종료로 분기한다. */
    data object ClickSubmit : GuestFeedbackRequestIntent

    data object ClickCopyLink : GuestFeedbackRequestIntent

    /** "링크 생성 완료" 모달의 닫기 버튼/배경 탭. 항목선택 화면으로 돌아간다(생성은 이미 끝난 상태). */
    data object DismissShareLinkModal : GuestFeedbackRequestIntent
}

@Immutable
data class GuestFeedbackRequestState(
    val selectedAxes: Set<GuestFeedbackAxisCode> = GuestFeedbackAxisCode.entries.toSet(),
    val submitting: Boolean = false,
    /** 링크 생성 성공 시의 공유 링크. non-null 이면 "링크 생성 완료" 모달을 노출한다. */
    val shareLink: String? = null,
    /** 링크 복사 완료 후의 2초 안내 모달 노출 여부. */
    val linkCopied: Boolean = false,
    /**
     * sessionId 에 저장된 공유 링크 token 이 있으면 true.
     * true 면 하단 버튼이 "피드백 링크 생성" 대신 "피드백 종료하기"로 바뀐다.
     */
    val hasActiveShare: Boolean = false,
) : MviState

sealed interface GuestFeedbackRequestEffect : MviEffect {
    data object NavigateBack : GuestFeedbackRequestEffect

    data class CopyToClipboard(
        val link: String,
    ) : GuestFeedbackRequestEffect

    data class ShowToast(
        val message: String,
    ) : GuestFeedbackRequestEffect
}
