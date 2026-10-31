package com.k4music.android.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.k4music.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check, as light as it gets: one small request to GitHub's latest-release JSON (no library, no
 * background service), at most once every six hours and only while the app is on screen. A newer release
 * shows a card on Home and in Settings; tapping it opens the APK download in the browser.
 */
object AppUpdate {
    class Release(val version: String, val apkUrl: String?, val pageUrl: String)

    private const val LATEST = "https://api.github.com/repos/KartikLangwan/K4Audio/releases/latest"
    private const val RELEASES = "https://github.com/KartikLangwan/K4Audio/releases"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private val versionPattern = Regex("[0-9]+(\\.[0-9]+){1,3}")
    private val apkPattern = Regex("K4(Audio|Music)-v.*\\.apk", RegexOption.IGNORE_CASE)
    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available.asStateFlow()
    @Volatile private var checkedAt = 0L

    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

    /** Newer when some numeric part is bigger, comparing "2.15.0" with "2.14.26" part by part. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c > 0
        }
        return false
    }

    internal fun parse(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").trimStart('v', 'V').takeIf { it.matches(versionPattern) } ?: return null
        val assets = o.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (apkPattern.matches(asset.optString("name"))) {
                apk = asset.optString("browser_download_url").takeIf { it.startsWith("https://github.com/") }
                break
            }
        }
        return Release(version, apk, o.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: RELEASES)
    }

    /** Asks GitHub (when due, or [force]d); [available] holds the newer release, or null. Throws when offline. */
    suspend fun refresh(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - checkedAt < EVERY_MS) return@withContext _available.value
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            check(connection.responseCode == 200) { "GitHub answered ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader().use { it.readText().take(200_000) }
            checkedAt = System.currentTimeMillis()
            val release = parse(text)?.takeIf { isNewer(it.version, currentVersion) }
            _available.value = release
            release
        } finally {
            connection.disconnect()
        }
    }

    /** The APK in the browser's downloader (the release page when the release has no APK). */
    fun open(context: Context, release: Release) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl ?: release.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
