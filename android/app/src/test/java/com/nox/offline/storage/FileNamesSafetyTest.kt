package com.nox.offline.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesSafetyTest {
    @Test fun safeNameStripsPathsAndControlChars() {
        assertEquals("evil.mp4", FileOps.safeName("../../data/evil.mp4"))
        assertEquals("a_b.mp4", FileOps.safeName("a:b.mp4"))
        assertEquals("video.mp4", FileOps.safeName(".."))
        assertEquals("video.mp4", FileOps.safeName(null))
        assertEquals(150, FileOps.safeName("x".repeat(400)).length)
    }

    @Test fun backupRejectsTraversal() {
        assertTrue(BackupManager.isSafeName("Видео 1.mp4"))
        assertFalse(BackupManager.isSafeName("../nox.db"))
        assertFalse(BackupManager.isSafeName("a/b.mp4"))
        assertFalse(BackupManager.isSafeName(".."))
        assertFalse(BackupManager.isSafeName(""))
        assertFalse(BackupManager.isSafeName("x".repeat(151)))
    }

    @Test fun backupRecognisesExistingVideos() {
        fun m(id: Long, title: String, size: Long, videoId: String = "", page: String = "") =
            com.nox.offline.data.db.MediaEntity(id = id, title = title, filePath = "/m/$id.mp4", sizeBytes = size,
                quality = "360", createdAt = 0, videoId = videoId, pageUrl = page)
        val lib = listOf(m(1, "Горы", 100, "v1", "https://vk.com/video1"), m(2, "Импорт тест", 2104518))
        assertEquals(1L, BackupManager.findSame(lib, "v1", "https://vk.com/video1", "Горы", 100)?.id)
        assertEquals(2L, BackupManager.findSame(lib, "", "", "Импорт тест", 2104518)?.id)
        // Другой размер или другое название — это другое видео.
        assertEquals(null, BackupManager.findSame(lib, "", "", "Импорт тест", 999))
        assertEquals(null, BackupManager.findSame(lib, "", "", "Другое", 2104518))
        assertEquals(null, BackupManager.findSame(lib, "v9", "https://vk.com/video9", "Горы", 100))
    }

    @Test fun spaceCheckKeepsMargin() {
        FileOps.requireSpace(100, 100L + 16 * 1024 * 1024)
        try {
            FileOps.requireSpace(100, 100)
            throw AssertionError("must throw")
        } catch (_: FileOps.NotEnoughSpace) {}
    }
}
