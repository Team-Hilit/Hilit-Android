package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.SavedFeedbackShare
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import javax.inject.Inject

/** sessionId 에 기기 저장된 지인 피드백 공유 기록을 조회한다. 없으면 null 이다. */
class GetSavedFeedbackShareUseCase
    @Inject
    constructor(
        private val feedbackShareLocalRepository: FeedbackShareLocalRepository,
    ) {
        suspend operator fun invoke(sessionId: Long): SavedFeedbackShare? =
            feedbackShareLocalRepository.getShare(sessionId)
    }
