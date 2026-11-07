package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.model.SavedFeedbackShare
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import com.dminus14.app.domain.repository.FeedbackShareRepository
import com.dminus14.app.domain.util.runCatchingCancellable
import javax.inject.Inject

/**
 * 지인 피드백 공유 링크를 생성한다.
 *
 * 서버에 공유를 만들고, 그 token 으로 공유 링크까지 조립한 뒤 token·링크·선택한 평가 항목을
 * [FeedbackShareLocalRepository] 에 함께 저장한다. 재진입 시 서버 호출 없이 저장된 링크를 그대로
 * 다시 띄우고, 요청 멘트도 생성 당시 선택 항목으로 조립할 수 있게 하기 위한 것이다.
 *
 * 링크 조립([CreateFeedbackShareDynamicLinkUseCase])은 실패해도 원시 딥링크로 대체되므로 항상
 * 성공한다. 따라서 저장 시점에 링크가 비어 있는 기록은 생기지 않는다.
 *
 * 생성이 `FEEDBACK_SHARE_ALREADY_EXISTS` 충돌로 실패했을 때의 복구는 이 use case 가 아니라
 * [GuestFeedbackRequestViewModel][com.dminus14.app.feature.interviewreport.guestfeedback.GuestFeedbackRequestViewModel]
 * 이 [ResolveSavedFeedbackShareLinkUseCase] 로 처리한다.
 */
class CreateFeedbackShareUseCase
    @Inject
    constructor(
        private val feedbackShareRepository: FeedbackShareRepository,
        private val feedbackShareLocalRepository: FeedbackShareLocalRepository,
        private val createFeedbackShareDynamicLink: CreateFeedbackShareDynamicLinkUseCase,
    ) {
        suspend operator fun invoke(
            sessionId: Long,
            axes: List<GuestFeedbackAxisCode>,
        ): Result<SavedFeedbackShare> =
            runCatchingCancellable {
                val token = feedbackShareRepository.createShare(sessionId, axes)
                val link = createFeedbackShareDynamicLink(token).getOrThrow()
                val share = SavedFeedbackShare(token = token, link = link, axes = axes)
                feedbackShareLocalRepository.saveShare(sessionId, share)
                share
            }
    }
