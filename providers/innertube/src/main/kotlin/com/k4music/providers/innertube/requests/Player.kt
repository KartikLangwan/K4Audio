package com.k4music.providers.innertube.requests

import com.k4music.providers.innertube.Innertube
import com.k4music.providers.innertube.models.Context
import com.k4music.providers.innertube.models.PlayerResponse
import com.k4music.providers.innertube.models.bodies.PlayerBody
import com.k4music.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsText
import io.ktor.util.generateNonceSuspend
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private const val VISITOR_TTL_MS = 6 * 60 * 60 * 1000L
private val VISITOR_DATA = Regex("\"(Cg[A-Za-z0-9_%-]{20,})\"")
@Volatile private var visitor: Pair<String, Long>? = null

/** The whole-song client with a recent visitor id; without one YouTube answers "not a bot". Null when none can be had. */
private suspend fun Innertube.wholeSongContext(): Context? {
    val id = visitor?.takeIf { System.currentTimeMillis() - it.second < VISITOR_TTL_MS }?.first
        ?: runCatchingCancellable {
            baseClient.get("https://www.youtube.com/sw.js_data") { timeout { requestTimeoutMillis = 3_000L } }.bodyAsText()
        }?.getOrNull()?.let { VISITOR_DATA.find(it)?.groupValues?.get(1) }?.also { visitor = it to System.currentTimeMillis() }
        ?: return null
    return Context.DefaultVisionOS.copy(client = Context.DefaultVisionOS.client.copy(defaultVisitorData = id))
}

private suspend fun Innertube.tryContexts(
    body: PlayerBody,
    checkIsValid: Boolean,
    vararg contexts: Context
): PlayerResponse? {
    contexts.forEach { context ->
        if (!currentCoroutineContext().isActive) return null

        val cpn = generateNonceSuspend(16)
        runCatchingCancellable {
            client.post(if (context.client.music) PLAYER_MUSIC else PLAYER) {
                setBody(
                    body.copy(
                        context = context,
                        cpn = cpn
                    )
                )

                context.apply()
                // Four clients are tried in turn; one stalled client must not eat a minute.
                timeout { requestTimeoutMillis = 5_000L }

                parameter("t", generateNonceSuspend(12))
                header("X-Goog-Api-Format-Version", "2")
                parameter("id", body.videoId)
            }.body<PlayerResponse>().also {
                // A refused visitor id must not stay for hours: the next song fetches another.
                if (context.client.clientName == Context.WHOLE_SONG_CLIENT && it.playabilityStatus?.status == "LOGIN_REQUIRED") visitor = null
            }
        }
            ?.getOrNull()
            ?.takeIf { !checkIsValid || it.isValid }
            ?.let {
                return it.copy(
                    cpn = cpn,
                    context = context
                )
            }
    }

    return null
}

// A ciphered-only answer is useless here (deciphering is yt-dlp's job): keep trying clients.
private val PlayerResponse.isValid
    get() = playabilityStatus?.status == "OK" &&
        streamingData?.adaptiveFormats?.any { it.mimeType.startsWith("audio/") && !it.url.isNullOrBlank() } == true

/** This answer's links serve the whole song; other clients' links may be refused after the first part (they need a PO token). */
val PlayerResponse.isWholeSong get() = context?.client?.clientName == Context.WHOLE_SONG_CLIENT

suspend fun Innertube.player(
    body: PlayerBody,
    checkIsValid: Boolean = true
): Result<PlayerResponse?>? = runCatchingCancellable {
    tryContexts(
        body,
        checkIsValid,
        *listOfNotNull(
            wholeSongContext(),
            Context.DefaultIOS,
            Context.DefaultWeb,
            Context.DefaultAndroidMusic,
            Context.DefaultTV
        ).toTypedArray()
    )
}

/** Only the whole-song client, for callers with no use for a link that stops early. */
suspend fun Innertube.wholeSongPlayer(body: PlayerBody): Result<PlayerResponse?>? = runCatchingCancellable {
    wholeSongContext()?.let { tryContexts(body, true, it) }
}
