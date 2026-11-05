package com.k4music.desktop.player

import com.k4music.desktop.AppLog
import com.k4music.android.playlistimport.Candidate
import com.k4music.android.playlistimport.pickBestMatch
import com.k4music.desktop.AppPaths
import com.k4music.desktop.k4.StreamFallback
import com.k4music.desktop.k4.findSaavnStream
import com.k4music.desktop.k4.resolveSaavnStream
import com.k4music.desktop.prefs.Settings
import com.k4music.providers.innertube.Innertube
import com.k4music.providers.innertube.models.bodies.PlayerBody
import com.k4music.providers.innertube.requests.wholeSongPlayer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where a resolved stream came from; decides how a later failure is handled. */
enum class StreamSource { Local, YtDlp, Saavn }

/** [temporary]: a stand-in while YouTube was slow; not kept, so the next play tries YouTube again. */
data class Stream(val url: String, val source: StreamSource, val headers: Map<String, String> = emptyMap(), val temporary: Boolean = false)

/** Nothing could serve this song (not a connection problem). */
class UnplayableException(message: String) : Exception(message)

/**
 * Downloaded or cached file -> YouTube's whole-song link (under a second, no process) -> yt-dlp (30 s deadline)
 * -> the same song on JioSaavn (Smart fallback) -> unplayable.
 * Resolved urls are kept per song for a few hours; one that gets refused is dropped at once.
 *
 * Only the whole-song client: other Innertube clients' links can be refused after the first part (HTTP 403).
 */
class StreamResolver {
    private data class Cached(val stream: Stream, val at: Long)
    private val cache = com.k4music.desktop.BoundedCache<String, Cached>(128)
    fun forget(id: String) { cache.remove(id) }
    fun close() { background.cancel() }

    private val directRefused = ConcurrentHashMap.newKeySet<String>()

    /** Saved files that would not play: this session goes online for them instead. */
    private val badFiles = ConcurrentHashMap.newKeySet<String>()

    /** The current [Stream] of [track] was refused; the next resolve picks the next source. */
    fun reject(track: Track, stream: Stream) {
        forget(track.id)
        if (stream.source == StreamSource.YtDlp) directRefused.add(track.id) // yt-dlp's turn, with its other clients
        if (!track.isLocal && File(stream.url).isFile) {
            badFiles.add(stream.url)
            StreamCache.drop(track.id, File(stream.url))
        }
        if (stream.source == StreamSource.Saavn && !track.isSaavn) StreamFallback.drop(track.id)
    }

    /** A resolve running right now, shared by whoever wants that song; the last one to lose interest stops it. */
    private class Flight(val job: kotlinx.coroutines.Deferred<Stream>) { var waiters = 0 }
    private val inFlight = ConcurrentHashMap<String, Flight>()
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** [patient]: for keeping the file (downloads): waits for the real source instead of a quick stand-in. */
    suspend fun resolve(track: Track, patient: Boolean = false): Stream {
        val key = if (patient) "${track.id}#patient" else track.id
        var started = false
        // Lazy, then started after it sits in the map: a handler that ran inside compute could remove its own key.
        val flight = inFlight.compute(key) { _, running ->
            (running ?: Flight(background.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { resolveNow(track, patient) }).also { started = true })
                .also { it.waiters++ }
        }!!
        if (started) {
            flight.job.invokeOnCompletion { inFlight.remove(key, flight) }
            flight.job.start()
        }
        try {
            return flight.job.await()
        } finally {
            // Skipped past: nobody wants this song now, so its yt-dlp stops rather than slowing the next one.
            var abandoned = false
            inFlight.compute(key) { _, current ->
                if (current === flight && --flight.waiters == 0 && !flight.job.isCompleted) { abandoned = true; null } else current
            }
            if (abandoned) flight.job.cancel() // outside compute: its completion handler touches the same map
        }
    }

    private var precacheJob: kotlinx.coroutines.Job? = null
    private var precaching = emptyList<Track>()

    /**
     * Saves [tracks] to the disk cache one after the other (the song playing, then the next), replacing
     * any earlier request: they then start at once, can be skipped through and survive a lost connection.
     */
    @Synchronized
    fun precache(tracks: List<Track>) {
        // Every play and resume asks again: starting over would throw away what is saved so far.
        if (tracks == precaching && precacheJob?.isActive == true) return
        precaching = tracks
        precacheJob?.cancel()
        precacheJob = background.launch {
            kotlinx.coroutines.delay(PRECACHE_DELAY_MS) // the song just started comes first
            for (track in tracks) {
                if (track.isLocal || Downloads.file(track.id) != null || StreamCache.find(track.id) != null) continue
                try {
                    // Patient: what is kept is the real source, never the quick stand-in.
                    val stream = resolve(track, patient = true)
                    if (!File(stream.url).isFile) StreamCache.store(track.id, stream)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.i(TAG, "Could not cache ${track.id} ahead: ${error.message}")
                }
            }
        }
    }

