@file:OptIn(UnstableApi::class)

package com.k4music.android.utils

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow

class CatchingDataSourceFactory(
    private val parent: DataSource.Factory,
    private val onError: ((Throwable, DataSpec?) -> Unit)?
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        private var spec: DataSpec? = null

        private fun report(ex: Exception): Nothing {
            onError?.invoke(ex, spec)
            // Preserve I/O errors so Media3 can apply its network retry policy.
            if (ex is IOException || ex is PlaybackException) throw ex
            throw PlaybackException(
                /* message = */ "Unknown playback error",
                /* cause = */ ex,
                /* errorCode = */ PlaybackException.ERROR_CODE_UNSPECIFIED
            )
        }

        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            return try { parent.open(dataSpec) } catch (ex: Exception) { report(ex) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            try { parent.read(buffer, offset, length) } catch (ex: Exception) { report(ex) }

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

fun DataSource.Factory.handleUnknownErrors(
    onError: ((Throwable, DataSpec?) -> Unit)? = null
): DataSource.Factory = CatchingDataSourceFactory(
    parent = this,
    onError = onError
)

/**
 * Reads pieces of one file as one stream. The resolver may end a read early (a chunk from the cache, a link's
 * edge) and an extractor must not take that for the end of the file: at an element boundary it ends the song there.
 */
internal class PieceReader(
    private val totalLength: () -> Long,
    private val readPiece: (ByteArray, Int, Int) -> Int,
    /** Opens the piece at this position: its size, 0 at the end of the file, or unset when unknown. */
    private val openPiece: (Long) -> Long
) {
    private var position = 0L
    private var pieceLeft = 0L

    fun start(position: Long, pieceLength: Long) {
        this.position = position
        pieceLeft = pieceLength
    }

    /** Carries on where the last read stopped, after the piece was closed. False at the end of the file. */
    fun resume(): Boolean {
        pieceLeft = openPiece(position)
        return pieceLeft != 0L
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val read = readPiece(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                if (pieceLeft > 0) pieceLeft -= read
                return read
            }
            // A piece of unknown size ran to its source's real end; so did one that came up short.
            if (pieceLeft != 0L) return C.RESULT_END_OF_INPUT
            val total = totalLength()
            if (total in 1..position) return C.RESULT_END_OF_INPUT
            pieceLeft = openPiece(position)
            if (pieceLeft == 0L) return C.RESULT_END_OF_INPUT
        }
    }
}

private const val IDLE_CLOSE_MS = 5_000L
private val idleChecks by lazy {
    Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "datasource-idle").apply { isDaemon = true } }
}

