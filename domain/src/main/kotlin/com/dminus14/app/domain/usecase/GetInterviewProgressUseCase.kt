package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.time.InterviewClock
import com.dminus14.app.domain.time.InterviewTimeCalculator
import javax.inject.Inject

class GetInterviewProgressUseCase
    @Inject
    constructor(
        private val repository: InterviewLocalRepository,
        private val clock: InterviewClock,
        private val calculator: InterviewTimeCalculator,
    ) {
        suspend operator fun invoke(): InterviewProgress? {
            val progress = repository.getProgress() ?: return null
            val remaining =
                calculator.retentionRemainingMillis(
                    progress,
                    clock.currentEpochMillis(),
                    clock.elapsedRealtimeMillis(),
                )
            if (remaining == 0L) repository.deleteSession(progress.sessionId)
            return progress.takeIf { remaining > 0L }
        }
    }
