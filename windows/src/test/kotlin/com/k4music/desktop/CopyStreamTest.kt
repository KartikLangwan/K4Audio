package com.k4music.desktop

import com.k4music.desktop.player.PIECE_BYTES
import com.k4music.desktop.player.copyStream
import com.k4music.desktop.player.streamLength
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CopyStreamTest {
    @Test fun `a long stream arrives whole, with no request over the size YouTube throttles`() {
        val song = ByteArray((PIECE_BYTES * 2 + 12345).toInt()) { (it % 251).toByte() }
        val asked = mutableListOf<LongRange>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (from, to) = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map(String::toLong)
            synchronized(asked) { asked += from..to }
            exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${song.size}")
            exchange.sendResponseHeaders(206, to - from + 1)
            exchange.responseBody.use { it.write(song, from.toInt(), (to - from + 1).toInt()) }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/")
                .header("Range", "bytes=0-${PIECE_BYTES - 1}").build()
            val output = ByteArrayOutputStream()
            var progress = 0L
            http.newCall(request).execute().use { first ->
                assertEquals(song.size.toLong(), first.streamLength())
                runBlocking { copyStream(http, request, first, output) { progress = it } }
            }
            assertContentEquals(song, output.toByteArray())
            assertEquals(song.size.toLong(), progress)
            assertEquals(3, asked.size)
            assertTrue(asked.all { it.last - it.first + 1 <= PIECE_BYTES })
        } finally {
            server.stop(0)
        }
    }
}
