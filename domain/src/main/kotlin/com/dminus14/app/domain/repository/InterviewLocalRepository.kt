package com.dminus14.app.domain.repository

import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewUploadTask

@Suppress("TooManyFunctions")
interface InterviewLocalRepository {
    suspend fun getProgress(): InterviewProgress?

    suspend fun saveProgress(progress: InterviewProgress)

    /** 저장된 진행 상태를 단일 쓰기 안에서 읽고 갱신한다. 진행 상태가 없으면 null을 반환한다. */
    suspend fun updateProgress(
        transform: (InterviewProgress) -> InterviewProgress,
    ): InterviewProgress?

    suspend fun clearProgress()

    /** 현재 진행 상태가 지정한 세션에 속할 때만 원자적으로 제거한다. */
    suspend fun clearProgressIfMatching(sessionId: Long): Boolean

    suspend fun getManifest(sessionId: Long): InterviewMediaManifest?

    suspend fun getUploadManifest(uploadTaskId: String): InterviewMediaManifest?

    suspend fun saveManifest(manifest: InterviewMediaManifest)

    suspend fun createMediaFile(
        sessionId: Long,
        type: InterviewMediaSegmentType,
        extension: String,
    ): InterviewMediaFileRef

    suspend fun createUploadMediaFile(
        uploadTaskId: String,
        extension: String,
    ): InterviewMediaFileRef

    suspend fun deleteMediaFile(ref: InterviewMediaFileRef)

    /** 미디어를 인계하고, 인계한 세션의 진행 상태를 제거했는지 반환한다. */
    suspend fun handoffUploadTask(task: InterviewUploadTask): Boolean

    suspend fun getUploadTask(uploadTaskId: String): InterviewUploadTask?

    suspend fun saveUploadTask(task: InterviewUploadTask)

    suspend fun getUploadTasks(): List<InterviewUploadTask>

    suspend fun deleteUploadTask(uploadTaskId: String)

    suspend fun deleteSession(sessionId: Long)

    suspend fun clearAll()

    suspend fun isCleanupPending(): Boolean

    suspend fun setCleanupPending(isPending: Boolean)
}
