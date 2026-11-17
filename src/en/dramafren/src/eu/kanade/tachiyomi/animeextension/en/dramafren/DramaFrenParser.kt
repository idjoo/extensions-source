package eu.kanade.tachiyomi.animeextension.en.dramafren

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/** Series card shown on the DramaFren catalog or search results. */
data class SeriesCard(
    val bookId: String,
    val title: String,
    val coverUrl: String?,
    val episodeCount: Int?,
)

data class SeriesPage(val series: List<SeriesCard>, val hasNextPage: Boolean)

data class SeriesDetails(
    val title: String,
    val coverUrl: String?,
    val upstreamProvider: String?,
    val categories: List<String>,
    val synopsis: String?,
)

/** One quality variant of a Stream Descriptor, keyed by the site's quality label. */
data class StreamVariant(val quality: String, val url: String)

data class EpisodeEntry(
    val chapterId: String,
    val name: String,
    val number: Int,
    val streams: List<StreamVariant>,
) {
    /** Episodes with no playable source are treated as Locked Episodes. */
    val isLocked: Boolean get() = streams.isEmpty()
}

/**
 * Pure parsing for the DramaFren `msdb` short-drama plugin pages and its admin-ajax JSON.
 * No Android or network dependencies so it can be exercised directly from JUnit.
 */
object DramaFrenParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val BOOK_ID = Regex("""msdb_book=(\d{1,24})""")
    private val EPISODE_COUNT = Regex("""(\d+)\s+episodes?""", RegexOption.IGNORE_CASE)
    private val CHAPTER_ID = Regex("""^\d{1,24}$""")

    /** Quality order used by the site player, best first. `auto` duplicates another entry. */
    private val QUALITY_ORDER = listOf("1080", "720", "540", "auto")

    fun parseSeriesPage(html: String, baseUrl: String): SeriesPage {
        val doc = Jsoup.parse(html, baseUrl)
        val series = doc.select("article.msdb-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=msdb_book=]") ?: return@mapNotNull null
            val bookId = BOOK_ID.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = card.selectFirst("h2")?.text()?.trim().orEmpty()
                .ifEmpty { card.selectFirst("img")?.attr("alt").orEmpty() }
            SeriesCard(
                bookId = bookId,
                title = title,
                coverUrl = card.selectFirst("img")?.absUrl("src")?.ifBlank { null },
                episodeCount = card.selectFirst(".msdb-badge")?.text()
                    ?.let { EPISODE_COUNT.find(it)?.groupValues?.get(1)?.toIntOrNull() },
            )
        }.distinctBy { it.bookId }
        val hasNext = doc.select(".msdb-pagination a").any { it.text().contains("Next", ignoreCase = true) }
        return SeriesPage(series, hasNext)
    }

    fun parseSeriesDetails(html: String, baseUrl: String): SeriesDetails {
        val doc = Jsoup.parse(html, baseUrl)
        val detail = doc.selectFirst(".msdb-detail") ?: doc
        val eyebrow = detail.selectFirst(".msdb-eyebrow")?.text()
        return SeriesDetails(
            title = detail.selectFirst("h1")?.text()?.trim().orEmpty(),
            coverUrl = detail.selectFirst(".msdb-detail-poster img")?.absUrl("src")?.ifBlank { null },
            upstreamProvider = eyebrow?.substringBefore("·")?.trim()?.ifEmpty { null },
            categories = detail.select(".msdb-tags span").map { it.text().trim() }.filter(String::isNotEmpty),
            synopsis = detail.selectFirst(".msdb-synopsis")?.wholeText()?.trim()?.ifEmpty { null },
        )
    }

    /** Parses the `msdb_playlist` admin-ajax response. Invalid or duplicate ids are dropped like the site player does. */
    fun parsePlaylist(body: String): List<EpisodeEntry> {
        val data = dataOf(body)
        val seen = HashSet<String>()
        return data["episodes"]?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            if (!CHAPTER_ID.matches(id) || !seen.add(id)) return@mapIndexedNotNull null
            EpisodeEntry(
                chapterId = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "EP ${index + 1}",
                number = index + 1,
                streams = parseSources(obj["sources"] as? JsonObject),
            )
        }
    }

    /** Parses the `msdb_video` admin-ajax response into quality variants, best first. */
    fun parseVideo(body: String): List<StreamVariant> = parseSources(dataOf(body)["sources"] as? JsonObject)

    private fun dataOf(body: String): JsonObject {
        val root = json.parseToJsonElement(body).jsonObject
        val ok = root["success"]?.jsonPrimitive?.let { runCatching { it.boolean }.getOrNull() } == true
        val data = root["data"] as? JsonObject
        if (!ok || data == null) {
            val message = data?.get("message")?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(message ?: "DramaFren request failed")
        }
        return data
    }

    private fun parseSources(sources: JsonObject?): List<StreamVariant> {
        if (sources == null) return emptyList()
        val variants = QUALITY_ORDER.mapNotNull { key ->
            val url = (sources[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            url?.takeIf { it.startsWith("https://") }?.let { StreamVariant(key, it) }
        }
        // `auto` mirrors a fixed quality; keep it only when nothing else is present.
        val fixed = variants.filter { it.quality != "auto" }
        return fixed.ifEmpty { variants }
    }
}
