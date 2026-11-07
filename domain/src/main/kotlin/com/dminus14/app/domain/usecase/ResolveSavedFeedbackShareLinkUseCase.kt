package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.SavedFeedbackShare
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import com.dminus14.app.domain.util.runCatchingCancellable
import javax.inject.Inject

/**
 * 기기에 저장된 공유 기록에서 다시 쓸 공유 링크를 얻는다.
 *
 * 저장된 링크가 있으면 네트워크를 쓰지 않고 그대로 돌려준다. 링크를 저장하기 전 버전에서 만든
 * 기록(token 만 있는 경우)은 token 으로 링크를 다시 만들어 저장한 뒤 돌려준다. 저장된 기록이
 * 아예 없으면 null 이다.
 *
 * 링크 조립은 실패해도 원시 딥링크로 대체되므로, 기록이 있는 한 null 을 돌려주지 않는다.
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
                saved.link?.let { return@runCatchingCancellable saved }

                val link = createFeedbackShareDynamicLink(saved.token).getOrThrow()
                val restored = saved.copy(link = link)
                feedbackShareLocalRepository.saveShare(sessionId, restored)
                restored
            }
    }
