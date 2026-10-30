package com.dminus14.app.feature.interviewreport

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.InterviewSessionNotFoundException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewReport
import com.dminus14.app.domain.model.InterviewReportStatus
import com.dminus14.app.domain.usecase.GetVideoExpiryUseCase
import com.dminus14.app.domain.usecase.ObserveInterviewReportUseCase
import com.dminus14.app.feature.interviewreport.mapper.InterviewReportUiMapper
import com.dminus14.app.feature.interviewreport.model.ReportUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class InterviewReportViewModel
    @Inject
    constructor(
        private val observeInterviewReport: ObserveInterviewReportUseCase,
        private val getVideoExpiry: GetVideoExpiryUseCase,
    ) : MviViewModel<InterviewReportIntent, InterviewReportState, InterviewReportEffect>(
            InterviewReportState(),
        ) {
        private val context = createContext(InterviewReportReducer)
        private var pollingJob: Job? = null
        private var expiryJob: Job? = null

        override fun onIntent(intent: InterviewReportIntent) {
            when (intent) {
                is InterviewReportIntent.Load -> {
                    if (state.value.sessionId == 0L) {
                        context.dispatch(InterviewReportMutation.SessionBound(intent.sessionId))
                    }
                    load()
                }

                InterviewReportIntent.RefreshReport -> {
                    refreshReport()
                }

                InterviewReportIntent.ClickRetry -> {
                    load()
                }

                InterviewReportIntent.ClickClose -> {
                    sendEffect(InterviewReportEffect.NavigateBack)
                }

                InterviewReportIntent.ClickWatchVideo -> {
                    handleWatchVideo(startSec = null)
                }

                is InterviewReportIntent.ClickWatchScene -> {
                    handleWatchVideo(startSec = intent.startSec)
                }

                is InterviewReportIntent.ClickHighlight -> {
                    context.dispatch(
                        InterviewReportMutation.HighlightSelected(
                            intent.cardIndex,
                            intent.spanIndex,
                        ),
                    )
                }

                InterviewReportIntent.DismissHighlight -> {
                    context.dispatch(InterviewReportMutation.HighlightDismissed)
                }

                InterviewReportIntent.ClickGuestFeedbackInvite -> {
                    val sessionId = state.value.sessionId
                    if (sessionId > 0L) {
                        sendEffect(InterviewReportEffect.NavigateToGuestFeedback(sessionId))
                    }
                }

                is InterviewReportIntent.SelectCard -> {
                    context.dispatch(InterviewReportMutation.CardSelected(intent.cardIndex))
                }

                is InterviewReportIntent.SelectGuestFeedback -> {
                    context.dispatch(
                        InterviewReportMutation.GuestFeedbackSelected(intent.guestIndex),
                    )
                }
            }
        }

        private fun refreshReport() {
            if (!state.value.isRefreshing && state.value.phase.reportOrNull() != null) {
                load(isRefresh = true)
            }
        }

        private fun load(isRefresh: Boolean = false) {
            val sessionId = state.value.sessionId
            if (sessionId <= 0L) {
                // 초기 phase 가 Loading 이라, 여기서 조용히 빠져나가면(잘못된 route 인자·딥링크로
                // sessionId 가 안 채워진 경우) 스피너에 영구 고착된다. UNKNOWN 상태를 Failed 로
                // 보내는 것과 같은 이유로 Failed 로 보내 재시도 UI 를 노출한다.
                context.dispatch(InterviewReportMutation.LoadFailed)
                return
            }
            pollingJob?.cancel()
            expiryJob?.cancel()
            context.dispatch(
                if (isRefresh) {
                    InterviewReportMutation.RefreshStarted
                } else {
                    InterviewReportMutation.LoadStarted
                },
            )
            fetchVideoExpiry(sessionId)
            pollingJob =
                context.launch {
                    try {
                        observeInterviewReport(sessionId)
                            .onEach { report ->
                                currentCoroutineContext().ensureActive()
                                val phase = report.toPhase()
                                if (isRefresh && phase is InterviewReportState.Phase.Failed) {
                                    sendEffect(
                                        InterviewReportEffect.ShowToast(
                                            "리포트를 새로고침하지 못했어요. 다시 시도해 주세요.",
                                        ),
                                    )
                                } else if (
                                    !isRefresh || phase !is InterviewReportState.Phase.Loading
                                ) {
                                    context.dispatch(
                                        InterviewReportMutation.PhaseReceived(phase),
                                    )
                                }
                            }.catch { error ->
                                handleCommonError(error, isRefresh)
                            }.collect()
                    } finally {
                        if (isRefresh) {
                            currentCoroutineContext().ensureActive()
                            context.dispatch(InterviewReportMutation.RefreshFinished)
                        }
                    }
                }
        }

        private fun InterviewReport.toPhase(): InterviewReportState.Phase =
            when (status) {
                InterviewReportStatus.READY -> {
                    InterviewReportState.Phase.Ready(InterviewReportUiMapper.map(this))
                }

                InterviewReportStatus.INSUFFICIENT_ANALYSIS -> {
                    InterviewReportState.Phase.InsufficientAnalysis(
                        InterviewReportUiMapper.map(this),
                    )
                }

                InterviewReportStatus.FAILED, InterviewReportStatus.UNKNOWN -> {
                    InterviewReportState.Phase.Failed
                }

                InterviewReportStatus.GENERATING -> {
                    InterviewReportState.Phase.Loading
                }
            }

        // 아래 에러 처리 사항은 임시입니다. 공통 처리 기획자 문의 모든 ViewModel 일괄 수정 예정
        private suspend fun handleCommonError(
            error: Throwable,
            isRefresh: Boolean,
        ) {
            currentCoroutineContext().ensureActive()
            context.dispatch(
                if (isRefresh) {
                    InterviewReportMutation.RefreshFinished
                } else {
                    InterviewReportMutation.LoadFailed
                },
            )
            when {
                isRefresh && error is InterviewSessionNotFoundException -> {
                    sendEffect(
                        InterviewReportEffect.ShowToast(
                            "리포트를 새로고침하지 못했어요. 다시 시도해 주세요.",
                        ),
                    )
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
         * 카운트다운 시드용 잔여시간을 별도 조회한다. 리포트 폴링과 독립적이며, 실패하거나 이미
         * 만료됐으면 [InterviewReportState.videoExpirySeconds] 를 null 로 두어 카운트다운을 숨긴다.
         */
        private fun fetchVideoExpiry(sessionId: Long) {
            expiryJob =
                context.launch {
                    getVideoExpiry(sessionId)
                        .onSuccess { expiry ->
                            currentCoroutineContext().ensureActive()
                            val remainingSeconds = expiry.expiresInSeconds
                            val seconds = remainingSeconds.takeIf { !expiry.expired && it > 0L }
                            context.dispatch(InterviewReportMutation.VideoExpiryReceived(seconds))
                        }
                }
        }

        private fun handleWatchVideo(startSec: Float?) {
            val current = state.value
            val report = current.phase.reportOrNull() ?: return
            val video = report.video
            if (video == null || video.expired || video.url.isNullOrBlank()) {
                sendEffect(InterviewReportEffect.ShowToast("영상이 만료되어 재생할 수 없어요."))
                return
            }
            sendEffect(InterviewReportEffect.NavigateToPlayer(current.sessionId, startSec))
        }

        /**
         * Ready / InsufficientAnalysis 두 Phase 가 담은 리포트를 꺼낸다. 그 외에는 null.
         * 분석 부족(INSUFFICIENT_ANALYSIS) 상태에서도 영상 자체는 재생 가능할 수 있어,
         * Ready 로만 좁히면 [handleWatchVideo] 가 조용히 아무 반응도 하지 않게 된다.
         */
        private fun InterviewReportState.Phase.reportOrNull(): ReportUiModel? =
            when (this) {
                is InterviewReportState.Phase.Ready -> report
                is InterviewReportState.Phase.InsufficientAnalysis -> report
                else -> null
            }
    }

/** UI 상태의 의미 있는 변경 원인을 같은 파일에서 제한한다. */
private sealed interface InterviewReportMutation {
    data class SessionBound(
        val sessionId: Long,
    ) : InterviewReportMutation

    data class HighlightSelected(
        val cardIndex: Int,
        val spanIndex: Int,
    ) : InterviewReportMutation

    data object HighlightDismissed : InterviewReportMutation

    data class CardSelected(
        val cardIndex: Int,
    ) : InterviewReportMutation

    data class GuestFeedbackSelected(
        val guestIndex: Int,
    ) : InterviewReportMutation

    data object RefreshStarted : InterviewReportMutation

    data object RefreshFinished : InterviewReportMutation

    data object LoadStarted : InterviewReportMutation

    data object LoadFailed : InterviewReportMutation

    data class PhaseReceived(
        val phase: InterviewReportState.Phase,
    ) : InterviewReportMutation

    data class VideoExpiryReceived(
        val seconds: Long?,
    ) : InterviewReportMutation
}

private object InterviewReportReducer : MviReducer<InterviewReportState, InterviewReportMutation> {
    override fun reduce(
        state: InterviewReportState,
        mutation: InterviewReportMutation,
    ): InterviewReportState =
        when (mutation) {
            is InterviewReportMutation.SessionBound -> {
                state.copy(sessionId = mutation.sessionId)
            }

            is InterviewReportMutation.HighlightSelected -> {
                state.copy(
                    selectedHighlight =
                        InterviewReportState.SelectedHighlight(
                            mutation.cardIndex,
                            mutation.spanIndex,
                        ),
                )
            }

            InterviewReportMutation.HighlightDismissed -> {
                state.copy(selectedHighlight = null)
            }

            is InterviewReportMutation.CardSelected -> {
                state.copy(selectedCardIndex = mutation.cardIndex)
            }

            is InterviewReportMutation.GuestFeedbackSelected -> {
                state.copy(selectedGuestFeedbackIndex = mutation.guestIndex)
            }

            InterviewReportMutation.RefreshStarted -> {
                state.copy(isRefreshing = true)
            }

            InterviewReportMutation.RefreshFinished -> {
                state.copy(isRefreshing = false)
            }

            InterviewReportMutation.LoadStarted -> {
                state.copy(
                    phase = InterviewReportState.Phase.Loading,
                    isRefreshing = false,
                    selectedCardIndex = 0,
                    selectedGuestFeedbackIndex = 0,
                    videoExpirySeconds = null,
                )
            }

            InterviewReportMutation.LoadFailed -> {
                state.copy(phase = InterviewReportState.Phase.Failed)
            }

            is InterviewReportMutation.PhaseReceived -> {
                receivePhase(state, mutation.phase)
            }

            is InterviewReportMutation.VideoExpiryReceived -> {
                state.copy(videoExpirySeconds = mutation.seconds)
            }
        }

    private fun receivePhase(
        state: InterviewReportState,
        phase: InterviewReportState.Phase,
    ): InterviewReportState {
        val report =
            when (phase) {
                is InterviewReportState.Phase.Ready -> phase.report
                is InterviewReportState.Phase.InsufficientAnalysis -> phase.report
                else -> null
            }
        return state.copy(
            phase = phase,
            selectedCardIndex =
                state.selectedCardIndex.coerceIn(
                    0,
                    (
                        report?.cards?.lastIndex
                            ?: 0
                    ).coerceAtLeast(0),
                ),
            selectedGuestFeedbackIndex =
                state.selectedGuestFeedbackIndex.coerceIn(
                    0,
                    (report?.guestFeedback?.guests?.lastIndex ?: 0).coerceAtLeast(0),
                ),
            selectedHighlight = null,
        )
    }
}
