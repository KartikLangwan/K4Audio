package com.k4music.android.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import com.k4music.android.Database
import com.k4music.app.R
import com.k4music.android.transaction
import com.k4music.android.utils.ActionReceiver
import com.k4music.android.utils.download
import com.k4music.android.utils.intent
import com.k4music.android.utils.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import android.util.Log

private val executor = Executors.newCachedThreadPool()
private val coroutineScope = CoroutineScope(
    executor.asCoroutineDispatcher() +
        SupervisorJob() +
        CoroutineExceptionHandler { _, error -> Log.e("K4Audio", "Unhandled background error", error) } +
        CoroutineName("PrecacheService-Worker-Scope")
)

// While the class is not a singleton (lifecycle), there should only be one download state at a time
private val mutableDownloadState = MutableStateFlow(false)
val downloadState = mutableDownloadState.asStateFlow()

/** Live state of one song's download: active ones and failed ones that were not retried yet. */
@OptIn(UnstableApi::class)
data class DownloadProgress(val id: String, val percent: Int, val state: Int) {
    val failed get() = state == Download.STATE_FAILED
}

private val mutableDownloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
val downloadProgress = mutableDownloadProgress.asStateFlow()

private const val DOWNLOAD_NOTIFICATION_UPDATE_INTERVAL = 1000L // default
private const val DOWNLOAD_WORK_NAME = "precacher-work"

