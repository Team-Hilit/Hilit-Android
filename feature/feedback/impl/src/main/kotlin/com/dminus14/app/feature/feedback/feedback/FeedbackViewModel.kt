package com.dminus14.app.feature.feedback.feedback

import com.dminus14.app.core.common.modal.GlobalModalRequest
import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.core.common.modal.showGlobalModal
import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.feature.feedback.session.CommentEditorState
import com.dminus14.app.feature.feedback.session.GuestFeedbackCommentEditor
import com.dminus14.app.feature.feedback.session.GuestFeedbackFlowData
import com.dminus14.app.feature.feedback.session.GuestFeedbackFlowSession
import com.dminus14.app.feature.feedback.session.MAX_COMMENT_LENGTH
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FeedbackViewModel
    @Inject
    constructor(
        private val session: GuestFeedbackFlowSession,
    ) : MviViewModel<FeedbackIntent, FeedbackState, FeedbackEffect>(FeedbackState()) {
        private val context = createContext(FeedbackReducer)
        private val commentEditor = GuestFeedbackCommentEditor(session)

        override fun onIntent(intent: FeedbackIntent) {
            when (intent) {
                FeedbackIntent.LoadSession -> {
                    loadSession()
                }

                is FeedbackIntent.AxisSelected -> {
                    context.dispatch(FeedbackMutation.AxisSelected(intent.axis))
                }

                is FeedbackIntent.RatingSelected -> {
                    selectRating(intent.axis, intent.level)
                }

                is FeedbackIntent.CommentEditorClicked -> {
                    openCommentEditor(intent.axis)
                }

                is FeedbackIntent.CommentChanged -> {
                    context.dispatch(FeedbackMutation.CommentChanged(intent.value))
                }

                FeedbackIntent.CommentConfirmed -> {
                    confirmComment()
                }

                FeedbackIntent.CommentDismissed -> {
                    dismissComment()
                }

                FeedbackIntent.VideoExpanded -> {
                    context.dispatch(FeedbackMutation.VideoExpanded)
                }

                FeedbackIntent.VideoIntroCompleted -> {
                    context.dispatch(FeedbackMutation.VideoIntroCompleted)
                }

                FeedbackIntent.VideoPlaybackFailed -> {
                    handlePlaybackFailure()
                }

                FeedbackIntent.ReviewClicked -> {
                    review()
                }

                FeedbackIntent.BackPressed -> {
                    confirmExit()
                }
            }
        }

        private fun loadSession() {
            if (state.value.hasLoaded) return
            val data =
                session.snapshot() ?: run {
                    context.sendEffect(FeedbackEffect.ExitRequested)
                    return
                }

            if (data.videoUrl.isBlank()) {
                handlePlaybackFailure()
            } else {
                context.dispatch(
                    FeedbackMutation.SessionLoaded(
                        data.requesterName,
                        data.videoUrl,
                        data.toAxisUiModels(),
                    ),
                )
            }
        }

        private fun selectRating(
            axis: GuestFeedbackAxisCode,
            level: Int,
        ) {
            if (level !in MIN_RATING_LEVEL..MAX_RATING_LEVEL || state.value.isPlaybackBlocked) {
                return
            }
            session.updateLevel(axis, level)
            context.dispatch(FeedbackMutation.RatingAccepted(axis, level))
        }

        private fun openCommentEditor(axis: GuestFeedbackAxisCode) {
            val editorState =
                commentEditor.open(
                    axes = state.value.axes,
                    axis = axis,
                    getCode = { it.code },
                    getComment = { it.comment },
                ) ?: return
            context.dispatch(FeedbackMutation.EditorOpened(editorState))
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
            context.dispatch(FeedbackMutation.CommentConfirmed(newAxes, editorState))
        }

        private fun dismissComment() {
            val editorState = commentEditor.dismiss()
            context.dispatch(FeedbackMutation.EditorDismissed(editorState))
        }

        private fun review() {
            if (state.value.canReview && !state.value.isPlaybackBlocked) {
                context.sendEffect(FeedbackEffect.ReviewReady)
            }
        }

        private fun confirmExit() {
            if (state.value.isPlaybackBlocked) return

            context.launch {
                val result =
                    showGlobalModal(
                        GlobalModalRequest(
                            title = "피드백을 종료하시겠습니까?",
                            message = "",
                            confirmText = "종료하기",
                            cancelText = "계속 작성",
                            dismissible = true,
                        ),
                    )
                if (result == GlobalModalResult.Confirm) {
                    session.clear()
                    context.sendEffect(FeedbackEffect.ExitRequested)
                }
            }
        }

        private fun handlePlaybackFailure() {
            if (state.value.isPlaybackBlocked) return
            val data = session.snapshot()
            val axes = data?.toAxisUiModels().orEmpty()
            context.dispatch(FeedbackMutation.PlaybackBlocked(axes))
            context.launch {
                val result =
                    showGlobalModal(
                        GlobalModalRequest(
                            title = "영상을 재생할 수 없어요",
                            message = "영상 재생 중 문제가 발생했어요. 앱을 종료한 뒤 다시 시도해주세요.",
                            confirmText = "종료하기",
                            dismissible = false,
                        ),
                    )
                if (result == GlobalModalResult.Confirm) {
                    session.clear()
                    context.sendEffect(FeedbackEffect.ExitRequested)
                }
            }
        }
    }

