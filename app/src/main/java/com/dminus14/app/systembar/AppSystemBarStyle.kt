package com.dminus14.app.systembar

import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import com.dminus14.app.feature.interview.api.ConfirmedInterviewResume
import com.dminus14.app.feature.interview.api.InterviewRoute
import com.dminus14.app.feature.interviewreport.api.InterviewReport
import com.dminus14.app.feature.interviewreport.api.InterviewReportPlayer

/** 앱 Route별 시스템 바 배경, 아이콘 밝기와 전체 화면 여부를 표현한다. */
internal data class AppSystemBarStyle(
    val statusBarColor: Color?,
    val navigationBarColor: Color?,
    val useDarkStatusBarIcons: Boolean,
    val useDarkNavigationBarIcons: Boolean,
    val drawsBehindSystemBars: Boolean,
)

/** 현재 Route의 화면 특성을 앱 전역 시스템 바 정책으로 변환한다. */
internal fun resolveAppSystemBarStyle(
    route: Any?,
    defaultBarColor: Color,
): AppSystemBarStyle {
    val defaultStyle =
        AppSystemBarStyle(
            statusBarColor = defaultBarColor,
            navigationBarColor = defaultBarColor,
            useDarkStatusBarIcons = true,
            useDarkNavigationBarIcons = true,
            drawsBehindSystemBars = false,
        )

    return when (route) {
        is ConfirmedInterviewResume,
        is InterviewRoute,
        -> {
            AppSystemBarStyle(
                statusBarColor = null,
                navigationBarColor = null,
                useDarkStatusBarIcons = false,
                useDarkNavigationBarIcons = false,
                drawsBehindSystemBars = true,
            )
        }

        is InterviewReport,
        is InterviewReportPlayer,
        -> {
            defaultStyle.copy(drawsBehindSystemBars = true)
        }

        else -> {
            defaultStyle
        }
    }
}

/** 시스템 바 아이콘 밝기를 적용하고 Edge-to-edge 영역에 정책 배경색을 그린다. */
@Composable
internal fun AppSystemBars(
    window: Window,
    style: AppSystemBarStyle,
    modifier: Modifier = Modifier,
) {
    SideEffect {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        // Android는 시스템 바 아이콘에 임의 색상 토큰을 받지 않아 밝고 어두운 모드만 지정한다.
        insetsController.isAppearanceLightStatusBars = style.useDarkStatusBarIcons
        insetsController.isAppearanceLightNavigationBars = style.useDarkNavigationBarIcons
    }

    Box(modifier = modifier.fillMaxSize()) {
        style.statusBarColor?.let { color ->
            Spacer(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(color),
            )
        }
        style.navigationBarColor?.let { color ->
            Spacer(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(color),
            )
        }
    }
}
