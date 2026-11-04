package com.k4music.android.utils

import androidx.media3.common.MediaItem
import com.k4music.providers.innertube.Innertube
import com.k4music.providers.innertube.models.bodies.ContinuationBody
import com.k4music.providers.innertube.models.bodies.NextBody
import com.k4music.providers.innertube.requests.nextPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class YouTubeRadio(
    private val videoId: String? = null,
    private var playlistId: String? = null,
    private var playlistSetVideoId: String? = null,
    private var parameters: String? = null
) {
    private var nextContinuation: String? = null

    suspend fun process(): List<MediaItem> {
        return withContext(Dispatchers.IO) {
            val continuation = nextContinuation

            val page = if (continuation == null) {
                Innertube.nextPage(
                    NextBody(
                        videoId = videoId,
                        playlistId = playlistId,
                        params = parameters,
                        playlistSetVideoId = playlistSetVideoId
                    )
                )?.map { nextResult ->
                    playlistId = nextResult.playlistId
                    parameters = nextResult.params
                    playlistSetVideoId = nextResult.playlistSetVideoId

                    nextResult.itemsPage
                }
            } else {
                Innertube.nextPage(ContinuationBody(continuation = continuation))
            }?.getOrNull() ?: return@withContext emptyList() // a failed fetch keeps our place for the next try

            nextContinuation = page.continuation?.takeUnless { it == continuation }
            page.items?.map(Innertube.SongItem::asMediaItem).orEmpty()
        }
    }
}
