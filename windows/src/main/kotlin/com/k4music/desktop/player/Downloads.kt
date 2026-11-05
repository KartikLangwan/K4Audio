package com.k4music.desktop.player

import com.k4music.desktop.AppLog
import com.k4music.desktop.AppPaths
import com.k4music.desktop.data.Db
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import com.k4music.providers.utils.useCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Offline songs. A download resolves the stream exactly like playback (so it gets the same
 * fallbacks), writes to `<id>.part` and renames on success; a broken download never shows as done.
 */
object Downloads {
    private const val PARALLEL = 4
    private val dir get() = AppPaths.downloads
    private val index by lazy { DownloadIndex(dir) }
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val removing = HashSet<String>()
    private var deletingAll = false
    @Volatile var onCleanupError: (String) -> Unit = {}
    private val permits = Semaphore(PARALLEL)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    lateinit var resolver: StreamResolver

    /** Ids of finished downloads. */
    private val _done = MutableStateFlow(index.ids())
    val done: StateFlow<Set<String>> = _done.asStateFlow()

    /** Download progress 0..1 per id while running. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()
    enum class Phase { Queued, Resolving, Downloading }
    data class Transfer(val track: Track, val phase: Phase = Phase.Queued, val bytes: Long = 0, val total: Long? = null)
    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()
    data class Failed(val track: Track, val message: String)
    private val _failed = MutableStateFlow<Map<String, Failed>>(emptyMap())
    val failed: StateFlow<Map<String, Failed>> = _failed.asStateFlow()

    fun dismissFailure(id: String) { _failed.update { it - id } }
    fun retry(id: String) { _failed.value[id]?.track?.let(::download) }

    @Synchronized fun file(id: String): File? = index.file(id).also { if (it == null) _done.update { ids -> ids - id } }

    suspend fun reconcile() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        synchronized(this@Downloads) { _done.value = index.ids() }
    }

    /** Interrupted downloads leave `.part` files; at start nothing is running, so they are all garbage. */
    fun sweepPartials() {
        dir.listFiles { file -> file.name.endsWith(".part") }?.forEach { runCatching { it.delete() } }
    }

    fun isDownloaded(id: String) = id in _done.value

