package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.exception.FeedbackShareNotFoundException
import com.dminus14.app.domain.exception.InvalidFeedbackShareStatusException
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import com.dminus14.app.domain.repository.FeedbackShareRepository
import com.dminus14.app.domain.util.runCatchingCancellable
import javax.inject.Inject

/**
 * 지인 피드백 공유 링크를 종료(비공개 전환)한다. 되돌릴 수 없다(feedback.md).
 *
 * 서버가 이미 종료된 상태(`FeedbackShareNotFoundException`/`InvalidFeedbackShareStatusException`,
 * 예: 링크를 못 찾음 또는 이미 비공개)로 응답하면, 저장된 token 이 가리키는 목표(피드백 요청이
 * 더 이상 활성이 아님)는 이미 달성된 것이므로 실패로 취급하지 않고 성공과 동일하게 처리한다.
 *
 * 성공하면 [FeedbackShareLocalRepository] 에 저장된 공유 기록(token·링크·선택 항목)도 함께 지운다.
 */
class EndFeedbackShareUseCase
    @Inject
    constructor(
        private val feedbackShareRepository: FeedbackShareRepository,
        private val feedbackShareLocalRepository: FeedbackShareLocalRepository,
    ) {
        suspend operator fun invoke(sessionId: Long): Result<Unit> =
            runCatchingCancellable {
                try {
                    feedbackShareRepository.closeShare(sessionId)
                } catch (_: FeedbackShareNotFoundException) {
                    Unit
                } catch (_: InvalidFeedbackShareStatusException) {
                    Unit
                }
                feedbackShareLocalRepository.clearShare(sessionId)
            }
    }
