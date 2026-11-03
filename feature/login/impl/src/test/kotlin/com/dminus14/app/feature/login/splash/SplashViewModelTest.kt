package com.dminus14.app.feature.login.splash

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.domain.exception.InvalidCredentialException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.SocialLoginFailedException
import com.dminus14.app.domain.exception.UnknownException
import com.dminus14.app.domain.exception.UserNotFoundException
import com.dminus14.app.domain.model.AuthSession
import com.dminus14.app.domain.model.ConsentDocument
import com.dminus14.app.domain.model.ConsentPendingStatus
import com.dminus14.app.domain.model.ConsentSubmission
import com.dminus14.app.domain.model.PendingConsentList
import com.dminus14.app.domain.model.UserProfile
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.repository.AuthRepository
import com.dminus14.app.domain.repository.ConsentRepository
import com.dminus14.app.domain.repository.SessionRepository
import com.dminus14.app.domain.repository.UserRepository
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.GetAuthSessionUseCase
import com.dminus14.app.domain.usecase.GetPendingConsentListUseCase
import com.dminus14.app.domain.usecase.LoginWithKakaoUseCase
import com.dminus14.app.feature.login.kakao.KakaoLoginException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {
    @Test
    fun `로그인 화면을 표시하면 자동 조회와 이동 없이 로그인 버튼을 노출한다`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val sessionRepository = FakeSessionRepository(sampleSession)
                val userRepository = FakeUserRepository(Result.success(sampleUserProfile))
                val consentRepository = FakeConsentRepository(Result.success(notSubmittedPending))
                val viewModel =
                    SplashViewModel(
                        GetAuthSessionUseCase(sessionRepository),
                        CheckUserProfileUseCase(userRepository),
                        LoginWithKakaoUseCase(FakeAuthRepository(Result.success(sampleSession))),
                        GetPendingConsentListUseCase(consentRepository),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.effect.collect(receivedEffects::add)
                }

                viewModel.onIntent(SplashIntent.ShowLogin)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.showKakaoLoginButton)
                assertFalse(viewModel.state.value.isLoading)
                assertEquals(0, sessionRepository.getCallCount)
                assertEquals(0, userRepository.getCallCount)
                assertEquals(0, consentRepository.getCallCount)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
                assertEquals(sampleSession, sessionRepository.getAuthSession())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `로그인 화면을 표시하면 이전 로그인 로딩을 해제한다`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(notSubmittedPending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                viewModel.onIntent(SplashIntent.ClickKakaoLogin)
                assertTrue(viewModel.state.value.isLoading)

                viewModel.onIntent(SplashIntent.ShowLogin)

                assertFalse(viewModel.state.value.isLoading)
                assertTrue(viewModel.state.value.showKakaoLoginButton)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `로그인 화면에서 카카오 로그인을 다시 완료하면 필요한 약관 동의로 이동한다`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(notSubmittedPending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                viewModel.onIntent(SplashIntent.ShowLogin)
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.ClickKakaoLogin)
                viewModel.onIntent(SplashIntent.KakaoLoginSucceeded("synthetic-credential"))

                assertEquals(SplashEffect.RequireConsent, effect.await())
                assertFalse(viewModel.state.value.isLoading)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션과 동의 최신·완성된 프로필이 있으면 Ready Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(SplashEffect.Ready, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션과 동의 최신이지만 프로필이 없으면 RequireOnboarding Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(upToDatePending),
                        profileResult =
                            Result.failure(UserNotFoundException(errCode = "USER_NOT_FOUND")),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(SplashEffect.RequireOnboarding, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션이 없으면 카카오 로그인 버튼을 노출한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }

                viewModel.onIntent(SplashIntent.Load)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.showKakaoLoginButton)
                assertFalse(viewModel.state.value.isLoading)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션이 없으면 이전 카카오 로그인 로딩 상태를 해제한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                    )

                viewModel.onIntent(SplashIntent.ClickKakaoLogin)
                assertTrue(viewModel.state.value.isLoading)

                viewModel.onIntent(SplashIntent.Load)
                advanceUntilIdle()

                assertFalse(viewModel.state.value.isLoading)
                assertTrue(viewModel.state.value.showKakaoLoginButton)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션은 있지만 최초 동의가 필요하면 RequireConsent Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(notSubmittedPending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(SplashEffect.RequireConsent, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션은 있지만 재동의가 필요하면 RequireConsent Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(stalePending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(SplashEffect.RequireConsent, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 동의 최신인데 프로필이 없으면 RequireOnboarding Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(upToDatePending),
                        profileResult =
                            Result.failure(UserNotFoundException(errCode = "USER_NOT_FOUND")),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(SplashEffect.RequireOnboarding, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 프로필 조회 성공했지만 이름이 없으면 RequireOnboarding Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile.copy(name = null)),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(SplashEffect.RequireOnboarding, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    // "이메일이 없으면 RequireOnboarding" 테스트는 필수 프로필 판정 정책이
    // name·email → name 으로 변경되면서 의미가 사라졌다. 동일한 커버리지는
    // 바로 위 "이름이 없으면 RequireOnboarding" 테스트가 대체한다.

    @Test
    fun `Load 시 세션은 있지만 동의 조회 네트워크 오류면 ShowNetworkErrorAndExit를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult =
                            Result.failure(
                                NetworkUnavailableException(errCode = "NETWORK_UNAVAILABLE"),
                            ),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }
                val globalEvent =
                    async(start = CoroutineStart.UNDISPATCHED) { GlobalErrorHandler.events.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(GlobalAppEvent.ShowNetworkErrorAndExit, globalEvent.await().event)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션은 있지만 동의 조회 서버 오류면 ShowServerErrorAndExit를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.failure(ServerException(errCode = "SERVER_ERROR")),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }
                val globalEvent =
                    async(start = CoroutineStart.UNDISPATCHED) { GlobalErrorHandler.events.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(GlobalAppEvent.ShowServerErrorAndExit, globalEvent.await().event)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션은 있지만 동의 조회 알 수 없는 오류면 ShowUnknownError를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.failure(UnknownException(errCode = "UNKNOWN")),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }
                val globalEvent =
                    async(start = CoroutineStart.UNDISPATCHED) { GlobalErrorHandler.events.first() }

                viewModel.onIntent(SplashIntent.Load)

                assertEquals(GlobalAppEvent.ShowUnknownError, globalEvent.await().event)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 세션은 있지만 동의 조회가 세션 만료면 토스트 없이 카카오 로그인 버튼을 노출한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult =
                            Result.failure(UnknownException(errCode = "TOKEN_EXPIRED")),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }

                viewModel.onIntent(SplashIntent.Load)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.showKakaoLoginButton)
                assertFalse(viewModel.state.value.isLoading)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `Load 시 프로필 조회가 세션 만료면 토스트 없이 카카오 로그인 버튼을 노출한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = sampleSession,
                        pendingResult = Result.success(upToDatePending),
                        profileResult =
                            Result.failure(UnknownException(errCode = "LOGIN_EXPIRED")),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }

                viewModel.onIntent(SplashIntent.Load)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.showKakaoLoginButton)
                assertFalse(viewModel.state.value.isLoading)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 로그인 성공 후 동의 최신·완성된 프로필이면 Ready Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                        loginResult = Result.success(sampleSession),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.KakaoLoginSucceeded("credential"))

                assertEquals(SplashEffect.Ready, effect.await())
                assertFalse(viewModel.state.value.isLoading)
                assertFalse(viewModel.state.value.showKakaoLoginButton)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 로그인 성공 후 동의 최신이지만 프로필이 없으면 RequireOnboarding Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult =
                            Result.failure(UserNotFoundException(errCode = "USER_NOT_FOUND")),
                        loginResult = Result.success(sampleSession),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.KakaoLoginSucceeded("credential"))

                assertEquals(SplashEffect.RequireOnboarding, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 로그인 성공 후 최초 동의가 필요하면 RequireConsent Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(notSubmittedPending),
                        profileResult = Result.success(sampleUserProfile),
                        loginResult = Result.success(sampleSession),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.KakaoLoginSucceeded("credential"))

                assertEquals(SplashEffect.RequireConsent, effect.await())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 로그인 API가 SOCIAL_LOGIN_FAILED면 ShowToast Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                        loginResult =
                            Result.failure(
                                SocialLoginFailedException(
                                    errCode = "SOCIAL_LOGIN_FAILED",
                                    message = "소셜 로그인에 실패했습니다.",
                                ),
                            ),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.KakaoLoginSucceeded("credential"))

                assertEquals(
                    SplashEffect.ShowToast("소셜 로그인에 실패했습니다."),
                    effect.await(),
                )
                assertFalse(viewModel.state.value.isLoading)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 로그인 API가 INVALID_CREDENTIAL이면 ShowToast Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                        loginResult =
                            Result.failure(
                                InvalidCredentialException(
                                    errCode = "INVALID_CREDENTIAL",
                                    message = "유효하지 않은 인증 정보입니다.",
                                ),
                            ),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.KakaoLoginSucceeded("credential"))

                assertEquals(
                    SplashEffect.ShowToast("유효하지 않은 인증 정보입니다."),
                    effect.await(),
                )
                assertFalse(viewModel.state.value.isLoading)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 로그인 API 네트워크 오류면 ShowNetworkErrorAndExit를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                        loginResult =
                            Result.failure(
                                NetworkUnavailableException(errCode = "NETWORK_UNAVAILABLE"),
                            ),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }
                val globalEvent =
                    async(start = CoroutineStart.UNDISPATCHED) { GlobalErrorHandler.events.first() }

                viewModel.onIntent(SplashIntent.KakaoLoginSucceeded("credential"))

                assertEquals(GlobalAppEvent.ShowNetworkErrorAndExit, globalEvent.await().event)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 로그인을 취소하면 로딩만 해제한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val receivedEffects = mutableListOf<SplashEffect>()
                backgroundScope.launch { viewModel.effect.collect(receivedEffects::add) }

                viewModel.onIntent(SplashIntent.ClickKakaoLogin)
                assertTrue(viewModel.state.value.isLoading)

                viewModel.onIntent(SplashIntent.KakaoLoginFailed(KakaoLoginException.Cancelled))
                advanceUntilIdle()

                assertFalse(viewModel.state.value.isLoading)
                assertEquals(emptyList<SplashEffect>(), receivedEffects)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `카카오 SDK 로그인 실패면 ShowToast Effect를 발행한다`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val viewModel =
                    createViewModel(
                        session = null,
                        pendingResult = Result.success(upToDatePending),
                        profileResult = Result.success(sampleUserProfile),
                    )
                val effect = async { viewModel.effect.first() }

                viewModel.onIntent(SplashIntent.ClickKakaoLogin)
                viewModel.onIntent(SplashIntent.KakaoLoginFailed(KakaoLoginException.AccessDenied))

                assertEquals(
                    SplashEffect.ShowToast("로그인 동의가 필요합니다."),
                    effect.await(),
                )
                assertFalse(viewModel.state.value.isLoading)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun createViewModel(
        session: AuthSession?,
        pendingResult: Result<PendingConsentList>,
        profileResult: Result<UserProfile>,
        loginResult: Result<AuthSession> = Result.success(sampleSession),
    ): SplashViewModel =
        SplashViewModel(
            GetAuthSessionUseCase(FakeSessionRepository(session)),
            CheckUserProfileUseCase(FakeUserRepository(profileResult)),
            LoginWithKakaoUseCase(FakeAuthRepository(loginResult)),
            GetPendingConsentListUseCase(FakeConsentRepository(pendingResult)),
        )

    private companion object {
        val sampleSession =
            AuthSession(
                accessToken = "access",
                refreshToken = "refresh",
            )

        val sampleUserProfile =
            UserProfile(
                name = "홍길동",
                email = "test@example.com",
                provider = "KAKAO",
                jobRole = "BACKEND",
                jobRoleLabel = "백엔드",
                careerYears = 1,
                remainingTicketCount = 3,
            )

        val upToDatePending =
            PendingConsentList(
                status = ConsentPendingStatus.UP_TO_DATE,
                items = emptyList(),
            )

        val notSubmittedPending =
            PendingConsentList(
                status = ConsentPendingStatus.NOT_SUBMITTED,
                items = emptyList(),
            )

        val stalePending =
            PendingConsentList(
                status = ConsentPendingStatus.STALE,
                items = emptyList(),
            )
    }

    private class FakeSessionRepository(
        private val session: AuthSession?,
    ) : SessionRepository {
        var getCallCount: Int = 0
            private set

        override suspend fun getAuthSession(): AuthSession? {
            getCallCount += 1
            return session
        }

        override suspend fun refreshToken(refreshToken: String): AuthSession =
            error("Not used in SplashViewModelTest")

        override suspend fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
        ): AuthSession = error("Not used in SplashViewModelTest")

        override suspend fun clearAuthSession() = error("Not used in SplashViewModelTest")
    }

    private class FakeUserRepository(
        private val result: Result<UserProfile>,
    ) : UserRepository {
        var getCallCount: Int = 0
            private set

        override suspend fun getUserProfile(): UserProfile {
            getCallCount += 1
            return result.getOrThrow()
        }

        override suspend fun updateUserProfile(update: UserProfileUpdate) =
            error("Not used in SplashViewModelTest")

        override suspend fun withdraw() = error("Not used in SplashViewModelTest")

        // 직군 목록 조회는 이 테스트 대상이 아니라 컴파일 통과용 스텁만 둔다.
        override suspend fun getJobList() = error("Not used in SplashViewModelTest")
    }

    private class FakeAuthRepository(
        private val loginResult: Result<AuthSession>,
    ) : AuthRepository {
        override suspend fun loginWithKakao(credential: String): AuthSession =
            loginResult.getOrThrow()

        override suspend fun logout() = error("Not used in SplashViewModelTest")
    }

    private class FakeConsentRepository(
        private val pendingResult: Result<PendingConsentList>,
    ) : ConsentRepository {
        var getCallCount: Int = 0
            private set

        override suspend fun getPendingConsentList(): PendingConsentList {
            getCallCount += 1
            return pendingResult.getOrThrow()
        }

        override suspend fun getConsentDocument(
            rawCode: String,
            version: Int,
        ): ConsentDocument = error("Not used in SplashViewModelTest")

        override suspend fun submitConsent(submission: ConsentSubmission) =
            error("Not used in SplashViewModelTest")
    }
}
