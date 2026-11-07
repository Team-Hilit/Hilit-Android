package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.exception.FeedbackShareNotFoundException
import com.dminus14.app.domain.exception.InvalidFeedbackShareStatusException
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.model.SavedFeedbackShare
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import com.dminus14.app.domain.repository.FeedbackShareRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndFeedbackShareUseCaseTest {
    @Test
    fun `종료 성공 시 저장된 공유 기록을 지운다`() {
        val localRepository =
            FakeFeedbackShareLocalRepository(
                mapOf(7L to SavedFeedbackShare("saved-token")),
            )
        val useCase = EndFeedbackShareUseCase(FakeFeedbackShareRepository(), localRepository)

        val actual = runBlocking { useCase(7L) }

        assertTrue(actual.isSuccess)
        assertEquals(null, runBlocking { localRepository.getShare(7L)?.token })
    }

    @Test
    fun `서버에 링크가 이미 없으면 성공으로 취급하고 저장된 공유 기록을 지운다`() {
        val localRepository =
            FakeFeedbackShareLocalRepository(
                mapOf(7L to SavedFeedbackShare("saved-token")),
            )
        val repository =
            FakeFeedbackShareRepository(
                closeFailure =
                    FeedbackShareNotFoundException(
                        errCode = "FEEDBACK_SHARE_NOT_FOUND",
                        message = "피드백 공유 링크를 찾을 수 없어요.",
                    ),
            )
        val useCase = EndFeedbackShareUseCase(repository, localRepository)

        val actual = runBlocking { useCase(7L) }

        assertTrue(actual.isSuccess)
        assertEquals(null, runBlocking { localRepository.getShare(7L)?.token })
    }

    @Test
    fun `이미 지원하지 않는 상태 전환이면 성공으로 취급하고 저장된 공유 기록을 지운다`() {
        val localRepository =
            FakeFeedbackShareLocalRepository(
                mapOf(7L to SavedFeedbackShare("saved-token")),
            )
        val repository =
            FakeFeedbackShareRepository(
                closeFailure =
                    InvalidFeedbackShareStatusException(
                        errCode = "INVALID_SHARE_STATUS",
                        message = "지원하지 않는 상태 전환이에요.",
                    ),
            )
        val useCase = EndFeedbackShareUseCase(repository, localRepository)

        val actual = runBlocking { useCase(7L) }

        assertTrue(actual.isSuccess)
        assertEquals(null, runBlocking { localRepository.getShare(7L)?.token })
    }

    @Test
    fun `그 외 오류는 실패로 전달하고 저장된 공유 기록을 유지한다`() {
        val localRepository =
            FakeFeedbackShareLocalRepository(
                mapOf(7L to SavedFeedbackShare("saved-token")),
            )
        val repository = FakeFeedbackShareRepository(closeFailure = IllegalStateException("서버 오류"))
        val useCase = EndFeedbackShareUseCase(repository, localRepository)

        val actual = runBlocking { useCase(7L) }

        assertTrue(actual.isFailure)
        assertEquals("saved-token", runBlocking { localRepository.getShare(7L)?.token })
    }

    private class FakeFeedbackShareRepository(
        private val closeFailure: Throwable? = null,
    ) : FeedbackShareRepository {
        override suspend fun createShare(
            sessionId: Long,
            axes: List<GuestFeedbackAxisCode>,
        ): String = error("사용하지 않음")

        override suspend fun closeShare(sessionId: Long) {
            closeFailure?.let { throw it }
        }
    }

    private class FakeFeedbackShareLocalRepository(
        initialShares: Map<Long, SavedFeedbackShare> = emptyMap(),
    ) : FeedbackShareLocalRepository {
        private val shares = initialShares.toMutableMap()

        override suspend fun getShare(sessionId: Long): SavedFeedbackShare? = shares[sessionId]

        override suspend fun saveShare(
            sessionId: Long,
            share: SavedFeedbackShare,
        ) {
            shares[sessionId] = share
        }

        override suspend fun clearShare(sessionId: Long) {
            shares.remove(sessionId)
        }
    }
}
