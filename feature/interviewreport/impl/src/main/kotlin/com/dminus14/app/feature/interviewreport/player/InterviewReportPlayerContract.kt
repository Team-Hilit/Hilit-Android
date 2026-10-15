package com.dminus14.app.feature.interviewreport.player

import androidx.compose.runtime.Immutable
import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState
import com.dminus14.app.feature.interviewreport.model.PlayerContentUiModel

sealed interface InterviewReportPlayerIntent : MviIntent {
    data class Load(
        val sessionId: Long,
    ) : InterviewReportPlayerIntent

    data object ClickClose : InterviewReportPlayerIntent

    data object ToggleTranscript : InterviewReportPlayerIntent
}

@Immutable
data class InterviewReportPlayerState(
    val phase: Phase = Phase.Loading,
    val transcriptVisible: Boolean = false,
) : MviState {
    sealed interface Phase {
        data object Loading : Phase

        data object Failed : Phase

        data class Ready(
            val content: PlayerContentUiModel,
        ) : Phase
    }
}

sealed interface InterviewReportPlayerEffect : MviEffect {
    data object NavigateBack : InterviewReportPlayerEffect
}
