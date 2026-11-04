package com.k4music.desktop

import com.k4music.desktop.player.StreamCache
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamCacheTest {
    @Test fun `trim removes the least recently played first and leaves a running save alone`() {
        val folder = Files.createTempDirectory("cache").toFile()
        try {
            val old = folder.resolve("old.YtDlp").apply { writeBytes(ByteArray(60)); setLastModified(1_000) }
            val recent = folder.resolve("recent.YtDlp").apply { writeBytes(ByteArray(60)); setLastModified(2_000) }
            val running = folder.resolve("song.part").apply { writeBytes(ByteArray(500)) }
            StreamCache.trim(folder, 100)
            assertEquals(false, old.exists())
            assertEquals(true, recent.exists())
            assertEquals(true, running.exists())
        } finally {
            folder.deleteRecursively()
        }
    }
}
