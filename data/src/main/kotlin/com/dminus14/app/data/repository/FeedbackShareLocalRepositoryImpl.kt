package com.dminus14.app.data.repository

import com.dminus14.app.data.local.feedback.FeedbackShareStore
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.model.SavedFeedbackShare
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedbackShareLocalRepositoryImpl
    @Inject
    constructor(
        private val shareStore: FeedbackShareStore,
    ) : FeedbackShareLocalRepository {
        override suspend fun getShare(sessionId: Long): SavedFeedbackShare? =
            shareStore.getShare(sessionId)?.let { stored ->
                SavedFeedbackShare(
                    token = stored.token,
                    link = stored.link,
                    axes = stored.axes.toAxisCodes(),
                )
            }

        override suspend fun saveShare(
            sessionId: Long,
            share: SavedFeedbackShare,
        ) {
            shareStore.setShare(
                sessionId = sessionId,
                share =
                    FeedbackShareStore.StoredShare(
                        token = share.token,
                        link = share.link,
                        axes = share.axes.map { it.name },
                    ),
            )
        }

        override suspend fun clearShare(sessionId: Long) {
            shareStore.clearShare(sessionId)
        }

        /**
         * 저장된 이름 중 현재 앱이 모르는 값은 버린다. 서버·앱 버전 차이로 항목이 바뀌어도
         * 기록 전체를 읽지 못하는 상태가 되지 않게 한다.
         */
        private fun List<String>.toAxisCodes(): List<GuestFeedbackAxisCode> =
            mapNotNull { name ->
                GuestFeedbackAxisCode.entries.firstOrNull { it.name == name }
            }
    }
