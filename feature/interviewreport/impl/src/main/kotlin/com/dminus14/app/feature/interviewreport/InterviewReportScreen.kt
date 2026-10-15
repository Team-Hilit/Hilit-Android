@file:Suppress("ktlint:standard:filename")

package com.dminus14.app.feature.interviewreport

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 1차 리포트 화면.
 *
 * ViewModel Effect 를 상위 Navigation 콜백으로 승격한다 (navigation.md §2 계약).
 */
@Composable
@Suppress("LongParameterList")
fun InterviewReportScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit,
    onWatchVideo: (sessionId: Long, startSec: Float?) -> Unit,
    onOpenGuestFeedback: (sessionId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InterviewReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(sessionId) {
        viewModel.onIntent(InterviewReportIntent.Load(sessionId))
    }
    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                InterviewReportEffect.NavigateBack -> {
                    onNavigateBack()
                }

                is InterviewReportEffect.NavigateToPlayer -> {
                    onWatchVideo(effect.sessionId, effect.startSec)
                }

                is InterviewReportEffect.NavigateToGuestFeedback -> {
                    onOpenGuestFeedback(effect.sessionId)
                }

                is InterviewReportEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    InterviewReportContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}
