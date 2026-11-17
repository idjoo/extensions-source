package eu.kanade.tachiyomi.animeextension.en.dramafren

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * Catalog Source for DramaFren's short-drama catalog (English Locale).
 * Series pages are plain HTML; Episodes and streams come from the site's `msdb` admin-ajax actions.
 */
class DramaFren : AnimeHttpSource() {

    override val name = "DramaFren"

    override val baseUrl = BASE_URL

    override val lang = "en"

    override val supportsLatest = false

    // No Referer by default so Upstream Provider cover CDNs do not reject hotlinks.
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)

    private val ajaxHeaders: Headers by lazy {
        headersBuilder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // Popular: the catalog only offers a single newest-first listing.
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/?msdb_catalog=1&msdb_page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseSeriesPage(response)

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("msdb_catalog", "1")
            .addQueryParameter("s", query.trim().take(100))
            .addQueryParameter("msdb_page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSeriesPage(response)

    private fun parseSeriesPage(response: Response): AnimesPage {
        val page = response.use { DramaFrenParser.parseSeriesPage(it.body.string(), baseUrl) }
        val animes = page.series.map { card ->
            SAnime.create().apply {
                url = seriesPath(card.bookId)
                title = card.title
                thumbnail_url = card.coverUrl
            }
        }
        return AnimesPage(animes, page.hasNextPage)
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val details = response.use { DramaFrenParser.parseSeriesDetails(it.body.string(), baseUrl) }
        return SAnime.create().apply {
            title = details.title
            thumbnail_url = details.coverUrl
            genre = details.categories.joinToString().ifEmpty { null }
            author = details.upstreamProvider
            description = details.synopsis
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    override fun episodeListRequest(anime: SAnime): Request =
        ajaxRequest("msdb_playlist", bookIdOf(anime.url), chapter = "")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val bookId = response.request.body.formValue("book")
        val entries = response.use { DramaFrenParser.parsePlaylist(it.body.string()) }
        return entries
            .filterNot { it.isLocked } // Locked Episodes stay hidden and are never unlocked.
            .map { entry ->
                SEpisode.create().apply {
                    url = "${seriesPath(bookId)}&msdb_episode=${entry.chapterId}"
                    name = entry.name
                    episode_number = entry.number.toFloat()
                }
            }
            .reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    // Stream URLs are signed and expire, so they are always fetched fresh per Episode.
    override fun videoListRequest(episode: SEpisode): Request {
        val url = (baseUrl + episode.url).toHttpUrl()
        return ajaxRequest(
            "msdb_video",
            url.queryParameter("msdb_book").orEmpty(),
            url.queryParameter("msdb_episode").orEmpty(),
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val streams = response.use { DramaFrenParser.parseVideo(it.body.string()) }
        val videoHeaders = headersBuilder().build()
        return streams.map { stream ->
            val label = if (stream.quality == "auto") "Default" else "${stream.quality}p"
            Video(stream.url, label, stream.url, headers = videoHeaders)
        }
    }

    private fun ajaxRequest(action: String, bookId: String, chapter: String): Request {
        val body = FormBody.Builder()
            .add("action", action)
            .add("book", bookId)
            .add("chapter", chapter)
            .add("lang", LANG)
            .build()
        return POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body)
    }

    private fun okhttp3.RequestBody?.formValue(name: String): String {
        val form = this as? FormBody ?: return ""
        return (0 until form.size).firstOrNull { form.name(it) == name }?.let(form::value).orEmpty()
    }

    companion object {
        const val BASE_URL = "https://v2.dramafren.org"
        const val LANG = "en"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"

        fun seriesPath(bookId: String): String = "/?msdb_book=$bookId&msdb_lang=$LANG"

        fun bookIdOf(path: String): String = Regex("""msdb_book=(\d+)""").find(path)?.groupValues?.get(1).orEmpty()
    }
}
