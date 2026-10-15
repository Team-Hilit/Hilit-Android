package com.dminus14.app.feature.feedback.onboarding

import android.os.SystemClock
import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.modal.GlobalModalRequest
import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.core.common.modal.showGlobalModal
import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.GuestFeedbackRequestException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.GuestFeedbackEntry
import com.dminus14.app.domain.model.GuestFeedbackUnavailableReason
import com.dminus14.app.domain.usecase.EnterGuestFeedbackUseCase
import com.dminus14.app.feature.feedback.session.GuestFeedbackFlowSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

@HiltViewModel
class FeedbackOnboardingViewModel
    @Inject
    constructor(
        private val enterGuestFeedbackUseCase: EnterGuestFeedbackUseCase,
        private val session: GuestFeedbackFlowSession,
    ) : MviViewModel<FeedbackOnboardingIntent, FeedbackOnboardingState, FeedbackOnboardingEffect>(
            FeedbackOnboardingState(),
        ) {
        private val context = createContext(FeedbackOnboardingReducer)
        private var firstBackPressedAt: Long? = null

        override fun onIntent(intent: FeedbackOnboardingIntent) {
            when (intent) {
                is FeedbackOnboardingIntent.Load -> {
                    load(intent.token)
                }

                FeedbackOnboardingIntent.StartClicked -> {
                    if (state.value.loadState == FeedbackOnboardingLoadState.Ready) {
                        context.dispatch(FeedbackOnboardingMutation.EditorOpened)
                    }
                }

                is FeedbackOnboardingIntent.NicknameChanged -> {
                    context.dispatch(FeedbackOnboardingMutation.NicknameChanged(intent.value))
                }

                FeedbackOnboardingIntent.NicknameConfirmed -> {
                    confirmNickname()
                }

                FeedbackOnboardingIntent.NameEditorDismissed -> {
                    context.dispatch(FeedbackOnboardingMutation.EditorDismissed)
                }

                FeedbackOnboardingIntent.BackPressed -> {
                    handleBack()
                }
            }
        }

        private fun load(token: String) {
            if (state.value.loadState != FeedbackOnboardingLoadState.Idle) return

            context.dispatch(FeedbackOnboardingMutation.LoadStarted)
            context.launch {
                enterGuestFeedbackUseCase(token)
                    .also { currentCoroutineContext().ensureActive() }
                    .onSuccess { entry ->
                        when (entry) {
                            is GuestFeedbackEntry.Open -> handleOpen(token, entry)
                            is GuestFeedbackEntry.Unavailable -> showUnavailable(entry.reason)
                        }
                    }.onFailure(::handleFailure)
            }
        }

        private suspend fun handleOpen(
            token: String,
            entry: GuestFeedbackEntry.Open,
        ) {
            if (!entry.submissionOpen) {
                showBlockingModal(
                    title = "피드백 작성 마감",
                    message = "현재는 피드백을 작성할 수 없어요.",
                )
                return
            }

            session.start(token, entry)
            context.dispatch(FeedbackOnboardingMutation.EntryOpened(entry.requesterName))
        }

        private suspend fun showUnavailable(reason: GuestFeedbackUnavailableReason) {
            val (title, message) =
                when (reason) {
                    GuestFeedbackUnavailableReason.PRIVATE -> {
                        "비공개 피드백" to "요청자가 피드백을 비공개했어요."
                    }

                    GuestFeedbackUnavailableReason.EXPIRED -> {
                        "피드백 기간 종료" to "피드백을 작성할 수 있는 기간이 끝났어요."
                    }

                    GuestFeedbackUnavailableReason.FULL -> {
                        "피드백 마감" to "피드백 가능한 최대 인원이 모두 찼어요."
                    }

                    GuestFeedbackUnavailableReason.ALREADY_SUBMITTED -> {
                        "이미 제출한 피드백" to "이 기기에서 이미 피드백을 제출했어요."
                    }
                }
            showBlockingModal(title, message)
        }

        private fun confirmNickname() {
            val nickname = state.value.nickname.trim()
            if (state.value.loadState != FeedbackOnboardingLoadState.Ready ||
                !state.value.canContinue ||
                session.snapshot() == null
            ) {
                return
            }

            session.setNickname(nickname)
            context.dispatch(FeedbackOnboardingMutation.NicknameConfirmed(nickname))
            context.sendEffect(FeedbackOnboardingEffect.FeedbackReady)
        }

        private fun handleBack() {
            val now = SystemClock.elapsedRealtime()
            if (shouldExitOnBack(firstBackPressedAt, now)) {
                session.clear()
                context.sendEffect(FeedbackOnboardingEffect.ExitRequested)
            } else {
                firstBackPressedAt = now
                context.sendEffect(FeedbackOnboardingEffect.ShowExitHint)
            }
        }

        private fun handleFailure(error: Throwable) {
            if (error is CancellationException) throw error
            context.dispatch(FeedbackOnboardingMutation.LoadFailed)
            context.launch {
                when (error) {
                    is GuestFeedbackRequestException -> {
                        showBlockingModal(
                            title = "요청 오류",
                            message = "서버 요청에 실패했습니다. 앱을 재실행하고 다시 시도해주세요.",
                        )
                    }

                    else -> {
                        if (error is NetworkUnavailableException || error is ServerException) {
                            session.clear()
                        }
                        handleCommonError(error)
                        if (error !is NetworkUnavailableException && error !is ServerException) {
                            showBlockingModal(
                                title = "오류가 발생했어요",
                                message = "앱을 종료한 뒤 링크를 다시 열어주세요.",
                            )
                        }
                    }
                }
            }
        }

        // 아래 에러 처리 사항은 임시입니다. 공통 처리 기획자 문의 모든 ViewModel 일괄 수정 예정
        private suspend fun handleCommonError(error: Throwable) {
            when {
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

        private suspend fun showBlockingModal(
            title: String,
            message: String,
        ) {
            context.dispatch(FeedbackOnboardingMutation.LoadFailed)
            session.clear()
            val result =
                showGlobalModal(
                    GlobalModalRequest(
                        title = title,
                        message = message,
                        confirmText = "종료하기",
                        dismissible = false,
                    ),
                )
            if (result == GlobalModalResult.Confirm) {
                context.sendEffect(FeedbackOnboardingEffect.ExitRequested)
            }
        }
    }

internal fun shouldExitOnBack(
    firstBackPressedAt: Long?,
    now: Long,
): Boolean = firstBackPressedAt?.let { now - it <= EXIT_INTERVAL_MILLIS } == true

private const val EXIT_INTERVAL_MILLIS = 2_000L

private sealed interface FeedbackOnboardingMutation {
    data object EditorOpened : FeedbackOnboardingMutation

    data class NicknameChanged(
        val value: String,
    ) : FeedbackOnboardingMutation

    data object EditorDismissed : FeedbackOnboardingMutation

    data object LoadStarted : FeedbackOnboardingMutation

    data class EntryOpened(
        val requesterName: String,
    ) : FeedbackOnboardingMutation

    data class NicknameConfirmed(
        val nickname: String,
    ) : FeedbackOnboardingMutation

    data object LoadFailed : FeedbackOnboardingMutation
}

/** 화면 상태 계산은 순수하며 세션 기록과 Modal 호출은 ViewModel에서 수행한다. */
private object FeedbackOnboardingReducer :
    MviReducer<FeedbackOnboardingState, FeedbackOnboardingMutation> {
    override fun reduce(
        state: FeedbackOnboardingState,
        mutation: FeedbackOnboardingMutation,
    ): FeedbackOnboardingState =
        with(state) {
            when (mutation) {
                FeedbackOnboardingMutation.EditorOpened -> {
                    copy(isNameEditorVisible = true)
                }

                is FeedbackOnboardingMutation.NicknameChanged -> {
                    copy(nickname = mutation.value)
                }

                FeedbackOnboardingMutation.EditorDismissed -> {
                    copy(isNameEditorVisible = false)
                }

                FeedbackOnboardingMutation.LoadStarted -> {
                    copy(loadState = FeedbackOnboardingLoadState.Loading)
                }

                is FeedbackOnboardingMutation.EntryOpened -> {
                    copy(
                        requesterName = mutation.requesterName,
                        loadState = FeedbackOnboardingLoadState.Ready,
                    )
                }

                is FeedbackOnboardingMutation.NicknameConfirmed -> {
                    copy(
                        nickname = mutation.nickname,
                        isNameEditorVisible = false,
                    )
                }

                FeedbackOnboardingMutation.LoadFailed -> {
                    copy(
                        loadState = FeedbackOnboardingLoadState.Failed,
                        isNameEditorVisible = false,
                    )
                }
            }
        }
}
