package com.dminus14.app.feature.interview.interview

import com.dminus14.app.domain.model.InterviewAnswerEndRequest
import javax.inject.Inject

/** A4 제출과 종료 의도를 직렬화하기 위한 최소 상태 머신이다. */
class InterviewTurnStateMachine
    @Inject
    constructor() {
        var isSubmitting: Boolean = false
            private set

        var temporaryFailureCount: Int = 0
            private set

        var pendingEndRequest: InterviewAnswerEndRequest? = null
            private set

        fun beginSubmission(): Boolean {
            if (isSubmitting) return false
            isSubmitting = true
            return true
        }

        fun finishSubmission() {
            isSubmitting = false
            temporaryFailureCount = 0
        }

        fun recordTemporaryFailure(): TemporaryFailureAction {
            temporaryFailureCount += 1
            return if (temporaryFailureCount == 1) {
                TemporaryFailureAction.RETRY_AUTOMATICALLY
            } else {
                isSubmitting = false
                TemporaryFailureAction.REQUIRE_USER_ACTION
            }
        }

        /** RETRY_AUTOMATICALLY 직후 같은 요청을 재제출할 수 있도록 제출 상태만 초기화한다. */
        fun prepareAutomaticRetry() {
            isSubmitting = false
        }

        /** 최초 종료 요청만 보존하며 이후 요청은 기존 종료 유형을 바꾸지 않는다. */
        fun requestEnd(request: InterviewAnswerEndRequest): Boolean {
            if (pendingEndRequest != null) return false
            pendingEndRequest = request
            return true
        }

        /** 제출 실패 뒤 busy 상태만 해제하고 최초 종료 의도는 세션 복구를 위해 유지한다. */
        fun abortSubmission() {
            isSubmitting = false
            temporaryFailureCount = 0
        }

        /** 서버 terminal 결과 처리를 완료한 뒤 종료 의도를 제거한다. */
        fun completeTerminal() {
            abortSubmission()
            pendingEndRequest = null
        }

        enum class TemporaryFailureAction { RETRY_AUTOMATICALLY, REQUIRE_USER_ACTION }
    }
