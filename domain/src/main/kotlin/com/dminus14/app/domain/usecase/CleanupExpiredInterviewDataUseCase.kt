package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.repository.InterviewWorkController
import com.dminus14.app.domain.time.InterviewClock
import javax.inject.Inject

class CleanupExpiredInterviewDataUseCase
    @Inject
    constructor(
        private val repository: InterviewLocalRepository,
        private val workController: InterviewWorkController,
        private val clock: InterviewClock,
        private val getProgress: GetInterviewProgressUseCase,
    ) {
        suspend operator fun invoke() {
            val currentEpochMillis = clock.currentEpochMillis()
            getProgress()
            repository
                .getUploadTasks()
                .filter { it.retentionDeadlineEpochMillis <= currentEpochMillis }
                .forEach { task ->
                    workController.cancelUpload(task.uploadTaskId)
                    repository.deleteUploadTask(task.uploadTaskId)
                }
        }
    }
