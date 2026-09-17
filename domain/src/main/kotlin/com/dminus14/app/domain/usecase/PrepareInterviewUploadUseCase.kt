package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.repository.InterviewLocalRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

class PrepareInterviewUploadUseCase
    @Inject
    constructor(
        private val repository: InterviewLocalRepository,
    ) {
        private val preparationMutex = Mutex()
        private val pendingTasks = mutableMapOf<Long, InterviewUploadTask>()

        suspend operator fun invoke(
            sessionId: Long,
            networkPolicy: InterviewUploadNetworkPolicy,
        ): InterviewUploadTask =
            preparationMutex.withLock {
                val existingTask =
                    repository.getUploadTasks().firstOrNull { it.sessionId == sessionId }
                if (existingTask != null) {
                    repository.handoffUploadTask(existingTask)
                    pendingTasks.remove(sessionId)
                    return@withLock existingTask
                }

                val progress =
                    requireNotNull(repository.getProgress()) { "Interview progress does not exist" }
                require(progress.sessionId == sessionId) {
                    "Interview session does not match progress"
                }
                val task =
                    pendingTasks.getOrPut(sessionId) {
                        InterviewUploadTask(
                            uploadTaskId = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            retentionDeadlineEpochMillis = progress.retentionDeadlineEpochMillis,
                            networkPolicy = networkPolicy,
                        )
                    }
                repository.handoffUploadTask(task)
                pendingTasks.remove(sessionId)
                task
            }
    }
