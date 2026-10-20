package com.dminus14.app.feature.interviewreport.guestfeedback

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 지인 피드백 항목선택 + 공유 링크 생성 화면.
 *
 * ViewModel Effect 를 상위 Navigation·클립보드로 승격한다. 링크 복사 후에는 ViewModel 이
 * 2초 뒤 [GuestFeedbackRequestEffect.NavigateBack] 을 발행해 리포트로 자동 복귀시킨다.
 */
@Composable
fun GuestFeedbackRequestScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GuestFeedbackRequestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(sessionId) {
        viewModel.onIntent(GuestFeedbackRequestIntent.Load(sessionId))
    }
    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                GuestFeedbackRequestEffect.NavigateBack -> {
                    onNavigateBack()
                }

                is GuestFeedbackRequestEffect.CopyToClipboard -> {
                    clipboardManager.setText(AnnotatedString(effect.link))
                }

                is GuestFeedbackRequestEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    GuestFeedbackRequestContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}
