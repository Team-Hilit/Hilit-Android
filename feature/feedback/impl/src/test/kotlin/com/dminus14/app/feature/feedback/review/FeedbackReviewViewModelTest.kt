package com.dminus14.app.feature.feedback.review

import androidx.lifecycle.ViewModelStore
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.usecase.SubmitGuestFeedbackUseCase
import com.dminus14.app.feature.feedback.FakeGuestFeedbackRepository
import com.dminus14.app.feature.feedback.MainDispatcherRule
import com.dminus14.app.feature.feedback.openEntry
import com.dminus14.app.feature.feedback.session.GuestFeedbackFlowSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackReviewViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `검토 화면은 모든 축과 선택 코멘트를 세션에서 변환한다`() {
        val viewModel =
            FeedbackReviewViewModel(
                SubmitGuestFeedbackUseCase(FakeGuestFeedbackRepository()),
                session(),
            )

        viewModel.onIntent(FeedbackReviewIntent.LoadSession)

        assertEquals(
            listOf("잘 맞춤", "안 들림"),
            viewModel.state.value.axes
                .map { it.levelLabel },
        )
        assertEquals(
            "합성 코멘트",
            viewModel.state.value.axes
                .first()
                .comment,
        )
    }

    @Test
    fun `검토 코멘트도 다음에서만 저장하고 평가 단계는 변경하지 않는다`() {
        val session = session()
        val viewModel =
            FeedbackReviewViewModel(
                SubmitGuestFeedbackUseCase(FakeGuestFeedbackRepository()),
                session,
            )
        viewModel.onIntent(FeedbackReviewIntent.LoadSession)

        viewModel.onIntent(FeedbackReviewIntent.EditCommentClicked(GuestFeedbackAxisCode.GAZE))
        viewModel.onIntent(FeedbackReviewIntent.CommentChanged("수정한 합성 코멘트"))
        viewModel.onIntent(FeedbackReviewIntent.CommentConfirmed)

        assertEquals(
            "수정한 합성 코멘트",
            session
                .snapshot()
                ?.ratings
                ?.get(GuestFeedbackAxisCode.GAZE)
                ?.comment,
        )
        assertEquals(
            4,
            session
                .snapshot()
                ?.ratings
                ?.get(GuestFeedbackAxisCode.GAZE)
                ?.level,
        )
    }

    @Test
    fun `제출 성공은 한 번 호출하고 세션 정리와 토스트와 완료 효과 순서를 지킨다`() =
        runTest {
            val repository = FakeGuestFeedbackRepository()
            val session = session()
            val viewModel = FeedbackReviewViewModel(SubmitGuestFeedbackUseCase(repository), session)
            val effects = mutableListOf<FeedbackReviewEffect>()
            val collectJob = backgroundScope.launch { viewModel.effect.take(2).toList(effects) }
            viewModel.onIntent(FeedbackReviewIntent.LoadSession)

            viewModel.onIntent(FeedbackReviewIntent.SubmitConfirmed)
            viewModel.onIntent(FeedbackReviewIntent.SubmitConfirmed)
            assertTrue(viewModel.state.value.isSubmitting)
            runCurrent()

            assertEquals(1, repository.submitCount)
            assertFalse(viewModel.state.value.isSubmitting)
            assertNull(session.snapshot())
            assertEquals(
                listOf(
                    FeedbackReviewEffect.ShowToast("피드백을 제출했어요."),
                    FeedbackReviewEffect.SubmissionCompleted,
                ),
                effects,
            )
            collectJob.cancel()
        }

    @Test
    fun `제출 중에는 이탈과 코멘트 수정과 중복 제출을 모두 무시한다`() =
        runTest {
            val submitGate = CompletableDeferred<Unit>()
            val repository = FakeGuestFeedbackRepository(submitGate = submitGate)
            val session = session()
            val viewModel = FeedbackReviewViewModel(SubmitGuestFeedbackUseCase(repository), session)
            val effects = mutableListOf<FeedbackReviewEffect>()
            val collectJob = backgroundScope.launch { viewModel.effect.toList(effects) }
            viewModel.onIntent(FeedbackReviewIntent.LoadSession)

            viewModel.onIntent(FeedbackReviewIntent.SubmitConfirmed)
            runCurrent()
            viewModel.onIntent(FeedbackReviewIntent.ReplayVideoClicked)
            viewModel.onIntent(
                FeedbackReviewIntent.EditCommentClicked(GuestFeedbackAxisCode.GAZE),
            )
            viewModel.onIntent(FeedbackReviewIntent.CommentChanged("변경하면 안 되는 코멘트"))
            viewModel.onIntent(FeedbackReviewIntent.CommentConfirmed)
            viewModel.onIntent(FeedbackReviewIntent.SubmitConfirmed)
            runCurrent()

            assertTrue(viewModel.state.value.isSubmitting)
            assertFalse(viewModel.state.value.isCommentEditorVisible)
            assertEquals(1, repository.submitCount)
            assertEquals(
                "합성 코멘트",
                session
                    .snapshot()
                    ?.ratings
                    ?.get(GuestFeedbackAxisCode.GAZE)
                    ?.comment,
            )
            assertTrue(effects.isEmpty())

            submitGate.complete(Unit)
            runCurrent()

            assertFalse(viewModel.state.value.isSubmitting)
            assertEquals(1, repository.submitCount)
            collectJob.cancel()
        }

    @Test
    fun `종료된 화면의 제출 취소는 세션을 지우거나 완료 효과를 발행하지 않는다`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeGuestFeedbackRepository(submitGate = gate)
            val session = session()
            val viewModel = FeedbackReviewViewModel(SubmitGuestFeedbackUseCase(repository), session)
            val store = ViewModelStore().apply { put("review", viewModel) }
            val effects = mutableListOf<FeedbackReviewEffect>()
            val collectJob = backgroundScope.launch { viewModel.effect.toList(effects) }
            viewModel.onIntent(FeedbackReviewIntent.LoadSession)
            viewModel.onIntent(FeedbackReviewIntent.SubmitConfirmed)
            runCurrent()

            store.clear()
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, repository.submitCount)
            assertTrue(session.snapshot() != null)
            assertTrue(effects.isEmpty())
            collectJob.cancel()
        }

    private fun session() =
        GuestFeedbackFlowSession().apply {
            start("synthetic-token", openEntry())
            setNickname("합성 지인")
            updateLevel(GuestFeedbackAxisCode.GAZE, 4)
            updateComment(GuestFeedbackAxisCode.GAZE, "합성 코멘트")
            updateLevel(GuestFeedbackAxisCode.VOICE, 1)
        }
}
