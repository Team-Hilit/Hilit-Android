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
 * 서버 생성 직후 token·항목을 먼저 저장하고, 링크가 완성되면 기록을 갱신한다. 동적 링크 응답을
 * 기다리는 동안 화면이 닫혀 작업이 취소돼도, 서버에 생긴 활성 공유의 token 은 기기에 남아
 * 재진입 시 [ResolveSavedFeedbackShareLinkUseCase] 가 링크를 다시 만들 수 있다.
 *
 * 링크 조립([CreateFeedbackShareDynamicLinkUseCase])이 실패해 원시 딥링크로 대체된 경우에는 그
 * 링크를 반환해 지금 공유에 쓰되 기록에는 저장하지 않는다. 다음 조회 때 동적 링크를 다시 시도한다.
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
                val pending = SavedFeedbackShare(token = token, axes = axes)
                feedbackShareLocalRepository.saveShare(sessionId, pending)

                val link = createFeedbackShareDynamicLink(token).getOrThrow()
                val share = pending.copy(link = link)
                if (!createFeedbackShareDynamicLink.isFallbackLink(token, link)) {
                    feedbackShareLocalRepository.saveShare(sessionId, share)
                }
                share
            }
    }
