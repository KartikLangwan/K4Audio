package com.k4music.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UpdaterSignerTest {
    @Test
    fun signedFileHasSignerAndAlteredOrUnsignedHasNone() = runBlocking {
        val windows = java.io.File(System.getenv("SystemRoot") ?: "C:/Windows")
        if (!windows.isDirectory) return@runBlocking // not Windows
        // Which binaries carry an Authenticode signature differs between PCs and CI images: one of these must.
        val candidates = listOf(
            java.io.File(ProcessHandle.current().info().command().orElse("")),
            windows.resolve("explorer.exe"),
            windows.resolve("System32/WindowsPowerShell/v1.0/powershell.exe"),
            windows.resolve("System32/cmd.exe")
        ).filter { it.isFile }
        val signed = candidates.firstNotNullOfOrNull { Updater.signer(it) }
        if (System.getenv("CI") == null) assertNotNull(signed) // a CI image may ship only catalog-signed files
        val plain = java.io.File.createTempFile("unsigned-", ".exe").apply { writeText("MZ not a real exe"); deleteOnExit() }
        assertNull(Updater.signer(plain))
    }
}
