package eu.kanade.tachiyomi.animeextension.en.dramatap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/** A Series card as listed on a DramaTap catalog page or in search results. */
data class SeriesCard(
    val id: Long,
    val slug: String,
    val title: String,
    val coverUrl: String?,
) {
    /** Relative Series path, also the persisted `SAnime.url`. */
    val path: String get() = seriesPath(id, slug)
}

data class SeriesListing(
    val series: List<SeriesCard>,
    val hasNextPage: Boolean,
)

data class SeriesDetails(
    val title: String?,
    val coverUrl: String?,
    val synopsis: String?,
    val categories: List<String>,
    val episodeCount: Int?,
)

data class EpisodeEntry(
    val number: Int,
    val streamUrl: String,
    val locked: Boolean,
)

data class EpisodeList(
    val title: String?,
    val episodes: List<EpisodeEntry>,
    /** Number of free Episodes on a VIP Series, or null when every Episode is free. */
    val freeEpisodes: Int?,
)

fun seriesPath(id: Long, slug: String): String =
    if (slug.isBlank()) "/d/$id" else "/d/$id/$slug"

/** Pure parsers for DramaTap SSR HTML and its JSON API. */
object DramaTapParser {

    /** Relative stream URLs are resolved against this origin, mirroring `absUrl` in the site's app.js. */
    const val RELATIVE_STREAM_ORIGIN = "https://chartdrama.com"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val SERIES_HREF = Regex("""^/d/(\d+)(?:/([^/?#]+))?""")
    private val EPISODE_FRAGMENT = Regex("""#ep=(\d+)""")

    fun parseSeriesId(path: String): Long? =
        SERIES_HREF.find(path.substringAfter("dramatap.app"))?.groupValues?.get(1)?.toLongOrNull()

    fun parseEpisodeNumber(episodeUrl: String): Int? =
        EPISODE_FRAGMENT.find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Parses SSR Series cards from home, `/new/{page}` and `/tag/{slug}/{page}` pages.
     * [pagePath] is the path of page 1 (for example `/new`), used to detect a link to the next page.
     */
    fun parseListing(html: String, pagePath: String?, page: Int): SeriesListing {
        val doc = Jsoup.parse(html)
        val seen = HashSet<Long>()
        val series = doc.select("a.card.ssr-card[href^=/d/]").mapNotNull { a ->
            val match = SERIES_HREF.find(a.attr("href")) ?: return@mapNotNull null
            val id = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.selectFirst(".t")?.text().orEmpty() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            SeriesCard(
                id = id,
                slug = match.groupValues[2],
                title = title,
                coverUrl = a.selectFirst("img[src]")?.attr("abs:src")?.ifBlank { null }
                    ?: a.selectFirst("img[src]")?.attr("src"),
            )
        }
        val hasNext = pagePath != null &&
            doc.selectFirst("a[href=${pagePath.trimEnd('/')}/${page + 1}]") != null
        return SeriesListing(series, hasNext)
    }

    fun parseSeriesDetails(html: String): SeriesDetails {
        val doc = Jsoup.parse(html)
        val ld = doc.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { json.parseToJsonElement(it.data()) as? JsonObject }.getOrNull() }
            .firstOrNull { it["@type"]?.jsonPrimitive?.contentOrNull == "TVSeries" }
        fun ldString(key: String) = ld?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

        val synopsis = doc.selectFirst(".ssr-synopsis p")?.text()?.trim()?.ifEmpty { null }
            ?: ldString("description")
        val categories = doc.select(".ssr-tags a.ssr-tag").map { it.text().trim() }.filter(String::isNotEmpty)
        return SeriesDetails(
            title = ldString("name") ?: doc.selectFirst("[data-drama-title]")?.attr("data-drama-title"),
            coverUrl = ldString("image"),
            synopsis = synopsis,
            categories = categories,
            episodeCount = ld?.get("numberOfEpisodes")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() },
        )
    }

    @Serializable
    private class SearchResponse(val items: List<SearchItem> = emptyList())

    @Serializable
    private class SearchItem(
        val id: Long,
        val title: String = "",
        val slug: String = "",
        val cover: String? = null,
    )

    fun parseSearch(body: String): List<SeriesCard> =
        json.decodeFromString<SearchResponse>(body).items
            .filter { it.title.isNotBlank() }
            .map { SeriesCard(it.id, it.slug, it.title, it.cover) }

    @Serializable
    private class EpisodesResponse(
        val items: List<EpisodeItem> = emptyList(),
        val title: String? = null,
        val vip: Int = 0,
        @SerialName("free_eps") val freeEps: Int = 0,
        val notfound: Int = 0,
        val gone: Int = 0,
    )

    @Serializable
    private class EpisodeItem(val ep: Int, val url: String? = null)

    /**
     * Parses `/api/episodes?id=`. On a VIP Series (`vip == 1` with `free_eps > 0`) every Episode past
     * `free_eps` is a Locked Episode, matching `vipLocked` in the site's player.
     */
    fun parseEpisodes(body: String): EpisodeList {
        val res = json.decodeFromString<EpisodesResponse>(body)
        if (res.notfound != 0 || res.gone != 0) return EpisodeList(res.title, emptyList(), null)
        val freeEps = if (res.vip != 0 && res.freeEps > 0) res.freeEps else null
        val episodes = res.items
            .filter { !it.url.isNullOrBlank() }
            .distinctBy { it.ep }
            .sortedBy { it.ep }
            .map { item ->
                val url = item.url!!.trim()
                EpisodeEntry(
                    number = item.ep,
                    streamUrl = if (url.startsWith("/")) RELATIVE_STREAM_ORIGIN + url else url,
                    locked = freeEps != null && item.ep > freeEps,
                )
            }
        return EpisodeList(res.title, episodes, freeEps)
    }
}
