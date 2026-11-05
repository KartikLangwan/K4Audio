package com.k4music.desktop.player

import com.k4music.desktop.AppLog
import com.k4music.desktop.k4.StreamFallback
import com.k4music.desktop.prefs.RepeatMode
import com.k4music.desktop.prefs.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.k4music.providers.sponsorblock.requests.segments
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Where a YouTube Music radio starts: a song, or an artist's shuffle/radio endpoint. */
data class RadioSeed(
    val videoId: String? = null,
    val playlistId: String? = null,
    val params: String? = null,
    val playlistSetVideoId: String? = null
)

/** What the UI shows about playback. Position lives in its own flow so ticks redraw only the seek bar. */
data class PlayerState(
    /** Wall-clock time (ms) when playback pauses for the sleep timer, or null. */
    val sleepAt: Long? = null,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val durationSeconds: Double = 0.0,
    val error: String? = null,
    val source: StreamSource? = null
) {
    val current get() = queue.getOrNull(index)
    val hasNext get() = index + 1 < queue.size
}

/**
 * The single owner of playback. Every change runs on one serial dispatcher, so queue edits,
 * loads and mpv events never race. Failures follow [chooseRecovery]: retry or switch source,
 * never skip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerController(private val mpv: Mpv, private val resolver: StreamResolver = StreamResolver()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()

    /** Insertion order before shuffling, by track id, so turning shuffle off restores it. */
    private var unshuffled: List<Track>? = null
    private var loadJob: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var queueGeneration = 0L
    internal val queueVersion get() = queueGeneration
    @Volatile private var closed = false
    @Volatile private var currentStream: Stream? = null
    @Volatile private var skipSegments: List<Pair<Double, Double>> = emptyList()
    private var sleepJob: Job? = null
    private val retries = PlaybackRetryBudget()
    private val fallbacks = PlaybackRetryBudget(limit = 1)
    // Listening time of the current song: only forward steps under 1.5 s count (seeks do not).
    @Volatile private var listenedTrack: Track? = null
    @Volatile private var listenedMs = 0L
    @Volatile private var lastPosition = 0.0
    @Volatile private var lastTick = -1.0
    private var lastSavedPosition = 0.0

    /** Called when the queue changes (for persistence) and when a song starts (for history). */
    var onQueueChanged: (List<Track>, Int, Double) -> Unit = { _, _, _ -> }
    var onTrackStarted: (Track) -> Unit = {}
    /** A song stopped being current after [Long] ms of real listening (history, play time). */
    var onPlayed: (Track, Long) -> Unit = { _, _ -> }
    /** Asked for more songs when the queue runs out with repeat off (radio). */
    var onQueueEnding: suspend (Track) -> List<Track> = { emptyList() }
    /** The chosen sound output failed and playback moved to the Windows default (for a notice). */
    var onAudioDeviceLost: () -> Unit = {}
    @Volatile private var lastAudioRecovery = 0L
    /** Opens a radio for a seed; each call of the returned function gives the next page of songs. */
    var radioSource: (RadioSeed) -> (suspend () -> List<Track>) = { { emptyList() } }
    /** The radio feeding the queue, if the queue came from one (Android's YouTubeRadio). */
    private var radio: (suspend () -> List<Track>)? = null
    private var radioJob: Job? = null
    private var radioRequest = 0L
    /** What the listener wants, whatever is happening: loads and retries follow this, so a pause always holds. */
    @Volatile private var wantPlaying = false
    /** A failure is being dealt with (waiting for a retry or for the network). */
    private var recovering = false
    /** mpv has the file open, so it can seek; until then a seek waits in [pendingSeek]. */
    private var fileReady = false
    private var pendingSeek: Double? = null
    /** A seek is under way: position ticks still in the pipe belong to where it was. */
    private var seeking = false
    /** Playback since the last failure; enough of it earns the retries back. */
    private var stableMs = 0L
    private var audioRetries = 0
    private var lastLoadAt = 0L

    init {
        // Volume 0 while not muted is almost always an accident and makes the app seem broken.
        if (Settings.volume == 0 && !Settings.muted) Settings.volume = 50
        // On the serial owner: mpv may still be loading, and the window must not wait for it.
        scope.launch {
            mpv.setVolume(Settings.volume)
            mpv.setMuted(Settings.muted)
            mpv.setAudioDevice(Settings.audioDevice)
            mpv.setSpeed(Settings.speed.coerceIn(0.25f, 3f))
            mpv.setAudioFilters(AudioEffects.chain())
        }
        mpv.setListener(object : Mpv.Listener {
            override fun onPosition(seconds: Double) {
                // mpv reports every decoded audio chunk (dozens a second); five updates a second keep
                // the seek bar, cover ring and lyrics smooth for a fraction of the redraws.
                if (kotlin.math.abs(seconds - lastTick) < 0.2) return
                lastTick = seconds
                dispatchEvent { onTick(seconds) }
            }
            private fun onTick(seconds: Double) {
                if (seeking) return
                // SponsorBlock: jump over a non-music segment the moment playback enters it.
                skipSegments.firstOrNull { seconds >= it.first && seconds < it.second - 0.5 }?.let { (_, end) ->
                    // Remember where playback is going: a skip to the very end must not read as a cut-off stream.
                    lastPosition = end
                    _position.value = end
                    mpv.seek(end)
                    return
                }
                val step = seconds - lastPosition
                if (step > 0 && step < 1.5 && _state.value.playing) {
                    listenedMs += (step * 1000).toLong()
                    stableMs += (step * 1000).toLong()
                    if (stableMs >= RETRIES_BACK_AFTER_MS) {
                        // A long song may meet several network drops: each one gets the full recovery.
                        retries.reset()
                        fallbacks.reset()
                        audioRetries = 0
                        stableMs = 0
                    }
                }
                lastPosition = seconds
                _position.value = seconds
                // Keep the saved resume point fresh, so a crash or power cut resumes near here.
                if (kotlin.math.abs(seconds - lastSavedPosition) >= 10) {
                    lastSavedPosition = seconds
                    _state.value.let { onQueueChanged(it.queue, it.index, seconds) }
                }
            }
            override fun onDuration(seconds: Double) = dispatchEvent { _state.update { it.copy(durationSeconds = seconds) } }
            override fun onPaused(paused: Boolean) = dispatchEvent { _state.update { it.copy(playing = !paused) } }
            override fun onBuffering(buffering: Boolean) = dispatchEvent {
                if (!buffering) { seekWatchdog?.cancel(); seeking = false }
                _state.update { it.copy(buffering = buffering) }
            }
            override fun onLoaded() = dispatchEvent {
                fileReady = true
                _state.update { it.copy(buffering = false, error = null) }
                pendingSeek?.let { pendingSeek = null; seekNow(it) }
                _state.value.current?.let { track -> onTrackStarted(track); prefetchNext() }
                // Keep a radio queue topped up, as Android does when three songs are left.
                _state.value.let { if (it.queue.size - it.index <= 3) fillRadio() }
            }
            override fun onAudioOutputFailed() = dispatchEvent {
                // A device that went away (Bluetooth off, headphones unplugged): never leave the
                // music silent. A chosen device falls back to the Windows default; the default
                // itself is reopened once, as Windows may have just switched it.
                val now = System.currentTimeMillis()
                if (now - lastAudioRecovery < 5_000) return@dispatchEvent
                lastAudioRecovery = now
                if (Settings.audioDevice != "auto") {
                    AppLog.w(TAG, "Audio output ${Settings.audioDevice} failed; using the Windows default")
                    Settings.audioDevice = "auto"
                    mpv.setAudioDevice("auto")
                    onAudioDeviceLost()
                } else {
                    AppLog.w(TAG, "Default audio output failed; reopening it")
                    mpv.reloadAudio()
                }
            }
            override fun onEnded(error: String?) {
                if (closed || currentStream == null) return
                val endedGeneration = generation
                scope.launch {
                    if (generation != endedGeneration) return@launch
                    val duration = _state.value.durationSeconds
                    when {
                        // A dead sound device is not the song's fault: reopen the output, keep the same stream.
                        error != null && error.contains("audio output", ignoreCase = true) -> {
                            if (++audioRetries > MAX_AUDIO_RETRIES) {
                                // No sound device at all: say so instead of reopening it forever.
                                _state.update { it.copy(buffering = false, playing = false, error = error) }
                                return@launch
                            }
                            mpv.reloadAudio(); delay(RECOVERY_DELAY_MS * audioRetries * audioRetries)
                            if (generation == endedGeneration) load(lastPosition, autoPlay = wantPlaying, fresh = false)
                        }
                        error != null -> onFailure(Failure.Source, error)
                        // A stream the server cut off reads as a normal end: resume it instead of moving on.
                        duration > 0 && lastPosition < duration - EARLY_END_SECONDS -> {
                            AppLog.w(TAG, "Stream ended early at ${lastPosition.toInt()}s of ${duration.toInt()}s; resuming")
                            onFailure(Failure.Network, "Stream ended early")
                        }
                        else -> onTrackFinished()
                    }
                }
            }
        })
    }

    // ---- Queue ----

    /** Native callbacks and listening counters share the same owner as queue transitions. */
    private fun dispatchEvent(block: () -> Unit) {
        val eventGeneration = generation
        scope.launch {
            if (!closed && currentStream != null && generation == eventGeneration) block()
        }
    }

    /** Preserve caller cancellation while atomically checking the queue on its serial owner. */
    internal suspend fun playIfUnchanged(track: Track, expectedVersion: Long) =
        withContext(scope.coroutineContext.minusKey(Job)) {
            if (queueGeneration == expectedVersion) playNow(listOf(track))
        }

    fun play(tracks: List<Track>, startIndex: Int = 0, startSeconds: Double = 0.0, autoPlay: Boolean = true) =
        scope.launch { playNow(tracks, startIndex, startSeconds, autoPlay) }

    /**
     * Plays [track] and fills the queue with related songs, as tapping a song does on Android:
     * the queue follows the song's mood, not other results that merely share its name.
     */
    fun playRadio(track: Track) = scope.launch {
        playNow(listOf(track))
        if (!track.isLocal && !track.isSaavn) {
            radio = radioSource(RadioSeed(videoId = track.id))
            fillRadio()
        }
    }

    /** Plays a radio from [seed] (an artist's Shuffle or Radio button). */
    fun playRadio(seed: RadioSeed) = scope.launch {
        val request = ++radioRequest
        val source = radioSource(seed)
        val first = withContext(Dispatchers.IO) { runCatching { source() }.getOrDefault(emptyList()) }
        if (first.isEmpty() || request != radioRequest) return@launch
        playNow(first)
        radio = source
    }

    /** Appends the radio's next page, skipping songs already queued. */
    private fun fillRadio() {
        val source = radio ?: return
        if (radioJob?.isActive == true) return
        radioJob = scope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { source() }.getOrDefault(emptyList()) }
            if (radio !== source) return@launch
            val known = _state.value.queue.mapTo(HashSet()) { it.id }
            val fresh = more.filter { known.add(it.id) }.let { if (Settings.shuffleEnabled) it.shuffled() else it }
            if (fresh.isEmpty()) return@launch
            unshuffled = unshuffled?.plus(fresh)
            _state.update { st ->
                // An endless radio would otherwise keep every song ever played: forget the oldest ones.
                val drop = if (st.queue.size + fresh.size > MAX_QUEUE && st.index > KEEP_PLAYED) st.index - KEEP_PLAYED else 0
                if (drop > 0) unshuffled = unshuffled?.filter { it !in st.queue.take(drop).toSet() }
                st.copy(queue = st.queue.drop(drop) + fresh, index = st.index - drop)
            }
            changed()
        }
    }

    private fun playNow(tracks: List<Track>, startIndex: Int = 0, startSeconds: Double = 0.0, autoPlay: Boolean = true) {
        if (tracks.isEmpty() || closed) return
        queueGeneration++
        radioRequest++
        radio = null
        radioJob?.cancel()
        var queue = tracks
        var index = startIndex.coerceIn(0, tracks.lastIndex)
        unshuffled = null
        if (Settings.shuffleEnabled) {
            unshuffled = queue
            queue = shuffledFrom(queue, index)
            index = 0
        }
        _state.update { it.copy(queue = queue, index = index) }
        load(startSeconds, autoPlay)
    }

    /** Puts back a saved queue without touching the network; the first play loads it. */
    fun restore(tracks: List<Track>, index: Int, positionSeconds: Double, autoPlay: Boolean = false) = scope.launch {
        if (tracks.isEmpty()) return@launch
        val i = index.coerceIn(0, tracks.lastIndex)
        _state.update { it.copy(queue = tracks, index = i, durationSeconds = tracks[i].durationMs?.div(1000.0) ?: 0.0) }
        _position.value = positionSeconds
        if (autoPlay) load(positionSeconds)
    }

    fun playAt(index: Int) = scope.launch {
        if (index !in _state.value.queue.indices) return@launch
        _state.update { it.copy(index = index) }
        load()
    }

    /** "Play next": right after the current song; never replaces a non-empty queue. */
    fun playNext(track: Track) = scope.launch {
        val s = _state.value
        if (s.queue.isEmpty()) return@launch playNow(listOf(track))
        val queue = s.queue.toMutableList().apply { add(s.index + 1, track) }
        unshuffled = unshuffled?.plus(track)
        _state.update { it.copy(queue = queue) }
        changed()
    }

    fun addToQueue(tracks: List<Track>) = scope.launch {
        if (_state.value.queue.isEmpty()) return@launch playNow(tracks)
        unshuffled = unshuffled?.plus(tracks)
        _state.update { it.copy(queue = it.queue + tracks) }
        changed()
    }

    fun removeAt(index: Int) = scope.launch {
        val s = _state.value
        if (index !in s.queue.indices || index == s.index) return@launch
        val removed = s.queue[index]
        unshuffled = unshuffled?.let { list ->
            val idx = list.indexOf(removed)
            if (idx >= 0) list.toMutableList().apply { removeAt(idx) } else list
        }
        val queue = s.queue.toMutableList().apply { removeAt(index) }
        _state.update { it.copy(queue = queue, index = if (index < it.index) it.index - 1 else it.index) }
        changed()
    }

    fun move(from: Int, to: Int) = scope.launch {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return@launch
        val queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }
        val index = when (s.index) {
            from -> to
            in (from + 1)..to -> s.index - 1
            in to until from -> s.index + 1
            else -> s.index
        }
        _state.update { it.copy(queue = queue, index = index) }
        changed()
    }

    fun clearQueue() = scope.launch {
        generation++
        queueGeneration++
        loadJob?.cancel()
        radio = null
        radioJob?.cancel()
        resolver.precache(emptyList())
        mpv.stop()
        currentStream = null
        unshuffled = null
        _state.value = PlayerState()
        _position.value = 0.0
        changed()
    }

    // ---- Transport ----

    fun togglePlay() = scope.launch {
        val s = _state.value
        when {
            s.current == null -> Unit
            s.error != null -> load(_position.value)
            // Still resolving or recovering: nothing to restart, it just starts paused (or not).
            loadJob?.isActive == true || recovering -> setPaused(wantPlaying)
            currentStream == null -> load(_position.value)
            else -> setPaused(!mpv.isPaused())
        }
    }

    fun pause() = scope.launch { if (_state.value.current != null) setPaused(true) }

    private fun setPaused(paused: Boolean) {
        wantPlaying = !paused
        mpv.setPaused(paused)
        _state.update { it.copy(playing = !paused) }
        if (!paused) prefetchNext()
    }

    fun next() = scope.launch {
        val s = _state.value
        when {
            s.hasNext -> { _state.update { it.copy(index = it.index + 1) }; load() }
            Settings.repeatMode == RepeatMode.All && s.queue.isNotEmpty() -> { _state.update { it.copy(index = 0) }; load() }
            s.queue.isNotEmpty() -> continueAfterQueue() // same as reaching the end: radio, not a dead button
        }
    }

    /** Restarts the song when past 3 s, otherwise goes back (wrapping only with Repeat all). */
    fun previous() = scope.launch {
        val s = _state.value
        when {
            _position.value > 3 || s.queue.isEmpty() -> mpv.seek(0.0)
            s.index > 0 -> { _state.update { it.copy(index = it.index - 1) }; load() }
            Settings.repeatMode == RepeatMode.All -> { _state.update { it.copy(index = it.queue.lastIndex) }; load() }
            else -> mpv.seek(0.0)
        }
    }

    /** "Stats for nerds": codec, bitrate and sample rate of what is playing now. */
    fun stats(): String? {
        val codec = mpv.getString("audio-codec-name") ?: return null
        val bitrate = mpv.getString("audio-bitrate")?.toDoubleOrNull()?.let { "${(it / 1000).toInt()} kbps" }
        val rate = mpv.getString("audio-params/samplerate")?.let { "$it Hz" }
        return listOfNotNull(codec, bitrate, rate).joinToString(" · ")
    }

    private var seekWatchdog: Job? = null

    /** Seeks on the serial owner; a seek that never restarts (dead connection) reloads at the target. */
    fun seek(seconds: Double) {
        _position.value = seconds
        scope.launch {
            lastPosition = seconds
            // Nothing is open yet: the load starts from the position just set.
            if (currentStream == null) return@launch
            if (!fileReady) { pendingSeek = seconds; return@launch }
            seekNow(seconds)
        }
    }

    private fun seekNow(seconds: Double) {
        seeking = true
        mpv.seek(seconds)
        val seekGeneration = generation
        _state.update { it.copy(buffering = true) }
        seekWatchdog?.cancel()
        seekWatchdog = scope.launch {
            delay(SEEK_STALL_MS)
            // Slow is not refused: the link stays valid, a dead one shows as an early end instead.
            if (generation == seekGeneration && _state.value.buffering && !closed) {
                seeking = false
                onFailure(Failure.Network, "Seek stalled", rejectStream = false)
            }
        }
    }
    fun setVolume(percent: Int) { Settings.volume = percent.coerceIn(0, 100); scope.launch { mpv.setVolume(Settings.volume) } }
    fun setMuted(muted: Boolean) { Settings.muted = muted; scope.launch { mpv.setMuted(muted) } }

    /** Sound outputs Windows offers now (refresh when Bluetooth or headphones connect). */
    fun audioDevices(): List<AudioDevice> = mpv.audioDevices()

    /** Plays through [device] from now on ("auto" = follow the Windows default). */
    fun setAudioDevice(device: String) {
        Settings.audioDevice = device
        mpv.setAudioDevice(device)
    }
    fun cycleRepeat() { Settings.repeatMode = Settings.repeatMode.next }

    fun setShuffle(enabled: Boolean) = scope.launch {
        if (Settings.shuffleEnabled == enabled) return@launch
        Settings.shuffleEnabled = enabled
        val s = _state.value
        val current = s.current ?: return@launch
        if (enabled) {
            unshuffled = s.queue
            _state.update { it.copy(queue = shuffledFrom(s.queue, s.index), index = 0) }
        } else {
            val restored = unshuffled?.filter { it in s.queue }.orEmpty() + s.queue.filterNot { it in unshuffled.orEmpty() }
            unshuffled = null
            _state.update { it.copy(queue = restored, index = restored.indexOf(current).coerceAtLeast(0)) }
        }
        changed()
    }

    private fun shuffledFrom(queue: List<Track>, index: Int): List<Track> {
        val current = queue[index]
        val rest = queue.toMutableList().apply { removeAt(index) }.shuffled()
        return listOf(current) + rest
    }

    // ---- Effects, sleep timer ----

    /** Re-applies speed and the audio filter chain (equalizer, effects, normalization, skip silence). */
    fun applyEffects() {
        mpv.setSpeed(Settings.speed.coerceIn(0.25f, 3f))
        mpv.setAudioFilters(AudioEffects.chain())
    }

    /** Pauses playback after [minutes]; null cancels the timer. */
    fun setSleepTimer(minutes: Int?) = scope.launch {
        sleepJob?.cancel()
        if (minutes == null) {
            _state.update { it.copy(sleepAt = null) }
            return@launch
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepAt = at) }
        sleepJob = scope.launch {
            // Wall-clock deadline, re-checked in short sleeps: a PC that slept through it still pauses on waking.
            while (System.currentTimeMillis() < at) delay((at - System.currentTimeMillis()).coerceIn(250L, 15_000L))
            setPaused(true)
            _state.update { it.copy(sleepAt = null) }
        }
    }

    private fun loadSponsorSegments(track: Track) {
        skipSegments = emptyList()
        if (!Settings.sponsorBlockEnabled || track.isLocal || track.isSaavn) return
        val wanted = Settings.sponsorBlockCategories.split(',').map { it.trim() }.toSet()
        scope.launch(Dispatchers.IO) {
            val segments = com.k4music.providers.sponsorblock.SponsorBlock.segments(
                videoId = track.id,
                categories = com.k4music.providers.sponsorblock.models.Category.entries.filter { it.serialName in wanted },
                actions = listOf(com.k4music.providers.sponsorblock.models.Action.Skip)
            )?.getOrNull().orEmpty()
            if (_state.value.current?.id == track.id) {
                skipSegments = segments.map { it.start.inWholeMilliseconds / 1000.0 to it.end.inWholeMilliseconds / 1000.0 }
            }
        }
    }

    // ---- Loading and recovery ----

    private fun flushListening() {
        val track = listenedTrack ?: return
        val ms = listenedMs
        listenedTrack = null
        listenedMs = 0
        if (ms > 0) onPlayed(track, ms)
    }

    /** [fresh]: started by the user (or the queue), so earlier failures of this song no longer count against it. */
    private fun load(startSeconds: Double = 0.0, autoPlay: Boolean = true, fresh: Boolean = true) {
        if (closed) return
        val loadingGeneration = ++generation
        val track = _state.value.current ?: return
        if (listenedTrack?.id != track.id) {
            flushListening()
            listenedTrack = track
        }
        lastPosition = startSeconds
        wantPlaying = autoPlay
        recovering = false
        fileReady = false
        pendingSeek = null
        seeking = false
        seekWatchdog?.cancel()
        loadJob?.cancel()
        // The old song must go silent now, not after the new one resolves (yt-dlp can take seconds),
        // or its position ticks would show against the new song.
        val hadStream = currentStream != null
        currentStream = null
        if (hadStream) mpv.stop()
        if (fresh) { retries.reset(); fallbacks.reset() }
        retries.onMediaTransition(track.id)
        fallbacks.onMediaTransition(track.id)
        currentStream = null
        _position.value = startSeconds
        _state.update { it.copy(buffering = true, playing = autoPlay, error = null, durationSeconds = track.durationMs?.div(1000.0) ?: 0.0) }
        changed()
        // Skipping fast through a list: only the song it stops on gets a yt-dlp run.
        val now = System.currentTimeMillis()
        val skippingFast = now - lastLoadAt < FAST_SKIP_MS
        lastLoadAt = now
        loadJob = scope.launch {
            try {
                if (skippingFast) delay(FAST_SKIP_MS)
                val stream = resolver.resolve(track)
                if (generation != loadingGeneration || closed) return@launch
                mpv.failure?.let { error(it) }
                currentStream = stream
                _state.update { it.copy(source = stream.source) }
                if (stream.source != StreamSource.Saavn) loadSponsorSegments(track) else skipSegments = emptyList()
                // Paused first, or a song meant to wait could blip; a skip made while resolving moved the position.
                mpv.setPaused(!wantPlaying)
                lastPosition = _position.value
                mpv.load(stream.url, _position.value, stream.headers)
                if (wantPlaying) prefetchNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Could not resolve ${track.id}", e)
                onFailure(if (e is IOException || !online()) Failure.Network else Failure.Source, e.message)
            }
        }
    }

    private suspend fun onFailure(failure: Failure, message: String?, rejectStream: Boolean = true) {
        val failedGeneration = generation
        val track = _state.value.current ?: return
        val stream = currentStream
        stableMs = 0
        recovering = true
        // No connection is nobody's fault: nothing is rejected or spent, and it carries on by itself once back.
        val savedFile = stream != null && java.io.File(stream.url).isFile
        if (!track.isLocal && !savedFile && !online()) {
            AppLog.w(TAG, "No connection; waiting to resume ${track.id}")
            _state.update { it.copy(buffering = true) }
            while (!online()) {
                delay(NETWORK_POLL_MS)
                if (generation != failedGeneration || closed) return
            }
            if (generation == failedGeneration) load(_position.value, autoPlay = wantPlaying, fresh = false)
            return
        }
        if (stream != null && rejectStream) resolver.reject(track, stream)
        val recovery = chooseRecovery(
            failure = failure,
            id = track.id,
            fallbackAllowed = !track.isSaavn && !track.isLocal && Settings.smartFallback,
            retries = retries,
            fallbacks = fallbacks
        )
        AppLog.w(TAG, "Playback failure for ${track.id} ($failure: $message) -> $recovery")
        when (recovery) {
            Recovery.Retry -> { delay(RECOVERY_DELAY_MS * retries.attempts * retries.attempts); if (generation == failedGeneration) load(_position.value, autoPlay = wantPlaying, fresh = false) }
            Recovery.Fallback -> {
                delay(RECOVERY_DELAY_MS)
                if (generation == failedGeneration) { StreamFallback.force(track.id); load(_position.value, autoPlay = wantPlaying, fresh = false) }
            }
            Recovery.None -> {
                recovering = false
                _state.update { it.copy(buffering = false, playing = false, error = message ?: "Playback failed") }
            }
        }
    }

    private suspend fun onTrackFinished() {
        val s = _state.value
        when {
            Settings.repeatMode == RepeatMode.One -> load()
            s.hasNext -> { _state.update { it.copy(index = it.index + 1) }; load() }
            Settings.repeatMode == RepeatMode.All && s.queue.isNotEmpty() -> { _state.update { it.copy(index = 0) }; load() }
            else -> continueAfterQueue()
        }
    }

    private suspend fun continueAfterQueue() {
        val s = _state.value
        val currentTrack = s.current
        val finishingGeneration = generation
        val finishingQueue = queueGeneration
        suspend fun lookup() = currentTrack?.let { runCatching { onQueueEnding(it) }.getOrNull() }.orEmpty()
            .filter { candidate -> _state.value.queue.none { it.id == candidate.id } }
        // One failed lookup (a network blip) must not end the music.
        val more = lookup().ifEmpty { delay(RADIO_RETRY_MS); lookup() }
        // Only apply radio if the user hasn't switched songs or changed playlists during lookup
        if (generation != finishingGeneration || queueGeneration != finishingQueue) return
        if (more.isNotEmpty()) {
            _state.update { it.copy(queue = it.queue + more, index = it.index + 1) }
            load()
        } else {
            // End of the queue: rewind to the first song and pause (Android behaviour).
            _state.update { it.copy(index = 0) }
            load(autoPlay = false)
        }
    }

    /**
     * Saves the next song to disk while this one plays, so it starts at once. Not the song playing:
     * mpv already holds it, and saving it too would fetch it twice.
     */
    private fun prefetchNext() {
        val s = _state.value
        if (!s.playing) return
        resolver.precache(listOfNotNull(s.queue.getOrNull(s.index + 1)))
    }

    private fun changed() {
        queueGeneration++
        val s = _state.value
        onQueueChanged(s.queue, s.index, _position.value)
    }

    private suspend fun online(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress("music.youtube.com", 443), 3_000) }
            true
        }.getOrDefault(false)
    }

    fun close() = runBlocking {
        // Join the serial playback dispatcher before disposing native resources.
        withContext(scope.coroutineContext.minusKey(Job)) {
            if (closed) return@withContext
            closed = true
            currentStream = null
            generation++
            scope.cancel()
            flushListening()
            changed()
            QueueStore.flush()
            resolver.close()
            mpv.close()
        }
    }

    private companion object {
        const val TAG = "Player"
        const val RECOVERY_DELAY_MS = 300L
        const val SEEK_STALL_MS = 15_000L
        const val RETRIES_BACK_AFTER_MS = 30_000L
        const val MAX_AUDIO_RETRIES = 3
        const val NETWORK_POLL_MS = 3_000L
        const val RADIO_RETRY_MS = 3_000L
        const val FAST_SKIP_MS = 400L
        const val MAX_QUEUE = 500
        const val KEEP_PLAYED = 50
        /** An end this far before the song's length is a broken stream, not the song finishing. */
        const val EARLY_END_SECONDS = 5.0
    }
}
