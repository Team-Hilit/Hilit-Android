package com.dminus14.app.feature.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withStarted
import com.dminus14.app.core.resources.Res
import com.dminus14.app.core.resources.home_background
import com.dminus14.app.feature.home.component.HomeReportSheet
import com.dminus14.app.feature.home.component.HomeReportSheetCallbacks
import com.dminus14.app.feature.home.component.HomeReportSheetContent
import com.dminus14.app.feature.home.component.HomeSheetAnchor
import com.dminus14.app.feature.interviewreport.api.InterviewReport
import com.dminus14.app.feature.login.api.Onboarding
import com.dminus14.app.feature.login.api.Splash
import com.dminus14.app.feature.mypage.MyPage
import com.dminus14.app.feature.onboarding.api.OnBoardingInterview
import com.dminus14.designsystem.component.text.GradientText
import com.dminus14.designsystem.component.topbar.HilitLogoTopBar
import com.dminus14.designsystem.theme.HilitTheme
import org.jetbrains.compose.resources.painterResource

private val HeroHorizontalPadding = 20.dp
private val GreetingTopSpacing = 54.dp
private val GreetingToHintSpacing = 60.dp
private val HintHorizontalPadding = 10.dp
private val HintVerticalPadding = 16.dp
private val TopBarShadowHeight = 16.dp
private val TopBarExpandedShadowColor = Color(0x99DDDFE5)
private val HomeTopBarHeight = 52.dp
private val FallbackExpandedTop = HomeTopBarHeight
private const val EXIT_CONFIRM_WINDOW_MILLIS = 3_000L

@Suppress("LongParameterList", "LongMethod")
@Composable
fun HomeScreen(
    onNavigate: (Any) -> Unit,
    onReplaceAll: (Any) -> Unit,
    onResumeConfirmed: (Long, Long) -> Unit,
    onSessionInvalid: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 세션 오버레이 닫을 때 리포트 시트를 중간(Peek)으로 되돌리라는 신호. 값이 바뀌면 시트가 리셋된다.
    var peekResetSignal by remember { mutableIntStateOf(0) }

    // 홈은 백스택 최하단이라 뒤로가기 한 번에 바로 종료된다. 두 번째 탭까지 유예를 둔다.
    DoubleBackToExitHandler()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.withStarted { viewModel.onIntent(HomeIntent.Load) }
    }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                HomeEffect.GoToMyPageRequested -> {
                    onNavigate(MyPage)
                }

                // 온보딩·스플래시로 이동할 때는 홈으로 되돌아오지 못하도록 스택을 비운다.
                HomeEffect.UserNameNotRegistered -> {
                    onReplaceAll(Onboarding)
                }

                HomeEffect.UserNotFound -> {
                    onReplaceAll(Splash)
                }

                HomeEffect.GoToOnboardingInterviewRequested -> {
                    onNavigate(OnBoardingInterview)
                }

                is HomeEffect.ResumeConfirmed -> {
                    onResumeConfirmed(effect.sessionId, effect.questionId)
                }

                HomeEffect.SessionInvalid -> {
                    onSessionInvalid()
                }

                HomeEffect.ReportSheetResetRequested -> {
                    peekResetSignal++
                }

                is HomeEffect.GoToReportRequested -> {
                    effect.reportId.toLongOrNull()?.let { sessionId ->
                        onNavigate(InterviewReport(sessionId = sessionId))
                    }
                }
            }
        }
    }

    HomeContent(
        state = state,
        callbacks =
            HomeContentCallbacks(
                onReportExpandClick = { viewModel.onIntent(HomeIntent.ClickReportExpand(it)) },
                onReportActionClick = { viewModel.onIntent(HomeIntent.ClickReportOpen(it)) },
                onReportSheetCollapsed = { viewModel.onIntent(HomeIntent.ReportSheetCollapsed) },
                onSessionStartClick = { viewModel.onIntent(HomeIntent.ClickSessionStart) },
                onSessionOverlayDismiss = {
                    viewModel.onIntent(
                        HomeIntent.ClickSessionOverlayDismiss,
                    )
                },
                onSessionResumeClick = { viewModel.onIntent(HomeIntent.ClickSessionResume) },
                onMyPageClick = { viewModel.onIntent(HomeIntent.ClickMyPage) },
                peekResetSignal = peekResetSignal,
            ),
        modifier = modifier,
    )
}

/** 뒤로가기 한 번은 안내 Toast만 띄우고, [EXIT_CONFIRM_WINDOW_MILLIS] 안에 한 번 더 누르면 앱을 종료한다. */
@Composable
private fun DoubleBackToExitHandler() {
    val activity = LocalActivity.current
    var lastBackPressedAtMillis by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPressedAtMillis <= EXIT_CONFIRM_WINDOW_MILLIS) {
            activity?.finishAffinity()
        } else {
            lastBackPressedAtMillis = now
            Toast
                .makeText(activity, "뒤로가기를 한 번 더 누르시면 종료됩니다.", Toast.LENGTH_SHORT)
                .show()
        }
    }
}

