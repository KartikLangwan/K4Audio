package com.k4music.android.utils

import androidx.media3.common.PlaybackException

/** Terminal-error recovery is bounded per track; a successful prepare cannot reset it. */
internal class PlaybackRetryBudget(private val limit: Int = 2) {
    private var mediaId: String? = null
    var attempts = 0
        private set
    fun onMediaTransition(id: String?) {
        if (id != mediaId) { mediaId = id; attempts = 0 }
    }
    fun reset() { mediaId = null; attempts = 0 }
    fun tryAcquire(id: String): Boolean {
        onMediaTransition(id)
        if (attempts >= limit) return false
        attempts++
        return true
    }
}

/**
 * Without a PO token, the iOS client's links serve about the first MiB at most, on many songs only the first
 * request (HTTP 403 after, measured). Enough to start a song at once while yt-dlp gets the full link. This
 * only matters when the whole-song client (see Innertube.player) has failed.
 */
const val DIRECT_STREAM_LIMIT = 1024 * 1024L

/** How much to ask of a direct link at [position]: one chunk, never past its limit or the song's end. */
fun directRangeLength(position: Long, remaining: Long, chunk: Long): Long =
    minOf(chunk, DIRECT_STREAM_LIMIT - position, remaining).coerceAtLeast(1L)

internal fun isRecoverablePlaybackFailure(code: Int, httpStatus: Int?): Boolean =
    if (httpStatus != null) httpStatus in setOf(403, 408, 410, 416, 429, 500, 502, 503, 504)
    else code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED // a failed stream lookup: try a fresh link first

/** Output/decoder faults say nothing about the stream: never pin the song to the fallback for them. */
internal fun isDeviceFailure(code: Int): Boolean = code in 5001..5002 || code in 4001..4003

internal enum class Recovery { Retry, Fallback, None }

/**
 * What to do about a failed stream; the song is never skipped. A lost connection retries
 * the same source; a broken source switches to the fallback (once); then plain retries.
 */
internal fun chooseRecovery(
    code: Int,
    httpStatus: Int?,
    id: String,
    fallbackAllowed: Boolean,
    retries: PlaybackRetryBudget,
    fallbacks: PlaybackRetryBudget
): Recovery = when {
    httpStatus == null && isRecoverablePlaybackFailure(code, null) && retries.tryAcquire(id) -> Recovery.Retry
    fallbackAllowed && !isDeviceFailure(code) && fallbacks.tryAcquire(id) -> Recovery.Fallback
    (isRecoverablePlaybackFailure(code, httpStatus) || isDeviceFailure(code)) && retries.tryAcquire(id) -> Recovery.Retry
    else -> Recovery.None
}

/**
 * On mobile data the player stops reading two minutes ahead and starts again under one, so it loads
 * in a few large bursts (the radio can sleep in between) and a skipped song costs little.
 */
internal class MobileDataRest {
    private var resting = false

    fun resting(metered: Boolean, bufferedUs: Long): Boolean {
        resting = when {
            !metered -> false
            bufferedUs >= 120_000_000L -> true
            bufferedUs < 60_000_000L -> false
            else -> resting
        }
        return resting
    }
}
