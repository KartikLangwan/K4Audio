package com.k4music.desktop.player

import com.k4music.desktop.AppLog
import com.k4music.desktop.AppPaths
import com.k4music.providers.utils.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Songs saved while listening (the one playing and the next): they start at once, can be skipped
 * through and keep playing without a connection. Unlike downloads, the least recently played go
 * when the cache is full. Named `<hash>.<source>`, as a file does not say where it came from.
 */
/** YouTube throttles a request without a Range header, or for over 11 MiB, to about 40 KB/s (measured); smaller ones come at full speed. */
internal const val PIECE_BYTES = 10L * 1024 * 1024

/** The first request of a stream: on YouTube, its first piece. */
internal fun Request.Builder.firstPiece(url: String) = apply {
    if (url.contains("googlevideo.com")) header("Range", "bytes=0-${PIECE_BYTES - 1}")
}

/** The whole stream's size, whichever part of it this response carries. */
internal fun okhttp3.Response.streamLength(): Long? =
    header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: body.contentLength().takeIf { it > 0 }

/** Writes the stream that [first] starts to [output], asking for the rest piece by piece. */
internal suspend fun copyStream(http: OkHttpClient, request: Request, first: okhttp3.Response, output: java.io.OutputStream, onProgress: (Long) -> Unit = {}) {
    val buffer = ByteArray(64 * 1024)
    var written = 0L
    suspend fun drain(response: okhttp3.Response) = response.body.byteStream().use { input ->
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read
            onProgress(written)
        }
    }
    drain(first)
    if (first.code != 206) return // a server that sent the whole thing at once
    val total = first.streamLength() ?: return
    while (written < total) {
        val before = written
        val range = "bytes=$written-${minOf(written + PIECE_BYTES, total) - 1}"
        http.newCall(request.newBuilder().header("Range", range).build()).useCancellable { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            drain(response)
        }
        check(written > before) { "Stream ended at $written of $total bytes" }
    }
}

object StreamCache {
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private const val MAX_SONG_BYTES = 128L * 1024 * 1024
    private val dir get() = AppPaths.local.resolve("stream-cache").apply { mkdirs() }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun fileFor(id: String, source: StreamSource) = File(dir, "${Downloads.safeName(id)}.${source.name}")

    fun find(id: String): Pair<File, StreamSource>? = StreamSource.entries.firstNotNullOfOrNull { source ->
        fileFor(id, source).takeIf { it.isFile }?.let { it to source }
    }

    /** A saved copy of [id] that would not play. */
    fun drop(id: String, file: File) { if (find(id)?.first == file) file.delete() }

    suspend fun store(id: String, stream: Stream) = withContext(Dispatchers.IO) {
        if (find(id) != null) return@withContext
        val request = Request.Builder().url(stream.url).apply {
            stream.headers.forEach { (key, value) -> header(key, value) }
            firstPiece(stream.url)
        }.build()
        http.newCall(request).useCancellable { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val total = response.streamLength() ?: -1L
            if (total > MAX_SONG_BYTES) return@useCancellable
            val part = File.createTempFile("song-", ".part", dir)
            try {
                part.outputStream().use { output -> copyStream(http, request, response, output) }
                check(part.length() > 0 && (total <= 0 || part.length() == total)) { "Cut off at ${part.length()} of $total bytes" }
                val saved = part.length()
                Files.move(part.toPath(), fileFor(id, stream.source).toPath(), StandardCopyOption.REPLACE_EXISTING)
                AppLog.i("StreamCache", "Saved $id ahead (${saved / 1024} KB)")
                trim()
            } finally {
                part.delete()
            }
        }
    }

    /** Oldest first until under the limit; .part files left by a crash go too. */
    fun trim() = trim(dir, MAX_BYTES)

    internal fun trim(folder: File, maxBytes: Long) {
        val files = folder.listFiles().orEmpty()
        files.filter { it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }.forEach { it.delete() }
        var size = files.filter { it.isFile && !it.name.endsWith(".part") }.sumOf { it.length() }
        for (file in files.filter { it.isFile && !it.name.endsWith(".part") }.sortedBy { it.lastModified() }) {
            if (size <= maxBytes) return
            size -= file.length()
            file.delete()
        }
    }
}
