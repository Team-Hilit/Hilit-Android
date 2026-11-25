package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.model.InterviewUploadTaskStatus
import com.dminus14.app.domain.repository.InterviewLocalRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

/** 종료된 세션의 진행 판정은 제거하되 미디어는 공통 보존 기한까지 정리 대상으로 유지한다. */
class RetainInterviewSessionForCleanupUseCase
    @Inject
    constructor(
        private val repository: InterviewLocalRepository,
    ) {
        private val cleanupMutex = Mutex()

        /** 이 세션의 진행 기록을 제거했으면 true를 반환한다. */
        suspend operator fun invoke(sessionId: Long): Boolean =
            cleanupMutex.withLock {
                val progress = repository.getProgress()
                if (progress?.sessionId != sessionId) return@withLock false

                // 인계가 중단되면 매니페스트가 이미 작업 디렉터리로 옮겨졌을 수 있다.
                val existingTask =
                    repository.getUploadTasks().firstOrNull { task ->
                        task.sessionId == sessionId &&
                            task.status == InterviewUploadTaskStatus.RETAINED
                    }
                if (existingTask != null) {
                    return@withLock repository.handoffUploadTask(existingTask)
                }
                if (repository.getManifest(sessionId) == null) {
                    return@withLock repository.clearProgressIfMatching(sessionId)
                }

                val task =
                    InterviewUploadTask(
                        uploadTaskId = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        retentionDeadlineEpochMillis = progress.retentionDeadlineEpochMillis,
                        status = InterviewUploadTaskStatus.RETAINED,
                    )
                repository.handoffUploadTask(task)
            }
    }
