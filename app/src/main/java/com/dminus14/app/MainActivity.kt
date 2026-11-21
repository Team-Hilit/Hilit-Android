package com.dminus14.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.chottulink.lib.ChottuLink
import com.dminus14.app.error.GlobalErrorHost
import com.dminus14.app.feature.feedback.api.FeedbackOnboarding
import com.dminus14.app.feature.interview.api.InterviewErrorRoute
import com.dminus14.app.feature.interview.api.InterviewRoute
import com.dminus14.app.feature.onboarding.api.OnBoardingInterview
import com.dminus14.app.interview.InterviewAppLifecycleCoordinator
import com.dminus14.app.modal.GlobalModalHost
import com.dminus14.app.modal.GlobalModalManager
import com.dminus14.app.navigation.AppNavigationState
import com.dminus14.app.systembar.AppSystemBars
import com.dminus14.app.systembar.resolveAppSystemBarStyle
import com.dminus14.designsystem.theme.HilitTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var navigationState: AppNavigationState

    @Inject
    lateinit var globalModalManager: GlobalModalManager

    @Inject
    lateinit var interviewAppLifecycleCoordinator: InterviewAppLifecycleCoordinator

    override fun onStart() {
        super.onStart()
        interviewAppLifecycleCoordinator.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFeedbackShareDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        navigationState.navigator.onExit = ::finishAffinity
        handleFeedbackShareDeepLink(intent)

        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            HilitTheme {
                val currentRoute = navigationState.navigator.backStack.lastOrNull()
                val systemBarStyle =
                    resolveAppSystemBarStyle(
                        route = currentRoute,
                        defaultBarColor = HilitTheme.colors.hilitWhite,
                    )
                val contentWindowInsets =
                    if (systemBarStyle.drawsBehindSystemBars) {
                        WindowInsets(0)
                    } else {
                        ScaffoldDefaults.contentWindowInsets
                    }

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = HilitTheme.colors.hilitWhite,
                        contentWindowInsets = contentWindowInsets,
                    ) { innerPadding ->
                        NavDisplay(
                            backStack = navigationState.navigator.backStack,
                            onBack = navigationState.navigator::goBack,
                            modifier = Modifier.padding(innerPadding),
                            entryDecorators =
                                listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberViewModelStoreNavEntryDecorator(),
                                ),
                            entryProvider =
                                entryProvider {
                                    navigationState.entryInstallers.forEach { installer ->
                                        installer()
                                    }
                                },
                        )
                    }

                    AppSystemBars(window = window, style = systemBarStyle)

                    GlobalModalHost(manager = globalModalManager)
                    GlobalErrorHost(
                        onExit = ::finishAffinity,
                        onGlobalEventRendered = interviewAppLifecycleCoordinator::acknowledge,
                    )
                }
            }
        }
    }

    /**
     * 지인 피드백 공유 딥링크(`hilit://feedback/{token}`)를 [FeedbackOnboarding] 진입으로 연결한다.
     *
     * ChottuLink SDK가 short link 해석 등 attribution을 함께 처리하도록
     * [ChottuLink.getAppLinkData]로 조회하고, 그 결과가 비어 있으면 커스텀 scheme intent의
     * `data`를 직접 사용한다.
     */
    private fun handleFeedbackShareDeepLink(intent: Intent?) {
        intent ?: return
        ChottuLink
            .getAppLinkData(intent)
            .addOnSuccessListener { result ->
                val link = result?.link ?: intent.data
                navigateToFeedbackOnboarding(link)
            }.addOnFailureListener {
                navigateToFeedbackOnboarding(intent.data)
            }
    }

    private fun navigateToFeedbackOnboarding(link: Uri?) {
        val isFeedbackShareLink =
            link != null &&
                link.scheme == FEEDBACK_SHARE_DEEP_LINK_SCHEME &&
                link.host == FEEDBACK_SHARE_DEEP_LINK_HOST
        val token = link?.lastPathSegment
        // token 이 빈 문자열이면(예: 잘못 해석된 링크) EnterGuestFeedbackUseCase 가 네트워크 호출도
        // 없이 검증 오류로 바로 실패해, 온보딩 화면을 열자마자 "링크를 다시 열어주세요" 에러
        // 모달만 뜨는 혼란스러운 UX가 된다. 애초에 진입하지 않는 게 낫다.
        // 면접 흐름 몰입 중에는 외부 진입으로 면접을 끊지 않는다(iOS 동일). 받은 링크는 보관하거나
        // 면접 종료 뒤 다시 처리하지 않고 버린다. ChottuLink 조회가 비동기라 판정은 이동 직전에 한다.
        if (isFeedbackShareLink && !token.isNullOrBlank() && !isInterviewFlowInProgress()) {
            navigationState.navigator.goTo(FeedbackOnboarding(token))
        }
    }

    /**
     * 면접 흐름(면접 준비 위저드, 면접 준비·진행 화면, 면접 오류 화면)이 back stack에 남아 있는지 판정한다.
     *
     * 면접 종료·중도 이탈은 back stack을 홈으로 교체하고 위저드 닫기는 해당 route를 꺼내므로,
     * 이 route가 남아 있다는 것은 면접 흐름이 아직 끝나지 않았다는 뜻이다.
     */
    private fun isInterviewFlowInProgress(): Boolean =
        navigationState.navigator.backStack.any { route ->
            route is OnBoardingInterview || route is InterviewRoute || route is InterviewErrorRoute
        }

    private companion object {
        const val FEEDBACK_SHARE_DEEP_LINK_SCHEME = "hilit"
        const val FEEDBACK_SHARE_DEEP_LINK_HOST = "feedback"
    }
}
