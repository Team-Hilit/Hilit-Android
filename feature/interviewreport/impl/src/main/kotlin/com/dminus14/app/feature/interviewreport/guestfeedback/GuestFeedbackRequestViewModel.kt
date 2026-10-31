package com.dminus14.app.feature.interviewreport.guestfeedback

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.EmptyAttitudeAxesException
import com.dminus14.app.domain.exception.FeedbackShareAlreadyExistsException
import com.dminus14.app.domain.exception.InterviewSessionNotFoundException
import com.dminus14.app.domain.exception.InvalidAttitudeAxisException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.TooManyAttitudeAxesException
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.model.SavedFeedbackShare
import com.dminus14.app.domain.usecase.CreateFeedbackShareUseCase
import com.dminus14.app.domain.usecase.EndFeedbackShareUseCase
import com.dminus14.app.domain.usecase.GetSavedFeedbackShareUseCase
import com.dminus14.app.domain.usecase.ResolveSavedFeedbackShareLinkUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

private const val COPIED_NOTICE_DURATION_MS = 2000L

@HiltViewModel
@Suppress("TooManyFunctions")
class GuestFeedbackRequestViewModel
    @Inject
    constructor(
        private val createFeedbackShare: CreateFeedbackShareUseCase,
        private val endFeedbackShare: EndFeedbackShareUseCase,
        private val getSavedFeedbackShare: GetSavedFeedbackShareUseCase,
        private val resolveSavedFeedbackShareLink: ResolveSavedFeedbackShareLinkUseCase,
    ) : MviViewModel<
            GuestFeedbackRequestIntent,
            GuestFeedbackRequestState,
            GuestFeedbackRequestEffect,
        >(
            GuestFeedbackRequestState(),
        ) {
        private val context = createContext(GuestFeedbackRequestReducer)
        private var sessionId: Long = 0L
        private var loadJob: Job? = null

        override fun onIntent(intent: GuestFeedbackRequestIntent) {
            when (intent) {
                is GuestFeedbackRequestIntent.Load -> {
                    if (sessionId == 0L) sessionId = intent.sessionId
                    load()
                }

                is GuestFeedbackRequestIntent.ToggleAxis -> {
                    context.dispatch(GuestFeedbackRequestMutation.AxisToggled(intent.axis))
                }

                GuestFeedbackRequestIntent.ClickClose -> {
                    context.sendEffect(GuestFeedbackRequestEffect.NavigateBack)
                }

                GuestFeedbackRequestIntent.ClickSubmit -> {
                    onClickSubmit()
                }

                GuestFeedbackRequestIntent.ClickCopyMessage -> {
                    copyMessage()
                }

                GuestFeedbackRequestIntent.ClickShareMessage -> {
                    shareMessage()
                }

                GuestFeedbackRequestIntent.DismissShareLinkModal -> {
                    context.dispatch(GuestFeedbackRequestMutation.ShareModalDismissed)
                }

                GuestFeedbackRequestIntent.ClickEndShare -> {
                    endShare()
                }
            }
        }

        /**
         * 저장된 공유 기록이 있으면 하단 버튼이 재노출로 동작하도록 state 에 싣고, 생성 당시
         * 선택 항목으로 토글을 복원한다.
         */
        private fun load() {
            if (sessionId <= 0L) return
            loadJob?.cancel()
            loadJob =
                context.launch {
                    val saved = getSavedFeedbackShare(sessionId) ?: return@launch
                    currentCoroutineContext().ensureActive()
                    context.dispatch(GuestFeedbackRequestMutation.SavedShareLoaded(saved))
                }
        }

        private fun onClickSubmit() {
            val current = state.value
            when {
                current.endOnlyShare -> endShare()
                current.hasActiveShare -> openSavedShare()
                else -> createShare()
            }
        }

        /**
         * 이미 활성 링크가 있을 때의 하단 버튼 동작. 서버에 다시 만들지 않고 기기에 저장된 링크로
         * 첫 생성과 같은 모달을 띄운다. 기록이 사라진 경우에만 새로 생성한다.
         */
        private fun openSavedShare() {
            if (state.value.submitting) return
            loadJob?.cancel()
            context.dispatch(GuestFeedbackRequestMutation.SubmissionStarted)
            context.launch {
                resolveSavedFeedbackShareLink(sessionId)
                    .onSuccess { saved ->
                        currentCoroutineContext().ensureActive()
                        if (saved == null) {
                            context.dispatch(GuestFeedbackRequestMutation.SavedShareMissing)
                            createShare()
                        } else {
                            context.dispatch(GuestFeedbackRequestMutation.ShareReady(saved))
                        }
                    }.onFailure { error ->
                        handleCommonError(error)
                    }
            }
        }

        private fun createShare() {
            val current = state.value
            if (current.submitting || current.selectedAxes.isEmpty()) return
            loadJob?.cancel()
            context.dispatch(GuestFeedbackRequestMutation.SubmissionStarted)
            context.launch {
                createFeedbackShare(sessionId, current.selectedAxes.toList())
                    .onSuccess { saved ->
                        currentCoroutineContext().ensureActive()
                        context.dispatch(GuestFeedbackRequestMutation.ShareReady(saved))
                    }.onFailure { error ->
                        handleCreateShareFailure(error)
                    }
            }
        }

        /**
         * `POST .../share`(create_1) 전용 비즈니스 예외는 인라인으로 안내하고, 나머지
         * network/server/unknown 은 공통 처리로 위임한다.
         */
        private suspend fun handleCreateShareFailure(error: Throwable) {
            currentCoroutineContext().ensureActive()
            when (error) {
                is EmptyAttitudeAxesException,
                is TooManyAttitudeAxesException,
                is InvalidAttitudeAxisException,
                -> {
                    context.dispatch(GuestFeedbackRequestMutation.SubmissionFailed)
                    context.sendEffect(GuestFeedbackRequestEffect.ShowToast(error.message))
                }

                is FeedbackShareAlreadyExistsException -> {
                    handleAlreadyExistsFailure(error)
                }

                is InterviewSessionNotFoundException -> {
                    context.dispatch(GuestFeedbackRequestMutation.SubmissionFailed)
                    context.sendEffect(GuestFeedbackRequestEffect.ShowToast(error.message))
                    context.sendEffect(GuestFeedbackRequestEffect.NavigateBack)
                }

                else -> {
                    handleCommonError(error)
                }
            }
        }

        /**
         * 서버에는 이미 활성 공유 링크가 있는 상태(생성 API 는 충돌 시 기존 token 을 돌려주지
         * 않음). 이 기기에 저장된 기록이 남아있다면 그 링크로 방금 생성에 성공한 것과 똑같이
         * 보여준다. 기록이 전혀 없으면(다른 기기에서 만들었거나 앱 데이터가 지워진 경우)
         * 재구성할 수 없으므로 안내만 한다.
         */
        private suspend fun handleAlreadyExistsFailure(error: FeedbackShareAlreadyExistsException) {
            val saved = resolveSavedFeedbackShareLink(sessionId).getOrNull()
            currentCoroutineContext().ensureActive()
            if (saved != null) {
                context.dispatch(GuestFeedbackRequestMutation.ShareReady(saved))
            } else {
                context.dispatch(GuestFeedbackRequestMutation.EndOnlyShareFound)
                context.sendEffect(GuestFeedbackRequestEffect.ShowToast(error.message))
            }
        }

        private fun endShare() {
            if (state.value.submitting) return
            loadJob?.cancel()
            context.dispatch(GuestFeedbackRequestMutation.SubmissionStarted)
            context.launch {
                endFeedbackShare(sessionId)
                    .onSuccess {
                        currentCoroutineContext().ensureActive()
                        context.dispatch(GuestFeedbackRequestMutation.ShareEnded)
                        context.sendEffect(
                            GuestFeedbackRequestEffect.ShowToast("피드백 요청을 종료했어요."),
                        )
                        context.sendEffect(GuestFeedbackRequestEffect.NavigateBack)
                    }.onFailure { error ->
                        handleCommonError(error)
                    }
            }
        }

        // 아래 에러 처리 사항은 임시입니다. 공통 처리 기획자 문의 모든 ViewModel 일괄 수정 예정
        private suspend fun handleCommonError(error: Throwable) {
            currentCoroutineContext().ensureActive()
            context.dispatch(GuestFeedbackRequestMutation.SubmissionFailed)
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

        /**
         * 복사 후에는 안내를 2초 보여주고 모달만 닫는다. 화면을 벗어나지 않으므로 사용자가
         * 바로 다시 복사하거나 공유할 수 있다.
         */
        private fun copyMessage() {
            val message = shareMessageOrNull() ?: return
            context.sendEffect(GuestFeedbackRequestEffect.CopyToClipboard(message))
            context.dispatch(GuestFeedbackRequestMutation.MessageCopied)
            context.launch {
                delay(COPIED_NOTICE_DURATION_MS)
                context.dispatch(GuestFeedbackRequestMutation.CopiedNoticeFinished)
            }
        }

        /**
         * 공유 시트는 사용자가 취소하고 돌아올 수 있으므로 모달을 그대로 둔다. 복사와 달리
         * 리포트로 자동 복귀시키지 않는다.
         */
        private fun shareMessage() {
            val message = shareMessageOrNull() ?: return
            context.sendEffect(GuestFeedbackRequestEffect.ShareText(message))
        }

        /** 복사와 공유가 똑같은 텍스트를 쓰도록 한 곳에서만 조립한다. */
        private fun shareMessageOrNull(): String? {
            val current = state.value
            val link = current.shareLink ?: return null
            return buildGuestFeedbackShareMessage(current.messageAxes, link)
        }
    }

private sealed interface GuestFeedbackRequestMutation {
    data object ShareModalDismissed : GuestFeedbackRequestMutation

    /** 기기에 저장된 기록을 불러와 토글까지 복원한다. */
    data class SavedShareLoaded(
        val saved: SavedFeedbackShare,
    ) : GuestFeedbackRequestMutation

    data class AxisToggled(
        val axis: GuestFeedbackAxisCode,
    ) : GuestFeedbackRequestMutation

    data object SubmissionStarted : GuestFeedbackRequestMutation

    data object SubmissionFailed : GuestFeedbackRequestMutation

    /** 생성·재노출·충돌 복구가 모두 같은 결과(링크 있는 기록)로 끝난다. */
    data class ShareReady(
        val saved: SavedFeedbackShare,
    ) : GuestFeedbackRequestMutation

    /** 저장돼 있다고 봤던 기록이 사라진 경우. 곧바로 새로 생성한다. */
    data object SavedShareMissing : GuestFeedbackRequestMutation

    /** 서버에는 활성 링크가 있으나 이 기기에서 링크를 꺼낼 수 없는 상태. */
    data object EndOnlyShareFound : GuestFeedbackRequestMutation

    data object ShareEnded : GuestFeedbackRequestMutation

    data object MessageCopied : GuestFeedbackRequestMutation

    data object CopiedNoticeFinished : GuestFeedbackRequestMutation
}

private object GuestFeedbackRequestReducer :
    MviReducer<GuestFeedbackRequestState, GuestFeedbackRequestMutation> {
    override fun reduce(
        state: GuestFeedbackRequestState,
        mutation: GuestFeedbackRequestMutation,
    ): GuestFeedbackRequestState =
        when (mutation) {
            GuestFeedbackRequestMutation.ShareModalDismissed -> {
                state.copy(shareLink = null)
            }

            is GuestFeedbackRequestMutation.SavedShareLoaded -> {
                state.copy(
                    savedShare = mutation.saved,
                    selectedAxes = restoredAxes(mutation.saved, state.selectedAxes),
                )
            }

            is GuestFeedbackRequestMutation.AxisToggled -> {
                state.copy(selectedAxes = toggledAxes(state.selectedAxes, mutation.axis))
            }

            GuestFeedbackRequestMutation.SubmissionStarted -> {
                state.copy(submitting = true)
            }

            GuestFeedbackRequestMutation.SubmissionFailed -> {
                state.copy(submitting = false)
            }

            is GuestFeedbackRequestMutation.ShareReady -> {
                state.copy(
                    submitting = false,
                    savedShare = mutation.saved,
                    shareLink = mutation.saved.link,
                )
            }

            GuestFeedbackRequestMutation.SavedShareMissing -> {
                state.copy(submitting = false, savedShare = null)
            }

            GuestFeedbackRequestMutation.EndOnlyShareFound -> {
                state.copy(submitting = false, endOnlyShare = true)
            }

            GuestFeedbackRequestMutation.ShareEnded -> {
                state.copy(
                    submitting = false,
                    savedShare = null,
                    endOnlyShare = false,
                    shareLink = null,
                )
            }

            GuestFeedbackRequestMutation.MessageCopied -> {
                state.copy(messageCopied = true)
            }

            GuestFeedbackRequestMutation.CopiedNoticeFinished -> {
                state.copy(messageCopied = false, shareLink = null)
            }
        }
}

/** 저장된 기록의 생성 당시 항목으로 토글을 복원한다. 기록에 항목이 없으면 현재 선택을 유지한다. */
private fun restoredAxes(
    saved: SavedFeedbackShare,
    current: Set<GuestFeedbackAxisCode>,
): Set<GuestFeedbackAxisCode> = saved.axes.takeIf { it.isNotEmpty() }?.toSet() ?: current

private fun toggledAxes(
    axes: Set<GuestFeedbackAxisCode>,
    axis: GuestFeedbackAxisCode,
): Set<GuestFeedbackAxisCode> =
    if (axis in axes) {
        // 최소 1개는 유지한다(서버 계약 1~5개).
        if (axes.size <= 1) axes else axes - axis
    } else {
        axes + axis
    }
