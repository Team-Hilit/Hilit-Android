package com.dminus14.app.feature.onboarding

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.JdValidationResult
import com.dminus14.app.domain.usecase.ValidateJdUrlUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnBoardingJobDescriptionHandlerTest {
    @Test
    fun `같은 링크를 다시 검증해도 취소된 이전 요청 결과는 새 검증을 완료하지 않는다`() =
        runTest {
            val oldResult = CompletableDeferred<Result<JdValidationResult>>()
            val newResult = CompletableDeferred<Result<JdValidationResult>>()
            val useCase = mockk<ValidateJdUrlUseCase>()
            var requests = 0
            coEvery { useCase(any()) } coAnswers {
                val result = if (requests++ == 0) oldResult else newResult
                withContext(NonCancellable) { result.await() }
            }
            val context = TestContext(this)
            var advanced = 0
            val handler = OnBoardingJobDescriptionHandler(context, useCase) { advanced += 1 }
            handler.onJobDescriptionLinkChange("https://example.invalid/job")
            handler.submitJobDescription()
            runCurrent()
            handler.onJobDescriptionLinkChange("https://example.invalid/job")
            handler.submitJobDescription()
            runCurrent()
            oldResult.complete(
                Result.success(JdValidationResult(valid = true, reason = null, message = null)),
            )
            runCurrent()
            assertEquals(0, advanced)
            assertEquals(JdLinkStatus.Validating, context.state.value.jdLinkStatus)
            newResult.complete(
                Result.success(JdValidationResult(valid = true, reason = null, message = null)),
            )
            runCurrent()
            assertEquals(1, advanced)
            assertEquals(JdLinkStatus.Valid, context.state.value.jdLinkStatus)
        }

    private class TestContext(
        private val scope: CoroutineScope,
    ) : MviContext<OnBoardingInterviewState, OnBoardingMutation, OnBoardingInterviewEffect> {
        override val state = MutableStateFlow(OnBoardingInterviewState())

        override fun dispatch(mutation: OnBoardingMutation) {
            state.value = OnBoardingReducer.reduce(state.value, mutation)
        }

        override fun sendEffect(effect: OnBoardingInterviewEffect) = Unit

        override fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
    }
}
