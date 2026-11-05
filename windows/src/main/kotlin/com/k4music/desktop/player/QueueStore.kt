package com.k4music.desktop.player

import com.k4music.desktop.AppLog
import com.k4music.desktop.AppPaths
import com.k4music.desktop.prefs.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Persistent queue (Settings → Player → Persistent queue): saved after changes, restored at
 * start without playing — playback starts when the user presses play, as on Android.
 */
object QueueStore {
    @Serializable
    data class Saved(val queue: List<Track>, val index: Int, val position: Double, val playing: Boolean = false)

    private val file get() = AppPaths.data.resolve("queue.json")
    /** Playback position only ("index seconds"): rewritten every few seconds, while the big queue file is not. */
    private val positionFile get() = AppPaths.data.resolve("queue.pos")
    private var onDisk: Saved? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "k4-queue").apply { isDaemon = true } }
    @Volatile private var pending: ScheduledFuture<*>? = null
    @Volatile private var lastSaved: Saved? = null

    /** [windowShown]: someone just opened the app. A start hidden in the tray stays quiet and uses no network. */
    fun attach(player: PlayerController, windowShown: Boolean = true) {
        player.onQueueChanged = { queue, index, position, playing ->
            val s = Saved(queue, index, position, playing)
            save(s)
        }
        if (!Settings.persistentQueue) return
        runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.queue.isNotEmpty() }
            ?.let { saved ->
                val newer = runCatching { positionFile.readText().trim().split(' ') }.getOrNull()
                    ?.takeIf { it.size >= 2 && it[0].toIntOrNull() == saved.index }
                val playing = newer?.getOrNull(2)?.toBooleanStrictOrNull() ?: saved.playing
                onDisk = saved
                player.restore(
                    saved.queue, saved.index, newer?.get(1)?.toDoubleOrNull() ?: saved.position,
                    autoPlay = windowShown && playing && Settings.resumeOnStart,
                    preload = windowShown
                )
            }
    }

    @Synchronized
    internal fun save(saved: Saved) {
        lastSaved = saved
        pending?.cancel(false)
        pending = writer.schedule({
            writeAtomic(saved)
        }, 500, TimeUnit.MILLISECONDS)
    }

    /** Synchronously flushes any pending queue save to disk before process exit. */
    @Synchronized
    fun flush() {
        pending?.cancel(false)
        val s = lastSaved ?: return
        // The final write queues behind any write which has already started.
        writer.submit { writeAtomic(s) }.get(15, TimeUnit.SECONDS)
    }

    private fun writeAtomic(saved: Saved) {
        runCatching {
            if (!Settings.persistentQueue) {
                file.delete(); positionFile.delete(); onDisk = null
                return
            }
            val unchanged = onDisk?.let { it.queue == saved.queue && it.index == saved.index } == true
            if (unchanged) {
                replace(positionFile, "${saved.index} ${saved.position} ${saved.playing}".toByteArray(Charsets.UTF_8))
            } else {
                replace(file, json.encodeToString(Saved.serializer(), saved).toByteArray(Charsets.UTF_8))
                replace(positionFile, "${saved.index} ${saved.position} ${saved.playing}".toByteArray(Charsets.UTF_8))
                onDisk = saved
            }
        }.onFailure { AppLog.w("Queue", "Could not save the queue safely", it) }
    }

    private fun replace(target: File, bytes: ByteArray) {
        val temp = File(target.path + ".tmp")
        java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
