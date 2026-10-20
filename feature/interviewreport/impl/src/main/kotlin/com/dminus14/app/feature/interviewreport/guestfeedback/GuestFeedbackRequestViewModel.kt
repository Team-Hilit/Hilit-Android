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
import com.dminus14.app.domain.usecase.CreateFeedbackShareDynamicLinkUseCase
import com.dminus14.app.domain.usecase.CreateFeedbackShareUseCase
import com.dminus14.app.domain.usecase.EndFeedbackShareUseCase
import com.dminus14.app.domain.usecase.GetSavedFeedbackShareTokenUseCase
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
        private val getSavedFeedbackShareToken: GetSavedFeedbackShareTokenUseCase,
        private val createFeedbackShareDynamicLink: CreateFeedbackShareDynamicLinkUseCase,
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
                    toggleAxis(intent.axis)
                }

                GuestFeedbackRequestIntent.ClickClose -> {
                    sendEffect(GuestFeedbackRequestEffect.NavigateBack)
                }

                GuestFeedbackRequestIntent.ClickSubmit -> {
                    onClickSubmit()
                }

                GuestFeedbackRequestIntent.ClickCopyLink -> {
                    copyLink()
                }

                GuestFeedbackRequestIntent.DismissShareLinkModal -> {
                    context.dispatch(GuestFeedbackRequestMutation.ShareModalDismissed)
                }
            }
        }

        /** sessionId 에 저장된 공유 링크 token 이 있으면 하단 버튼을 "피드백 종료하기"로 바꾼다. */
        private fun load() {
            if (sessionId <= 0L) return
            loadJob?.cancel()
            loadJob =
                context.launch {
                    val savedToken = getSavedFeedbackShareToken(sessionId)
                    currentCoroutineContext().ensureActive()
                    val hasSavedShare = savedToken != null
                    context.dispatch(
                        GuestFeedbackRequestMutation.SavedShareChecked(hasSavedShare),
                    )
                }
        }

        private fun toggleAxis(axis: GuestFeedbackAxisCode) {
            context.dispatch(GuestFeedbackRequestMutation.AxisToggled(axis))
        }

        private fun onClickSubmit() {
            if (state.value.hasActiveShare) {
                endShare()
            } else {
                createShare()
            }
        }

        private fun createShare() {
            val current = state.value
            if (current.submitting || current.selectedAxes.isEmpty()) return
            loadJob?.cancel()
            context.dispatch(GuestFeedbackRequestMutation.SubmissionStarted)
            context.launch {
                createFeedbackShare(sessionId, current.selectedAxes.toList())
                    .onSuccess { token ->
                        currentCoroutineContext().ensureActive()
                        // 아래는 항상 성공한다(동적 링크 생성 실패 시 원시 딥링크로 self-heal).
                        val shareLink = createFeedbackShareDynamicLink(token).getOrThrow()
                        currentCoroutineContext().ensureActive()
                        context.dispatch(GuestFeedbackRequestMutation.ShareCreated(shareLink))
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
                    sendEffect(GuestFeedbackRequestEffect.ShowToast(error.message))
                }

                is FeedbackShareAlreadyExistsException -> {
                    handleAlreadyExistsFailure(error)
                }

                is InterviewSessionNotFoundException -> {
                    context.dispatch(GuestFeedbackRequestMutation.SubmissionFailed)
                    sendEffect(GuestFeedbackRequestEffect.ShowToast(error.message))
                    sendEffect(GuestFeedbackRequestEffect.NavigateBack)
                }

                else -> {
                    handleCommonError(error)
                }
            }
        }

        /**
         * 서버에는 이미 활성 공유 링크가 있는 상태(생성 API 는 충돌 시 기존 token 을 돌려주지
         * 않음). 이 기기에 그 링크를 만들 때 저장해둔 실제 token 이 남아있다면 그걸로 딥링크를
         * 다시 만들어 방금 생성에 성공한 것과 똑같이 보여준다. 로컬에 token 이 전혀 없으면(다른
         * 기기에서 만들었거나 앱 데이터가 지워진 경우) 재구성할 수 없으므로 기존처럼 종료 가능
         * 상태로만 맞추고 안내한다.
         */
        private suspend fun handleAlreadyExistsFailure(error: FeedbackShareAlreadyExistsException) {
            val savedToken = getSavedFeedbackShareToken(sessionId)
            currentCoroutineContext().ensureActive()
            if (savedToken != null) {
                // 아래는 항상 성공한다(동적 링크 생성 실패 시 원시 딥링크로 self-heal).
                val shareLink = createFeedbackShareDynamicLink(savedToken).getOrThrow()
                currentCoroutineContext().ensureActive()
                context.dispatch(GuestFeedbackRequestMutation.ShareCreated(shareLink))
            } else {
                context.dispatch(GuestFeedbackRequestMutation.ExistingShareFound)
                sendEffect(GuestFeedbackRequestEffect.ShowToast(error.message))
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
                        sendEffect(GuestFeedbackRequestEffect.ShowToast("피드백 요청을 종료했어요."))
                        sendEffect(GuestFeedbackRequestEffect.NavigateBack)
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

        private fun copyLink() {
            val link = state.value.shareLink ?: return
            sendEffect(GuestFeedbackRequestEffect.CopyToClipboard(link))
            context.dispatch(GuestFeedbackRequestMutation.LinkCopied)
            context.launch {
                delay(COPIED_NOTICE_DURATION_MS)
                sendEffect(GuestFeedbackRequestEffect.NavigateBack)
            }
        }
    }

private sealed interface GuestFeedbackRequestMutation {
    data object ShareModalDismissed : GuestFeedbackRequestMutation

    data class SavedShareChecked(
        val exists: Boolean,
    ) : GuestFeedbackRequestMutation

    data class AxisToggled(
        val axis: GuestFeedbackAxisCode,
    ) : GuestFeedbackRequestMutation

    data object SubmissionStarted : GuestFeedbackRequestMutation

    data object SubmissionFailed : GuestFeedbackRequestMutation

    data class ShareCreated(
        val link: String,
    ) : GuestFeedbackRequestMutation

    data object ExistingShareFound : GuestFeedbackRequestMutation

    data object ShareEnded : GuestFeedbackRequestMutation

    data object LinkCopied : GuestFeedbackRequestMutation
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

            is GuestFeedbackRequestMutation.SavedShareChecked -> {
                state.copy(hasActiveShare = mutation.exists)
            }

            is GuestFeedbackRequestMutation.AxisToggled -> {
                val axes = state.selectedAxes
                val next =
                    if (mutation.axis in axes) {
                        if (axes.size <= 1) axes else axes - mutation.axis
                    } else {
                        axes + mutation.axis
                    }
                state.copy(selectedAxes = next)
            }

            GuestFeedbackRequestMutation.SubmissionStarted -> {
                state.copy(submitting = true)
            }

            GuestFeedbackRequestMutation.SubmissionFailed -> {
                state.copy(submitting = false)
            }

            is GuestFeedbackRequestMutation.ShareCreated -> {
                state.copy(
                    submitting = false,
                    shareLink = mutation.link,
                    hasActiveShare = true,
                )
            }

            GuestFeedbackRequestMutation.ExistingShareFound -> {
                state.copy(submitting = false, hasActiveShare = true)
            }

            GuestFeedbackRequestMutation.ShareEnded -> {
                state.copy(submitting = false, hasActiveShare = false)
            }

            GuestFeedbackRequestMutation.LinkCopied -> {
                state.copy(linkCopied = true)
            }
        }
}
