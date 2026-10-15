package com.dminus14.app.core.common.mvi

import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Feature ViewModel의 MVI 보일러플레이트를 담당하는 Base.
 *
 * StateFlow, Effect Channel과 Reducer 실행 문맥을 제공한다.
 * UseCase 호출과 에러 정책은 Feature 내부 ViewModel 또는 Handler가 소유한다.
 *
 * @param initialState 화면 초기 State
 */
abstract class MviViewModel<I : MviIntent, S : MviState, E : MviEffect>(
    initialState: S,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private var contextCreated = false

    /** 화면 이벤트 진입점. Feature에서 `when`으로 분기한다. */
    @MainThread
    abstract fun onIntent(intent: I)

    /** 화면당 한 번 생성하며, 모든 Handler가 같은 문맥과 Reducer를 공유한다. */
    protected fun <M> createContext(reducer: MviReducer<S, M>): MviContext<S, M, E> {
        check(!contextCreated) { "MVI 실행 문맥은 화면당 하나만 생성해야 합니다." }
        contextCreated = true
        return object : MviContext<S, M, E> {
            override val state: StateFlow<S> = this@MviViewModel.state

            override fun dispatch(mutation: M) {
                _state.update { current -> reducer.reduce(current, mutation) }
            }

            override fun sendEffect(effect: E) = this@MviViewModel.sendEffect(effect)

            override fun launch(block: suspend CoroutineScope.() -> Unit): Job =
                viewModelScope.launch(block = block)
        }
    }

    /**
     * Effect를 채널에 즉시(non-blocking) 발행한다.
     *
     * 호출 즉시 [Channel.trySend]로 채널에 넣기 때문에 별도 코루틴을 생성하지 않고,
     * 호출 순서와 채널 진입 순서가 항상 일치한다. 단, 동시에 발행되는 Effect 수가
     * [Channel.BUFFERED] 용량을 넘거나 채널이 닫힌 경우 발행이 실패할 수 있으며,
     * 이 경우 Effect는 유실되고 경고 로그만 남긴다(화면 크래시로 이어지지 않음).
     */
    protected fun sendEffect(effect: E) {
        _effect
            .trySend(effect)
            .onFailure { cause ->
                // Effect data에는 인증 URL이나 불투명 미디어 참조가 포함될 수 있다.
                Log.w(TAG, "Effect 발행 실패: ${effect::class.simpleName}", cause)
            }
    }

    private companion object {
        const val TAG = "MviViewModel"
    }
}