    private suspend fun resolveNow(track: Track, patient: Boolean): Stream = withContext(Dispatchers.IO) {
        // A downloaded song always plays from disk: offline-safe and instant.
        Downloads.file(track.id)?.takeIf { it.absolutePath !in badFiles }?.let { return@withContext Stream(it.absolutePath, StreamSource.Local) }
        if (track.isLocal) {
            val file = File(track.localPath)
            if (!file.isFile) throw UnplayableException("File not found: ${file.name}")
            return@withContext Stream(file.absolutePath, StreamSource.Local)
        }
        val fallbackOn = Settings.smartFallback
        if (!fallbackOn && StreamFallback.url(track.id) != null) {
            StreamFallback.drop(track.id) // fallback turned off: back to YouTube
            forget(track.id)
        }
        if (fallbackOn && StreamFallback.isForced(track.id)) {
            return@withContext saavnFallback(track) ?: throw UnplayableException("No JioSaavn match")
        }
        StreamCache.find(track.id)?.takeIf { (file, _) -> file.absolutePath !in badFiles }?.let { (file, source) ->
            file.setLastModified(System.currentTimeMillis()) // just played: cleanup takes others first
            return@withContext Stream(file.absolutePath, source)
        }
        cache[track.id]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return@withContext it.stream }

