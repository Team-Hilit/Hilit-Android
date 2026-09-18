package com.dminus14.app.data.local.interview

import android.content.Context
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaOwnerType
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("TooManyFunctions")
class InterviewFileStore private constructor(
    private val root: File,
    private val cacheRoot: File,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        root = context.noBackupFilesDir.resolve("interview"),
        cacheRoot = context.cacheDir.resolve("interview"),
    )

    /** JVM 단위 테스트에서 Android Context 없이 앱 전용 저장소 루트를 제공한다. */
    internal constructor(
        noBackupRoot: File,
        cacheDirectoryRoot: File,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit,
    ) : this(
        root = noBackupRoot.resolve("interview"),
        cacheRoot = cacheDirectoryRoot.resolve("interview"),
    )

    fun sessionDirectory(sessionId: Long): File = root.resolve(sessionId.toString())

    fun uploadRootDirectory(): File = root.resolve(UPLOAD_DIRECTORY)

    fun uploadDirectory(uploadTaskId: String): File =
        uploadRootDirectory().resolve(requireUploadTaskId(uploadTaskId))

    fun uploadCacheDirectory(uploadTaskId: String): File =
        cacheRoot.resolve(UPLOAD_DIRECTORY).resolve(requireUploadTaskId(uploadTaskId))

    fun create(
        sessionId: Long,
        type: InterviewMediaSegmentType,
        extension: String,
    ): InterviewMediaFileRef {
        val token = UUID.randomUUID().toString()
        createFile(
            sessionDirectory(sessionId).resolve(segmentDirectory(type)),
            token,
            extension,
        )
        return InterviewMediaFileRef(
            value = token,
            ownerType = InterviewMediaOwnerType.SESSION,
            ownerId = sessionId.toString(),
            segmentType = type,
        )
    }

    fun createUploadMediaFile(
        uploadTaskId: String,
        extension: String,
    ): InterviewMediaFileRef {
        val token = UUID.randomUUID().toString()
        createFile(uploadDirectory(uploadTaskId).resolve(MERGED_DIRECTORY), token, extension)
        return InterviewMediaFileRef(
            value = token,
            ownerType = InterviewMediaOwnerType.UPLOAD,
            ownerId = uploadTaskId,
        )
    }

    fun resolve(ref: InterviewMediaFileRef): File {
        require(runCatching { UUID.fromString(ref.value) }.isSuccess) {
            "Invalid media reference"
        }
        return checkNotNull(
            candidateDirectories(ref).firstNotNullOfOrNull { directory ->
                directory
                    .listFiles()
                    .orEmpty()
                    .firstOrNull { file ->
                        file.isFile && file.nameWithoutExtension == ref.value
                    }
            },
        ) { "Media reference does not exist" }
    }

    fun delete(ref: InterviewMediaFileRef) {
        runCatching { resolve(ref) }.getOrNull()?.delete()
    }

    /**
     * 세션 미디어를 업로드 작업 디렉터리로 옮긴다.
     *
     * 업로드 작업 메타데이터가 먼저 기록되어 대상 디렉터리가 이미 존재할 수 있으므로,
     * 디렉터리 자체를 이동하지 않고 항목 단위로 옮긴다. 같은 이름이 양쪽에 모두 있으면
     * 충돌로 보고 즉시 실패하며, 부분 이동 후 재시도하면 남은 항목만 이어서 옮긴다.
     */
    fun handoff(
        sessionId: Long,
        uploadTaskId: String,
    ): File {
        val source = sessionDirectory(sessionId)
        val target = uploadDirectory(uploadTaskId)
        if (!source.exists() && target.isDirectory) return target
        check(source.isDirectory) { "Interview session directory does not exist" }
        check(target.isDirectory || target.mkdirs()) { "Failed to create upload directory" }
        val entries = source.listFiles().orEmpty()
        check(entries.none { entry -> target.resolve(entry.name).exists() }) {
            "Upload task directory already contains interview media"
        }
        entries.forEach { entry ->
            check(entry.renameTo(target.resolve(entry.name))) {
                "Failed to hand off interview media"
            }
        }
        source.delete()
        return target
    }

    fun deleteSession(sessionId: Long) {
        sessionDirectory(sessionId).deleteRecursively()
    }

    fun deleteUpload(uploadTaskId: String) {
        uploadDirectory(uploadTaskId).deleteRecursively()
        uploadCacheDirectory(uploadTaskId).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    /**
     * 세션 미디어는 업로드 인계 시 세션 디렉터리째 업로드 디렉터리로 이동하므로,
     * 세션 참조는 세션 디렉터리와 업로드 디렉터리의 동일 세그먼트 경로만 순서대로 조회한다.
     */
    private fun candidateDirectories(ref: InterviewMediaFileRef): Sequence<File> =
        when (ref.ownerType) {
            InterviewMediaOwnerType.UPLOAD -> {
                sequenceOf(uploadDirectory(ref.ownerId).resolve(MERGED_DIRECTORY))
            }

            InterviewMediaOwnerType.SESSION -> {
                val segment =
                    segmentDirectory(
                        requireNotNull(ref.segmentType) { "Invalid media reference" },
                    )
                val sessionId =
                    requireNotNull(ref.ownerId.toLongOrNull()) { "Invalid media reference" }
                sequenceOf(sessionDirectory(sessionId).resolve(segment)) +
                    uploadRootDirectory()
                        .listFiles()
                        .orEmpty()
                        .asSequence()
                        .filter(File::isDirectory)
                        .map { directory -> directory.resolve(segment) }
            }
        }

    private fun createFile(
        directory: File,
        token: String,
        extension: String,
    ) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Failed to create interview media directory"
        }
        val safeExtension =
            extension
                .trimStart('.')
                .lowercase()
                .filter(Char::isLetterOrDigit)
                .ifEmpty { DEFAULT_EXTENSION }
        check(directory.resolve("$token.$safeExtension").createNewFile()) {
            "Failed to create interview media file"
        }
    }

    private fun segmentDirectory(type: InterviewMediaSegmentType): String = type.name.lowercase()

    private fun requireUploadTaskId(uploadTaskId: String): String {
        require(runCatching { UUID.fromString(uploadTaskId) }.isSuccess) {
            "Invalid upload task id"
        }
        return uploadTaskId
    }

    private companion object {
        const val UPLOAD_DIRECTORY = "uploads"
        const val MERGED_DIRECTORY = "merged"
        const val DEFAULT_EXTENSION = "bin"
    }
}
