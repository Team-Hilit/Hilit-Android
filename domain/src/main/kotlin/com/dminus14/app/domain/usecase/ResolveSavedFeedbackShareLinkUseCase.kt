package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.SavedFeedbackShare
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import com.dminus14.app.domain.util.runCatchingCancellable
import javax.inject.Inject

/**
 * 기기에 저장된 공유 기록에서 다시 쓸 공유 링크를 얻는다.
 *
 * 저장된 동적 링크가 있으면 네트워크를 쓰지 않고 그대로 돌려준다. 링크가 없는 기록(링크를
 * 저장하기 전 버전에서 만들었거나, 링크 완성 전에 생성이 취소된 경우)과 원시 딥링크가 저장된
 * 기록(이전 버전에서 대체값까지 저장한 경우)은 token 으로 동적 링크를 다시 만든다. 저장된 기록이
 * 아예 없으면 null 이다.
 *
 * 다시 만든 링크가 정상 동적 링크일 때만 기록을 갱신한다. 또 실패해 원시 딥링크로 대체되면 그
 * 링크로 지금 공유는 진행하되 저장하지 않아, 다음 조회 때 다시 시도한다. 링크 조립은 실패해도
 * 대체값을 돌려주므로 기록이 있는 한 null 을 돌려주지 않는다.
 */
class ResolveSavedFeedbackShareLinkUseCase
    @Inject
    constructor(
        private val feedbackShareLocalRepository: FeedbackShareLocalRepository,
        private val createFeedbackShareDynamicLink: CreateFeedbackShareDynamicLinkUseCase,
    ) {
        suspend operator fun invoke(sessionId: Long): Result<SavedFeedbackShare?> =
            runCatchingCancellable {
                val saved =
                    feedbackShareLocalRepository.getShare(sessionId)
                        ?: return@runCatchingCancellable null
                val savedLink = saved.link
                if (savedLink != null &&
                    !createFeedbackShareDynamicLink.isFallbackLink(saved.token, savedLink)
                ) {
                    return@runCatchingCancellable saved
                }

                val link = createFeedbackShareDynamicLink(saved.token).getOrThrow()
                val restored = saved.copy(link = link)
                if (!createFeedbackShareDynamicLink.isFallbackLink(saved.token, link)) {
                    feedbackShareLocalRepository.saveShare(sessionId, restored)
                }
                restored
            }
    }
