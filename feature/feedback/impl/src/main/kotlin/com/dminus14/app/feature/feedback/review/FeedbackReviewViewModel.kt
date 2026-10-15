package com.dminus14.app.feature.feedback.review

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.modal.GlobalModalRequest
import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.core.common.modal.showGlobalModal
import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.GuestFeedbackAlreadySubmittedException
import com.dminus14.app.domain.exception.GuestFeedbackCapacityFullException
import com.dminus14.app.domain.exception.GuestFeedbackRequestException
import com.dminus14.app.domain.exception.GuestFeedbackShareClosedException
import com.dminus14.app.domain.exception.GuestFeedbackValidationException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.model.GuestFeedbackRating
import com.dminus14.app.domain.model.GuestFeedbackSubmission
import com.dminus14.app.domain.usecase.SubmitGuestFeedbackUseCase
import com.dminus14.app.feature.feedback.feedback.ratingOptions
import com.dminus14.app.feature.feedback.feedback.title
import com.dminus14.app.feature.feedback.session.CommentEditorState
import com.dminus14.app.feature.feedback.session.GuestFeedbackCommentEditor
import com.dminus14.app.feature.feedback.session.GuestFeedbackFlowSession
import com.dminus14.app.feature.feedback.session.MAX_COMMENT_LENGTH
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

