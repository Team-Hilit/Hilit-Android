package com.dminus14.app.data.local.interview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class InterviewFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `부분 인계 뒤 재시도하면 같은 디렉터리로 남은 파일만 이동한다`() {
        val noBackupRoot = temporaryFolder.newFolder("no-backup")
        val cacheRoot = temporaryFolder.newFolder("cache")
        val store = InterviewFileStore(noBackupRoot, cacheRoot, Unit)
        val uploadTaskId = UUID.randomUUID().toString()
        val source = store.sessionDirectory(SESSION_ID)
        val target = store.uploadDirectory(uploadTaskId)
        val movedDirectory = source.resolve("question_video").apply { mkdirs() }
        movedDirectory.resolve("first.mp4").writeText("synthetic", Charsets.UTF_8)
        source
            .resolve("answer_video")
            .apply { mkdirs() }
            .resolve("second.mp4")
            .writeText("synthetic", Charsets.UTF_8)
        target.mkdirs()
        target.resolve("task.json").writeText("{}", Charsets.UTF_8)
        assertTrue(movedDirectory.renameTo(target.resolve(movedDirectory.name)))

        store.handoff(SESSION_ID, uploadTaskId)

        assertTrue(target.resolve("question_video/first.mp4").isFile)
        assertTrue(target.resolve("answer_video/second.mp4").isFile)
        assertTrue(target.resolve("task.json").isFile)
        assertFalse(source.exists())
    }

    private companion object {
        const val SESSION_ID = 145L
    }
}
