package com.dminus14.app.feature.login.splash

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dminus14.app.core.resources.Res
import com.dminus14.app.core.resources.hiiii_logo
import com.dminus14.app.domain.util.runCatchingCancellable
import com.dminus14.app.feature.home.api.Home
import com.dminus14.app.feature.login.api.Onboarding
import com.dminus14.app.feature.login.api.Term
import com.dminus14.app.feature.login.kakao.KakaoLoginClient
import com.dminus14.app.feature.login.kakao.KakaoLoginException
import com.dminus14.designsystem.component.button.KakaoLoginButton
import com.dminus14.designsystem.component.loading.HilitLoadingIndicator
import com.dminus14.designsystem.theme.HilitTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.coroutines.cancellation.CancellationException

private const val KAKAO_LOGIN_BUTTON_ENTER_DURATION_MS = 300

@Composable
fun SplashScreen(
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    autoLogin: Boolean = true,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val kakaoLoginClient =
        EntryPointAccessors
            .fromApplication(
                context.applicationContext,
                KakaoLoginClientEntryPoint::class.java,
            ).kakaoLoginClient()

    LaunchedEffect(autoLogin) {
        viewModel.onIntent(if (autoLogin) SplashIntent.Load else SplashIntent.ShowLogin)
    }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                SplashEffect.Ready -> {
                    onNavigate(Home)
                }

                SplashEffect.RequireConsent -> {
                    onNavigate(Term)
                }

                SplashEffect.RequireOnboarding -> {
                    onNavigate(Onboarding)
                }

                is SplashEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    SplashContent(
        state = state,
        onKakaoLoginClick = {
            startKakaoLogin(
                activity = activity,
                context = context,
                scope = scope,
                kakaoLoginClient = kakaoLoginClient,
                viewModel = viewModel,
            )
        },
        modifier = modifier,
    )
}

private fun startKakaoLogin(
    activity: Activity?,
    context: Context,
    scope: CoroutineScope,
    kakaoLoginClient: KakaoLoginClient,
    viewModel: SplashViewModel,
) {
    val hostActivity = activity
    if (hostActivity == null) {
        Toast
            .makeText(context, "로그인을 진행할 수 없습니다.", Toast.LENGTH_SHORT)
            .show()
        return
    }

    viewModel.onIntent(SplashIntent.ClickKakaoLogin)
    scope.launch {
        try {
            runCatchingCancellable { kakaoLoginClient.login(hostActivity) }
                .onSuccess { credential ->
                    viewModel.onIntent(SplashIntent.KakaoLoginSucceeded(credential))
                }.onFailure { error ->
                    viewModel.onIntent(SplashIntent.KakaoLoginFailed(error))
                }
        } catch (cancellation: CancellationException) {
            viewModel.onIntent(SplashIntent.KakaoLoginFailed(KakaoLoginException.Cancelled))
            throw cancellation
        }
    }
}

@Composable
private fun SplashContent(
    state: SplashState,
    onKakaoLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(HilitTheme.colors.hilitWhite),
        contentAlignment = Alignment.Center,
    ) {
        SplashLogo()
        SplashKakaoLoginSection(
            visible = state.showKakaoLoginButton,
            onKakaoLoginClick = onKakaoLoginClick,
        )
        if (state.isLoading) {
            SplashLoadingOverlay()
        }
    }
}

@Composable
private fun SplashLogo() {
    Image(
        painter = painterResource(Res.drawable.hiiii_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .offset(y = (-50).dp)
                .size(
                    width = 171.dp,
                    height = 72.dp,
                ),
    )
}

@Composable
private fun SplashKakaoLoginSection(
    visible: Boolean,
    onKakaoLoginClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter =
                slideInVertically(
                    animationSpec =
                        tween(
                            durationMillis = KAKAO_LOGIN_BUTTON_ENTER_DURATION_MS,
                            easing = EaseOut,
                        ),
                    initialOffsetY = { it },
                ) +
                    fadeIn(
                        animationSpec =
                            tween(
                                durationMillis = KAKAO_LOGIN_BUTTON_ENTER_DURATION_MS,
                                easing = EaseOut,
                            ),
                    ),
        ) {
            KakaoLoginButton(
                onClick = onKakaoLoginClick,
            )
        }
    }
}

@Composable
private fun SplashLoadingOverlay() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(HilitTheme.colors.gray200.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        contentAlignment = Alignment.Center,
    ) {
        HilitLoadingIndicator(size = 24.dp)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface KakaoLoginClientEntryPoint {
    fun kakaoLoginClient(): KakaoLoginClient
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
    widthDp = 375,
    heightDp = 812,
)
@Composable
private fun SplashContentPreview() {
    HilitTheme {
        SplashContent(
            state = SplashState(showKakaoLoginButton = true),
            onKakaoLoginClick = {},
        )
    }
}
