package com.k4music.desktop

import com.k4music.providers.sync.LibraryStore
import com.k4music.providers.sync.LibraryTransfer
import com.k4music.providers.sync.LocalPlaylist
import com.k4music.providers.sync.SongMeta
import com.k4music.providers.sync.SyncCrypto
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStore : LibraryStore {
    val liked = HashMap<String, Long>()
    val lists = LinkedHashMap<Long, Pair<String, List<String>>>()
    val songs = HashMap<String, SongMeta>()
    private var next = 1L
    override suspend fun likes() = liked.toMap()
    override suspend fun playlists() = lists.map { LocalPlaylist(it.key, it.value.first, it.value.second) }
    override suspend fun songMeta(ids: Collection<String>) = ids.mapNotNull { id -> songs[id]?.let { id to it } }.toMap()
    override suspend fun ensureSong(id: String, meta: SongMeta?) { songs.putIfAbsent(id, meta ?: SongMeta(id)) }
    override suspend fun setLike(id: String, at: Long) { liked[id] = at }
    override suspend fun createPlaylist(name: String): Long { val id = next++; lists[id] = name to emptyList(); return id }
    override suspend fun updatePlaylist(id: Long, name: String, songIds: List<String>) { lists[id] = name to songIds }
}

class PhoneTransferTest {
    private fun phone() = FakeStore().apply {
        songs["a"] = SongMeta("A"); songs["b"] = SongMeta("B"); songs["c"] = SongMeta("C")
        liked["a"] = 10; liked["local:/sdcard/x.mp3"] = 11
        lists[1] = "Gym" to listOf("a", "b", "local:/sdcard/x.mp3")
        lists[2] = "Chill" to listOf("c")
    }

    @Test
    fun importOnlyAddsAndKeepsWhatThePcHas() = runBlocking {
        val pc = FakeStore().apply {
            songs["z"] = SongMeta("Z"); liked["z"] = 1; liked["a"] = 5
            lists[100] = "gym" to listOf("z", "a")
        }
        val doc = LibraryTransfer.export(phone())
        val result = LibraryTransfer.import(doc, pc)

        assertEquals(mapOf("z" to 1L, "a" to 5L), pc.liked, "existing likes kept as they were, local files never travel")
        assertEquals("gym" to listOf("z", "a", "b"), pc.lists[100], "same-named playlist gets only the missing songs")
        assertEquals("Chill" to listOf("c"), pc.lists.values.last())
        assertEquals("C", pc.songs["c"]?.title)
        assertEquals(LibraryTransfer.Imported(likes = 0, playlists = 2, songs = 2), result)
        // Sending the same library again changes nothing.
        assertEquals(LibraryTransfer.Imported(0, 0, 0), LibraryTransfer.import(doc, pc))
    }

    @Test
    fun onlyTheScannedCodeCanBeRead() = runBlocking {
        val crypto = SyncCrypto.create()
        val packed = LibraryTransfer.pack(LibraryTransfer.export(phone()), crypto)
        assertEquals(setOf("a"), LibraryTransfer.unpack(packed, assertNotNull(SyncCrypto.fromCode(crypto.code))).likes.keys)
        assertTrue(runCatching { LibraryTransfer.unpack(packed, SyncCrypto.create()) }.isFailure)
    }

    @Test
    fun qrTextRoundTrips() {
        val code = SyncCrypto.create().code
        val invite = LibraryTransfer.Invite(listOf("192.168.1.5", "10.0.0.2"), 50123, code)
        assertEquals(invite, LibraryTransfer.Invite.parse(invite.text()))
        assertTrue(invite.text().length < 60, "the QR text stays short so the code stays coarse: ${invite.text().length}")
        // Older apps' prefixes still read.
        assertEquals(invite, LibraryTransfer.Invite.parse("k4audio-transfer:1;192.168.1.5,10.0.0.2;50123;$code"))
        assertNull(LibraryTransfer.Invite.parse("https://example.com"))
        assertNull(LibraryTransfer.Invite.parse("k4audio-transfer:1;192.168.1.5;0;$code"))
        assertNull(LibraryTransfer.Invite.parse("k4audio-transfer:1;192.168.1.5;5000;not-a-code"))
    }

