package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.time.InterviewClock
import com.dminus14.app.domain.time.InterviewTimeCalculator
import javax.inject.Inject

class CheckpointInterviewProgressUseCase
    @Inject
    constructor(
        private val repository: InterviewLocalRepository,
        private val clock: InterviewClock,
        private val calculator: InterviewTimeCalculator,
    ) {
        /** 녹화 마감에 걸린 시간은 면접 시간에 포함하지 않는다. */
        fun markFreeze(sessionId: Long) =
            Freeze(sessionId, clock.currentEpochMillis(), clock.elapsedRealtimeMillis())

        suspend operator fun invoke(freeze: Freeze? = null): Boolean {
            val epoch = freeze?.epochMillis ?: clock.currentEpochMillis()
            val realtime = freeze?.elapsedRealtimeMillis ?: clock.elapsedRealtimeMillis()
            val updated =
                repository.updateProgress { progress ->
                    if (progress.isFrozen) return@updateProgress progress
                    if (freeze != null && progress.sessionId != freeze.sessionId) {
                        return@updateProgress progress
                    }
                    progress.copy(
                        retentionRemainingAtCheckpointMillis =
                            calculator.retentionRemainingMillis(progress, epoch, realtime),
                        retentionCheckpointElapsedRealtimeMillis = realtime,
                        isFrozen = freeze != null,
                        elapsedAtCheckpointMillis =
                            calculator.elapsedMillis(progress, epoch, realtime),
                        checkpointedAtEpochMillis = epoch,
                        elapsedCheckpointElapsedRealtimeMillis = realtime,
                    )
                }
            return updated != null && (freeze == null || updated.sessionId == freeze.sessionId)
        }

        data class Freeze internal constructor(
            val sessionId: Long,
            val epochMillis: Long,
            val elapsedRealtimeMillis: Long,
        )
    }
