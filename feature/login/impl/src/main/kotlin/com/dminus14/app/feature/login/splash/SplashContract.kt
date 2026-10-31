package com.dminus14.app.feature.login.splash

import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState

sealed interface SplashIntent : MviIntent {
    /** 화면 진입 시 저장된 세션·프로필 존재 여부를 확인한다. */
    data object Load : SplashIntent

    /** 저장된 세션을 유지하고 자동 이동 없이 로그인 버튼을 표시한다. */
    data object ShowLogin : SplashIntent

    data object ClickKakaoLogin : SplashIntent

    data class KakaoLoginSucceeded(
        val credential: String,
    ) : SplashIntent

    data class KakaoLoginFailed(
        val error: Throwable,
    ) : SplashIntent
}

data class SplashState(
    val showKakaoLoginButton: Boolean = false,
    val isLoading: Boolean = false,
) : MviState

sealed interface SplashEffect : MviEffect {
    /** 동의·온보딩이 완료되어 홈으로 이동한다. */
    data object Ready : SplashEffect

    /** 약관(최초/재동의)이 필요하다. */
    data object RequireConsent : SplashEffect

    /** 온보딩(이름 등록 등)이 필요하다. */
    data object RequireOnboarding : SplashEffect

    /** 로그인 실패 등 1회성 Toast를 표시한다. */
    data class ShowToast(
        val message: String,
    ) : SplashEffect
}
