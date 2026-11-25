package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.time.InterviewClock
import javax.inject.Inject

class StartInterviewTimerUseCase
    @Inject
    constructor(
        private val repository: InterviewLocalRepository,
        private val clock: InterviewClock,
    ) {
        suspend operator fun invoke(sessionId: Long) {
            val now = clock.currentEpochMillis()
            val realtime = clock.elapsedRealtimeMillis()
            val updated =
                repository.updateProgress { progress ->
                    check(progress.sessionId == sessionId)
                    if (progress.timerStartedAtEpochMillis != null && !progress.isFrozen) {
                        progress
                    } else {
                        progress.copy(
                            timerStartedAtEpochMillis = progress.timerStartedAtEpochMillis ?: now,
                            elapsedAtCheckpointMillis = progress.elapsedAtCheckpointMillis ?: 0L,
                            isFrozen = false,
                            checkpointedAtEpochMillis = now,
                            elapsedCheckpointElapsedRealtimeMillis = realtime,
                        )
                    }
                }
            checkNotNull(updated)
        }
    }