class SeamlessDataSourceFactory(
    private val parent: DataSource.Factory,
    private val totalLength: (key: String?) -> Long
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        private lateinit var spec: DataSpec
        private val lock = ReentrantLock()
        private var idleCheck: ScheduledFuture<*>? = null
        private var closedForIdle = false
        @Volatile private var lastUsed = 0L

        /** Where this read ends: what was asked for, or the end of the file (not above 0 when unknown). */
        private fun end() = if (spec.length != C.LENGTH_UNSET.toLong()) spec.position + spec.length else totalLength(spec.key)

        private val reader = PieceReader(
            totalLength = ::end,
            readPiece = parent::read,
            openPiece = { position ->
                parent.close()
                val rest = if (spec.length != C.LENGTH_UNSET.toLong()) end() - position else C.LENGTH_UNSET.toLong()
                try {
                    parent.open(spec.buildUpon().setPosition(position).setLength(rest).build())
                } catch (error: IOException) {
                    if (DataSourceException.isCausedByPositionOutOfRange(error)) 0L else throw error
                }
            }
        )

        /**
         * The player stops reading once it has buffered enough, and may not read again for minutes. Meanwhile an
         * open read would hold its connection and its cache lock, which keeps a download of the same song waiting.
         */
        private fun closeIfIdle() {
            if (System.nanoTime() - lastUsed < TimeUnit.MILLISECONDS.toNanos(IDLE_CLOSE_MS) || !lock.tryLock()) return
            try {
                if (!closedForIdle) {
                    closedForIdle = true
                    runCatching { parent.close() }
                }
            } finally {
                lock.unlock()
            }
        }

        override fun open(dataSpec: DataSpec): Long = lock.withLock {
            spec = dataSpec
            closedForIdle = false
            reader.start(dataSpec.position, parent.open(dataSpec))
            lastUsed = System.nanoTime()
            idleCheck?.cancel(false)
            idleCheck = idleChecks.scheduleWithFixedDelay(::closeIfIdle, IDLE_CLOSE_MS, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
            val end = end()
            if (end > 0) (end - dataSpec.position).coerceAtLeast(0L) else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
            lastUsed = System.nanoTime()
            if (closedForIdle) {
                closedForIdle = false
                if (!reader.resume()) return@withLock C.RESULT_END_OF_INPUT
            }
            reader.read(buffer, offset, length).also { lastUsed = System.nanoTime() }
        }

        override fun close() = lock.withLock {
            idleCheck?.cancel(false)
            idleCheck = null
            closedForIdle = false
            parent.close()
        }

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

class FallbackDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val fallback: DataSource.Factory
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource {
        private var active = parent
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            active.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            active = parent
            try {
                return active.open(dataSpec)
            } catch (error: Exception) {
                runCatching { active.close() }
                active = fallback.createDataSource()
                listeners.forEach(active::addTransferListener)
                try {
                    return active.open(dataSpec)
                } catch (fallbackError: Exception) {
                    runCatching { active.close() }
                    error.addSuppressed(fallbackError)
                    throw error
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
        override fun getUri(): Uri? = active.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
        override fun close() = active.close()
    }

    override fun createDataSource() = Source(upstream.createDataSource())
}

fun DataSource.Factory.withFallback(
    fallbackFactory: DataSource.Factory
): DataSource.Factory = FallbackDataSourceFactory(this, fallbackFactory)

fun DataSource.Factory.withFallback(
    context: Context,
    resolver: ResolvingDataSource.Resolver
) = withFallback(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), resolver))

class RetryingDataSourceFactory(
    private val parent: DataSource.Factory,
    private val maxRetries: Int,
    private val printStackTrace: Boolean,
    private val exponential: Boolean,
    private val predicate: (Throwable) -> Boolean
) : DataSource.Factory {
    init { require(maxRetries > 0) { "At least one playback attempt is required" } }
    inner class Source(private val parent: DataSource) : DataSource by parent {
        override fun open(dataSpec: DataSpec): Long {
            var lastException: Throwable? = null
            var retries = 0
            while (retries < maxRetries) {
                if (retries > 0) Log.d(TAG, "Retry $retries of $maxRetries fetching datasource")

                @Suppress("TooGenericExceptionCaught")
                return try {
                    parent.open(dataSpec)
                } catch (ex: Exception) {
                    lastException = ex
                    runCatching { parent.close() }
                    if (printStackTrace) Log.e(
                        /* tag = */ TAG,
                        /* msg = */ "Exception caught by retry mechanism",
                        /* tr = */ ex
                    )
                    if (predicate(ex) && retries + 1 < maxRetries) {
                        val time = if (exponential) 150L * 2.0.pow(retries).toLong() else 300L
                        Log.d(TAG, "Retry policy accepted retry, sleeping for $time milliseconds")
                        Thread.sleep(time)
                        retries++
                        continue
                    }
                    Log.e(
                        TAG,
                        "Retry policy declined retry, throwing the last exception..."
                    )
                    throw ex
                }
            }
            Log.e(
                TAG,
                "Max retries $maxRetries exceeded, throwing the last exception..."
            )
            throw lastException!!
        }

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

// TODO: fix this mess
inline fun <reified T : Throwable> DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true
) = retryIf(maxRetries, printStackTrace, exponential) { ex -> ex.findCause<T>() != null }

private const val TAG = "DataSource.Factory"

fun DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true,
    predicate: (Throwable) -> Boolean
): DataSource.Factory = RetryingDataSourceFactory(this, maxRetries, printStackTrace, exponential, predicate)

val Cache.asDataSource: CacheDataSource.Factory get() = CacheDataSource.Factory()
    .setCache(this).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

val Context.defaultDataSource
    get() = DefaultDataSource.Factory(
        this,
        DefaultHttpDataSource.Factory().setConnectTimeoutMs(16000)
            .setReadTimeoutMs(8000)
            // A default header, not setUserAgent: the factory UA would overwrite the client UA yt-dlp's URLs are bound to.
            .setDefaultRequestProperties(mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0"))
    )
