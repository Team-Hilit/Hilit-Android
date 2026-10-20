package com.dminus14.app.feature.login.splash

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.CustomException
import com.dminus14.app.domain.exception.InvalidCredentialException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.SocialLoginFailedException
import com.dminus14.app.domain.exception.UserNotFoundException
import com.dminus14.app.domain.model.ConsentPendingStatus
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.GetAuthSessionUseCase
import com.dminus14.app.domain.usecase.GetPendingConsentListUseCase
import com.dminus14.app.domain.usecase.LoginWithKakaoUseCase
import com.dminus14.app.feature.login.kakao.KakaoLoginException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SplashViewModel
    @Inject
    constructor(
        private val getAuthSessionUseCase: GetAuthSessionUseCase,
        private val checkUserProfileUseCase: CheckUserProfileUseCase,
        private val loginWithKakaoUseCase: LoginWithKakaoUseCase,
        private val getPendingConsentListUseCase: GetPendingConsentListUseCase,
    ) : MviViewModel<SplashIntent, SplashState, SplashEffect>(SplashState()) {
        private val mvi = createContext(SplashReducer)

        override fun onIntent(intent: SplashIntent) {
            when (intent) {
                SplashIntent.Load -> {
                    load()
                }

                SplashIntent.ClickKakaoLogin -> {
                    mvi.dispatch(SplashMutation.LoginStarted)
                }

                is SplashIntent.KakaoLoginSucceeded -> {
                    loginWithKakao(intent.credential)
                }

                is SplashIntent.KakaoLoginFailed -> {
                    when (intent.error) {
                        is KakaoLoginException.Cancelled -> {
                            mvi.dispatch(SplashMutation.LoginFinished)
                        }

                        else -> {
                            mvi.dispatch(SplashMutation.LoginFinished)
                            mvi.sendEffect(
                                SplashEffect.ShowToast(
                                    intent.error.message ?: DEFAULT_LOGIN_ERROR_MESSAGE,
                                ),
                            )
                        }
                    }
                }
            }
        }

        private fun load() {
            mvi.launch {
                getAuthSessionUseCase()
                    .onSuccess { session ->
                        if (session != null) {
                            checkPendingConsent()
                        } else {
                            mvi.dispatch(SplashMutation.LoginRequired)
                        }
                    }.onFailure {
                        mvi.dispatch(SplashMutation.LoginRequired)
                    }
            }
        }

        private fun loginWithKakao(credential: String) {
            mvi.launch {
                mvi.dispatch(SplashMutation.LoginStarted)

                loginWithKakaoUseCase(credential)
                    .onSuccess {
                        checkPendingConsent()
                    }.onFailure { throwable ->
                        when (throwable) {
                            is SocialLoginFailedException,
                            is InvalidCredentialException,
                            -> {
                                mvi.dispatch(SplashMutation.LoginFinished)
                                mvi.sendEffect(SplashEffect.ShowToast(throwable.message))
                            }

                            else -> {
                                handleBootstrapFailure(throwable)
                            }
                        }
                    }
            }
        }

        private suspend fun checkPendingConsent() {
            getPendingConsentListUseCase()
                .onSuccess { pending ->
                    when (pending.status) {
                        ConsentPendingStatus.NOT_SUBMITTED,
                        ConsentPendingStatus.STALE,
                        ConsentPendingStatus.UNKNOWN,
                        -> {
                            mvi.dispatch(SplashMutation.BootstrapCompleted)
                            mvi.sendEffect(SplashEffect.RequireConsent)
                        }

                        ConsentPendingStatus.UP_TO_DATE -> {
                            checkUserProfile()
                        }
                    }
                }.onFailure { throwable ->
                    handleBootstrapFailure(throwable)
                }
        }

        private suspend fun checkUserProfile() {
            checkUserProfileUseCase()
                .onSuccess { profile ->
                    mvi.dispatch(SplashMutation.BootstrapCompleted)
                    if (profile.name.isNullOrBlank()) {
                        // 이름이 비어 있으면 필수 프로필 미완성으로 보고 온보딩으로 보낸다.
                        mvi.sendEffect(SplashEffect.RequireOnboarding)
                    } else {
                        mvi.sendEffect(SplashEffect.Ready)
                    }
                }.onFailure { error ->
                    when (error) {
                        is UserNotFoundException -> {
                            mvi.dispatch(SplashMutation.BootstrapCompleted)
                            mvi.sendEffect(SplashEffect.RequireOnboarding)
                        }

                        else -> {
                            handleBootstrapFailure(error)
                        }
                    }
                }
        }

        // 아래 에러 처리 사항은 임시입니다. 공통 처리 기획자 문의 모든 ViewModel 일괄 수정 예정
        private suspend fun handleBootstrapFailure(error: Throwable) {
            mvi.dispatch(SplashMutation.LoginFinished)
            when {
                isSessionExpired(error) -> {
                    // 세션 만료(재발급 불가)는 오류가 아니라 "미로그인" 상태이므로
                    // 토스트 없이 로그인 버튼만 노출한다.
                    mvi.dispatch(SplashMutation.SessionExpired)
                }

                error is NetworkUnavailableException -> {
                    GlobalErrorHandler.emit(GlobalAppEvent.ShowNetworkErrorAndExit)
                }

                error is ServerException -> {
                    GlobalErrorHandler.emit(GlobalAppEvent.ShowServerErrorAndExit)
                }

                else -> {
                    GlobalErrorHandler.emit(GlobalAppEvent.ShowUnknownError)
                }
            }
        }

        /**
         * 세션 만료로 재로그인이 필요한 상태인지 판별한다.
         *
         * AccessToken 만료(401 [TOKEN_EXPIRED])는 `TokenAuthenticator`가 재발급을 시도하고, RefreshToken까지
         * 만료되면 세션을 정리한 뒤 원래의 401을 그대로 전파한다. 이때 재발급 실패의 `SessionException`은
         * Authenticator 내부에서 소비되므로 부트스트랩 호출부에는 원래 401 코드(TOKEN_EXPIRED /
         * INVALID_TOKEN / LOGIN_EXPIRED)를 가진 [CustomException]으로 도달한다.
         */
        private fun isSessionExpired(error: Throwable): Boolean =
            error is CustomException && error.errCode in SESSION_EXPIRED_ERROR_CODES

        private companion object {
            const val DEFAULT_LOGIN_ERROR_MESSAGE = "로그인에 실패했습니다."

            /** 401 인증 만료 계열 에러 코드. 데이터 계층 `ApiErrorCode`와 값을 맞춘다. */
            val SESSION_EXPIRED_ERROR_CODES =
                setOf("TOKEN_EXPIRED", "INVALID_TOKEN", "LOGIN_EXPIRED")
        }
    }

private sealed interface SplashMutation {
    data object LoginStarted : SplashMutation

    data object LoginFinished : SplashMutation

    data object LoginRequired : SplashMutation

    data object BootstrapCompleted : SplashMutation

    data object SessionExpired : SplashMutation
}

/** 사용자 입력과 비동기 결과를 화면 상태로만 계산한다. */
private object SplashReducer : MviReducer<SplashState, SplashMutation> {
    override fun reduce(
        state: SplashState,
        mutation: SplashMutation,
    ): SplashState =
        with(state) {
            when (mutation) {
                SplashMutation.LoginStarted -> {
                    copy(isLoading = true)
                }

                SplashMutation.LoginFinished -> {
                    copy(isLoading = false)
                }

                SplashMutation.LoginRequired -> {
                    copy(isLoading = false, showKakaoLoginButton = true)
                }

                SplashMutation.BootstrapCompleted -> {
                    copy(
                        isLoading = false,
                        showKakaoLoginButton = false,
                    )
                }

                SplashMutation.SessionExpired -> {
                    copy(showKakaoLoginButton = true)
                }
            }
        }
}