    @Test
    fun phoneSendsAndPcReceivesOverTheNetwork() {
        val pc = FakeStore()
        LibraryTransfer.Receiver().use { receiver ->
            val invite = receiver.invite(listOf("127.0.0.1"))
            var received: Any? = null
            val listener = thread { received = receiver.receive(10_000) { doc -> runBlocking { LibraryTransfer.import(doc, pc) }.let { "OK ${it.likes} ${it.playlists} ${it.songs}" } } }

            // Someone without the code is turned away; the PC keeps waiting for the real phone.
            val stranger = LibraryTransfer.Invite(invite.hosts, invite.port, SyncCrypto.create().code)
            val refused = LibraryTransfer.send(stranger, LibraryTransfer.pack(runBlocking { LibraryTransfer.export(phone()) }, SyncCrypto.fromCode(stranger.code)!!))
            assertTrue(refused.startsWith("ERR"), refused)

            val reply = LibraryTransfer.send(invite, LibraryTransfer.pack(runBlocking { LibraryTransfer.export(phone()) }, SyncCrypto.fromCode(invite.code)!!))
            listener.join(10_000)
            assertEquals("OK 1 2 3", reply)
            assertNotNull(received)
            assertEquals(setOf("a"), pc.liked.keys)
        }
    }

    @Test
    fun theQrCodeDecodesFromAnImageAtScreenSize() {
        val invite = LibraryTransfer.Invite(listOf("192.168.1.5", "10.0.0.2"), 50123, SyncCrypto.create().code)
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(invite.text(), com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 4, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        val cell = 5 // a 280 dp square on a 100% screen is about this many pixels per module
        val size = matrix.width * cell
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, if (matrix.get(x / cell, y / cell)) 0 else 0xFFFFFF)
        val gray = ByteArray(size * size) { i -> (image.getRGB(i % size, i / size) and 0xFF).toByte() }
        val source = object : com.google.zxing.LuminanceSource(size, size) {
            override fun getRow(y: Int, row: ByteArray?) = (row ?: ByteArray(size)).also { gray.copyInto(it, 0, y * size, (y + 1) * size) }
            override fun getMatrix() = gray
        }
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        assertEquals(invite.text(), com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text)
    }

    @Test
    fun aStalledConnectionDoesNotBlockThePhone() {
        val pc = FakeStore()
        LibraryTransfer.Receiver().use { receiver ->
            val invite = receiver.invite(listOf("127.0.0.1"))
            var received: Any? = null
            val listener = thread { received = receiver.receive(15_000) { doc -> runBlocking { LibraryTransfer.import(doc, pc) }.let { "OK ${it.likes} ${it.playlists} ${it.songs}" } } }
            java.net.Socket("127.0.0.1", invite.port).use { _ -> // connects, sends nothing
                val started = System.currentTimeMillis()
                val reply = LibraryTransfer.send(invite, LibraryTransfer.pack(runBlocking { LibraryTransfer.export(phone()) }, SyncCrypto.fromCode(invite.code)!!))
                assertEquals("OK 1 2 3", reply)
                assertTrue(System.currentTimeMillis() - started < 5_000, "the real phone had to wait for the stalled one")
            }
            listener.join(10_000)
            assertNotNull(received)
        }
    }

    @Test
    fun youTubeImportedPlaylistsTravelToo() = runBlocking {
        val store = FakeStore().apply { songs["y"] = SongMeta("Y"); lists[7] = "From YouTube" to listOf("y") }
        assertEquals(listOf("y"), LibraryTransfer.export(store).playlists.single().songs)
    }
}
