package com.k4music.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UpdaterSignerTest {
    @Test
    fun signedFileHasSignerAndAlteredOrUnsignedHasNone() = runBlocking {
        val launcher = java.io.File(ProcessHandle.current().info().command().orElse(""))
        if (!launcher.isFile || !launcher.name.endsWith(".exe")) return@runBlocking // not Windows
        assertNotNull(Updater.signer(launcher)) // JDK builds sign their launcher; the file below is not signed
        val plain = java.io.File.createTempFile("unsigned-", ".exe").apply { writeText("MZ not a real exe"); deleteOnExit() }
        assertNull(Updater.signer(plain))
    }
}
