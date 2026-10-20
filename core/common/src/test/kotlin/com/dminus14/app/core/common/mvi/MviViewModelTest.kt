package com.dminus14.app.core.common.mvi

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MviViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `연속 변경은 최신 상태에 순서대로 반영되고 Effect보다 먼저 보인다`() =
        runTest(dispatcher) {
            val viewModel = CounterViewModel()
            viewModel.onIntent(CounterIntent.Increment)
            viewModel.onIntent(CounterIntent.Increment)

            assertEquals(2, viewModel.state.value.count)
            assertEquals(CounterEffect.Changed(1), viewModel.effect.first())
            assertEquals(CounterEffect.Changed(2), viewModel.effect.first())
        }

    @Test
    fun `ViewModel을 제거하면 Handler의 자식 작업도 취소된다`() =
        runTest(dispatcher) {
            val viewModel = CounterViewModel()
            val store = ViewModelStore()
            store.put("counter", viewModel)
            var completed = false
            val job =
                viewModel.context.launch {
                    try {
                        awaitCancellation()
                    } finally {
                        completed = true
                    }
                }
            runCurrent()
            assertTrue(job.isActive)

            store.clear()
            runCurrent()

            assertTrue(job.isCancelled)
            assertTrue(completed)
            assertFalse(job.isActive)
        }

    @Test(expected = IllegalStateException::class)
    fun `한 화면은 실행 문맥을 중복 생성할 수 없다`() {
        CounterViewModel().createAnotherContext()
    }

    private sealed interface CounterIntent : MviIntent {
        data object Increment : CounterIntent
    }

    private data class CounterState(
        val count: Int = 0,
    ) : MviState

    private sealed interface CounterEffect : MviEffect {
        data class Changed(
            val count: Int,
        ) : CounterEffect
    }

    private class CounterViewModel :
        MviViewModel<CounterIntent, CounterState, CounterEffect>(CounterState()) {
        val context =
            createContext(
                MviReducer<CounterState, Int> { state, amount ->
                    state.copy(
                        count =
                            state.count + amount,
                    )
                },
            )

        override fun onIntent(intent: CounterIntent) {
            when (intent) {
                CounterIntent.Increment -> {
                    context.dispatch(1)
                    context.sendEffect(CounterEffect.Changed(state.value.count))
                }
            }
        }

        fun createAnotherContext() =
            createContext(MviReducer<CounterState, Int> { state, _ -> state })
    }
}