internal val GuestFeedbackAxisCode.title: String
    get() =
        when (this) {
            GuestFeedbackAxisCode.GAZE -> "시선"
            GuestFeedbackAxisCode.EXPRESSION -> "표정"
            GuestFeedbackAxisCode.POSTURE -> "자세"
            GuestFeedbackAxisCode.GESTURE -> "손동작"
            GuestFeedbackAxisCode.VOICE -> "목소리"
        }

private const val MIN_RATING_LEVEL = 1
private const val MAX_RATING_LEVEL = 4

private sealed interface FeedbackMutation {
    data class AxisSelected(
        val axis: GuestFeedbackAxisCode,
    ) : FeedbackMutation

    data class CommentChanged(
        val value: String,
    ) : FeedbackMutation

    data object VideoExpanded : FeedbackMutation

    data object VideoIntroCompleted : FeedbackMutation

    data class SessionLoaded(
        val name: String,
        val videoUrl: String,
        val axes: List<FeedbackAxisUiModel>,
    ) : FeedbackMutation

    data class RatingAccepted(
        val axis: GuestFeedbackAxisCode,
        val level: Int,
    ) : FeedbackMutation

    data class EditorOpened(
        val editorState: CommentEditorState,
    ) : FeedbackMutation

    data class CommentConfirmed(
        val axes: List<FeedbackAxisUiModel>,
        val editorState: CommentEditorState,
    ) : FeedbackMutation

    data class EditorDismissed(
        val editorState: CommentEditorState,
    ) : FeedbackMutation

    data class PlaybackBlocked(
        val axes: List<FeedbackAxisUiModel>,
    ) : FeedbackMutation
}

/** 화면 상태 계산은 순수하며 세션 기록과 Modal 호출은 ViewModel에서 수행한다. */
private object FeedbackReducer : MviReducer<FeedbackState, FeedbackMutation> {
    override fun reduce(
        state: FeedbackState,
        mutation: FeedbackMutation,
    ): FeedbackState =
        with(state) {
            when (mutation) {
                is FeedbackMutation.AxisSelected -> {
                    copy(
                        selectedAxis = mutation.axis,
                        isVideoExpanded = false,
                    )
                }

                is FeedbackMutation.CommentChanged -> {
                    copy(
                        editingValue = mutation.value.take(MAX_COMMENT_LENGTH),
                    )
                }

                FeedbackMutation.VideoExpanded -> {
                    copy(isVideoExpanded = true)
                }

                FeedbackMutation.VideoIntroCompleted -> {
                    copy(isVideoIntroVisible = false)
                }

                is FeedbackMutation.SessionLoaded -> {
                    copy(
                        requesterName = mutation.name,
                        videoUrl = mutation.videoUrl,
                        axes = mutation.axes,
                        hasLoaded = true,
                    )
                }

                is FeedbackMutation.RatingAccepted -> {
                    copy(axes = ratedAxes(axes, mutation))
                }

                is FeedbackMutation.EditorOpened -> {
                    withEditorState(mutation.editorState)
                }

                is FeedbackMutation.CommentConfirmed -> {
                    val updated = copy(axes = mutation.axes)
                    updated.withEditorState(mutation.editorState)
                }

                is FeedbackMutation.EditorDismissed -> {
                    withEditorState(mutation.editorState)
                }

                is FeedbackMutation.PlaybackBlocked -> {
                    copy(
                        isPlaybackBlocked = true,
                        axes = mutation.axes,
                    )
                }
            }
        }

    private fun ratedAxes(
        axes: List<FeedbackAxisUiModel>,
        mutation: FeedbackMutation.RatingAccepted,
    ): List<FeedbackAxisUiModel> =
        axes.map { item ->
            if (item.code == mutation.axis) {
                item.copy(level = mutation.level)
            } else {
                item
            }
        }
}

private fun GuestFeedbackFlowData.toAxisUiModels(): List<FeedbackAxisUiModel> =
    axes.map { axis ->
        val draft = ratings[axis.code]
        FeedbackAxisUiModel(
            axis.code,
            axis.code.title,
            draft?.level,
            draft?.comment.orEmpty(),
        )
    }
