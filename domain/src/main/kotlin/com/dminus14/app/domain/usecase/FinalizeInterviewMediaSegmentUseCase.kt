package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.InterviewMediaFinalizeState
import com.dminus14.app.domain.repository.InterviewLocalRepository
import javax.inject.Inject

class FinalizeInterviewMediaSegmentUseCase
    @Inject
    constructor(
        private val repository: InterviewLocalRepository,
    ) {
        suspend operator fun invoke(
            sessionId: Long,
            sequence: Int,
            endedAtMillis: Long,
        ) {
            val manifest = checkNotNull(repository.getManifest(sessionId))
            check(manifest.segments.any { it.sequence == sequence })
            repository.saveManifest(
                manifest.copy(
                    segments =
                        manifest.segments.map { segment ->
                            if (segment.sequence == sequence) {
                                segment.copy(
                                    endedAtMillis = endedAtMillis,
                                    finalizeState = InterviewMediaFinalizeState.FINALIZED,
                                )
                            } else {
                                segment
                            }
                        },
                ),
            )
        }
    }
