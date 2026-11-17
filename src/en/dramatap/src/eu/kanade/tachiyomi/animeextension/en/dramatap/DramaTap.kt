package eu.kanade.tachiyomi.animeextension.en.dramatap

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class DramaTap : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "DramaTap"

    override val baseUrl = BASE_URL

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences? by lazy {
        runCatching { getSourcePreferences() }.getOrNull()
    }

    private val dailyCap by lazy {
        DailyEpisodeCap(
            object : CapStore {
                override fun load(): String? = preferences?.getString(PREF_CAP_STATE, null)
                override fun save(value: String) {
                    preferences?.edit()?.putString(PREF_CAP_STATE, value)?.apply()
                }
            },
        )
    }

    /** No Referer: upstream cover and video CDNs reject foreign referers (the site sets `no-referrer`). */
    override fun headersBuilder(): Headers.Builder = Headers.Builder().add("User-Agent", USER_AGENT)

    private val streamHeaders by lazy { Headers.Builder().add("User-Agent", USER_AGENT).build() }

    // ============================== Catalog ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        listingPage(response, pagePath = null, page = 1)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page <= 1) "$baseUrl/new" else "$baseUrl/new/$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        listingPage(response, "/new", pageOf(response))

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/search".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).build()
            return GET(url, headers)
        }
        val slug = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selectedSlug()
            ?: return popularAnimeRequest(page)
        return GET(if (page <= 1) "$baseUrl/tag/$slug" else "$baseUrl/tag/$slug/$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url
        if (url.encodedPath == "/api/search") {
            val cards = response.use { DramaTapParser.parseSearch(it.body.string()) }
            return AnimesPage(cards.map(::toAnime), false)
        }
        val tagPath = url.pathSegments.take(2).takeIf { it.firstOrNull() == "tag" }?.joinToString("/", prefix = "/")
        return listingPage(response, tagPath, pageOf(response))
    }

    override fun getFilterList(): AnimeFilterList = dramaTapFilters()

    private fun pageOf(response: Response): Int =
        response.request.url.pathSegments.lastOrNull()?.toIntOrNull() ?: 1

    private fun listingPage(response: Response, pagePath: String?, page: Int): AnimesPage {
        val listing = response.use { DramaTapParser.parseListing(it.body.string(), pagePath, page) }
        return AnimesPage(listing.series.map(::toAnime), listing.hasNextPage)
    }

    private fun toAnime(card: SeriesCard) = SAnime.create().apply {
        url = card.path
        title = card.title
        thumbnail_url = card.coverUrl
    }

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val details = response.use { DramaTapParser.parseSeriesDetails(it.body.string()) }
        return SAnime.create().apply {
            details.title?.let { title = it }
            thumbnail_url = details.coverUrl
            description = details.synopsis
            genre = details.categories.joinToString().ifEmpty { null }
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    private fun episodesRequest(seriesId: Long): Request = GET("$baseUrl/api/episodes?id=$seriesId", headers)

    override fun episodeListRequest(anime: SAnime): Request {
        val id = DramaTapParser.parseSeriesId(anime.url) ?: error("Invalid Series URL: ${anime.url}")
        return episodesRequest(id)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val id = response.request.url.queryParameter("id")?.toLongOrNull() ?: error("Missing Series id")
        val list = response.use { DramaTapParser.parseEpisodes(it.body.string()) }
        val showLocked = preferences?.getBoolean(PREF_SHOW_LOCKED, false) ?: false
        // Slug only affects WebView; the site redirects bare /d/{id} to the canonical path.
        val seriesUrl = seriesPath(id, "")
        return list.episodes
            .filter { showLocked || !it.locked }
            .map { ep ->
                SEpisode.create().apply {
                    url = "$seriesUrl#ep=${ep.number}"
                    name = "Episode ${ep.number}"
                    episode_number = ep.number.toFloat()
                    if (ep.locked) scanlator = "Locked (VIP)"
                }
            }
            .reversed()
    }

    // ============================== Videos ==============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val seriesId = DramaTapParser.parseSeriesId(episode.url) ?: error("Invalid Episode URL")
        val number = DramaTapParser.parseEpisodeNumber(episode.url) ?: error("Invalid Episode URL")
        val list = client.newCall(episodesRequest(seriesId)).execute().use { res ->
            if (!res.isSuccessful) error("HTTP error ${res.code}")
            DramaTapParser.parseEpisodes(res.body.string())
        }
        val entry = list.episodes.firstOrNull { it.number == number } ?: error("Episode $number is unavailable")
        if (entry.locked) error("Episode $number is a Locked Episode (DramaTap VIP only)")
        if (!dailyCap.tryConsume(seriesId, number)) {
            val hours = (dailyCap.lockedForMillis() + 3_599_999) / 3_600_000
            error("DramaTap free daily limit reached. Try again in about $hours h.")
        }
        resolveVideos(entry.streamUrl)
    }

    private fun resolveVideos(streamUrl: String): List<Video> {
        if (!streamUrl.substringBefore('?').endsWith(".m3u8")) {
            return listOf(Video(streamUrl, "Default", streamUrl, streamHeaders))
        }
        val manifest = client.newCall(GET(streamUrl, streamHeaders)).execute().use { res ->
            if (!res.isSuccessful) error("Stream HTTP error ${res.code}")
            res.body.string()
        }
        val variants = VerticalHlsParser.parseMaster(manifest, streamUrl)
        if (variants.isEmpty()) return listOf(Video(streamUrl, "Default", streamUrl, streamHeaders))
        val videos = variants.map { v ->
            Video(
                v.url,
                v.qualityLabel,
                v.url,
                streamHeaders,
                emptyList(),
                v.audioUrls.map { (url, label) -> Track(url, label) },
            )
        }
        return sortByQuality(videos)
    }

    private fun sortByQuality(videos: List<Video>): List<Video> {
        val preferred = preferences?.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return videos.sortedByDescending { it.quality == preferred }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Preferred quality"
            entries = QUALITIES
            entryValues = QUALITIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show Locked Episodes"
            summary = "List VIP-only Episodes (marked Locked). They cannot be played without DramaTap VIP."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        const val BASE_URL = "https://dramatap.app"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITIES = arrayOf("1080p", "720p", "540p", "480p", "360p")
        private const val PREF_SHOW_LOCKED = "show_locked_episodes"
        private const val PREF_CAP_STATE = "daily_cap_state"
    }
}