@OptIn(UnstableApi::class)
class PrecacheService : DownloadService(
    /* foregroundNotificationId             = */ ServiceNotifications.download.notificationId!!,
    /* foregroundNotificationUpdateInterval = */ DOWNLOAD_NOTIFICATION_UPDATE_INTERVAL,
    /* channelId                            = */ ServiceNotifications.download.id,
    /* channelNameResourceId                = */ R.string.pre_cache,
    /* channelDescriptionResourceId         = */ 0
) {
    private val downloadQueue =
        Channel<DownloadManager>(onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val downloadNotificationHelper by lazy {
        DownloadNotificationHelper(
            /* context = */ this,
            /* channelId = */ ServiceNotifications.download.id
        )
    }

    private val notificationActionReceiver = NotificationActionReceiver()

    inner class NotificationActionReceiver : ActionReceiver("com.k4music.android.precache") {
        val cancel by action { context, _ ->
            runCatching {
                sendRemoveAllDownloads(
                    /* context         = */ context,
                    /* clazz           = */ PrecacheService::class.java,
                    /* foreground      = */ true
                )
            }.recoverCatching {
                sendRemoveAllDownloads(
                    /* context         = */ context,
                    /* clazz           = */ PrecacheService::class.java,
                    /* foreground      = */ false
                )
            }
        }
    }

    private var progressUpdaterJob: Job? = null
    private var progressTicker: Job? = null

    // DownloadManager must be read on its application thread (main).
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: DownloadManager? = null

    private fun publishProgress() {
        val active = manager?.currentDownloads.orEmpty()
            .filter { it.state != Download.STATE_REMOVING }
            .associate {
                it.request.id to DownloadProgress(
                    id = it.request.id,
                    percent = it.percentDownloaded.takeIf { p -> p >= 0f }?.toInt()?.coerceIn(0, 100) ?: 0,
                    state = it.state
                )
            }
        mutableDownloadProgress.update { old -> old.filter { (id, v) -> v.failed && id !in active } + active }
    }

    override fun onCreate() {
        super.onCreate()

        notificationActionReceiver.register(flags = ContextCompat.RECEIVER_NOT_EXPORTED)
        mutableDownloadState.update { false }
    }

    @kotlin.OptIn(FlowPreview::class)
    override fun getDownloadManager(): DownloadManager {
        // A process singleton, shared with the player: a download never has to start the player service for it.
        val cache = PlayerService.createCache(applicationContext)

        progressUpdaterJob?.cancel()
        progressUpdaterJob = coroutineScope.launch {
            downloadQueue
                .receiveAsFlow()
                .debounce(100.milliseconds)
                .collect { downloadManager ->
                    mutableDownloadState.update { !downloadManager.isIdle }
                }
        }

        return DownloadManager(
            /* context = */ this,
            /* databaseProvider = */ PlayerService.createDatabaseProvider(this),
            /* cache = */ cache,
            /* upstreamFactory = */ PlayerService.createYouTubeDataSourceResolverFactory(
                findMediaItem = { null },
                context = this,
                cache = cache,
                chunkLength = null
            ),
            /* executor = */ executor
        ).apply {
            maxParallelDownloads = 2 // each one runs the Python resolver; four at once starved the UI
            minRetryCount = 3
            requirements = Requirements(Requirements.NETWORK)

            manager = this
            progressTicker?.cancel()
            progressTicker = mainScope.launch {
                while (true) {
                    publishProgress()
                    delay(if (mutableDownloadState.value) 500 else 3_000) // idle: no need to wake the main thread twice a second
                }
            }

            addListener(
                object : DownloadManager.Listener {
                    override fun onIdle(downloadManager: DownloadManager) {
                        mutableDownloadState.update { false }
                        publishProgress()
                    }

                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?
                    ) {
                        if (download.state == Download.STATE_COMPLETED) {
                            val id = download.request.id
                            DownloadPins.add(id)
                            val length = download.contentLength.takeIf { it > 0 } ?: download.bytesDownloaded
                            if (length > 0) {
                                val mutations = ContentMetadataMutations()
                                ContentMetadataMutations.setContentLength(mutations, length)
                                cache.applyContentMetadataMutations(id, mutations)
                                transaction {
                                    if (Database.updateDownloadedLength(id, length) == 0) {
                                        Database.insert(com.k4music.android.models.Format(songId = id, contentLength = length))
                                    }
                                }
                            }
                        }
                        if (download.state == Download.STATE_FAILED) {
                            val id = download.request.id
                            DownloadPins.remove(id) // a retry pins it again; failed bytes must stay evictable
                            mutableDownloadProgress.update { it + (id to DownloadProgress(id, 0, Download.STATE_FAILED)) }
                        }
                        publishProgress()
                        downloadQueue.trySend(downloadManager)
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download
                    ) {
                        DownloadPins.remove(download.request.id)
                        mutableDownloadProgress.update { it - download.request.id }
                        publishProgress()
                        downloadQueue.trySend(downloadManager)
                    }
                }
            )
        }
    }

    override fun getScheduler() = WorkManagerScheduler(this, DOWNLOAD_WORK_NAME)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ) = NotificationCompat
        .Builder(
            /* context = */ this,
            /* notification = */ downloadNotificationHelper.buildProgressNotification(
                /* context            = */ this,
                /* smallIcon          = */ R.drawable.download,
                /* contentIntent      = */ null,
                /* message            = */ null,
                /* downloads          = */ downloads,
                /* notMetRequirements = */ notMetRequirements
            )
        )
        .setChannelId(ServiceNotifications.download.id)
        .addAction(
            NotificationCompat.Action.Builder(
                /* icon = */ R.drawable.close,
                /* title = */ getString(R.string.cancel),
                /* intent = */ notificationActionReceiver.cancel.pendingIntent
            ).build()
        )
        .build()

    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(notificationActionReceiver)
        mutableDownloadState.update { false }
        progressUpdaterJob?.cancel()
        mainScope.cancel()
        manager = null
        mutableDownloadProgress.update { it.filterValues(DownloadProgress::failed) }
    }

    companion object {
        /** Cancels a running download or deletes a finished one (frees its cached bytes). */
        fun remove(context: Context, cache: Cache?, ids: Collection<String>) {
            val appContext = context.applicationContext
            coroutineScope.launch {
                ids.forEach { id ->
                    DownloadPins.remove(id)
                    runCatching { cache?.removeResource(id) }
                    runCatching {
                        DownloadService.sendRemoveDownload(appContext, PrecacheService::class.java, id, false)
                    }
                }
                mutableDownloadProgress.update { it - ids.toSet() }
            }
        }

        fun removeAll(context: Context, cache: Cache?, ids: Collection<String>) {
            val appContext = context.applicationContext
            coroutineScope.launch {
                runCatching {
                    DownloadService.sendRemoveAllDownloads(appContext, PrecacheService::class.java, false)
                }
                ids.forEach { DownloadPins.remove(it); runCatching { cache?.removeResource(it) } }
                mutableDownloadProgress.update { emptyMap() }
            }
        }

        fun scheduleCache(context: Context, mediaItem: MediaItem) {
            if (mediaItem.isLocal) return
            DownloadPins.add(mediaItem.mediaId) // safe from cache cleanup from the first byte

            val downloadRequest = DownloadRequest
                .Builder(
                    /* id      = */ mediaItem.mediaId,
                    /* uri     = */ mediaItem.requestMetadata.mediaUri
                        ?: "https://youtube.com/watch?v=${mediaItem.mediaId}".toUri()
                )
                .setCustomCacheKey(mediaItem.mediaId)
                .setData(mediaItem.mediaId.encodeToByteArray())
                .build()

            transaction {
                runCatching {
                    Database.insert(mediaItem)
                }.also { if (it.isFailure) return@transaction }

                coroutineScope.launch {
                    context.download<PrecacheService>(downloadRequest).exceptionOrNull()?.let {
                        if (it is CancellationException) throw it

                        it.printStackTrace()
                        context.toast(context.getString(R.string.error_pre_cache))
                    }
                }
            }
        }
    }
}
