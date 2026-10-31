package com.dminus14.app.feature.interviewreport.guestfeedback

import android.content.Intent
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
 * ViewModel Effect 를 상위 Navigation·클립보드·공유 시트로 승격한다. 멘트 복사 후에는
 * ViewModel 이 2초 뒤 [GuestFeedbackRequestEffect.NavigateBack] 을 발행해 리포트로 자동
 * 복귀시킨다. 공유 시트는 취소할 수 있으므로 모달을 그대로 둔다.
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
                    clipboardManager.setText(AnnotatedString(effect.text))
                }

                is GuestFeedbackRequestEffect.ShareText -> {
                    context.startActivity(shareChooserIntent(effect.text))
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

/**
 * 멘트와 링크를 카카오톡·메시지 등으로 바로 넘기는 공유 시트 Intent.
 *
 * 공유 대상 앱을 사용자가 고르므로 전송 자체는 사용자의 명시적 선택으로만 일어난다.
 */
private fun shareChooserIntent(text: String): Intent =
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        },
        null,
    )
