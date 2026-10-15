package com.dminus14.app.feature.feedback.onboarding

import androidx.lifecycle.ViewModelStore
import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.core.common.modal.globalModalEvents
import com.dminus14.app.domain.usecase.EnterGuestFeedbackUseCase
import com.dminus14.app.feature.feedback.FakeGuestFeedbackRepository
import com.dminus14.app.feature.feedback.MainDispatcherRule
import com.dminus14.app.feature.feedback.api.FeedbackOnboarding
import com.dminus14.app.feature.feedback.openEntry
import com.dminus14.app.feature.feedback.session.GuestFeedbackFlowSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackOnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `열린 공유를 한 번 조회하고 민감한 진입 데이터를 메모리 세션에 보관한다`() =
        runTest {
            val repository = FakeGuestFeedbackRepository()
            val session = GuestFeedbackFlowSession()
            val viewModel =
                FeedbackOnboardingViewModel(EnterGuestFeedbackUseCase(repository), session)

            viewModel.onIntent(FeedbackOnboardingIntent.Load("synthetic-token"))
            viewModel.onIntent(FeedbackOnboardingIntent.Load("synthetic-token"))
            runCurrent()

            assertEquals(1, repository.enterCount)
            assertEquals("합성 요청자", viewModel.state.value.requesterName)
            assertEquals(FeedbackOnboardingLoadState.Ready, viewModel.state.value.loadState)
            assertEquals(openEntry().videoUrl, session.snapshot()?.videoUrl)
        }

    @Test
    fun `유효한 별칭만 확정해 세션에 저장하고 평가 준비 효과를 발행한다`() =
        runTest {
            val session = GuestFeedbackFlowSession().apply { start("synthetic-token", openEntry()) }
            val viewModel =
                FeedbackOnboardingViewModel(
                    EnterGuestFeedbackUseCase(FakeGuestFeedbackRepository()),
                    session,
                )
            val effect = async { viewModel.effect.first() }
            viewModel.onIntent(FeedbackOnboardingIntent.Load("synthetic-token"))
            runCurrent()

            viewModel.onIntent(FeedbackOnboardingIntent.NicknameChanged("  합성 지인  "))
            assertTrue(viewModel.state.value.canContinue)
            viewModel.onIntent(FeedbackOnboardingIntent.NicknameConfirmed)
            runCurrent()

            assertEquals("합성 지인", session.snapshot()?.nickname)
            assertEquals(FeedbackOnboardingEffect.FeedbackReady, effect.await())
        }

    @Test
    fun `빈 값과 줄바꿈과 열두 자 초과 별칭은 다음 진행을 차단한다`() {
        listOf("", "합성\n지인", "\n합성 지인", "합성 지인\r", "가".repeat(13)).forEach { nickname ->
            val state = FeedbackOnboardingState(nickname = nickname)
            assertFalse(state.canContinue)
        }
        assertTrue(FeedbackOnboardingState(nickname = " 한 ").canContinue)
        assertTrue(FeedbackOnboardingState(nickname = "가".repeat(12)).canContinue)
    }

    @Test
    fun `시작 경로 문자열은 공유 토큰 원문을 노출하지 않는다`() {
        val route = FeedbackOnboarding("sensitive-synthetic-token")

        assertFalse(route.toString().contains("sensitive-synthetic-token"))
        assertTrue(route.toString().contains("redacted"))
    }

    @Test
    fun `첫 뒤로가기는 종료하지 않고 2초 안의 두 번째 뒤로가기만 종료한다`() {
        val firstBackPressedAt = 1_000L

        assertFalse(shouldExitOnBack(firstBackPressedAt = null, now = firstBackPressedAt))
        assertTrue(shouldExitOnBack(firstBackPressedAt, now = firstBackPressedAt + 2_000L))
        assertFalse(shouldExitOnBack(firstBackPressedAt, now = firstBackPressedAt + 2_001L))
    }

    @Test
    fun `진입 준비 전과 로딩 중에는 시작과 별칭 확정을 차단한다`() =
        runTest {
            val enterGate = CompletableDeferred<Unit>()
            val session = GuestFeedbackFlowSession()
            val viewModel =
                FeedbackOnboardingViewModel(
                    EnterGuestFeedbackUseCase(FakeGuestFeedbackRepository(enterGate = enterGate)),
                    session,
                )

            viewModel.onIntent(FeedbackOnboardingIntent.StartClicked)
            viewModel.onIntent(FeedbackOnboardingIntent.NicknameChanged("합성 지인"))
            viewModel.onIntent(FeedbackOnboardingIntent.NicknameConfirmed)
            assertFalse(viewModel.state.value.isNameEditorVisible)
            assertEquals(null, session.snapshot())

            viewModel.onIntent(FeedbackOnboardingIntent.Load("synthetic-token"))
            runCurrent()
            assertEquals(FeedbackOnboardingLoadState.Loading, viewModel.state.value.loadState)
            viewModel.onIntent(FeedbackOnboardingIntent.StartClicked)
            viewModel.onIntent(FeedbackOnboardingIntent.NicknameConfirmed)
            assertFalse(viewModel.state.value.isNameEditorVisible)
            assertEquals(null, session.snapshot())

            enterGate.complete(Unit)
            runCurrent()
        }

    @Test
    fun `알 수 없는 진입 오류는 토스트와 닫을 수 없는 종료 모달을 거쳐 한 번 종료한다`() =
        runTest {
            val session = GuestFeedbackFlowSession()
            val repository =
                FakeGuestFeedbackRepository(failure = IllegalStateException("synthetic"))
            val viewModel =
                FeedbackOnboardingViewModel(EnterGuestFeedbackUseCase(repository), session)
            val globalEvent = async { GlobalErrorHandler.events.first() }
            val modalEvent = async { globalModalEvents.first() }
            val exitEffect = async { viewModel.effect.first() }
            runCurrent()

            viewModel.onIntent(FeedbackOnboardingIntent.Load("synthetic-token"))
            runCurrent()

            assertEquals(GlobalAppEvent.ShowUnknownError, globalEvent.await().event)
            assertEquals(FeedbackOnboardingLoadState.Failed, viewModel.state.value.loadState)
            assertEquals(null, session.snapshot())
            viewModel.onIntent(FeedbackOnboardingIntent.StartClicked)
            viewModel.onIntent(FeedbackOnboardingIntent.NicknameChanged("합성 지인"))
            viewModel.onIntent(FeedbackOnboardingIntent.NicknameConfirmed)
            assertFalse(viewModel.state.value.isNameEditorVisible)
            assertEquals(null, session.snapshot())
            val requestEvent = modalEvent.await()
            assertEquals("오류가 발생했어요", requestEvent.request.title)
            assertEquals(
                "앱을 종료한 뒤 링크를 다시 열어주세요.",
                requestEvent.request.message,
            )
            assertEquals("종료하기", requestEvent.request.confirmText)
            assertEquals(null, requestEvent.request.cancelText)
            assertFalse(requestEvent.request.dismissible)
            assertFalse(exitEffect.isCompleted)

            requestEvent.complete(GlobalModalResult.Confirm)
            runCurrent()

            assertEquals(FeedbackOnboardingEffect.ExitRequested, exitEffect.await())
        }

    @Test
    fun `종료된 화면의 조회 결과는 새 세션이나 실패 상태를 만들지 않는다`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeGuestFeedbackRepository(enterGate = gate)
            val session = GuestFeedbackFlowSession()
            val enterFeedback = EnterGuestFeedbackUseCase(repository)
            val viewModel = FeedbackOnboardingViewModel(enterFeedback, session)
            val store = ViewModelStore().apply { put("entry", viewModel) }
            viewModel.onIntent(FeedbackOnboardingIntent.Load("synthetic-token"))
            runCurrent()

            store.clear()
            gate.complete(Unit)
            runCurrent()

            assertEquals(FeedbackOnboardingLoadState.Loading, viewModel.state.value.loadState)
            assertEquals(null, session.snapshot())
            assertEquals(1, repository.enterCount)
        }
}
