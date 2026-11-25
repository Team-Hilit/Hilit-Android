package com.dminus14.app.domain.model

sealed interface InterviewResumeState {
    data object Resumable : InterviewResumeState

    data object Ended : InterviewResumeState

    data class Unknown(
        val rawValue: String,
    ) : InterviewResumeState
}

sealed interface InterviewTerminalStatus {
    data object Completed : InterviewTerminalStatus

    data object Abandoned : InterviewTerminalStatus

    data object Invalid : InterviewTerminalStatus

    data class Unknown(
        val rawValue: String,
    ) : InterviewTerminalStatus
}

/** 면접 세션 재개 상태 정보이다. 서버 경과 시간은 판정 참고값으로만 보존한다. */
data class InterviewResumeStatus(
    val resumeState: InterviewResumeState,
    val startedAt: String?,
    val elapsedSeconds: Long?,
    val status: InterviewTerminalStatus?,
)

/** 면접 세션 재개 확정 결과이다. */
data class InterviewResumeConfirm(
    val nextQuestion: NextQuestion?,
    val sessionEnded: Boolean,
    val wrapUpMessage: WrapUpMessage?,
    val endType: InterviewEndType?,
    val status: InterviewTerminalStatus?,
    val abandonCause: InterviewAbandonCause?,
    val endedAt: String?,
) {
    val hasUnconfirmedEnd: Boolean
        get() =
            status is InterviewTerminalStatus.Unknown ||
                abandonCause is InterviewAbandonCause.Unknown ||
                endType is InterviewEndType.Unknown ||
                (status == null && abandonCause == null && endType == null)
}
