package com.k4music.android.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.audiofx.LoudnessEnhancer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import com.k4music.android.Database
import com.k4music.android.Dependencies
import com.k4music.android.MainActivity
import com.k4music.app.R
import com.k4music.android.models.Event
import com.k4music.android.models.Format
import com.k4music.android.models.QueuedMediaItem
import com.k4music.android.models.Song
import com.k4music.android.models.SongWithContentLength
import com.k4music.android.preferences.AppearancePreferences
import com.k4music.android.preferences.DataPreferences
import com.k4music.android.preferences.PlayerPreferences
import com.k4music.android.query
import com.k4music.android.transaction
import com.k4music.android.utils.ActionReceiver
import com.k4music.android.utils.ConditionalCacheDataSourceFactory
import com.k4music.android.utils.DIRECT_STREAM_LIMIT
import com.k4music.android.utils.directRangeLength
import com.k4music.android.utils.GlyphInterface
import com.k4music.android.utils.InvincibleService
import com.k4music.android.utils.TimerJob
import com.k4music.android.utils.YouTubeDLResponse
import com.k4music.android.utils.YouTubeRadio
import com.k4music.android.utils.activityPendingIntent
import com.k4music.android.utils.asDataSource
import com.k4music.android.utils.broadcastPendingIntent
import com.k4music.android.utils.defaultDataSource
import com.k4music.android.utils.findCause
import com.k4music.android.utils.findNextMediaItemById
import com.k4music.android.utils.forcePlayFromBeginning
import com.k4music.android.utils.forceSeekToNext
import com.k4music.android.utils.forceSeekToPrevious
import com.k4music.android.utils.get
import com.k4music.android.k4.isSaavnKey
import com.k4music.android.k4.resolveSaavnStream
import com.k4music.android.k4.StreamFallback
import com.k4music.android.k4.parseClockToMs
import com.k4music.android.k4.findSaavnStream
import com.k4music.android.utils.handleUnknownErrors
import com.k4music.android.utils.intent
import com.k4music.android.utils.mediaItems
import com.k4music.android.utils.PlaybackRetryBudget
import com.k4music.android.utils.Recovery
import com.k4music.android.utils.chooseRecovery
import com.k4music.android.utils.progress
import com.k4music.android.utils.retryIf
import com.k4music.android.utils.shuffleFromCurrent
import com.k4music.android.utils.readOnlyWhen
import com.k4music.android.utils.setPlaybackPitch
import com.k4music.android.utils.shouldBePlaying
import com.k4music.android.utils.thumbnail
import com.k4music.android.utils.timer
import com.k4music.android.utils.toast
import com.k4music.compose.preferences.SharedPreferencesProperty
import com.k4music.core.data.enums.ExoPlayerDiskCacheSize
import com.k4music.core.data.utils.UriCache
import com.k4music.core.ui.utils.EqualizerIntentBundleAccessor
import com.k4music.core.ui.utils.isAtLeastAndroid10
import com.k4music.core.ui.utils.isAtLeastAndroid12
import com.k4music.core.ui.utils.isAtLeastAndroid13
import com.k4music.core.ui.utils.isAtLeastAndroid6
import com.k4music.core.ui.utils.isAtLeastAndroid8
import com.k4music.core.ui.utils.isAtLeastAndroid9
import com.k4music.core.ui.utils.songBundle
import com.k4music.core.ui.utils.streamVolumeFlow
import com.k4music.providers.innertube.Innertube
import com.k4music.providers.innertube.models.NavigationEndpoint
import com.k4music.providers.innertube.models.bodies.PlayerBody
import com.k4music.providers.innertube.models.bodies.SearchBody
import com.k4music.providers.innertube.requests.player
import com.k4music.providers.innertube.requests.searchPage
import com.k4music.providers.innertube.utils.from
import com.k4music.providers.sponsorblock.SponsorBlock
import com.k4music.providers.sponsorblock.models.Action
import com.k4music.providers.sponsorblock.models.Category
import com.k4music.providers.sponsorblock.requests.segments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import android.os.Binder as AndroidBinder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import android.util.Log

const val LOCAL_KEY_PREFIX = "local:"
private const val TAG = "PlayerService"

@get:OptIn(UnstableApi::class)
val DataSpec.isLocal get() = key?.startsWith(LOCAL_KEY_PREFIX) == true

val MediaItem.isLocal get() = mediaId.startsWith(LOCAL_KEY_PREFIX)
val Song.isLocal get() = id.startsWith(LOCAL_KEY_PREFIX)

private const val LIKE_ACTION = "com.k4music.android.LIKE"
private const val LOOP_ACTION = "com.k4music.android.LOOP"

@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // intended in this class: it is a service
@OptIn(UnstableApi::class)
class PlayerService : InvincibleService(), Player.Listener, PlaybackStatsListener.Callback {
    private lateinit var mediaSession: MediaSession
    private lateinit var cache: Cache
    private lateinit var player: ExoPlayer

    private val defaultActions =
        PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_PLAY_FROM_SEARCH

    private val stateBuilder
        get() = PlaybackState.Builder().setActions(
            defaultActions.let {
                if (isAtLeastAndroid12) it or PlaybackState.ACTION_SET_PLAYBACK_SPEED else it
            }
        ).addCustomAction(
            PlaybackState.CustomAction.Builder(
                /* action = */
                LIKE_ACTION,
                /* name   = */
                getString(R.string.like),
                /* icon   = */
                if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline
            ).build()
        ).addCustomAction(
            PlaybackState.CustomAction.Builder(
                /* action = */
                LOOP_ACTION,
                /* name   = */
                getString(R.string.repeat),
                /* icon   = */
                PlayerPreferences.repeatMode.icon
            ).build()
        )

    private val playbackStateMutex = Mutex()
    private val metadataBuilder = MediaMetadata.Builder()

    private var timerJob: TimerJob? by mutableStateOf(null)
    private var radio: YouTubeRadio? = null