    @Synchronized
    fun download(track: Track) {
        val previous = jobs[track.id]
        if (!scopeJob.isActive || deletingAll || track.id in removing || track.isLocal || previous?.isActive == true) return
        _failed.update { it - track.id }
        _progress.update { it + (track.id to 0f) }
        _transfers.update { it + (track.id to Transfer(track)) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join() // A cancelled attempt finishes cleanup before its replacement starts.
                permits.withPermit {
                    if (file(track.id) == null) {
                        // A refused or dropped url is rejected in fetch, so the second run gets a fresh YouTube link;
                        // only then JioSaavn, and not for a lost connection (it says nothing about the song).
                        // The first error stays the one shown.
                        try { fetch(track) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (first: Exception) {
                            try { delay(1_000); fetch(track) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (second: Exception) {
                                if (second is java.io.IOException) delay(5_000)
                                else if (com.k4music.desktop.prefs.Settings.smartFallback && !track.isSaavn) com.k4music.desktop.k4.StreamFallback.force(track.id)
                                try { fetch(track) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { throw first }
                            }
                        }
                    } else _done.update { it + track.id }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Downloads", "Download failed for ${track.id}", e)
                _failed.update { it + (track.id to Failed(track, e.message ?: "Download failed")) }
            } finally {
                synchronized(this@Downloads) {
                    if (jobs.remove(track.id, kotlin.coroutines.coroutineContext[Job])) {
                        _progress.update { it - track.id }
                        _transfers.update { it - track.id }
                    }
                }
            }
        }
        jobs[track.id] = job
        job.start()
    }

    @Synchronized
    fun cancel(id: String) { jobs[id]?.cancel() }

    suspend fun close() {
        val pending = synchronized(this) { scopeJob.children.toList().also { scopeJob.cancel() } }
        pending.forEach { it.join() }
    }

    @Synchronized
    fun delete(id: String) {
        if (deletingAll || !removing.add(id)) return
        cancel(id)
        val previous = jobs[id]
        scope.launch {
            try {
                previous?.join()
                index.remove(setOf(id))
                _failed.update { it - id }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { reportCleanupError(error) }
            finally { synchronized(this@Downloads) { removing.remove(id); _done.value = index.ids() } }
        }
    }

    @Synchronized fun deleteAll() {
        if (deletingAll) return
        deletingAll = true
        val pending = jobs.values.toList().also { list -> list.forEach { it.cancel() } }
        scope.launch {
            try {
                pending.forEach { it.join() }
                index.remove(index.indexedIds())
                _failed.value = emptyMap()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { reportCleanupError(error) }
            finally { synchronized(this@Downloads) { deletingAll = false; _done.value = index.ids() } }
        }
    }

    private fun reportCleanupError(error: Exception) {
        AppLog.w("Downloads", "Cleanup failed", error)
        onCleanupError(error.message ?: "Could not remove downloads. Try again.")
    }

    private suspend fun fetch(track: Track) {
        _transfers.update { it + (track.id to Transfer(track, Phase.Resolving)) }
        Db.library.insert(track)
        val stream = resolver.resolve(track, patient = true)
        // Heard it already: the copy saved while listening is the song, no need to fetch it again.
        File(stream.url).takeIf { it.isFile }?.let { saved ->
            val part = File.createTempFile("${safeName(track.id)}-", ".part", dir)
            try {
                saved.copyTo(part, overwrite = true)
                publish(track, part, audioExtension(part))
            } finally {
                part.delete()
            }
            return
        }
        val request = Request.Builder().url(stream.url).apply {
            stream.headers.forEach { (k, v) -> header(k, v) }
            firstPiece(stream.url)
        }.build()
        http.newCall(request).useCancellable { response ->
            if (!response.isSuccessful) {
                // A refused url must not be served again from the resolver cache on Retry.
                resolver.reject(track, stream)
                error("HTTP ${response.code}")
            }
            val body = response.body
            val extension = when {
                body.contentType()?.subtype?.contains("webm") == true -> "webm"
                stream.url.contains(".mp4") || body.contentType()?.subtype?.contains("mp4") == true -> "m4a"
                body.contentType()?.subtype?.contains("mpeg") == true -> "mp3"
                body.contentType()?.subtype?.let { it.contains("ogg") || it.contains("opus") } == true -> "ogg"
                body.contentType()?.subtype?.contains("aac") == true -> "aac"
                else -> "audio"
            }
            val part = File.createTempFile("${safeName(track.id)}-", ".part", dir)
            val total = response.streamLength()
            _transfers.update { it + (track.id to Transfer(track, Phase.Downloading, total = total)) }
            try {
                java.io.FileOutputStream(part).use { output ->
                    var lastProgress = System.nanoTime() - 100_000_000
                    copyStream(http, request, response, output) { written ->
                        val now = System.nanoTime()
                        if (now - lastProgress >= 100_000_000 || written == total) {
                            lastProgress = now
                            _transfers.update { it + (track.id to Transfer(track, Phase.Downloading, written, total)) }
                            if (total != null) _progress.update { it + (track.id to (written.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                    output.fd.sync()
                }
                check(part.length() > 0) { "Empty audio response" }
                check(total == null || part.length() == total) { "Download was cut off (expected $total bytes, got ${part.length()})" }
                publish(track, part, extension)
            } catch (e: StreamRefusedException) {
                part.delete()
                resolver.reject(track, stream) // the retry gets a new link instead of this one again
                throw e
            } catch (e: Throwable) {
                part.delete()
                throw e
            }
        }
    }

    /** Moves a finished [part] into place and lists it as downloaded. */
    private suspend fun publish(track: Track, part: File, extension: String) {
        val name = "${safeName(track.id)}.$extension"
        val target = File(dir, name)
        val context = kotlin.coroutines.coroutineContext
        synchronized(this) {
            context.ensureActive()
            // Cancellation and commit share this lock, so deletion cannot be undone by a late commit.
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            try { index.publish(track.id, name) } catch (error: Exception) {
                target.delete()
                throw error
            }
            _done.update { it + track.id }
        }
        AppLog.i("Downloads", "Downloaded ${track.id} (${target.length() / 1024} KB)")
    }

    /** A saved stream's name does not say what it holds; its first bytes do. */
    internal fun audioExtension(file: File): String {
        val head = file.inputStream().use { it.readNBytes(12) }
        return when {
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "webm"
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "m4a"
            else -> "audio"
        }
    }

    internal fun safeName(id: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

}
