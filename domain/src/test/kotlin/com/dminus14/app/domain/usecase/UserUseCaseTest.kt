package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.exception.ValidationException
import com.dminus14.app.domain.model.AuthSession
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.model.UserProfile
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.repository.InterviewWorkController
import com.dminus14.app.domain.repository.SessionRepository
import com.dminus14.app.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserUseCaseTest {
    private fun newClearInterviewLocalData(
        workController: NoOpInterviewWorkController = NoOpInterviewWorkController(),
    ) = ClearInterviewLocalDataUseCase(NoOpInterviewLocalRepository(), workController)

    @Test
    fun `프로필 조회는 저장소 결과를 그대로 반환한다`() =
        runTest {
            val repository = FakeUserRepository()
            val useCase = CheckUserProfileUseCase(repository)

            val result = useCase()

            assertSame(repository.profile, result.getOrThrow())
            assertEquals(1, repository.profileCallCount)
        }

    @Test
    fun `프로필 수정 입력의 공백을 제거해 저장소에 전달한다`() =
        runTest {
            val repository = FakeUserRepository()
            val useCase = UpdateUserProfileUseCase(repository)

            val result =
                useCase(
                    UserProfileUpdate(
                        name = "  홍길동  ",
                        jobRole = "  BACKEND  ",
                        careerYears = 3,
                    ),
                )

            assertTrue(result.isSuccess)
            assertEquals(
                UserProfileUpdate("홍길동", "BACKEND", 3),
                repository.update,
            )
        }

    @Test
    fun `프로필 수정 입력이 유효하지 않으면 저장소를 호출하지 않는다`() =
        runTest {
            val invalidUpdates =
                listOf(
                    UserProfileUpdate("   ", "BACKEND", 3),
                    UserProfileUpdate("가".repeat(6), "BACKEND", 3),
                    UserProfileUpdate("홍길동", "   ", 3),
                    UserProfileUpdate("홍길동", "BACKEND", -1),
                    UserProfileUpdate("홍길동", "BACKEND", 11),
                )

            invalidUpdates.forEach { update ->
                val repository = FakeUserRepository()
                val result = UpdateUserProfileUseCase(repository)(update)

                assertTrue(result.exceptionOrNull() is ValidationException)
                assertEquals(0, repository.updateCallCount)
            }
        }

    @Test
    fun `회원 탈퇴 성공 후 인증 세션을 삭제한다`() =
        runTest {
            val calls = mutableListOf<String>()
            val userRepository = FakeUserRepository(calls = calls)
            val sessionRepository = FakeSessionRepository(calls = calls)
            val useCase =
                WithdrawUserUseCase(userRepository, sessionRepository, newClearInterviewLocalData())

            val result = useCase()

            assertTrue(result.isSuccess)
            assertEquals(listOf("withdraw", "clearSession"), calls)
        }

    @Test
    fun `회원 탈퇴가 실패하면 인증 세션을 삭제하지 않는다`() =
        runTest {
            val calls = mutableListOf<String>()
            val failure = IllegalStateException("synthetic withdrawal failure")
            val userRepository = FakeUserRepository(withdrawalFailure = failure, calls = calls)
            val sessionRepository = FakeSessionRepository(calls = calls)
            val useCase =
                WithdrawUserUseCase(userRepository, sessionRepository, newClearInterviewLocalData())

            val result = useCase()

            assertSame(failure, result.exceptionOrNull())
            assertEquals(listOf("withdraw"), calls)
        }

    @Test
    fun `탈퇴 후 인증 세션 삭제 실패를 숨기지 않는다`() =
        runTest {
            val calls = mutableListOf<String>()
            val failure = IllegalStateException("synthetic session failure")
            val userRepository = FakeUserRepository(calls = calls)
            val sessionRepository = FakeSessionRepository(clearFailure = failure, calls = calls)
            val useCase =
                WithdrawUserUseCase(userRepository, sessionRepository, newClearInterviewLocalData())

            val result = useCase()

            assertSame(failure, result.exceptionOrNull())
            assertEquals(listOf("withdraw", "clearSession"), calls)
        }

    @Test
    fun `면접 로컬 정리가 실패해도 인증 세션은 삭제되고 탈퇴는 성공한다`() =
        runTest {
            val calls = mutableListOf<String>()
            val userRepository = FakeUserRepository(calls = calls)
            val sessionRepository = FakeSessionRepository(calls = calls)
            val workController =
                NoOpInterviewWorkController(cancelAllFailure = IllegalStateException("정리 실패"))
            val useCase =
                WithdrawUserUseCase(
                    userRepository,
                    sessionRepository,
                    newClearInterviewLocalData(workController),
                )

            val result = useCase()

            assertTrue(result.isSuccess)
            assertEquals(1, workController.cancelAllCallCount)
            assertEquals(listOf("withdraw", "clearSession"), calls)
        }

    @Test
    fun `회원 탈퇴 중 코루틴 취소를 Result 실패로 감싸지 않는다`() =
        runTest {
            val cancellation = CancellationException("synthetic cancellation")
            val useCase =
                WithdrawUserUseCase(
                    FakeUserRepository(withdrawalFailure = cancellation),
                    FakeSessionRepository(),
                    newClearInterviewLocalData(),
                )

            val actual = captureFailure { useCase() }

            assertSame(cancellation, actual)
        }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("예외가 발생해야 합니다.")
        } catch (error: Throwable) {
            error
        }

    private class FakeUserRepository(
        val profile: UserProfile = UserProfile("홍길동", null, null, null, null, null, null),
        private val withdrawalFailure: Throwable? = null,
        private val calls: MutableList<String> = mutableListOf(),
    ) : UserRepository {
        var profileCallCount = 0
            private set
        var updateCallCount = 0
            private set
        var update: UserProfileUpdate? = null
            private set

        override suspend fun getUserProfile(): UserProfile {
            profileCallCount += 1
            return profile
        }

        override suspend fun updateUserProfile(update: UserProfileUpdate) {
            updateCallCount += 1
            this.update = update
        }

        override suspend fun withdraw() {
            calls += "withdraw"
            withdrawalFailure?.let { throw it }
        }

        override suspend fun getJobList() = error("사용하지 않음")
    }

    private class FakeSessionRepository(
        private val clearFailure: Throwable? = null,
        private val calls: MutableList<String> = mutableListOf(),
    ) : SessionRepository {
        override suspend fun getAuthSession(): AuthSession? = error("사용하지 않는 함수입니다.")

        override suspend fun refreshToken(refreshToken: String): AuthSession =
            error("사용하지 않는 함수입니다.")

        override suspend fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
        ): AuthSession = error("사용하지 않는 함수입니다.")

        override suspend fun clearAuthSession() {
            calls += "clearSession"
            clearFailure?.let { throw it }
        }
    }

    private class NoOpInterviewLocalRepository : InterviewLocalRepository {
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

        override suspend fun clearAll() = Unit

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
}
