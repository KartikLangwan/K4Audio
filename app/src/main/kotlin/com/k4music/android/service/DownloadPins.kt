package com.k4music.android.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import com.k4music.android.Dependencies
import java.util.TreeSet

/** Songs the user downloaded: they share the player's cache but cleanup never removes them. */
object DownloadPins {
    private val prefs by lazy { Dependencies.application.getSharedPreferences("download_pins", Context.MODE_PRIVATE) }
    @Volatile private var pins: Set<String>? = null
    private fun current() = pins ?: synchronized(this) { pins ?: prefs.getStringSet(KEY, emptySet()).orEmpty().toSet().also { pins = it } }

    fun contains(id: String) = id in current()

    @Synchronized fun add(id: String) = store(current() + id)
    @Synchronized fun remove(id: String) = store(current() - id)

    private fun store(value: Set<String>) {
        pins = value
        prefs.edit().putStringSet(KEY, value).apply()
    }

    /** Downloads made before pins existed: read once from the download manager's own records. */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun migrate(context: Context) {
        if (prefs.getBoolean(MIGRATED, false)) return
        runCatching {
            val found = HashSet<String>()
            DefaultDownloadIndex(PlayerService.createDatabaseProvider(context)).getDownloads(Download.STATE_COMPLETED).use {
                while (it.moveToNext()) found += it.download.request.id
            }
            store(current() + found)
            prefs.edit().putBoolean(MIGRATED, true).apply()
        }
    }

    private const val KEY = "ids"
    private const val MIGRATED = "migrated"
}

/**
 * Least-recently-played eviction of everything except downloads, which also do not count against
 * the limit (the setting is about how much it keeps of what was only played).
 */
@OptIn(UnstableApi::class)
class PinnedLruCacheEvictor(private val maxBytes: Long, private val isPinned: (String) -> Boolean) : CacheEvictor {
    private val spans = TreeSet(
        compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position }
    )
    private var totalBytes = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != -1L) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        totalBytes += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        totalBytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, required: Long) {
        if (totalBytes + required <= maxBytes) return // cheap: pins can only make the real number smaller
        var unpinned = spans.sumOf { if (isPinned(it.key)) 0L else it.length }
        for (span in spans.toList()) {
            if (unpinned + required <= maxBytes) return
            if (isPinned(span.key)) continue
            unpinned -= span.length
            cache.removeSpan(span)
        }
    }
}