@Composable
@Suppress("LongMethod")
internal fun HomeContent(
    state: HomeState,
    callbacks: HomeContentCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var topBarBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    var sheetAnchor by remember { mutableStateOf(HomeSheetAnchor.Peek) }
    // Peek(0f)→Expanded(1f) 진행률. 앵커가 settle 됐을 때만 바뀌는 sheetAnchor 와 달리 드래그·
    // snap 애니메이션 매 프레임 갱신돼, 탑바 그림자가 시트 높이에 실시간으로 붙어 움직인다(#199).
    var shadowAlpha by remember { mutableFloatStateOf(0f) }
    val expandedTopPx =
        if (topBarBottomPx.isNaN()) {
            with(density) { FallbackExpandedTop.toPx() }
        } else {
            topBarBottomPx
        }

    Box(modifier = modifier.fillMaxSize()) {
        HomeBackgroundWithHero(userName = state.userName)

        HomeReportSheet(
            content =
                HomeReportSheetContent(
                    reports = state.reports,
                    expandedReportIds = state.expandedReportIds,
                    expandedTopPx = expandedTopPx,
                    peekResetSignal = callbacks.peekResetSignal,
                ),
            callbacks =
                HomeReportSheetCallbacks(
                    onReportExpandClick = callbacks.onReportExpandClick,
                    onReportActionClick = callbacks.onReportActionClick,
                    onSheetAnchorChange = { anchor ->
                        sheetAnchor = anchor
                        if (anchor == HomeSheetAnchor.Collapsed) callbacks.onReportSheetCollapsed()
                    },
                    onExpandProgressChange = { shadowAlpha = it },
                ),
            modifier = Modifier.fillMaxSize(),
        )

        HomeTopBar(
            shadowAlpha = shadowAlpha,
            onBottomPositioned = { topBarBottomPx = it },
            onMyPageClick = callbacks.onMyPageClick,
            modifier =
                Modifier
                    .zIndex(1f)
                    .fillMaxWidth(),
        )

        // 세션 시작 오버레이. state가 non-null이면 페이드인으로 위에 얹혀 다른 UI를 가린다.
        // 시작 계열(시작하기·처음부터 시작)은 티켓 분기, 닫기 계열(닫기·홈으로·뒤로가기)은 오버레이
        // 해제 + 시트 중간 복귀, 이어서 진행은 후속 구현.
        HomeSessionStartOverlay(
            state = state.sessionStartOverlay,
            callbacks =
                HomeSessionStartCallbacks(
                    onCloseClick = callbacks.onSessionOverlayDismiss,
                    onStartClick = callbacks.onSessionStartClick,
                    onGoHomeClick = callbacks.onSessionOverlayDismiss,
                    onRestartClick = callbacks.onSessionStartClick,
                    onResumeClick = callbacks.onSessionResumeClick,
                    onBackClick = callbacks.onSessionOverlayDismiss,
                ),
            modifier = Modifier.zIndex(2f),
        )

        if (state.isLoading) {
            HomeLoadingOverlay()
        }
    }
}

@Composable
private fun HomeBackgroundWithHero(userName: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(Res.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = HomeTopBarHeight),
        ) {
            HomeHeroSection(userName = userName)
        }
    }
}

@Composable
private fun HomeLoadingOverlay() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(HilitTheme.colors.hilitWhite.copy(alpha = 0.6f))
                .clickable(
                    enabled = false,
                    indication = null,
                    interactionSource = null,
                    onClick = { },
                ),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HomeTopBar(
    shadowAlpha: Float,
    onBottomPositioned: (Float) -> Unit,
    onMyPageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HilitLogoTopBar(
            onRightClick = onMyPageClick,
            modifier =
                Modifier.onGloballyPositioned { coordinates ->
                    onBottomPositioned(coordinates.positionInRoot().y + coordinates.size.height)
                },
        )
        // 시트 높이(Peek→Expanded 진행률)에 그대로 붙어 애니메이션되도록 항상 그리고
        // alpha 만 바꾼다. if 로 껐다 켰다 하면 그 시점의 조성/해제 자체가 끊겨 보인다(#199).
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TopBarShadowHeight)
                    .alpha(shadowAlpha)
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    TopBarExpandedShadowColor,
                                    TopBarExpandedShadowColor.copy(alpha = 0f),
                                ),
                        ),
                    ),
        )
    }
}

@Composable
private fun HomeHeroSection(userName: String) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HeroHorizontalPadding)
                .padding(top = GreetingTopSpacing),
    ) {
        GradientText(
            text = "오랜만이에요\n${userName}님!",
        )
        Text(
            text = "밑으로 스크롤해서 면접을 시작해 보세요!",
            style = HilitTheme.typography.body3,
            color = HilitTheme.colors.hilitGreen800,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = HintHorizontalPadding,
                        vertical = HintVerticalPadding,
                    ).padding(top = GreetingToHintSpacing),
        )
    }
}
