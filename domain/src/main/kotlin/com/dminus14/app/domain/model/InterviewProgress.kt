package com.dminus14.app.domain.model

/** 로컬에서 소유하는 진행 중 면접과 타이머·보존 기한 체크포인트다. */
data class InterviewProgress(
    val sessionId: Long,
    val retentionDeadlineEpochMillis: Long,
    val retentionRemainingAtCheckpointMillis: Long,
    val retentionCheckpointElapsedRealtimeMillis: Long?,
    val timerStartedAtEpochMillis: Long?,
    val elapsedAtCheckpointMillis: Long?,
    val checkpointedAtEpochMillis: Long?,
    val elapsedCheckpointElapsedRealtimeMillis: Long?,
    val isFrozen: Boolean = false,
)
