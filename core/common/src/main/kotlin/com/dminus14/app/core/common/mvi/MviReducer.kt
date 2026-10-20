package com.dminus14.app.core.common.mvi

/**
 * 현재 상태와 내부 변경 사유만으로 새 상태를 계산한다.
 *
 * StateFlow의 원자적 갱신 중 재실행될 수 있으므로 IO, Effect 발행과 외부 가변 상태 변경을 금지한다.
 */
fun interface MviReducer<S : MviState, M> {
    fun reduce(
        state: S,
        mutation: M,
    ): S
}