    private lateinit var bitmapProvider: BitmapProvider

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, error -> Log.e("K4Audio", "Unhandled background error", error) })
    private var preferenceUpdaterJob: Job? = null
    private var volumeNormalizationJob: Job? = null
    private var sponsorBlockJob: Job? = null
    private var playbackRecoveryJob: Job? = null
    private val playbackRetryBudget = PlaybackRetryBudget()
    private val playbackFallbackBudget = PlaybackRetryBudget(limit = 1)
    private var budgetResetJob: Job? = null
    private var nextSongJob: Job? = null
    private var nextSongId: String? = null
    private var positionSaveJob: Job? = null
    private var radioFetching = false
    private var lastMetadataDuration = C.TIME_UNSET
    private var lastMetadataItem: MediaItem? = null

    override var isInvincibilityEnabled by mutableStateOf(false)

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val audioEffects = AudioEffects()

    private val binder = Binder()

    private var isNotificationStarted = false
    override val notificationId get() = ServiceNotifications.default.notificationId!!
    private val notificationActionReceiver = NotificationActionReceiver()

    private val mediaItemState = MutableStateFlow<MediaItem?>(null)
    private val isLikedState = mediaItemState
        .flatMapLatest { item ->
            item?.mediaId?.let {
                Database
                    .likedAt(it)
                    .distinctUntilChanged()
                    .cancellable()
            } ?: flowOf(null)
        }
        .map { it != null }
        .onEach {
            updateNotification()
        }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private val glyphInterface by lazy { GlyphInterface(applicationContext) }

    private var poiTimestamp: Long? by mutableStateOf(null)

    override fun onBind(intent: Intent?): AndroidBinder {
        super.onBind(intent)
        return binder
    }

    @Suppress("CyclomaticComplexMethod")
    override fun onCreate() {
        super.onCreate()

        glyphInterface.tryInit()
        notificationActionReceiver.register(flags = ContextCompat.RECEIVER_NOT_EXPORTED)

        bitmapProvider = BitmapProvider(
            getBitmapSize = {
                (512 * resources.displayMetrics.density)
                    .roundToInt()
                    .coerceAtMost(AppearancePreferences.maxThumbnailSize)
            },
            getColor = { isSystemInDarkMode ->
                if (isSystemInDarkMode) Color.BLACK else Color.WHITE
            },
            context = this
        )

        cache = createCache(this)
        player = ExoPlayer.Builder(this, createRendersFactory(), createMediaSourceFactory())
            .setHandleAudioBecomingNoisy(true)
            // Network, not local: the Wi-Fi radio must stay up with the screen off or the stream stalls.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(createLoadControl())
            .setAudioAttributes(
                /* audioAttributes = */
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */
                PlayerPreferences.handleAudioFocus
            )
            .setUsePlatformDiagnostics(false)
            .build()
            .apply {
                skipSilenceEnabled = PlayerPreferences.skipSilence
                addListener(this@PlayerService)
                addAnalyticsListener(
                    PlaybackStatsListener(
                        /* keepHistory = */
                        false,
                        /* callback = */
                        this@PlayerService
                    )
                )
            }

        updateRepeatMode()
        player.shuffleModeEnabled = PlayerPreferences.shuffleEnabled
        audioEffects.attach(player.audioSessionId)
        maybeRestorePlayerQueue()

        mediaSession = MediaSession(baseContext, TAG).apply {
            setCallback(SessionCallback())
            setPlaybackState(stateBuilder.build())
            setSessionActivity(activityPendingIntent<MainActivity>())
            isActive = true
        }

        coroutineScope.launch {
            var first = true
            combine(mediaItemState, isLikedState) { mediaItem, _ ->
                // work around NPE in other processes
                if (first) {
                    first = false
                    return@combine
                }

                if (mediaItem == null) return@combine
                withContext(Dispatchers.Main) {
                    updatePlaybackState()
                    updateNotification()
                }
            }.collect()
        }

        maybeResumePlaybackWhenDeviceConnected()

        // Every YouTube song needs yt-dlp within its first minute, and a cold Python takes many seconds:
        // start it just after launch so the first skip does not wait for it. Small phones start it on first use.
        coroutineScope.launch {
            delay(PYTHON_WARMUP_DELAY_MS)
            if (getSystemService<android.app.ActivityManager>()?.isLowRamDevice != true) runCatching { Dependencies.warmUp() }
        }

        preferenceUpdaterJob = coroutineScope.launch {
            fun <T : Any> subscribe(
                prop: SharedPreferencesProperty<T>,
                callback: (T) -> Unit
            ) = launch { prop.stateFlow.collectLatest { handler.post { callback(it) } } }

            subscribe(AppearancePreferences.isShowingThumbnailInLockscreenProperty) {
                maybeShowSongCoverInLockScreen()
            }

            subscribe(PlayerPreferences.bassBoostLevelProperty) { audioEffects.apply() }
            subscribe(PlayerPreferences.bassBoostProperty) {
                audioEffects.apply()
                if (!it) maybeNormalizeVolume()
            }
            subscribe(PlayerPreferences.reverbProperty) { audioEffects.apply() }
            subscribe(PlayerPreferences.equalizerEnabledProperty) { audioEffects.apply() }
            subscribe(PlayerPreferences.equalizerLevelsProperty) { audioEffects.apply() }
            subscribe(PlayerPreferences.virtualizerModeProperty) { audioEffects.apply() }
            subscribe(PlayerPreferences.virtualizerStrengthProperty) { audioEffects.apply() }
            subscribe(PlayerPreferences.stereoWideningProperty) { audioEffects.apply() }
            subscribe(PlayerPreferences.shuffleEnabledProperty) { enabled ->
                if (enabled && !player.shuffleModeEnabled) player.shuffleFromCurrent()
                player.shuffleModeEnabled = enabled
            }
            subscribe(PlayerPreferences.isInvincibilityEnabledProperty) {
                this@PlayerService.isInvincibilityEnabled = it
            }
            subscribe(PlayerPreferences.pitchProperty) {
                player.setPlaybackPitch(it.coerceAtLeast(0.01f))
            }
            subscribe(PlayerPreferences.queueLoopEnabledProperty) {
                updateRepeatMode()
                updateNotification()
            }
            subscribe(PlayerPreferences.resumePlaybackWhenDeviceConnectedProperty) {
                maybeResumePlaybackWhenDeviceConnected()
            }
            subscribe(PlayerPreferences.skipSilenceProperty) { player.skipSilenceEnabled = it }
            subscribe(PlayerPreferences.speedProperty) {
                player.setPlaybackSpeed(it.coerceAtLeast(0.01f))
            }
            subscribe(PlayerPreferences.trackLoopEnabledProperty) {
                updateRepeatMode()
                updateNotification()
            }
            subscribe(PlayerPreferences.volumeNormalizationBaseGainProperty) { maybeNormalizeVolume() }
            subscribe(PlayerPreferences.volumeNormalizationProperty) { maybeNormalizeVolume() }
            subscribe(PlayerPreferences.sponsorBlockEnabledProperty) { maybeSponsorBlock() }

            launch {
                val audioManager = getSystemService<AudioManager>()
                val stream = AudioManager.STREAM_MUSIC

                val min = when {
                    audioManager == null -> 0
                    isAtLeastAndroid9 -> audioManager.getStreamMinVolume(stream)
                    else -> 0
                }

                streamVolumeFlow(stream).collectLatest {
                    if (PlayerPreferences.stopOnMinimumVolume && it == min) handler.post(player::pause)
                }
            }
        }
    }

    private fun updateRepeatMode() {
        player.repeatMode = when (PlayerPreferences.repeatMode) {
            PlayerPreferences.RepeatMode.One -> Player.REPEAT_MODE_ONE
            PlayerPreferences.RepeatMode.All -> Player.REPEAT_MODE_ALL
            PlayerPreferences.RepeatMode.Off -> Player.REPEAT_MODE_OFF
        }
    }

    // Media3 assigns the session id asynchronously; effects bound to an old (or unset) id do nothing.
    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        audioEffects.attach(audioSessionId)
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        maybeNormalizeVolume()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.shouldBePlaying || PlayerPreferences.stopWhenClosed) {
            broadcastPendingIntent<NotificationDismissReceiver>().send()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Covers every play entry point (UI, notification, headset, Android Auto) for a restored queue.
        if (playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            resetPlaybackRecovery()
            player.prepare()
        }
        maybeSavePlayerQueue()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        budgetResetJob?.cancel()
        positionSaveJob?.cancel()
        if (!isPlaying) return
        // A song that has played on fine earned its retries back: a long song may meet several network drops.
        budgetResetJob = coroutineScope.launch(Dispatchers.Main) {
            delay(BUDGET_RESET_AFTER_MS)
            resetPlaybackRecovery()
        }
        // Keeps the resume point fresh, so a killed app comes back near here.
        positionSaveJob = coroutineScope.launch(Dispatchers.Main) {
            while (true) {
                delay(POSITION_SAVE_MS)
                if (!PlayerPreferences.persistentQueue) continue
                val position = player.currentPosition
                query { runCatching { Database.updateQueuePosition(position) } }
            }
        }
        maybeCacheNextSong()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) continueAfterQueueEnd()
    }

    private fun resetPlaybackRecovery() {
        playbackRetryBudget.reset()
        playbackFallbackBudget.reset()
    }

    /** The queue ran out: carry on with songs like the last one instead of going silent. */
    private fun continueAfterQueueEnd() {
        val last = player.currentMediaItem ?: return
        if (radio == null) {
            if (last.isLocal || isSaavnKey(last.mediaId)) return
            radio = YouTubeRadio(videoId = last.mediaId)
        }
        maybeProcessRadio()
    }

    /**
     * Downloads the next song into the shared cache while this one plays: it starts at once from
     * disk, works if the network drops, and any part of it can be skipped to. Least recently played
     * songs make room as the cache fills up.
     */
    private fun maybeCacheNextSong() {
        val nextIndex = player.nextMediaItemIndex
        val next = nextIndex.takeIf { it != C.INDEX_UNSET }?.let(player::getMediaItemAt)
            ?.takeIf { it.mediaId != player.currentMediaItem?.mediaId } // repeat on a one-song queue
        val id = next?.mediaId
        if (id == nextSongId && nextSongJob?.isActive == true) return
        nextSongJob?.cancel()
        nextSongId = null
        if (next == null || next.isLocal || !player.playWhenReady || PlayerPreferences.pauseCache || dataSaverOn()) return
        nextSongId = id
        val factory = nextSongFactory
        nextSongJob = coroutineScope.launch {
            delay(NEXT_SONG_DELAY_MS) // the song just started comes first
            runCatching {
                runInterruptible {
                    val source = factory.createDataSource()
                    try {
                        source.open(DataSpec.Builder().setUri(next.mediaId).setKey(next.mediaId).build())
                        val buffer = ByteArray(64 * 1024)
                        while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {
                            if (Thread.interrupted()) throw InterruptedException()
                        }
                        Log.i(TAG, "Next song $id is ready on disk")
                    } finally {
                        runCatching { source.close() }
                    }
                }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException && it !is InterruptedException) Log.w(TAG, "Could not cache next song $id", it) }
        }
    }

    private val nextSongFactory by lazy {
        createYouTubeDataSourceResolverFactory(
            context = applicationContext,
            cache = cache,
            chunkLength = null,
            findMediaItem = { videoId -> withContext(Dispatchers.Main) { player.findNextMediaItemById(videoId) } },
            blockOnCache = true
        )
    }

    private fun dataSaverOn() = android.os.Build.VERSION.SDK_INT >= 24 && getSystemService<android.net.ConnectivityManager>()?.let {
        it.isActiveNetworkMetered && it.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } == true

    @Suppress("DEPRECATION")
    private fun online() = getSystemService<android.net.ConnectivityManager>()?.let { it.activeNetworkInfo?.isConnected == true } ?: true

    override fun onDestroy() {
        playbackRecoveryJob?.cancel()
        runCatching {
            maybeSavePlayerQueue()

            player.removeListener(this)
            player.stop()
            player.release()

            unregisterReceiver(notificationActionReceiver)

            mediaSession.isActive = false
            mediaSession.release()
            // The cache is a process singleton (see createCache): downloads still use it after this service dies.

            if (isAtLeastAndroid6) {
                audioDeviceCallback?.let {
                    audioManager?.unregisterAudioDeviceCallback(it)
                    audioDeviceCallback = null
                }
            }
            loudnessEnhancer?.release()
            audioEffects.release()
            preferenceUpdaterJob?.cancel()

            coroutineScope.cancel()
            glyphInterface.close()
        }

        super.onDestroy()
    }

    override fun shouldBeInvincible() = !player.shouldBePlaying

    override fun onConfigurationChanged(newConfig: Configuration) {
        handler.post {
            if (!bitmapProvider.setDefaultBitmap() || player.currentMediaItem == null) return@post
            updateNotification()
        }

        super.onConfigurationChanged(newConfig)
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val totalPlayTimeMs = playbackStats.totalPlayTimeMs
        if (totalPlayTimeMs < 5000) return

        if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return
        val mediaItem = eventTime.timeline[eventTime.windowIndex].mediaItem

        if (!DataPreferences.pausePlaytime) {
            query {
                runCatching {
                    Database.incrementTotalPlayTimeMs(mediaItem.mediaId, totalPlayTimeMs)
                }
            }
        }

        if (!DataPreferences.pauseHistory) {
            query {
                runCatching {
                    Database.insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = System.currentTimeMillis(),
                            playTime = totalPlayTimeMs
                        )
                    )
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A replaced queue gets a random shuffle order from Exo, which can place songs behind
        // the starting one where they would never play.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled) {
            player.shuffleFromCurrent()
        }

        if (
            AppearancePreferences.hideExplicit &&
            mediaItem?.mediaMetadata?.extras?.songBundle?.explicit == true
        ) {
            if (player.currentTimeline.mediaItems.all { it.mediaMetadata.extras?.songBundle?.explicit == true }) {
                player.pause()
                mediaItemState.update { mediaItem }
                toast(getString(R.string.queue_filtered_message))
                return
            }
            player.forceSeekToNext()
            return
        }

        playbackRecoveryJob?.cancel()
        resetPlaybackRecovery()
        mediaItemState.update { mediaItem }
        maybeCacheNextSong()

        maybeRecoverPlaybackError()
        maybeNormalizeVolume()
        maybeProcessRadio()

        with(bitmapProvider) {
            when {
                mediaItem == null -> load(null)
                mediaItem.mediaMetadata.artworkUri == lastUri -> bitmapProvider.load(lastUri)
            }
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            updateMediaSessionQueue(player.currentTimeline)
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        updateMediaSessionQueue(timeline)
        maybeSavePlayerQueue()
        maybeCacheNextSong()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        Log.w(TAG, "Playback failure ${error.errorCodeName}", error)

        val current = player.currentMediaItem ?: return
        if (current.isLocal) return
        val id = current.mediaId
        val position = player.currentPosition.coerceAtLeast(0L)
        // No connection is nobody's fault: spend no retries, and carry on by itself once it is back.
        if (!online()) {
            playbackRecoveryJob?.cancel()
            playbackRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
                while (!online()) {
                    if (!player.playWhenReady || player.currentMediaItem?.mediaId != id) return@launch
                    delay(NETWORK_POLL_MS)
                }
                if (player.currentMediaItem?.mediaId != id || player.playerError == null) return@launch
                player.seekTo(position)
                player.prepare()
            }
            return
        }
        val recovery = chooseRecovery(
            code = error.errorCode,
            httpStatus = error.findCause<InvalidResponseCodeException>()?.responseCode,
            id = id,
            fallbackAllowed = !isSaavnKey(id) && PlayerPreferences.smartFallback,
            retries = playbackRetryBudget,
            fallbacks = playbackFallbackBudget
        )
        if (recovery == Recovery.None) return
        if (recovery == Recovery.Fallback) {
            Log.i(TAG, "Switching $id to the JioSaavn fallback after ${error.errorCodeName}")
            StreamFallback.force(id)
        }

        playbackRecoveryJob?.cancel()
        val attempts = playbackRetryBudget.attempts
        playbackRecoveryJob = coroutineScope.launch(Dispatchers.Main) {
            delay(if (recovery == Recovery.Fallback) RECOVERY_DELAY_MS else RECOVERY_DELAY_MS * 4 * attempts * attempts)
            if (player.currentMediaItem?.mediaId != id || player.playerError == null) return@launch
            player.seekTo(position)
            player.prepare()
        }
    }

    private fun updateMediaSessionQueue(timeline: Timeline) = mediaSession.setQueue(
        List(timeline.windowCount) { index ->
            val mediaItem = timeline.getWindow(index, Timeline.Window()).mediaItem
            MediaSession.QueueItem(
                MediaDescription.Builder()
                    .setMediaId(mediaItem.mediaId)
                    .setTitle(mediaItem.mediaMetadata.title)
                    .setSubtitle(mediaItem.mediaMetadata.artist)
                    .setIconUri(mediaItem.mediaMetadata.artworkUri)
                    .build(),
                index.toLong()
            )
        }
    )

    private fun maybeRecoverPlaybackError() {
        if (player.playerError != null) player.prepare()
    }

    private fun maybeProcessRadio() {
        if (player.mediaItemCount - player.currentMediaItemIndex > 3 || radioFetching) return

        radio?.let { radio ->
            radioFetching = true
            coroutineScope.launch(Dispatchers.Main) {
                try {
                    val known = player.currentTimeline.mediaItems.mapTo(HashSet()) { it.mediaId }
                    val items = Database.filterBlacklistedSongs(radio.process()).filter { known.add(it.mediaId) }
                    val ended = player.playbackState == Player.STATE_ENDED
                    if (items.isNotEmpty()) {
                        val first = player.mediaItemCount
                        player.addMediaItems(items)
                        if (ended) {
                            player.seekTo(first, C.TIME_UNSET)
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                } finally {
                    radioFetching = false
                }
            }
        }
    }

    private fun maybeSavePlayerQueue() {
        if (!PlayerPreferences.persistentQueue) return

        val mediaItems = player.currentTimeline.mediaItems
        val mediaItemIndex = player.currentMediaItemIndex
        val mediaItemPosition = player.currentPosition

        transaction {
            runCatching {
                Database.clearQueue()
                Database.insert(
                    mediaItems.mapIndexed { index, mediaItem ->
                        QueuedMediaItem(
                            mediaItem = mediaItem,
                            position = if (index == mediaItemIndex) mediaItemPosition else null
                        )
                    }
                )
            }
        }
    }

    private fun maybeRestorePlayerQueue() {
        if (!PlayerPreferences.persistentQueue) return

        transaction {
            val queue = Database.queue()
            if (queue.isEmpty()) return@transaction
            Database.clearQueue()

            val index = queue
                .indexOfFirst { it.position != null }
                .coerceIn(0, (queue.size - 1).coerceAtLeast(0))

            handler.post {
                runCatching {
                    player.setMediaItems(
                        /* mediaItems = */
                        queue.map { item ->
                            item.mediaItem.buildUpon()
                                .setUri(item.mediaItem.mediaId)
                                .setCustomCacheKey(item.mediaItem.mediaId)
                                .build()
                                .apply {
                                    mediaMetadata.extras?.songBundle?.apply {
                                        isFromPersistentQueue = true
                                    }
                                }
                        },
                        /* startIndex = */
                        index,
                        /* startPositionMs = */
                        queue.getOrNull(index)?.position ?: C.TIME_UNSET
                    )
                    // Not prepared here: resolving a stream at launch competes with the UI.
                    // onPlayWhenReadyChanged prepares on the first play.

                    runCatching {
                        isNotificationStarted = true
                        startForeground()
                    }
                }.onFailure { Log.w(TAG, "Failed to restore player queue on startup", it) }
            }
        }
    }

    private fun maybeNormalizeVolume() {
        if (!PlayerPreferences.volumeNormalization) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob?.invokeOnCompletion { volumeNormalizationJob = null }
            player.volume = 1f
            return
        }

        // Session 0 is the global output mix; never attach to it.
        if (player.audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            if (loudnessEnhancer == null) loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
        }.onFailure { return }

        val songId = player.currentMediaItem?.mediaId ?: return
        volumeNormalizationJob?.cancel()
        volumeNormalizationJob = coroutineScope.launch {
            runCatching {
                fun Float?.toMb() = ((this ?: 0f) * 100).toInt()

                Database.loudnessDb(songId).cancellable().collectLatest { loudness ->
                    // Tracks without loudness metadata (Saavn, local, Piped) are commercial masters, which
                    // YouTube rates around +6 dB over its reference: treat them so, not as quiet (-9 dB
                    // boosted them 14 dB over YouTube songs and clipped).
                    val nominalLoudnessMb = 600
                    val loudnessMb = loudness?.let {
                        val mb = (it * 100).toInt()
                        if (mb !in -2000..2000) {
                            withContext(Dispatchers.Main) {
                                toast(
                                    getString(
                                        R.string.loudness_normalization_extreme,
                                        getString(R.string.format_db, (mb / 100f).toString())
                                    )
                                )
                            }
                            nominalLoudnessMb
                        } else {
                            mb
                        }
                    } ?: nominalLoudnessMb

                    Database.loudnessBoost(songId).cancellable().collectLatest { boost ->
                        withContext(Dispatchers.Main) {
                            val boostMb = boost.toMb()
                            val baseGainMb = PlayerPreferences.volumeNormalizationBaseGain.toMb()
                            // Smoothly clamp target gain between 0 and 15 dB to prevent digital clipping
                            val targetGain = (baseGainMb + boostMb - loudnessMb).coerceIn(0, 1500)
                            loudnessEnhancer?.setTargetGain(targetGain)
                            loudnessEnhancer?.enabled = true
                        }
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // TODO: evaluate CyclomaticComplexMethod threshold
    private fun maybeSponsorBlock() {
        poiTimestamp = null

        if (!PlayerPreferences.sponsorBlockEnabled) {
            sponsorBlockJob?.cancel()
            sponsorBlockJob?.invokeOnCompletion { sponsorBlockJob = null }
            return
        }

        sponsorBlockJob?.cancel()
        sponsorBlockJob = coroutineScope.launch {
            mediaItemState.onStart { emit(mediaItemState.value) }.collectLatest { mediaItem ->
                poiTimestamp = null
                val videoId = mediaItem?.mediaId
                    ?.removePrefix("https://youtube.com/watch?v=")
                    // Only YouTube videos have segments; files and JioSaavn songs must not be sent to SponsorBlock.
                    ?.takeIf { it.isNotBlank() && !mediaItem.isLocal && !isSaavnKey(it) } ?: return@collectLatest

                SponsorBlock
                    .segments(videoId)
                    ?.onSuccess { segments ->
                        poiTimestamp =
                            segments.find { it.category == Category.PoiHighlight }?.start?.inWholeMilliseconds
                    }
                    ?.map { segments ->
                        segments
                            .sortedBy { it.start.inWholeMilliseconds }
                            .filter { it.action == Action.Skip }
                    }
                    ?.mapCatching { segments ->
                        suspend fun posMillis() =
                            withContext(Dispatchers.Main) { player.currentPosition }

                        suspend fun speed() =
                            withContext(Dispatchers.Main) { player.playbackParameters.speed }

                        suspend fun seek(millis: Long) =
                            withContext(Dispatchers.Main) { player.seekTo(millis) }

                        val ctx = currentCoroutineContext()
                        val lastSegmentEnd =
                            segments.lastOrNull()?.end?.inWholeMilliseconds ?: return@mapCatching

                        @Suppress("LoopWithTooManyJumpStatements")
                        do {
                            if (lastSegmentEnd < posMillis()) {
                                delay(1000.milliseconds)
                                continue
                            }

                            val nextSegment = segments.firstOrNull { posMillis() < it.end.inWholeMilliseconds }
                            if (nextSegment == null) {
                                // Past the last segment (or paused exactly on its end): wait instead of spinning.
                                delay(1000.milliseconds)
                                continue
                            }

                            // Wait for next segment
                            if (nextSegment.start.inWholeMilliseconds > posMillis()) {
                                val timeNextSegment =
                                    nextSegment.start.inWholeMilliseconds - posMillis()
                                val speed = speed().toDouble()
                                delay((timeNextSegment / speed).milliseconds)
                            }

                            if (posMillis().milliseconds !in nextSegment.start..nextSegment.end) {
                                // Player is not in the segment for some reason, maybe the user seeked or paused
                                delay(100.milliseconds)
                                continue
                            }

                            seek(nextSegment.end.inWholeMilliseconds)
                        } while (ctx.isActive)
                    }?.onFailure {
                        it.printStackTrace()
                    }
            }
        }
    }

    private fun maybeShowSongCoverInLockScreen() = handler.post {
        val bitmap = if (isAtLeastAndroid13 || AppearancePreferences.isShowingThumbnailInLockscreen) {
            bitmapProvider.bitmap
        } else {
            null
        }
        val uri = player.mediaMetadata.artworkUri?.toString()?.thumbnail(512)

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_ART_URI, uri)

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, uri)

        if (isAtLeastAndroid13 && player.currentMediaItemIndex == 0) {
            metadataBuilder.putText(
                MediaMetadata.METADATA_KEY_TITLE,
                "${player.mediaMetadata.title} "
            )
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun maybeResumePlaybackWhenDeviceConnected() {
        if (!isAtLeastAndroid6) return

        audioDeviceCallback?.let {
            audioManager?.unregisterAudioDeviceCallback(it)
            audioDeviceCallback = null
        }

        if (!PlayerPreferences.resumePlaybackWhenDeviceConnected) return
        if (audioManager == null) audioManager = getSystemService<AudioManager>()

        val callback = object : AudioDeviceCallback() {
            private fun canPlayMusic(audioDeviceInfo: AudioDeviceInfo) =
                audioDeviceInfo.isSink && (
                    audioDeviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    )
                    .let {
                        if (!isAtLeastAndroid8) {
                            it
                        } else {
                            it || audioDeviceInfo.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        }
                    }

            // Android also reports the devices already connected right after registering: those are not news.
            private val registeredAt = SystemClock.elapsedRealtime()

            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                if (SystemClock.elapsedRealtime() - registeredAt < 1_000) return
                if (!player.isPlaying && addedDevices.any(::canPlayMusic)) player.play()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = Unit
        }

        audioDeviceCallback = callback
        audioManager?.registerAudioDeviceCallback(callback, handler)
    }

    private fun openEqualizer() =
        EqualizerIntentBundleAccessor.sendOpenEqualizer(player.audioSessionId)

    private fun closeEqualizer() =
        EqualizerIntentBundleAccessor.sendCloseEqualizer(player.audioSessionId)

    private fun updatePlaybackState() = coroutineScope.launch {
        playbackStateMutex.withLock {
            withContext(Dispatchers.Main) {
                mediaSession.setPlaybackState(
                    stateBuilder
                        .setActiveQueueItemId(player.currentMediaItemIndex.toLong())
                        .setState(
                            player.androidPlaybackState,
                            player.currentPosition,
                            player.playbackParameters.speed,
                            SystemClock.elapsedRealtime()
                        )
                        .setBufferedPosition(player.bufferedPosition)
                        .build()
                )
            }
        }
    }

    private val Player.androidPlaybackState
        get() = when (playbackState) {
            Player.STATE_BUFFERING -> if (playWhenReady) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            Player.STATE_READY -> if (playWhenReady) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            Player.STATE_IDLE -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_NONE
        }

    // legacy behavior may cause inconsistencies, but not available on sdk 24 or lower
    @Suppress("DEPRECATION")
    override fun onEvents(player: Player, events: Player.Events) {
        // Setting metadata rescales the cover on the main thread: only when something in it changed.
        if (player.duration != C.TIME_UNSET && (
                player.duration != lastMetadataDuration ||
                    player.currentMediaItem !== lastMetadataItem ||
                    events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
                )
        ) {
            lastMetadataDuration = player.duration
            lastMetadataItem = player.currentMediaItem
            mediaSession.setMetadata(
                metadataBuilder
                    .putText(
                        MediaMetadata.METADATA_KEY_TITLE,
                        player.mediaMetadata.title?.toString().orEmpty()
                    )
                    .putText(
                        MediaMetadata.METADATA_KEY_ARTIST,
                        player.mediaMetadata.artist?.toString().orEmpty()
                    )
                    .putText(
                        MediaMetadata.METADATA_KEY_ALBUM,
                        player.mediaMetadata.albumTitle?.toString().orEmpty()
                    )
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
                    .build()
            )
        }

        updatePlaybackState()

        if (
            !events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_METADATA_CHANGED
            )
        ) {
            return
        }

        val notification = notification()

        if (notification == null) {
            isNotificationStarted = false
            makeInvincible(false)
            stopForeground(false)
            closeEqualizer()
            ServiceNotifications.default.cancel(this)
            return
        }

        if (player.shouldBePlaying && !isNotificationStarted) {
            isNotificationStarted = true
            // Android 12+ can refuse this from the background; playback must not crash over it.
            runCatching {
                startForegroundService(this@PlayerService, intent<PlayerService>())
                startForeground()
            }.onFailure { Log.w(TAG, "Could not enter the foreground", it) }
            makeInvincible(false)
            openEqualizer()
        } else {
            if (!player.shouldBePlaying) {
                isNotificationStarted = false
                stopForeground(false)
                makeInvincible(true)
                closeEqualizer()
            }
            updateNotification()
        }
    }

    private fun notification(): (NotificationCompat.Builder.() -> NotificationCompat.Builder)? {
        if (player.currentMediaItem == null) return null

        val mediaMetadata = player.mediaMetadata

        bitmapProvider.load(mediaMetadata.artworkUri) {
            maybeShowSongCoverInLockScreen()
            updateNotification()
        }

        return {
            @Suppress("DEPRECATION")
            this
                .setContentTitle(mediaMetadata.title?.toString().orEmpty())
                .setContentText(mediaMetadata.artist?.toString().orEmpty())
                .setSubText(player.playerError?.message)
                .setLargeIcon(bitmapProvider.bitmap)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSmallIcon(
                    player.playerError?.let { R.drawable.alert_circle } ?: R.drawable.ic_stat_k4audio
                )
                .setOngoing(false)
                .setContentIntent(
                    activityPendingIntent<MainActivity>(flags = PendingIntent.FLAG_UPDATE_CURRENT)
                )
                .setDeleteIntent(broadcastPendingIntent<NotificationDismissReceiver>())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(
                    R.drawable.play_skip_back,
                    getString(R.string.skip_back),
                    notificationActionReceiver.previous.pendingIntent
                )
                .let {
                    if (player.shouldBePlaying) {
                        it.addAction(
                            R.drawable.pause,
                            getString(R.string.pause),
                            notificationActionReceiver.pause.pendingIntent
                        )
                    } else {
                        it.addAction(
                            R.drawable.play,
                            getString(R.string.play),
                            notificationActionReceiver.play.pendingIntent
                        )
                    }
                }
                .addAction(
                    R.drawable.play_skip_forward,
                    getString(R.string.skip_forward),
                    notificationActionReceiver.next.pendingIntent
                )
                .addAction(
                    if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline,
                    getString(R.string.like),
                    notificationActionReceiver.like.pendingIntent
                )
                .addAction(
                    PlayerPreferences.repeatMode.icon,
                    getString(R.string.repeat),
                    notificationActionReceiver.loop.pendingIntent
                )
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                        .setMediaSession(MediaSessionCompat.Token.fromToken(mediaSession.sessionToken))
                )
        }
    }

    private fun updateNotification() = runCatching {
        handler.post {
            notification()?.let { ServiceNotifications.default.sendNotification(this, it) }
        }
    }

    override fun startForeground() {
        notification()
            ?.let { ServiceNotifications.default.startForeground(this, it) }
    }

    /**
     * Keeps the whole song (up to 10 minutes) in memory, ahead of and behind the listener, so a skip
     * to any part of it plays at once. Audio is compressed here: a song is only a few MB.
     */
    private fun createLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 600_000, 500, 1_500)
        .setBackBuffer(600_000, true)
        .setTargetBufferBytes(32 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    private fun createMediaSourceFactory() = DefaultMediaSourceFactory(
        /* dataSourceFactory = */
        createYouTubeDataSourceResolverFactory(
            findMediaItem = { videoId ->
                withContext(Dispatchers.Main) {
                    player.findNextMediaItemById(videoId)
                }
            },
            context = applicationContext,
            cache = cache
        ),
        /* extractorsFactory = */
        DefaultExtractorsFactory()
    ).setLoadErrorHandlingPolicy(
        object : DefaultLoadErrorHandlingPolicy() {
            override fun isEligibleForFallback(exception: IOException) = true
        }
    )

    private fun createRendersFactory() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            val minimumSilenceDuration =
                PlayerPreferences.minimumSilence.coerceIn(1000L..2_000_000L)

            @Suppress("DEPRECATION")
            return DefaultAudioSink.Builder(applicationContext)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioOffloadSupportProvider(
                    DefaultAudioOffloadSupportProvider(applicationContext)
                )
                .setAudioProcessorChain(
                    DefaultAudioProcessorChain(
                        arrayOf(audioEffects.widener),
                        SilenceSkippingAudioProcessor(
                            /* minimumSilenceDurationUs = */
                            minimumSilenceDuration,
                            /* silenceRetentionRatio = */
                            0.01f,
                            /* maxSilenceToKeepDurationUs = */
                            minimumSilenceDuration,
                            /* minVolumeToKeepPercentageWhenMuting = */
                            0,
                            /* silenceThresholdLevel = */
                            256
                        ),
                        SonicAudioProcessor()
                    )
                )
                .build()
                .apply {
                    if (isAtLeastAndroid10) setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                }
        }
    }

    @Stable
    inner class Binder : AndroidBinder() {
        val player: ExoPlayer
            get() = this@PlayerService.player

        val cache: Cache
            get() = this@PlayerService.cache

        val mediaSession
            get() = this@PlayerService.mediaSession

        val audioEffects: StateFlow<AudioCapabilities?>
            get() = this@PlayerService.audioEffects.capabilities

        val sleepTimerMillisLeft: StateFlow<Long?>?
            get() = timerJob?.millisLeft

        private var radioJob: Job? = null

        var isLoadingRadio by mutableStateOf(false)
            private set

        var invincible
            get() = isInvincibilityEnabled
            set(value) {
                isInvincibilityEnabled = value
            }

        val poiTimestamp get() = this@PlayerService.poiTimestamp

        fun setBitmapListener(listener: ((Bitmap?) -> Unit)?) = bitmapProvider.setListener(listener)

        @kotlin.OptIn(FlowPreview::class)
        fun startSleepTimer(delayMillis: Long) {
            timerJob?.cancel()

            timerJob = coroutineScope.timer(delayMillis) {
                ServiceNotifications.sleepTimer.sendNotification(this@PlayerService) {
                    this
                        .setContentTitle(getString(R.string.sleep_timer_ended))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(true)
                        .setSmallIcon(R.drawable.ic_stat_k4audio)
                }

                handler.post {
                    player.pause()
                    player.stop()

                    glyphInterface.glyph {
                        turnOff()
                    }
                }
            }.also { job ->
                glyphInterface.progress(
                    job
                        .millisLeft
                        .takeWhile { it != null }
                        .debounce(500.milliseconds)
                        .map { ((it ?: 0L) / delayMillis.toFloat() * 100).toInt() }
                )
            }
        }

        fun cancelSleepTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        fun setupRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = true)

        fun playRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = false)

        private fun startRadio(endpoint: NavigationEndpoint.Endpoint.Watch?, justAdd: Boolean) {
            radioJob?.cancel()
            radio = null

            YouTubeRadio(
                endpoint?.videoId,
                endpoint?.playlistId,
                endpoint?.playlistSetVideoId,
                endpoint?.params
            ).let { radioData ->
                isLoadingRadio = true
                radioJob = coroutineScope.launch {
                    val items = radioData.process().let { Database.filterBlacklistedSongs(it) }

                    withContext(Dispatchers.Main) {
                        if (justAdd) {
                            // The radio's first song is usually the seed, but not always: never queue it twice.
                            val playing = player.currentMediaItem?.mediaId
                            player.addMediaItems(items.drop(1).filter { it.mediaId != playing })
                        } else {
                            player.forcePlayFromBeginning(items)
                        }
                    }

                    radio = radioData
                    isLoadingRadio = false
                }
            }
        }

        fun stopRadio() {
            isLoadingRadio = false
            radioJob?.cancel()
            radio = null
        }

        /**
         * This method should ONLY be called when the application (sc. activity) is in the foreground!
         */
        fun retryPlayback() {
            resetPlaybackRecovery()
            player.prepare()
        }

        fun restartForegroundOrStop() {
            player.pause()
            isInvincibilityEnabled = false
            stopSelf()
        }

        fun handleCustomAction(action: String) = when (action) {
            LIKE_ACTION -> likeAction()
            LOOP_ACTION -> loopAction()
            else -> Unit
        }

        fun isCached(song: SongWithContentLength) =
            song.contentLength?.let { cache.isCached(song.song.id, 0L, it) } ?: false

        fun playFromSearch(query: String) {
            coroutineScope.launch {
                Innertube.searchPage(
                    body = SearchBody(
                        query = query,
                        params = Innertube.SearchFilter.Song.value
                    ),
                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                )
                    ?.getOrNull()
                    ?.items
                    ?.firstOrNull()
                    ?.info
                    ?.endpoint
                    ?.let { playRadio(it) }
            }
        }
    }

    private fun likeAction() = mediaItemState.value?.let { mediaItem ->
        query {
            runCatching {
                val newLikedAt = if (isLikedState.value) null else System.currentTimeMillis()
                if (
                    Database.like(
                        songId = mediaItem.mediaId,
                        likedAt = newLikedAt
                    ) == 0
                ) {
                    Database.insert(mediaItem) { it.copy(likedAt = newLikedAt) }
                }
            }
        }
    }.let { }

    private fun loopAction() {
        PlayerPreferences.repeatMode = PlayerPreferences.repeatMode.next
    }

    private inner class SessionCallback : MediaSession.Callback() {
        override fun onPlay() = player.play()
        override fun onPause() = player.pause()
        override fun onSkipToPrevious() = runCatching(player::forceSeekToPrevious).let { }
        override fun onSkipToNext() = runCatching(player::forceSeekToNext).let { }
        override fun onSeekTo(pos: Long) = player.seekTo(pos)
        override fun onStop() = player.pause()
        override fun onRewind() = player.seekToDefaultPosition()
        override fun onSkipToQueueItem(id: Long) =
            runCatching { player.seekToDefaultPosition(id.toInt()) }.let { }

        override fun onSetPlaybackSpeed(speed: Float) {
            PlayerPreferences.speed = speed.coerceIn(0.01f..2f)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) return
            binder.playFromSearch(query)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            super.onCustomAction(action, extras)
            when (action) {
                LIKE_ACTION -> likeAction()
                LOOP_ACTION -> loopAction()
            }
        }
    }

    inner class NotificationActionReceiver internal constructor() :
        ActionReceiver("com.k4music.android") {
        val pause by action { _, _ ->
            player.pause()
        }
        val play by action { _, _ ->
            player.play()
        }
        val next by action { _, _ ->
            player.forceSeekToNext()
        }
        val previous by action { _, _ ->
            player.forceSeekToPrevious()
        }
        val like by action { _, _ ->
            likeAction()
        }
        val loop by action { _, _ ->
            loopAction()
        }
    }

    class NotificationDismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = with(context) {
            stopService(intent<PlayerService>())
            Unit
        }
    }

    companion object {
        private const val DEFAULT_CACHE_DIRECTORY = "exoplayer"
        private const val DEFAULT_CHUNK_LENGTH = 512 * 1024L
        private const val YT_DLP_TIMEOUT_MS = 20_000L
        /** A skip past the direct link waits this long per try; the run itself keeps going and is shared. */
        private const val YT_DLP_SEEK_WAIT_MS = 12_000L
        private const val INNERTUBE_TIMEOUT_MS = 6_000L
        private const val BUDGET_RESET_AFTER_MS = 30_000L
        private const val POSITION_SAVE_MS = 15_000L
        private const val NETWORK_POLL_MS = 2_000L
        private const val NEXT_SONG_DELAY_MS = 5_000L
        private const val PYTHON_WARMUP_DELAY_MS = 1_500L
        /** yt-dlp was only slow, not refused: back to YouTube soon, not a day later. */
        private const val SLOW_YOUTUBE_PIN_MS = 60 * 60 * 1000L
        private const val STREAM_URL_TTL_MS = 3 * 60 * 60 * 1000L
        /** Runs younger than this are shared; a failed one is not repeated for a short while. */
        private const val YT_DLP_RESULT_TTL_MS = 2 * 60 * 60 * 1000L
        private const val YT_DLP_FAILED_TTL_MS = 30_000L
        private const val YT_DLP_STUCK_MS = 90_000L
        /** A download is not waiting to start playing: give a slow yt-dlp (cold Python, weak phone) time to finish. */
        private const val YT_DLP_DOWNLOAD_TIMEOUT_MS = 45_000L
        private const val RECOVERY_DELAY_MS = 300L
        private val ytDlpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** yt-dlp errors that mean "YouTube is refusing this network", not "this song is unavailable". */
        private val YOUTUBE_BLOCKED_SIGNS = listOf("not a bot", "HTTP Error 429", "LOGIN_REQUIRED", "page needs to be reloaded")
        private const val YOUTUBE_BLOCK_PAUSE_MS = 10 * 60 * 1000L
        @Volatile private var youtubeBlockedUntil = 0L
        private fun youtubeBlocked() = System.currentTimeMillis() < youtubeBlockedUntil

        private class YtDlpRun(val job: kotlinx.coroutines.Deferred<YouTubeDLResponse>, val startedAt: Long)
        private val ytDlpRuns = HashMap<String, YtDlpRun>()

        /**
         * One yt-dlp run per song, whoever asks: a seek interrupts the waiting load, not the run, so
         * the result is still there for the next attempt instead of starting Python over.
         */
        private fun ytDlpRun(id: String, format: String) = synchronized(ytDlpRuns) {
            val now = System.currentTimeMillis()
            ytDlpRuns[id]?.takeIf { run ->
                val age = now - run.startedAt
                when {
                    !run.job.isCompleted -> age < YT_DLP_STUCK_MS
                    run.job.isCancelled -> age < YT_DLP_FAILED_TTL_MS
                    else -> age < YT_DLP_RESULT_TTL_MS
                }
            }?.job ?: ytDlpScope.async {
                try {
                    val started = System.currentTimeMillis()
                    YouTubeDLResponse.fromString(Dependencies.runDownload(id, format)).also {
                        Log.i(TAG, "yt-dlp answered for $id in ${System.currentTimeMillis() - started} ms")
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "yt-dlp failed for $id", error)
                    if (YOUTUBE_BLOCKED_SIGNS.any { sign -> error.message?.contains(sign) == true }) {
                        Log.w(TAG, "YouTube is blocking this network; JioSaavn for a while")
                        youtubeBlockedUntil = System.currentTimeMillis() + YOUTUBE_BLOCK_PAUSE_MS
                    }
                    throw error
                }
            }.also { job ->
                if (ytDlpRuns.size >= 64) ytDlpRuns.entries.removeAll { it.value.job.isCompleted }
                ytDlpRuns[id] = YtDlpRun(job, now)
            }
        }

        private fun ytDlpFormat(firstItag: String?) =
            (listOfNotNull(firstItag) + PlayerPreferences.audioQuality.youtubeItags.map { it.toString() } + "bestaudio" + "best")
                .distinct().joinToString("/")

        /** The run's answer within [waitMs], or null. A cancelled load (a seek) stays a cancelled load. */
        private fun awaitYtDlp(id: String, format: String, waitMs: Long): YouTubeDLResponse? {
            if (youtubeBlocked()) return null
            val run = ytDlpRun(id, format)
            return try {
                runBlocking { withTimeoutOrNull(waitMs) { run.await() } }
            } catch (error: InterruptedException) {
                throw java.io.InterruptedIOException("Load cancelled")
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Load cancelled")
                null
            }
        }

        fun createDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)
        fun createCache(
            context: Context,
            directoryName: String = DEFAULT_CACHE_DIRECTORY,
            size: ExoPlayerDiskCacheSize = DataPreferences.exoPlayerDiskCacheMaxSize
        ) = with(context) {
            val cacheEvictor = when (size) {
                ExoPlayerDiskCacheSize.Unlimited -> NoOpCacheEvictor()
                else -> PinnedLruCacheEvictor(size.bytes, DownloadPins::contains)
            }

            synchronized(sharedCaches) {
                sharedCaches.getOrPut(directoryName) {
                    // Downloads live here too, so not in cacheDir: Android empties that when storage runs low.
                    val old = cacheDir.resolve(directoryName)
                    val directory = noBackupFilesDir.resolve(directoryName)
                    if (old.isDirectory && !directory.exists()) old.renameTo(directory)
                    directory.mkdirs()
                    DownloadPins.migrate(context)
                    SimpleCache(directory, cacheEvictor, createDatabaseProvider(context))
                }
            }
        }
        private val sharedCaches = HashMap<String, SimpleCache>()

        @Suppress("CyclomaticComplexMethod")
        fun createYouTubeDataSourceResolverFactory(
            context: Context,
            cache: Cache,
            chunkLength: Long? = DEFAULT_CHUNK_LENGTH,
            findMediaItem: suspend (videoId: String) -> MediaItem? = { null },
            uriCache: UriCache<String, Long?> = UriCache(ttlMs = STREAM_URL_TTL_MS),
            /** Wait for a range another reader is writing instead of reading it without keeping it. */
            blockOnCache: Boolean = false
        ): DataSource.Factory {
            val rejectedDirectStreams = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            // Songs whose cached link is Innertube's direct one, which stops at DIRECT_STREAM_LIMIT.
            val limitedStreams = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val streamHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
            return ResolvingDataSource.Factory(
            ConditionalCacheDataSourceFactory(
                cacheDataSourceFactory = cache.readOnlyWhen { PlayerPreferences.pauseCache }.asDataSource.apply {
                    if (blockOnCache) setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
                },
                upstreamDataSourceFactory = context.defaultDataSource,
                shouldCache = { !it.isLocal }
            )
        ) { dataSpec ->
            val mediaId = dataSpec.key?.removePrefix("https://youtube.com/watch?v=")
                ?: error("A key must be set")

            fun DataSpec.withRequestHeaders(headers: Map<String, String>) = buildUpon()
                .setHttpRequestHeaders(headers + httpRequestHeaders)
                .build()

            fun DataSpec.ranged(contentLength: Long?): DataSpec = if (chunkLength == null) {
                // Downloads read a song in one request. YouTube throttles a request without a
                // Range header to a trickle (~30 KB/s); an open-ended range comes at full speed.
                if (length == C.LENGTH_UNSET.toLong() && uri.host?.endsWith("googlevideo.com") == true)
                    withRequestHeaders(mapOf("Range" to "bytes=$position-"))
                else this
            } else if (contentLength == null && length == C.LENGTH_UNSET.toLong() && uri.host?.endsWith("googlevideo.com") == true) {
                withRequestHeaders(mapOf("Range" to "bytes=$position-")) // no length known: an open range, never a throttled plain request
            } else contentLength?.let {

                val remaining = contentLength - dataSpec.position
                if (remaining <= 0L) return@let null
                val length = remaining.coerceAtMost(chunkLength).let { available ->
                    if (dataSpec.length > 0L) available.coerceAtMost(dataSpec.length) else available
                }
                // Media3 generates the Range header from position/length; never shift the seek twice.
                this.subrange(0L, length)
            } ?: this

            // YouTube could not serve this song: play the same track from JioSaavn under the same
            // cache key. The old bytes are purged first so two sources never mix in one file.
            fun saavnFallback(pinMs: Long? = null): DataSpec? {
                // Mid-song the player holds an offset into the YouTube file: it must reopen the song
                // from its start (see onPlayerError) rather than read that offset in another file.
                if (dataSpec.position > 0 && !StreamFallback.isForced(mediaId)) throw IOException("YouTube stream not ready")
                uriCache.remove(mediaId)
                val item = runCatching { runBlocking(Dispatchers.IO) { findMediaItem(mediaId) } }.getOrNull()
                val song = if (item != null) null
                else runCatching { runBlocking(Dispatchers.IO) { Database.song(mediaId).first() } }.getOrNull()
                val title = item?.mediaMetadata?.title?.toString() ?: song?.title
                val artist = item?.mediaMetadata?.artist?.toString() ?: song?.artistsText.orEmpty()
                val durationText = item?.mediaMetadata?.extras?.songBundle?.durationText ?: song?.durationText
                val url = title?.let {
                    runCatching {
                        runBlocking(Dispatchers.IO) { findSaavnStream(
                                it,
                                artist,
                                durationText?.let(::parseClockToMs)?.takeIf { ms -> ms > 0 },
                                PlayerPreferences.audioQuality.saavn
                            ) }
                    }.getOrNull()
                }
                if (url == null) {
                    // Nothing to switch to: keep the cached audio instead of purging it for nothing.
                    StreamFallback.resolved(mediaId, null)
                    Log.w(TAG, "No JioSaavn fallback for $mediaId ($title)")
                    return null
                }
                StreamFallback.resolved(mediaId, url, pinMs)
                limitedStreams.remove(mediaId)
                runCatching { cache.removeResource(mediaId) }
                Log.i(TAG, "Playing $mediaId from JioSaavn fallback")
                val fallbackLen: Long? = null // Download completion records the actual byte length.
                transaction {
                    runCatching {
                        item?.let(Database::insert)
                        Database.insert(
                            Format(
                                songId = mediaId,
                                itag = null,
                                mimeType = "audio/mp4",
                                bitrate = null,
                                loudnessDb = null,
                                contentLength = fallbackLen,
                                lastModified = null
                            )
                        )
                    }
                }
                uriCache.push(key = mediaId, meta = fallbackLen, uri = url.toUri())
                return dataSpec.withUri(url.toUri()).ranged(fallbackLen)
            }

            val cachedLength = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(mediaId))
            val fullyCached = cachedLength > 0 && cache.isCached(mediaId, 0, cachedLength)
            val fallbackOn = PlayerPreferences.smartFallback

            // Played songs are read in chunks, so the cache cannot learn their length itself. Knowing it
            // lets a finished song count as cached; two different files must never share one key.
            fun rememberLength(length: Long?) {
                if (length == null || length <= 0 || length == cachedLength) return
                if (cachedLength > 0) {
                    runCatching { cache.removeResource(mediaId) }
                    if (dataSpec.position > 0) throw androidx.media3.common.ParserException.createForMalformedContainer("Stream changed mid-song", null)
                }
                val mutations = androidx.media3.datasource.cache.ContentMetadataMutations()
                androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(mutations, length)
                runCatching { cache.applyContentMetadataMutations(mediaId, mutations) }
            }

            fun extract(firstItag: String?, waitMs: Long): YouTubeDLResponse? {
                val info = awaitYtDlp(mediaId, ytDlpFormat(firstItag), waitMs)
                if (info?.id != null && info.id != mediaId) throw VideoIdMismatchException()
                return info
            }

            /** The direct link served its part: move this song to the full link. Null when it is not ready. */
            fun switchToFullLink(cachedItag: String?, waitMs: Long): DataSpec? {
                val info = extract(cachedItag, waitMs) ?: return null
                val format = info.formats?.firstOrNull { it.formatId == info.formatId }
                val uri = (info.url?.takeIf { it.isNotBlank() } ?: format?.url?.takeIf { it.isNotBlank() })?.toUri() ?: return null
                val length = info.fileSize?.takeIf { it > 0L } ?: uri.getQueryParameter("clen")?.toLongOrNull()
                rememberLength(length)
                if (streamHeaders.size >= 64) streamHeaders.clear()
                streamHeaders[mediaId] = info.httpHeaders
                limitedStreams.remove(mediaId)
                uriCache.push(key = mediaId, meta = length, uri = uri)
                return dataSpec.withUri(uri).withRequestHeaders(info.httpHeaders).ranged(length)
            }

            if (!fullyCached && StreamFallback.url(mediaId) != null &&
                (!fallbackOn || (dataSpec.position == 0L && StreamFallback.isStale(mediaId)))
            ) {
                // Fallback turned off, or the pin is a day old (YouTube first, always): forget the
                // JioSaavn copy and its cached bytes, back to YouTube. Only at the start of a song,
                // so one playback never mixes two sources.
                StreamFallback.drop(mediaId)
                uriCache.remove(mediaId)
                runCatching { cache.removeResource(mediaId) }
            }

            val cachedSpan = when {
                chunkLength == null -> 0L
                cachedLength > 0 -> chunkLength.coerceAtMost(cachedLength - dataSpec.position) // the song's last chunk is shorter
                else -> chunkLength
            }

            if (fullyCached) {
                dataSpec // Completed audio stays playable offline, independent of source-pin expiry.
            } else if (!dataSpec.isLocal && fallbackOn && StreamFallback.isForced(mediaId)) {
                saavnFallback() ?: throw (if (chunkLength == null) IOException("No playable stream") else UnplayableException())
            } else if (
                dataSpec.isLocal || (cachedSpan > 0 && cache.isCached(mediaId, dataSpec.position, cachedSpan))
            ) {
                // Cached bytes only cover one chunk: end the read at the cached edge. Unbounded, the next
                // uncached byte would be fetched with this bare-id uri (file not found, playback dies after a seek).
                if (dataSpec.isLocal || chunkLength == null) dataSpec
                else dataSpec.subrange(0L, if (dataSpec.length > 0L) dataSpec.length.coerceAtMost(cachedSpan) else cachedSpan)
            } else {
                val knownUri = uriCache[mediaId]
                if (knownUri != null && mediaId in limitedStreams) {
                    val itag = knownUri.uri.getQueryParameter("itag")
                    val inDirectPart = dataSpec.position < DIRECT_STREAM_LIMIT
                    // Already extracted: use it. Still inside the first minute: no need to wait for it.
                    (if (inDirectPart && !ytDlpRun(mediaId, ytDlpFormat(itag)).isCompleted) null
                    else switchToFullLink(itag, if (inDirectPart) 1_000L else YT_DLP_SEEK_WAIT_MS))
                        ?: if (!inDirectPart && !ytDlpRun(mediaId, ytDlpFormat(itag)).isCompleted) {
                            throw IOException("Full stream link not ready yet") // the player tries again; the run goes on
                        } else if (inDirectPart) {
                            val remaining = (knownUri.meta ?: Long.MAX_VALUE) - dataSpec.position
                            val wanted = directRangeLength(dataSpec.position, remaining, chunkLength ?: DIRECT_STREAM_LIMIT)
                            dataSpec.withUri(knownUri.uri)
                                .withRequestHeaders(streamHeaders[mediaId].orEmpty())
                                .subrange(0L, if (dataSpec.length > 0L) dataSpec.length.coerceAtMost(wanted) else wanted)
                        } else {
                            // yt-dlp could not help: the link itself decides. A refusal lands in onPlayerError's fallback.
                            dataSpec.withUri(knownUri.uri).withRequestHeaders(streamHeaders[mediaId].orEmpty()).ranged(knownUri.meta)
                        }
                } else knownUri?.let { cachedUri ->
                    dataSpec
                        .withUri(cachedUri.uri)
                        .withRequestHeaders(streamHeaders[mediaId].orEmpty())
                        .ranged(cachedUri.meta)
                } ?: run {
                    if (isSaavnKey(mediaId)) {
                        // K4Audio: JioSaavn-backed item — resolve its CDN url once
                        // and remember it, skipping the YouTube/yt-dlp path entirely.
                        val cdn = runBlocking(Dispatchers.IO) { resolveSaavnStream(mediaId, PlayerPreferences.audioQuality.saavn) }
                            ?: throw (if (chunkLength == null) IOException("No playable stream") else UnplayableException())
                        val mediaItem = runCatching { runBlocking(Dispatchers.IO) { findMediaItem(mediaId) } }.getOrNull()
                        val saavnLen: Long? = null // Avoid a blocking HEAD request before playback.
                        transaction {
                            runCatching {
                                mediaItem?.let(Database::insert)
                                Database.insert(
                                    Format(
                                        songId = mediaId,
                                        itag = null,
                                        mimeType = "audio/mp4",
                                        bitrate = null,
                                        loudnessDb = null,
                                        contentLength = saavnLen,
                                        lastModified = null
                                    )
                                )
                            }
                        }
                        uriCache.push(key = mediaId, meta = saavnLen, uri = cdn.toUri())
                        return@run dataSpec.withUri(cdn.toUri()).ranged(saavnLen)
                    }

                    StreamFallback.url(mediaId)?.let { saved ->
                        uriCache.push(key = mediaId, meta = null, uri = saved.toUri())
                        return@run dataSpec.withUri(saved.toUri())
                    }

                    // YouTube's first answer: metadata, and a link that starts the song at once.
                    // A stalled client must not hold the song back, yt-dlp can answer instead.
                    val body = if (mediaId in rejectedDirectStreams) null else runBlocking(Dispatchers.IO) {
                        withTimeoutOrNull(INNERTUBE_TIMEOUT_MS) {
                            com.k4music.android.utils.networkResult { Innertube.player(PlayerBody(videoId = mediaId)) }
                        }
                    }?.getOrNull()
                    val youtubeFormat = body?.streamingData?.audioFormat(PlayerPreferences.audioQuality.youtubeItags)

                    // Downloads read past the direct link's limit straight away, so they skip it.
                    val directUrl = youtubeFormat?.url?.takeIf {
                        it.isNotBlank() && mediaId !in rejectedDirectStreams && chunkLength != null && dataSpec.position < DIRECT_STREAM_LIMIT
                    }
                    val uri: android.net.Uri
                    val formatId: String?
                    val bitrate: Long?
                    val contentLength: Long?

                    if (directUrl != null) {
                        uri = directUrl.toUri()
                        formatId = youtubeFormat.itag.toString()
                        bitrate = youtubeFormat.bitrate
                        contentLength = youtubeFormat.contentLength
                        // The link only serves the song's first minute: get the full one ready before it runs out.
                        limitedStreams.add(mediaId)
                        ytDlpRun(mediaId, ytDlpFormat(formatId))
                    } else {
                        val info = extract(null, if (chunkLength == null) YT_DLP_DOWNLOAD_TIMEOUT_MS else YT_DLP_TIMEOUT_MS)
                        val format = info?.formats?.firstOrNull { it.formatId == info.formatId }
                        uri = (info?.url?.takeIf { it.isNotBlank() } ?: format?.url?.takeIf { it.isNotBlank() })?.toUri()
                            ?: return@run (if (fallbackOn) saavnFallback(pinMs = SLOW_YOUTUBE_PIN_MS) else null) ?: throw (if (chunkLength == null) IOException("No playable stream") else UnplayableException())
                        formatId = info?.formatId
                        bitrate = format?.abr?.let { it * 1000 }?.toLong()
                        contentLength = info?.fileSize?.takeIf { it > 0L } ?: uri.getQueryParameter("clen")?.toLongOrNull()
                        if (streamHeaders.size >= 64) streamHeaders.clear()
                        streamHeaders[mediaId] = info?.httpHeaders.orEmpty()
                    }
                    rememberLength(contentLength)

                    val mediaItem = runCatching {
                        runBlocking(Dispatchers.IO) { findMediaItem(mediaId) }
                    }.getOrNull()

                    val extras = mediaItem?.mediaMetadata?.extras?.songBundle
                    if (extras?.durationText == null) {
                        body
                            ?.streamingData
                            ?.highestQualityFormat
                            ?.approxDurationMs
                            ?.div(1000)
                            ?.let(DateUtils::formatElapsedTime)
                            ?.removePrefix("0")
                            ?.let { durationText ->
                                extras?.durationText = durationText
                                Database.updateDurationText(mediaId, durationText)
                            }
                    }

                    transaction {
                        runCatching {
                            mediaItem?.let(Database::insert)
                            Database.insert(
                                Format(
                                    songId = mediaId,
                                    itag = formatId?.toIntOrNull() ?: youtubeFormat?.itag,
                                    mimeType = youtubeFormat?.mimeType,
                                    bitrate = bitrate,
                                    loudnessDb = body?.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                    contentLength = contentLength,
                                    lastModified = youtubeFormat?.lastModified
                                )
                            )
                        }
                    }

                    uriCache.push(
                        key = mediaId,
                        meta = contentLength,
                        uri = uri
                    )

                    val spec = dataSpec
                        .withUri(uri)
                        .withRequestHeaders(streamHeaders[mediaId].orEmpty())
                    if (directUrl != null) {
                        val remaining = (contentLength ?: Long.MAX_VALUE) - dataSpec.position
                        val wanted = directRangeLength(dataSpec.position, remaining, chunkLength ?: DIRECT_STREAM_LIMIT)
                        spec.subrange(0L, if (dataSpec.length > 0L) dataSpec.length.coerceAtMost(wanted) else wanted)
                    } else spec.ranged(contentLength)
                }
            }
        }.handleUnknownErrors { error, spec ->
            // Only an HTTP refusal proves a stream url bad; a dropped socket keeps it.
            val http = error.findCause<InvalidResponseCodeException>() ?: return@handleUnknownErrors
            val id = (http.dataSpec.key ?: spec?.key)?.removePrefix("https://youtube.com/watch?v=")
                ?: return@handleUnknownErrors
            uriCache.remove(id)
            limitedStreams.remove(id)
            // A refused link must not come back from the shared result.
            synchronized(ytDlpRuns) {
                ytDlpRuns[id]?.takeIf { it.job.isCompleted && !it.job.isCancelled && http.responseCode in setOf(403, 410) }?.let { ytDlpRuns.remove(id) }
            }
            if ((http.responseCode == 403 || http.responseCode == 410) && !isSaavnKey(id) && StreamFallback.url(id) == null) {
                // A valid-looking Innertube URL can still be forbidden by the CDN.
                // Re-extract this exact video through yt-dlp on the next attempt.
                if (rejectedDirectStreams.size >= 64) rejectedDirectStreams.clear()
                rejectedDirectStreams.add(id)
            }
        }.retryIf(maxRetries = 2) { error ->
            val status = error.findCause<InvalidResponseCodeException>()?.responseCode
            if (status != null) status in setOf(403, 408, 410, 429, 500, 502, 503, 504)
            else error.findCause<IOException>() != null && error.findCause<java.io.FileNotFoundException>() == null && error.findCause<androidx.media3.common.ParserException>() == null && error.findCause<java.io.InterruptedIOException>()?.let { it !is java.net.SocketTimeoutException } != true
        }
        }
    }
}