@HiltViewModel
class FeedbackReviewViewModel
    @Inject
    constructor(
        private val submitGuestFeedbackUseCase: SubmitGuestFeedbackUseCase,
        private val session: GuestFeedbackFlowSession,
    ) : MviViewModel<FeedbackReviewIntent, FeedbackReviewState, FeedbackReviewEffect>(
            FeedbackReviewState(),
        ) {
        private val context = createContext(FeedbackReviewReducer)
        private val commentEditor = GuestFeedbackCommentEditor(session)
        private var isConfirmationPending = false

        override fun onIntent(intent: FeedbackReviewIntent) {
            if (state.value.isSubmitting && intent != FeedbackReviewIntent.LoadSession) return

            when (intent) {
                FeedbackReviewIntent.LoadSession -> {
                    loadSession()
                }

                FeedbackReviewIntent.ReplayVideoClicked -> {
                    context.sendEffect(
                        FeedbackReviewEffect.ReplayRequested,
                    )
                }

                is FeedbackReviewIntent.EditCommentClicked -> {
                    openCommentEditor(intent.axis)
                }

                is FeedbackReviewIntent.CommentChanged -> {
                    context.dispatch(FeedbackReviewMutation.CommentChanged(intent.value))
                }

                FeedbackReviewIntent.CommentConfirmed -> {
                    confirmComment()
                }

                FeedbackReviewIntent.CommentDismissed -> {
                    dismissComment()
                }

                FeedbackReviewIntent.SubmitClicked -> {
                    showSubmitConfirmation()
                }

                FeedbackReviewIntent.SubmitConfirmed -> {
                    submit()
                }
            }
        }

        private fun loadSession() {
            if (state.value.hasLoaded) return
            val data =
                session.snapshot() ?: run {
                    context.sendEffect(FeedbackReviewEffect.ExitRequested)
                    return
                }
            val axes =
                data.axes.mapNotNull { axis ->
                    val draft = data.ratings[axis.code]
                    val level = draft?.level ?: return@mapNotNull null
                    FeedbackReviewAxisUiModel(
                        code = axis.code,
                        title = axis.code.title,
                        level = level,
                        levelLabel =
                            axis.code
                                .ratingOptions()
                                .firstOrNull { it.level == level }
                                ?.label ?: return@mapNotNull null,
                        comment = draft.comment,
                    )
                }
            context.dispatch(
                FeedbackReviewMutation.SessionLoaded(data.requesterName, data.nickname, axes),
            )
        }

        private fun openCommentEditor(axis: GuestFeedbackAxisCode) {
            val editorState =
                commentEditor.open(
                    axes = state.value.axes,
                    axis = axis,
                    getCode = { it.code },
                    getComment = { it.comment },
                ) ?: return
            context.dispatch(FeedbackReviewMutation.EditorOpened(editorState))
        }

        private fun confirmComment() {
            val (newAxes, editorState) =
                commentEditor.confirm(
                    axes = state.value.axes,
                    editingAxis = state.value.editingAxis,
                    editingValue = state.value.editingValue,
                    getCode = { it.code },
                    updateComment = { item, comment -> item.copy(comment = comment) },
                ) ?: return
            context.dispatch(FeedbackReviewMutation.CommentConfirmed(newAxes, editorState))
        }

        private fun dismissComment() {
            val editorState = commentEditor.dismiss()
            context.dispatch(FeedbackReviewMutation.EditorDismissed(editorState))
        }

        private fun showSubmitConfirmation() {
            if (state.value.isSubmitting || isConfirmationPending) return
            isConfirmationPending = true
            context.launch {
                try {
                    val result =
                        showGlobalModal(
                            GlobalModalRequest(
                                title = "피드백 제출",
                                message = "제출하면 다시 고칠 수 없어요.",
                                confirmText = "제출",
                                cancelText = "취소",
                                dismissible = true,
                            ),
                        )
                    if (result == GlobalModalResult.Confirm) {
                        submit()
                    }
                } finally {
                    isConfirmationPending = false
                }
            }
        }

        private fun submit() {
            if (state.value.isSubmitting) return
            val data =
                session.snapshot() ?: run {
                    context.sendEffect(FeedbackReviewEffect.ExitRequested)
                    return
                }
            val ratings =
                data.axes.mapNotNull { axis ->
                    val draft = data.ratings[axis.code] ?: return@mapNotNull null
                    val level = draft.level ?: return@mapNotNull null
                    GuestFeedbackRating(axis.code, level, draft.comment)
                }

            if (ratings.size != data.axes.size) {
                context.sendEffect(FeedbackReviewEffect.ReplayRequested)
            } else {
                context.dispatch(FeedbackReviewMutation.SubmissionStarted)
                context.launch {
                    submitGuestFeedbackUseCase(
                        token = data.token,
                        axes = data.axes,
                        submission = GuestFeedbackSubmission(data.nickname, ratings),
                    ).also { currentCoroutineContext().ensureActive() }
                        .onSuccess {
                            session.clear()
                            context.dispatch(FeedbackReviewMutation.SubmissionFinished)
                            context.sendEffect(FeedbackReviewEffect.ShowToast("피드백을 제출했어요."))
                            context.sendEffect(FeedbackReviewEffect.SubmissionCompleted)
                        }.onFailure(::handleFailure)
                }
            }
        }

        private fun handleFailure(error: Throwable) {
            if (error is CancellationException) throw error
            context.dispatch(FeedbackReviewMutation.SubmissionFinished)
            context.launch {
                when (error) {
                    is GuestFeedbackValidationException -> {
                        showGlobalModal(
                            GlobalModalRequest(
                                title = "피드백을 제출할 수 없어요",
                                message = "작성한 내용을 확인한 뒤 다시 시도해주세요.",
                                confirmText = "확인",
                                dismissible = true,
                            ),
                        )
                    }

                    is GuestFeedbackRequestException -> {
                        showExitError(
                            "요청 오류",
                            "서버 요청에 실패했습니다. 앱을 재실행하고 다시 시도해주세요.",
                        )
                    }

                    is GuestFeedbackShareClosedException -> {
                        showExitError(
                            "피드백 기간 종료",
                            "피드백 가능한 기간이 지났습니다.",
                        )
                    }

                    is GuestFeedbackCapacityFullException -> {
                        showExitError(
                            "피드백 마감",
                            "최대 피드백 가능 인원을 초과하여 더 이상 피드백을 받을 수 없습니다.",
                        )
                    }

                    is GuestFeedbackAlreadySubmittedException -> {
                        showExitError(
                            "이미 제출한 피드백",
                            "이미 이 기기에서 피드백한 이력이 있습니다. 중복 피드백은 할 수 없습니다.",
                        )
                    }

                    else -> {
                        if (error is NetworkUnavailableException || error is ServerException) {
                            session.clear()
                        }
                        handleCommonError(error)
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

        private suspend fun showExitError(
            title: String,
            message: String,
        ) {
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
                session.clear()
                context.sendEffect(FeedbackReviewEffect.ExitRequested)
            }
        }
    }

private sealed interface FeedbackReviewMutation {
    data class CommentChanged(
        val value: String,
    ) : FeedbackReviewMutation

    data class SessionLoaded(
        val name: String,
        val nickname: String,
        val axes: List<FeedbackReviewAxisUiModel>,
    ) : FeedbackReviewMutation

    data class EditorOpened(
        val editorState: CommentEditorState,
    ) : FeedbackReviewMutation

    data class CommentConfirmed(
        val axes: List<FeedbackReviewAxisUiModel>,
        val editorState: CommentEditorState,
    ) : FeedbackReviewMutation

    data class EditorDismissed(
        val editorState: CommentEditorState,
    ) : FeedbackReviewMutation

    data object SubmissionStarted : FeedbackReviewMutation

    data object SubmissionFinished : FeedbackReviewMutation
}

/** 화면 상태 계산은 순수하며 세션 기록과 Modal 호출은 ViewModel에서 수행한다. */
private object FeedbackReviewReducer : MviReducer<FeedbackReviewState, FeedbackReviewMutation> {
    override fun reduce(
        state: FeedbackReviewState,
        mutation: FeedbackReviewMutation,
    ): FeedbackReviewState =
        with(state) {
            when (mutation) {
                is FeedbackReviewMutation.CommentChanged -> {
                    copy(
                        editingValue = mutation.value.take(MAX_COMMENT_LENGTH),
                    )
                }

                is FeedbackReviewMutation.SessionLoaded -> {
                    copy(
                        requesterName = mutation.name,
                        nickname = mutation.nickname,
                        axes = mutation.axes,
                        hasLoaded = true,
                    )
                }

                is FeedbackReviewMutation.EditorOpened -> {
                    withEditorState(mutation.editorState)
                }

                is FeedbackReviewMutation.CommentConfirmed -> {
                    copy(
                        axes = mutation.axes,
                    ).withEditorState(mutation.editorState)
                }

                is FeedbackReviewMutation.EditorDismissed -> {
                    withEditorState(mutation.editorState)
                }

                FeedbackReviewMutation.SubmissionStarted -> {
                    copy(
                        isSubmitting = true,
                    ).withEditorState(CommentEditorState.Hidden)
                }

                FeedbackReviewMutation.SubmissionFinished -> {
                    copy(isSubmitting = false)
                }
            }
        }
}
