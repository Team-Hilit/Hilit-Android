package com.dminus14.app.domain.repository

import com.dminus14.app.domain.model.SavedFeedbackShare

/**
 * 지인 피드백 공유 기록의 기기 내 저장소 계약이다.
 *
 * 링크 생성([FeedbackShareRepository.createShare]) 성공 시 저장하고, 종료
 * ([FeedbackShareRepository.closeShare]) 성공 시 지운다. 화면 진입 시 이 값의 존재 여부로 하단
 * 버튼이 서버에 새로 생성할지, 저장된 링크를 다시 띄울지를 가른다.
 */
interface FeedbackShareLocalRepository {
    suspend fun getShare(sessionId: Long): SavedFeedbackShare?

    suspend fun saveShare(
        sessionId: Long,
        share: SavedFeedbackShare,
    )

    suspend fun clearShare(sessionId: Long)
}
