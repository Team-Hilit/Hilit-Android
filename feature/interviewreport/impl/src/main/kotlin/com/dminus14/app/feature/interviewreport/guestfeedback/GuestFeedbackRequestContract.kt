package com.dminus14.app.feature.interviewreport.guestfeedback

import androidx.compose.runtime.Immutable
import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.model.SavedFeedbackShare

sealed interface GuestFeedbackRequestIntent : MviIntent {
    /** 화면 진입 시 sessionId 에 저장된 공유 기록이 있는지 확인하고 선택 항목을 복원한다. */
    data class Load(
        val sessionId: Long,
    ) : GuestFeedbackRequestIntent

    data class ToggleAxis(
        val axis: GuestFeedbackAxisCode,
    ) : GuestFeedbackRequestIntent

    data object ClickClose : GuestFeedbackRequestIntent

    /**
     * 하단 버튼 클릭. 저장된 공유 기록이 없으면 서버에 새로 생성하고, 있으면 서버를 거치지 않고
     * 저장된 링크를 바로 복사한다(#219).
     */
    data object ClickSubmit : GuestFeedbackRequestIntent

    /** 멘트와 링크를 클립보드로 복사한다. */
    data object ClickCopyMessage : GuestFeedbackRequestIntent

    /** 멘트와 링크를 안드로이드 공유 시트로 넘긴다. */
    data object ClickShareMessage : GuestFeedbackRequestIntent
}

@Immutable
data class GuestFeedbackRequestState(
    val selectedAxes: Set<GuestFeedbackAxisCode> = GuestFeedbackAxisCode.entries.toSet(),
    val submitting: Boolean = false,
    /** 링크 생성 성공 시의 공유 링크. non-null 이면 "링크 생성 완료" 모달을 노출한다. */
    val shareLink: String? = null,
    /**
     * sessionId 에 기기 저장된 공유 기록. non-null 이면 하단 버튼이 서버에 새로 생성하지 않고
     * 이 기록의 링크를 복사한다.
     */
    val savedShare: SavedFeedbackShare? = null,
    /**
     * 서버에는 활성 링크가 있지만 이 기기에 기록이 없어(다른 기기에서 만들었거나 앱 데이터가
     * 지워진 경우) 링크를 다시 꺼낼 수 없는 상태. 이때만 하단 버튼이 "피드백 종료하기"가 된다.
     */
    val endOnlyShare: Boolean = false,
) : MviState {
    /** 종료하지 않은 공유 링크가 이미 있는지 여부. */
    val hasActiveShare: Boolean get() = savedShare != null

    /**
     * 평가 항목 토글을 더 이상 바꿀 수 없는 상태(#219).
     *
     * 생성 API 는 최초 1회만 성공하고 이후에는 409 로 거절되므로, 토글을 바꿔도 실제 링크에는
     * 반영되지 않는다. 화면과 링크가 어긋나지 않도록 생성 이후에는 조작을 막는다.
     */
    val axesLocked: Boolean get() = hasActiveShare || endOnlyShare

    /**
     * 요청 멘트에 쓸 평가 항목.
     *
     * 저장된 기록이 있으면 생성 당시 항목을 쓴다. 재진입 후 사용자가 토글을 바꿔도 서버에 등록된
     * 항목은 그대로이므로, 화면의 토글 상태가 아니라 저장값이 멘트의 기준이어야 한다.
     */
    val messageAxes: Set<GuestFeedbackAxisCode>
        get() = savedShare?.axes?.takeIf { it.isNotEmpty() }?.toSet() ?: selectedAxes
}

sealed interface GuestFeedbackRequestEffect : MviEffect {
    data object NavigateBack : GuestFeedbackRequestEffect

    data class CopyToClipboard(
        val text: String,
    ) : GuestFeedbackRequestEffect

    /** 안드로이드 공유 시트(`ACTION_SEND`)로 넘길 텍스트. */
    data class ShareText(
        val text: String,
    ) : GuestFeedbackRequestEffect

    data class ShowToast(
        val message: String,
    ) : GuestFeedbackRequestEffect
}
