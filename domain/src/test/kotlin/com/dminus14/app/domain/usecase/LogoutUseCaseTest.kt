package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.AuthSession
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.repository.AuthRepository
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.repository.InterviewWorkController
import com.dminus14.app.domain.repository.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutUseCaseTest {
    private fun newClearInterviewLocalData() =
        ClearInterviewLocalDataUseCase(
            NoOpInterviewLocalRepository(),
            NoOpInterviewWorkController(),
        )

    @Test
    fun `서버 폐기가 성공하면 로컬 세션도 삭제된다`() {
        val authRepository = FakeAuthRepository(logoutResult = Result.success(Unit))
        val sessionRepository = FakeSessionRepository()
        val useCase = LogoutUseCase(authRepository, sessionRepository, newClearInterviewLocalData())

        val actual = runBlocking { useCase() }

        assertTrue(actual.isSuccess)
        assertEquals(1, sessionRepository.clearCallCount)
    }

    @Test
    fun `서버 폐기가 실패해도 로컬 세션은 삭제된다`() {
        val authRepository =
            FakeAuthRepository(logoutResult = Result.failure(IllegalStateException("서버 오류")))
        val sessionRepository = FakeSessionRepository()
        val useCase = LogoutUseCase(authRepository, sessionRepository, newClearInterviewLocalData())

        val actual = runBlocking { useCase() }

        assertTrue(actual.isSuccess)
        assertEquals(1, sessionRepository.clearCallCount)
    }

    @Test
    fun `로컬 세션 삭제 자체가 실패하면 Result 실패로 전달된다`() {
        val authRepository = FakeAuthRepository(logoutResult = Result.success(Unit))
        val sessionRepository =
            FakeSessionRepository(clearResult = Result.failure(IllegalStateException("저장소 오류")))
        val useCase = LogoutUseCase(authRepository, sessionRepository, newClearInterviewLocalData())

        val actual = runBlocking { useCase() }

        assertTrue(actual.isFailure)
    }

    @Test
    fun `로그아웃은 면접 로컬 정리 UseCase를 호출한다`() {
        val authRepository = FakeAuthRepository(logoutResult = Result.success(Unit))
        val sessionRepository = FakeSessionRepository()
        val interviewRepository = NoOpInterviewLocalRepository()
        val workController = NoOpInterviewWorkController()
        val clearInterviewLocalData =
            ClearInterviewLocalDataUseCase(interviewRepository, workController)
        val useCase = LogoutUseCase(authRepository, sessionRepository, clearInterviewLocalData)

        runBlocking { useCase() }

        assertEquals(1, workController.cancelAllCallCount)
        assertEquals(1, interviewRepository.clearAllCallCount)
    }

    @Test
    fun `면접 로컬 정리가 실패해도 인증 세션은 삭제되고 로그아웃은 성공한다`() {
        val authRepository = FakeAuthRepository(logoutResult = Result.success(Unit))
        val sessionRepository = FakeSessionRepository()
        val workController =
            NoOpInterviewWorkController(cancelAllFailure = IllegalStateException("정리 실패"))
        val clearInterviewLocalData =
            ClearInterviewLocalDataUseCase(NoOpInterviewLocalRepository(), workController)
        val useCase = LogoutUseCase(authRepository, sessionRepository, clearInterviewLocalData)

        val actual = runBlocking { useCase() }

        assertTrue(actual.isSuccess)
        assertEquals(1, workController.cancelAllCallCount)
        assertEquals(1, sessionRepository.clearCallCount)
    }

    private class NoOpInterviewLocalRepository : InterviewLocalRepository {
        var clearAllCallCount = 0
            private set

        override suspend fun getProgress(): InterviewProgress? = null

        override suspend fun saveProgress(progress: InterviewProgress) = Unit

        override suspend fun updateProgress(
            transform: (InterviewProgress) -> InterviewProgress,
        ): InterviewProgress? = null

        override suspend fun clearProgress() = Unit

        override suspend fun clearProgressIfMatching(sessionId: Long) = false

        override suspend fun getManifest(sessionId: Long): InterviewMediaManifest? = null

        override suspend fun getUploadManifest(uploadTaskId: String): InterviewMediaManifest? = null

        override suspend fun saveManifest(manifest: InterviewMediaManifest) = Unit

        override suspend fun createMediaFile(
            sessionId: Long,
            type: InterviewMediaSegmentType,
            extension: String,
        ): InterviewMediaFileRef = error("사용하지 않음")

        override suspend fun createUploadMediaFile(
            uploadTaskId: String,
            extension: String,
        ): InterviewMediaFileRef = error("사용하지 않음")

        override suspend fun deleteMediaFile(ref: InterviewMediaFileRef) = Unit

        override suspend fun handoffUploadTask(task: InterviewUploadTask) = false

        override suspend fun getUploadTask(uploadTaskId: String): InterviewUploadTask? = null

        override suspend fun saveUploadTask(task: InterviewUploadTask) = Unit

        override suspend fun getUploadTasks(): List<InterviewUploadTask> = emptyList()

        override suspend fun deleteUploadTask(uploadTaskId: String) = Unit

        override suspend fun deleteSession(sessionId: Long) = Unit

        override suspend fun clearAll() {
            clearAllCallCount += 1
        }

        override suspend fun isCleanupPending(): Boolean = false

        override suspend fun setCleanupPending(isPending: Boolean) = Unit
    }

    private class NoOpInterviewWorkController(
        private val cancelAllFailure: Throwable? = null,
    ) : InterviewWorkController {
        var cancelAllCallCount = 0
            private set

        override suspend fun enqueueUpload(
            uploadTaskId: String,
            networkPolicy: InterviewUploadNetworkPolicy,
        ) = Unit

        override suspend fun enqueueRetentionCleanup(deadlineEpochMillis: Long) = Unit

        override suspend fun isUploadRunningOrPending(uploadTaskId: String): Boolean = false

        override suspend fun cancelUpload(uploadTaskId: String) = Unit

        override suspend fun cancelAll() {
            cancelAllCallCount += 1
            cancelAllFailure?.let { throw it }
        }
    }

    private class FakeAuthRepository(
        private val logoutResult: Result<Unit>,
    ) : AuthRepository {
        override suspend fun loginWithKakao(credential: String): AuthSession = error("사용하지 않음")

        override suspend fun logout() {
            logoutResult.getOrThrow()
        }
    }

    private class FakeSessionRepository(
        private val clearResult: Result<Unit> = Result.success(Unit),
    ) : SessionRepository {
        var clearCallCount = 0
            private set

        override suspend fun getAuthSession(): AuthSession? = null

        override suspend fun refreshToken(refreshToken: String): AuthSession = error("사용하지 않음")

        override suspend fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
        ): AuthSession = error("사용하지 않음")

        override suspend fun clearAuthSession() {
            clearCallCount += 1
            clearResult.getOrThrow()
        }
    }
}
