package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * bLyrics (BetterLyrics) API: a title/artist query returns Apple-style TTML.
 * https://lyrics-api.boidu.dev
 */
internal class BlyricsSource : LyricsSource {

    private val throttle = Throttle(300)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        if (query.durationSec <= 0) return emptyList()
        val url = requestUrl(track, settings)
        throttle.await()
        val ttml = runCatching { fetchTtml(url) }.getOrNull() ?: return emptyList()
        if (ttml.isBlank()) return emptyList()
        return listOf(
            Lyrics(
                id = "blyrics:" + candidateId(query.title, query.artist, query.durationSec),
                title = track.title,
                subtitle = "${query.artist} • word synced",
                lyrics = null,
                extras = mapOf("url" to url),
            ),
        )
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val url = lyrics.extras["url"] ?: throw LyricsException("bLyrics candidate is missing its query")
        throttle.await()
        val ttml = fetchTtml(url)
        val lines = Ttml.parse(ttml)
        if (lines.isEmpty()) throw LyricsException("bLyrics returned no usable lyrics")
        return lyrics.copy(lyrics = timed(lines, wordLevel = lines.any { it.words.size > 1 }))
    }

    private suspend fun fetchTtml(url: String): String {
        val body = Http.get(url)
        val root = Http.json(body).obj() ?: return ""
        return root.str("ttml").orEmpty()
    }

    private fun requestUrl(track: Track, settings: Settings?): String {
        val base = settings?.getString(KEY_ENDPOINT)?.trim().orEmpty().ifEmpty { BASE_URL }
        val query = track.query()
        return buildString {
            append(base)
            append("?s=").append(Http.encode(track.title))
            append("&a=").append(Http.encode(query.artist))
            if (query.durationSec > 0) append("&d=").append(query.durationSec)
            if (query.album.isNotBlank()) append("&al=").append(Http.encode(query.album))
        }
    }

    companion object {
        const val KEY_ENDPOINT = "endpoint"
        private const val BASE_URL = "https://lyrics-api.boidu.dev/getLyrics"

        fun settings(): List<Setting> = listOf(
            SettingTextInput(
                title = "API endpoint",
                key = KEY_ENDPOINT,
                summary = "Leave blank for https://lyrics-api.boidu.dev/getLyrics",
                defaultValue = "",
            ),
        )
    }
}
