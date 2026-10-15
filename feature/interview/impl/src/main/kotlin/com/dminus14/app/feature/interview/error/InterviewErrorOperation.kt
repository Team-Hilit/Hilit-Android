package com.dminus14.app.feature.interview.error

import com.dminus14.app.core.common.mvi.MviContext
import kotlinx.coroutines.CoroutineScope

/** 조회와 별개로, 재개·중단의 서버 변경 작업과 최종 결과를 화면 수명 안에서 직렬화한다. */
internal class InterviewErrorOperation {
    var isRunning: Boolean = false
        private set
    var isCompleted: Boolean = false
        private set

    fun complete() {
        isCompleted = true
    }

    fun launch(
        context: MviContext<InterviewErrorState, InterviewErrorMutation, InterviewErrorEffect>,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        if (isRunning || isCompleted) return
        isRunning = true
        context.launch {
            try {
                block()
            } finally {
                isRunning = false
            }
        }
    }
}