        val pinned = if (fallbackOn) StreamFallback.url(track.id) else null // read once: another thread may drop it
        val stream = when {
            track.isSaavn -> resolveSaavnStream(track.id, Settings.audioQuality.saavn)
                ?.let { Stream(it, StreamSource.Saavn) }
                ?: throw UnplayableException("JioSaavn stream unavailable")
            pinned != null -> Stream(pinned, StreamSource.Saavn)
            else -> {
                val youtube = wholeSongLink(track) ?: when {
                    youtubeBlocked() -> null // don't keep knocking while YouTube is blocking this network
                    fallbackOn && !patient -> youtubeOrQuickFallback(track)
                    else -> ytDlp(track.id)
                }
                // A blocked network is not this song's fault: play JioSaavn for now, YouTube again later.
                val blocked = youtubeBlocked()
                youtube
                    ?: (if (fallbackOn) saavnFallback(track, remember = !blocked)?.let { if (blocked) it.copy(temporary = true) else it } else null)
                    ?: (if (fallbackOn && !blocked) similarYoutube(track) else null)
                    ?: throw UnplayableException(if (blocked) "YouTube is blocking this network for now" else "No playable stream")
            }
        }
        if (!stream.temporary) cache[track.id] = Cached(stream, System.currentTimeMillis())
        stream
    }

    /** YouTube's own whole-song link, when it gives one; everything else about the song goes on as before. */
    private suspend fun wholeSongLink(track: Track): Stream? {
        if (track.id in directRefused) return null
        val answer = kotlinx.coroutines.withTimeoutOrNull(DIRECT_TIMEOUT_MS) { Innertube.wholeSongPlayer(PlayerBody(videoId = track.id)) }?.getOrNull()
        val url = answer?.streamingData?.audioFormat(Settings.audioQuality.youtubeItags)?.url?.takeIf { it.isNotBlank() } ?: return null
        return Stream(url, StreamSource.YtDlp)
    }

    /**
     * YouTube normally answers in 2-3 s. When it is slow (bad network, YouTube busy), the same song
     * is looked up on JioSaavn meanwhile and whichever is ready first plays, instead of waiting out
     * yt-dlp's full deadline.
     */
    private suspend fun youtubeOrQuickFallback(track: Track): Stream? {
        // Not a child of this call: a coroutine scope would wait for yt-dlp before answering, which is the whole point lost.
        val yt = background.async { ytDlp(track.id) }
        try {
            val quick = kotlinx.coroutines.withTimeoutOrNull(SLOW_YOUTUBE_MS) { yt.await() }
            if (quick != null || yt.isCompleted) return quick ?: yt.await()
            AppLog.w(TAG, "YouTube is slow for ${track.id}; trying JioSaavn meanwhile")
            val saavn = saavnFallback(track, remember = false)
            if (saavn != null && !yt.isCompleted) {
                // The stand-in plays now; YouTube's answer is kept for the next time.
                background.launch { runCatching { yt.await() }.getOrNull()?.let { cache[track.id] = Cached(it, System.currentTimeMillis()) } }
                return saavn.copy(temporary = true)
            }
            return yt.await() ?: saavn?.copy(temporary = true)
        } catch (error: kotlinx.coroutines.CancellationException) {
            yt.cancel()
            throw error
        }
    }

    /** Runs the bundled yt-dlp.exe with QuickJS for YouTube's JS challenge. Null on failure/timeout. */
    private suspend fun ytDlp(videoId: String): Stream? = try {
        runInterruptible(Dispatchers.IO) {
            com.k4music.desktop.data.Maintenance.usingYtDlp {
                // "Sign in to confirm you're not a bot" is often specific to yt-dlp's default web client:
                // try the other clients once before treating the network as blocked.
                val blocked = java.util.concurrent.atomic.AtomicBoolean(false)
                // Measured 2026-11-05: android_vr's links are refused past the first MiB and tv fails; these serve the whole song.
                val alt = "youtube:player_client=android_sdkless,web_embedded"
                // The client that worked last time goes first, so a bot-checked client costs one run, not one per song.
                val first = if (useAltClient) alt else null
                val second = if (useAltClient) null else alt
                ytDlpNow(videoId, first, blocked)
                    ?: if (blocked.get()) ytDlpNow(videoId, second, blocked, markBlocked = true)?.also { useAltClient = second != null } else null
            }
        }
    } catch (error: java.io.IOException) {
        AppLog.w(TAG, "yt-dlp unavailable: ${error.message}")
        null
    }

    private fun ytDlpNow(videoId: String, extractorArgs: String?, blocked: java.util.concurrent.atomic.AtomicBoolean, markBlocked: Boolean = false): Stream? {
        val exe = AppPaths.tools.resolve("yt-dlp/yt-dlp.exe")
        val qjs = AppPaths.tools.resolve("qjs.exe")
        if (!exe.isFile) return null
        val temp = AppPaths.local.resolve("tmp").apply { mkdirs() }
        val process = ProcessBuilder(
            // The cache keeps YouTube's player script and solved challenges: later songs resolve much faster.
            exe.absolutePath, "-J", "--no-warnings", "--no-playlist",
            "--cache-dir", AppPaths.local.resolve("yt-dlp-cache").absolutePath,
            // Streaming quality (Settings) picks the format; any audio if those are missing.
            "-f", (Settings.audioQuality.youtubeItags.map { it.toString() } + "bestaudio" + "best").joinToString("/"), "--socket-timeout", "12", "--retries", "2",
            "--js-runtimes", "quickjs:${qjs.absolutePath}",
            *(Settings.ytCookies.browser?.let { arrayOf("--cookies-from-browser", it) } ?: emptyArray()),
            *(if (extractorArgs != null) arrayOf("--extractor-args", extractorArgs) else emptyArray()),
            "https://www.youtube.com/watch?v=$videoId"
        ).apply {
            // yt-dlp unpacks itself into TEMP; keep that inside K4 Audio's own folder.
            environment()["TEMP"] = temp.absolutePath
            environment()["TMP"] = temp.absolutePath
        }.start()
        return try {
            val output = StringBuilder()
            val errors = StringBuilder()
            val reader = kotlin.concurrent.thread(isDaemon = true, name = "ytdlp-stdout") {
                runCatching { output.append(process.inputStream.bufferedReader().readText()) }
            }
            val errorReader = kotlin.concurrent.thread(isDaemon = true, name = "ytdlp-stderr") {
                runCatching { errors.append(process.errorStream.bufferedReader().readText().takeLast(2_000)) }
            }
            if (!process.waitFor(YT_DLP_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                AppLog.w(TAG, "yt-dlp timed out for $videoId")
                return null
            }
            reader.join(2_000)
            errorReader.join(1_000)
            if (reader.isAlive) return null // the answer is still arriving: never parse half of it
            val info = runCatching { json.decodeFromString(YtDlpInfo.serializer(), output.toString()) }
                .onFailure {
                    val error = errors.lines().lastOrNull { it.startsWith("ERROR") }.orEmpty().take(200)
                    AppLog.w(TAG, "yt-dlp gave no usable answer for $videoId (exit ${process.exitValue()}): $error")
                    if (BLOCKED_SIGNS.any { it in error }) { blocked.set(true); if (markBlocked) onYoutubeBlocked() }
                    else if (GONE_SIGNS.none { it in error }) blocked.set(true) // odd failure: other clients often still work
                }
                .getOrNull() ?: return null
            if (info.id != videoId) return null
            val url = info.url?.takeIf { it.isNotBlank() }
                ?: info.formats?.firstOrNull { it.formatId == info.formatId }?.url?.takeIf { it.isNotBlank() }
                ?: return null
            Stream(url, StreamSource.YtDlp, info.httpHeaders)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Last resort: the song's own video and JioSaavn both failed, so look for another YouTube upload of it.
     * Only a result whose title, artist or duration pass the same check as JioSaavn matches is played.
     */
    private suspend fun similarYoutube(track: Track): Stream? {
        val exe = AppPaths.tools.resolve("yt-dlp/yt-dlp.exe")
        if (!exe.isFile || track.title.isBlank()) return null
        val lines = runCatching {
            runInterruptible(Dispatchers.IO) {
                com.k4music.desktop.data.Maintenance.usingYtDlp {
                    kotlinx.coroutines.runBlocking {
                        com.k4music.desktop.data.Maintenance.runTool(listOf(
                            exe.absolutePath, "--flat-playlist", "--no-warnings", "--socket-timeout", "10",
                            "--encoding", "utf-8", "--print", "%(id)s	%(title)s	%(channel)s	%(duration)s",
                            "ytsearch5:${track.title} ${track.artists.orEmpty()}".trim()
                        ), 20)
                    }
                }
            }
        }.getOrNull()?.lines().orEmpty()
        val candidates = lines.mapNotNull { line ->
            val f = line.split('	')
            if (f.size < 4 || f[0].length != 11 || f[0] == track.id) null
            else Candidate(f[1], f[2], f[3].toDoubleOrNull()?.let { (it * 1000).toLong() }, f[0])
        }
        val match = pickBestMatch(track.title, track.artists.orEmpty(), track.durationMs, candidates) ?: return null
        AppLog.i(TAG, "Playing ${track.id} from another YouTube upload (${match.payload})")
        return ytDlp(match.payload)
    }

    /**
     * YouTube sometimes refuses every request from a network for a while ("Sign in to confirm you're
     * not a bot", HTTP 429). Then yt-dlp rests for [BLOCK_PAUSE_MS] and songs play from JioSaavn
     * without being pinned to it.
     */
    @Volatile private var blockedUntil = 0L
    @Volatile private var useAltClient = false
    /** Called (from a background thread) when YouTube starts blocking this network. */
    var onBlocked: () -> Unit = {}
    private fun youtubeBlocked() = System.currentTimeMillis() < blockedUntil
    private fun onYoutubeBlocked() {
        val wasBlocked = youtubeBlocked()
        blockedUntil = System.currentTimeMillis() + BLOCK_PAUSE_MS
        if (!wasBlocked) {
            AppLog.w(TAG, "YouTube is blocking this network; JioSaavn for the next ${BLOCK_PAUSE_MS / 60_000} minutes")
            onBlocked()
        }
    }

    /** [remember]: save the match so later plays go straight to JioSaavn (YouTube refused the song). */
    private suspend fun saavnFallback(track: Track, remember: Boolean = true): Stream? {
        val url = runCatching {
            findSaavnStream(track.title, track.artists.orEmpty(), track.durationMs, Settings.audioQuality.saavn)
        }.getOrNull()
        if (remember) StreamFallback.resolved(track.id, url)
        if (url == null) {
            AppLog.w(TAG, "No JioSaavn fallback for ${track.id} (${track.title})")
            return null
        }
        AppLog.i(TAG, "Playing ${track.id} from the JioSaavn fallback")
        return Stream(url, StreamSource.Saavn)
    }

    @Serializable
    private data class YtDlpInfo(
        val id: String,
        @SerialName("format_id") val formatId: String? = null,
        val url: String? = null,
        val formats: List<Format>? = null,
        @SerialName("http_headers") val httpHeaders: Map<String, String> = emptyMap()
    ) {
        @Serializable
        data class Format(@SerialName("format_id") val formatId: String, val url: String? = null)
    }

    private companion object {
        const val TAG = "StreamResolver"
        const val TTL_MS = 3 * 60 * 60 * 1000L
        const val PRECACHE_DELAY_MS = 3_000L
        const val DIRECT_TIMEOUT_MS = 6_000L
        const val SLOW_YOUTUBE_MS = 7_000L
        const val YT_DLP_TIMEOUT_S = 30L // a cold start (unpack + JS challenge) can pass 15 s and was killed
        const val BLOCK_PAUSE_MS = 10 * 60 * 1000L
        /** yt-dlp errors that mean "YouTube is refusing this network", not "this song is unavailable". */
        val BLOCKED_SIGNS = listOf("not a bot", "HTTP Error 429", "LOGIN_REQUIRED", "page needs to be reloaded")
        /** Songs no client can fix: do not spend a second yt-dlp run on them. */
        val GONE_SIGNS = listOf("Video unavailable", "Private video", "has been removed", "copyright", "not available in your country")
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
