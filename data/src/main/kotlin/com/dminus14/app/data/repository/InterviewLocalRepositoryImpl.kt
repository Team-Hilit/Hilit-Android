package com.dminus14.app.data.repository

import com.dminus14.app.data.local.interview.InterviewCleanupPendingStore
import com.dminus14.app.data.local.interview.InterviewFileStore
import com.dminus14.app.data.local.interview.InterviewManifestStore
import com.dminus14.app.data.local.interview.InterviewProgressStore
import com.dminus14.app.data.local.interview.InterviewUploadTaskStore
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.repository.InterviewLocalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("TooManyFunctions")
class InterviewLocalRepositoryImpl
    @Inject
    constructor(
        private val progressStore: InterviewProgressStore,
        private val manifestStore: InterviewManifestStore,
        private val fileStore: InterviewFileStore,
        private val uploadTaskStore: InterviewUploadTaskStore,
        private val cleanupPendingStore: InterviewCleanupPendingStore,
    ) : InterviewLocalRepository {
        override suspend fun getProgress(): InterviewProgress? = progressStore.read()

        override suspend fun saveProgress(progress: InterviewProgress) {
            progressStore.write(progress)
        }

        override suspend fun updateProgress(
            transform: (InterviewProgress) -> InterviewProgress,
        ): InterviewProgress? = progressStore.update(transform)

        override suspend fun clearProgress() {
            progressStore.clear()
        }

        override suspend fun getManifest(sessionId: Long): InterviewMediaManifest? =
            withContext(Dispatchers.IO) { manifestStore.read(sessionId) }

        override suspend fun getUploadManifest(uploadTaskId: String): InterviewMediaManifest? =
            withContext(Dispatchers.IO) { manifestStore.readUpload(uploadTaskId) }

        override suspend fun saveManifest(manifest: InterviewMediaManifest) {
            withContext(Dispatchers.IO) { manifestStore.write(manifest) }
        }

        override suspend fun createMediaFile(
            sessionId: Long,
            type: InterviewMediaSegmentType,
            extension: String,
        ): InterviewMediaFileRef =
            withContext(Dispatchers.IO) { fileStore.create(sessionId, type, extension) }

        override suspend fun createUploadMediaFile(
            uploadTaskId: String,
            extension: String,
        ): InterviewMediaFileRef =
            withContext(Dispatchers.IO) { fileStore.createUploadMediaFile(uploadTaskId, extension) }

        override suspend fun deleteMediaFile(ref: InterviewMediaFileRef) {
            withContext(Dispatchers.IO) { fileStore.delete(ref) }
        }

        override suspend fun handoffUploadTask(task: InterviewUploadTask) {
            withContext(Dispatchers.IO) {
                if (uploadTaskStore.read(task.uploadTaskId) != task) {
                    uploadTaskStore.write(task)
                }
                fileStore.handoff(task.sessionId, task.uploadTaskId)
            }
            progressStore.clear()
        }

        override suspend fun getUploadTask(uploadTaskId: String): InterviewUploadTask? =
            withContext(Dispatchers.IO) { uploadTaskStore.read(uploadTaskId) }

        override suspend fun saveUploadTask(task: InterviewUploadTask) {
            withContext(Dispatchers.IO) { uploadTaskStore.write(task) }
        }

        override suspend fun getUploadTasks(): List<InterviewUploadTask> =
            withContext(Dispatchers.IO) { uploadTaskStore.readAll() }

        override suspend fun deleteUploadTask(uploadTaskId: String) {
            withContext(Dispatchers.IO) { fileStore.deleteUpload(uploadTaskId) }
        }

        override suspend fun deleteSession(sessionId: Long) {
            withContext(Dispatchers.IO) { fileStore.deleteSession(sessionId) }
            if (progressStore.read()?.sessionId == sessionId) progressStore.clear()
        }

        override suspend fun clearAll() {
            withContext(Dispatchers.IO) { fileStore.clearAll() }
            progressStore.clear()
            cleanupPendingStore.setPending(false)
        }

        override suspend fun isCleanupPending(): Boolean = cleanupPendingStore.isPending()

        override suspend fun setCleanupPending(isPending: Boolean) {
            cleanupPendingStore.setPending(isPending)
        }
    }
