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

    @Test fun spaceCheckKeepsMargin() {
        FileOps.requireSpace(100, 100L + 16 * 1024 * 1024)
        try {
            FileOps.requireSpace(100, 100)
            throw AssertionError("must throw")
        } catch (_: FileOps.NotEnoughSpace) {}
    }
}
