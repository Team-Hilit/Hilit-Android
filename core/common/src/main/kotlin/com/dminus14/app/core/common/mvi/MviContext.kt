package com.dminus14.app.core.common.mvi

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * 화면 내부 Handler가 공유하는 단일 상태와 실행 문맥.
 *
 * Intent 처리와 [dispatch], [sendEffect]는 Main에서 호출한다. IO 작업은 [launch] 내부에서
 * withContext로 전환한 뒤 Main으로 돌아와 결과를 전달한다. Handler는 별도 Scope를 만들지 않는다.
 */
interface MviContext<S : MviState, M, E : MviEffect> {
    val state: StateFlow<S>

    /** 순수 Reducer를 동기 실행하므로 반환 직후 state에서 결과를 읽을 수 있다. */
    @MainThread
    fun dispatch(mutation: M)

    /** 기존 BUFFERED 채널에 즉시 발행한다. 버퍼 초과 시 전달을 보장하지 않는다. */
    @MainThread
    fun sendEffect(effect: E)

    /** ViewModel 종료 시 함께 취소되는 Main 자식 작업을 시작한다. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job
}
