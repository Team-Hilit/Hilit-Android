package com.dminus14.app.feature.onboarding

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.InterviewSessionResult
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.usecase.GetInterviewSessionUseCase
import com.dminus14.app.domain.usecase.MakeInterviewSessionUseCase
import com.dminus14.app.domain.usecase.SaveInterviewSessionProgressUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnBoardingSessionPreparationHandlerTest {
    @Test
    fun `세션 생성 중과 완료 후 반복 요청은 새 세션을 만들지 않는다`() =
        runTest {
            val creation = CompletableDeferred<Result<InterviewSessionResult>>()
            val makeSession = mockk<MakeInterviewSessionUseCase>()
            val getSession = mockk<GetInterviewSessionUseCase>()
            val saveProgress = mockk<SaveInterviewSessionProgressUseCase>()
            coEvery { makeSession(any()) } coAnswers { creation.await() }
            coEvery { saveProgress(any()) } returns Unit
            coEvery { getSession(any()) } returns
                Result.success(
                    InterviewSessionStatus(InterviewSessionStatusType.READY, null, null),
                )
            val context = TestContext(this)
            val handler =
                OnBoardingSessionPreparationHandler(context, makeSession, getSession, saveProgress)
            repeat(2) { handler.startPreload("가상 직무", 0, "synthetic-portfolio", null, null) }
            runCurrent()
            coVerify(exactly = 1) { makeSession(any()) }
            creation.complete(
                Result.success(
                    InterviewSessionResult(1, InterviewSessionStatusType.PROCESSING, "/status"),
                ),
            )
            advanceUntilIdle()
            handler.startPreload("가상 직무", 0, "synthetic-portfolio", null, null)
            advanceUntilIdle()
            coVerify(exactly = 1) { makeSession(any()) }
            coVerify(exactly = 1) { saveProgress(1) }
            assertEquals(listOf(OnBoardingInterviewEffect.NavigateToResult(1)), context.effects)
        }

    private class TestContext(
        private val scope: CoroutineScope,
    ) : MviContext<OnBoardingInterviewState, OnBoardingMutation, OnBoardingInterviewEffect> {
        override val state =
            MutableStateFlow(OnBoardingInterviewState(step = OnBoardingInterviewStep.Preload))
        val effects = mutableListOf<OnBoardingInterviewEffect>()

        override fun dispatch(mutation: OnBoardingMutation) {
            state.value = OnBoardingReducer.reduce(state.value, mutation)
        }

        override fun sendEffect(effect: OnBoardingInterviewEffect) {
            effects += effect
        }

        override fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
    }
}
