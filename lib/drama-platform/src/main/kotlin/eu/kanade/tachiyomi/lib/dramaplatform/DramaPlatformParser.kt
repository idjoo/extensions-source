package eu.kanade.tachiyomi.lib.dramaplatform

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

data class SeriesSummary(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
)

data class SeriesPage(
    val series: List<SeriesSummary>,
    val hasNextPage: Boolean,
)

data class SeriesDetails(
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val categories: List<String>,
    val upstreamProvider: String?,
    val status: Int,
)

data class EpisodeEntry(
    val url: String,
    val name: String,
    val episodeNumber: Float,
    val upstreamProvider: String?,
)

data class PlaybackTarget(
    val bookId: String,
    val rawSerial: Int,
    val rawCatalog: Boolean,
    val isLocked: Boolean = false,
)

data class EpisodeUrlRef(
    val episodePath: String,
    val displayNumber: Int,
    val playbackTarget: PlaybackTarget?,
)

data class FilterOption(
    val name: String,
    val slug: String,
)

object DramaPlatformParser {

    private val SERIES_PATH_REGEX = Regex("""^(?:/[a-z]{2,3})?/series/([^/?#]+)/?$""")
    private val SERIES_OR_EPISODE_PATH_REGEX = Regex("""^((?:/[a-z]{2,3})?/series/[^/?#]+)(?:/episode-\d+)?/?$""")
    private val CATEGORY_PATH_REGEX = Regex("""^(?:/[a-z]{2,3})?/category/([^/?#]+)/?$""")
    private val SOURCE_PATH_REGEX = Regex("""^(?:/[a-z]{2,3})?/source/([^/?#]+)/?$""")
    private val SLUG_QUERY_REGEX = Regex("""^slug:([A-Za-z0-9_-]+)$""", RegexOption.IGNORE_CASE)
    private val TRAILING_COUNT_REGEX = Regex("""\s+[\d,.]+\s+\S+$""")
    private val EPISODE_NUMBER_PATH_REGEX = Regex("""/episode-(\d+)(?:[/?#]|$)""")
    private val TRAILING_UPDATING_REGEX = Regex("""\s+updating$""", RegexOption.IGNORE_CASE)
    private val RATING_REGEX = Regex("""^\d+(?:\.\d+)?$""")
    private const val NEXT_FLIGHT_PREFIX = "self.__next_f.push([1,\""

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseSeriesPage(
        html: String,
        currentPage: Int,
        baseUrl: String = "https://dramatip.net",
    ): SeriesPage {
        val doc = Jsoup.parse(html, baseUrl)
        val seenUrls = LinkedHashSet<String>()
        val seriesList = mutableListOf<SeriesSummary>()

        for (anchor in doc.select("a[href*='/series/']")) {
            val rawHref = anchor.attr("href").trim()
            val relativePath = normalizeRelativePath(rawHref, baseUrl) ?: continue
            val match = SERIES_PATH_REGEX.matchEntire(relativePath) ?: continue
            if (match.groupValues[1].equals("page", ignoreCase = true)) continue

            val img = anchor.selectFirst("img")
            val headingText = anchor.selectFirst("h2, h3")?.text()?.trim().orEmpty()
            val altText = img?.attr("alt")?.trim().orEmpty()
            val rawTitle = headingText.ifBlank { altText }
            if (rawTitle.isBlank() || img == null) continue

            val cleanPath = relativePath.trimEnd('/')
            if (!seenUrls.add(cleanPath)) continue

            seriesList.add(
                SeriesSummary(
                    url = cleanPath,
                    title = cleanSeriesTitle(rawTitle),
                    thumbnailUrl = img.absUrl("src").ifBlank { img.attr("src") }.ifBlank { null },
                ),
            )
        }

        val nextPage = currentPage + 1
        val nextPathRegex = Regex("""/page/$nextPage(?:[/?#]|$)""")
        val nextQueryRegex = Regex("""[?&]page=$nextPage(?:[&#]|$)""")
        val hasNextPage = doc.select("a[href]").any { link ->
            val href = link.attr("href")
            nextPathRegex.containsMatchIn(href) || nextQueryRegex.containsMatchIn(href)
        }

        return SeriesPage(seriesList, hasNextPage)
    }

    fun parseSeriesDetails(
        html: String,
        seriesPath: String,
        baseUrl: String = "https://dramatip.net",
    ): SeriesDetails {
        val doc = Jsoup.parse(html, baseUrl)
        val tvSeries = extractTvSeriesJsonLd(doc)

        val rawJsonTitle = tvSeries?.get("name")?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
        val rawH1Title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val rawTitle = rawJsonTitle.ifBlank { rawH1Title }
        val slug = seriesPath.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        val isOngoing = slug.endsWith("-updating", ignoreCase = true) ||
            TRAILING_UPDATING_REGEX.containsMatchIn(rawTitle) ||
            TRAILING_UPDATING_REGEX.containsMatchIn(rawH1Title)

        val upstreamProvider = extractUpstreamProvider(doc, html)

        val bodyDoc = doc.clone().apply { select("header, footer, nav").remove() }
        val rating = bodyDoc.select("span:has(svg.text-rating), span:has(svg)")
            .map { it.text().trim() }
            .firstOrNull { RATING_REGEX.matches(it) }

        val episodeCount = tvSeries?.get("numberOfEpisodes")?.jsonPrimitiveOrNull()?.intOrNull

        val synopsis = tvSeries?.get("description")?.jsonPrimitiveOrNull()?.contentOrNull?.trim().orEmpty()
        val metaLines = buildList {
            if (!rating.isNullOrBlank()) add("Rating: $rating/10")
            if (episodeCount != null && episodeCount > 0) add("Episodes: $episodeCount")
        }
        val fullDescription = listOfNotNull(
            synopsis.takeIf { it.isNotBlank() },
            metaLines.takeIf { it.isNotEmpty() }?.joinToString(" | "),
        ).joinToString("\n\n")

        val categories = when (val genreEl = tvSeries?.get("genre")) {
            is JsonArray -> genreEl.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            is JsonPrimitive -> listOfNotNull(genreEl.contentOrNull?.trim()?.takeIf(String::isNotEmpty))
            else -> emptyList()
        }

        val imageUrl = when (val imageEl = tvSeries?.get("image")) {
            is JsonPrimitive -> imageEl.contentOrNull
            is JsonArray -> imageEl.firstOrNull()?.jsonPrimitiveOrNull()?.contentOrNull
            is JsonObject -> imageEl["url"]?.jsonPrimitiveOrNull()?.contentOrNull
            else -> null
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        return SeriesDetails(
            title = cleanSeriesTitle(rawTitle),
            description = fullDescription,
            thumbnailUrl = imageUrl,
            categories = categories,
            upstreamProvider = upstreamProvider,
            status = if (isOngoing) SAnime.ONGOING else SAnime.COMPLETED,
        )
    }

    fun parseEpisodeList(
        html: String,
        seriesPath: String,
        showLockedEpisodes: Boolean,
        baseUrl: String = "https://dramatip.net",
    ): List<EpisodeEntry> {
        val doc = Jsoup.parse(html, baseUrl)
        val cleanSeriesPath = seriesPath.substringBefore('#').substringBefore('?').trimEnd('/')
        val upstreamProvider = extractUpstreamProvider(doc, html)

        val rscBundle = extractRscEpisodeBundle(html)
        if (rscBundle != null && rscBundle.episodes.isNotEmpty()) {
            val encodedBookId = URLEncoder.encode(rscBundle.bookId, "UTF-8")
            val rawFlag = if (rscBundle.rawCatalog) 1 else 0
            val byDisplay = LinkedHashMap<Int, EpisodeEntry>()

            for (ep in rscBundle.episodes) {
                if (ep.isLocked && !showLockedEpisodes) continue
                val displayNumber = (ep.rawSerial - rscBundle.firstSerial + 1).coerceAtLeast(1)
                val name = if (ep.isLocked) "[Locked] Episode $displayNumber" else "Episode $displayNumber"
                val lockedSuffix = if (ep.isLocked) "&locked=1" else ""
                val url = "$cleanSeriesPath/episode-$displayNumber#bookId=$encodedBookId&serial=${ep.rawSerial}&raw=$rawFlag$lockedSuffix"
                byDisplay[displayNumber] = EpisodeEntry(
                    url = url,
                    name = name,
                    episodeNumber = displayNumber.toFloat(),
                    upstreamProvider = upstreamProvider,
                )
            }
            return byDisplay.entries
                .sortedByDescending { it.key }
                .map { it.value }
        }

        val jsonLdCount = extractTvSeriesJsonLd(doc)?.get("numberOfEpisodes")?.jsonPrimitiveOrNull()?.intOrNull ?: 0
        val domMax = doc.select("a[href*='/episode-']").mapNotNull { link ->
            EPISODE_NUMBER_PATH_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        val fallbackCount = maxOf(jsonLdCount, domMax)
        if (fallbackCount <= 0) return emptyList()

        return (fallbackCount downTo 1).map { displayNumber ->
            EpisodeEntry(
                url = "$cleanSeriesPath/episode-$displayNumber",
                name = "Episode $displayNumber",
                episodeNumber = displayNumber.toFloat(),
                upstreamProvider = upstreamProvider,
            )
        }
    }

    fun parseEpisodeUrl(episodeUrl: String): EpisodeUrlRef {
        val pathPart = episodeUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        val displayNumber = EPISODE_NUMBER_PATH_REGEX.find(pathPart)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val fragment = episodeUrl.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isBlank()) {
            return EpisodeUrlRef(
                episodePath = pathPart,
                displayNumber = displayNumber,
                playbackTarget = null,
            )
        }

        val params = LinkedHashMap<String, String>()
        for (pair in fragment.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq)
            val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            params[key] = value
        }

        val bookId = params["bookId"]?.takeIf { it.isNotBlank() }
        val rawSerial = params["serial"]?.toIntOrNull()
        val rawCatalog = params["raw"] == "1" || params["raw"].equals("true", ignoreCase = true)
        val isLocked = params["locked"] == "1" || params["locked"].equals("true", ignoreCase = true)
        val target = if (bookId != null && rawSerial != null) {
            PlaybackTarget(bookId = bookId, rawSerial = rawSerial, rawCatalog = rawCatalog, isLocked = isLocked)
        } else {
            null
        }

        return EpisodeUrlRef(
            episodePath = pathPart,
            displayNumber = displayNumber,
            playbackTarget = target,
        )
    }

    fun recoverPlaybackTarget(html: String, displayNumber: Int): PlaybackTarget? {
        val bundle = extractRscEpisodeBundle(html) ?: return null
        val matchedEpisode = bundle.episodes.firstOrNull { ep ->
            (ep.rawSerial - bundle.firstSerial + 1).coerceAtLeast(1) == displayNumber
        }
        val matchedSerial = matchedEpisode?.rawSerial ?: (displayNumber + bundle.firstSerial - 1)

        return PlaybackTarget(
            bookId = bundle.bookId,
            rawSerial = matchedSerial,
            rawCatalog = bundle.rawCatalog,
            isLocked = matchedEpisode?.isLocked ?: false,
        )
    }

    fun parseCategories(
        html: String,
        baseUrl: String = "https://dramatip.net",
    ): List<FilterOption> = parseFilterOptions(html, baseUrl, "/category/", CATEGORY_PATH_REGEX)

    fun parseUpstreamProviders(
        html: String,
        baseUrl: String = "https://dramatip.net",
    ): List<FilterOption> = parseFilterOptions(html, baseUrl, "/source/", SOURCE_PATH_REGEX)

    fun resolveDirectSeriesPath(
        query: String,
        localePrefix: String,
        baseUrl: String = "https://dramatip.net",
    ): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        SLUG_QUERY_REGEX.matchEntire(trimmed)?.let { match ->
            val slug = match.groupValues[1]
            return "${localePrefix.trimEnd('/')}/series/$slug"
        }

        val candidatePath = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                val pathAfterHost = runCatching { java.net.URI(trimmed).rawPath }.getOrNull() ?: return null
                pathAfterHost
            }
            trimmed.startsWith("/") -> trimmed.substringBefore('#').substringBefore('?')
            else -> return null
        }

        val match = SERIES_OR_EPISODE_PATH_REGEX.matchEntire(candidatePath) ?: return null
        val slug = match.groupValues[1].substringAfterLast('/')
        if (slug.equals("page", ignoreCase = true)) return null
        return match.groupValues[1].trimEnd('/')
    }

    private fun parseFilterOptions(
        html: String,
        baseUrl: String,
        segment: String,
        pathRegex: Regex,
    ): List<FilterOption> {
        val doc = Jsoup.parse(html, baseUrl).apply { select("header, footer, nav").remove() }
        val bySlug = LinkedHashMap<String, FilterOption>()

        for (anchor in doc.select("a[href*='$segment']")) {
            val relPath = normalizeRelativePath(anchor.attr("href").trim(), baseUrl) ?: continue
            val slug = pathRegex.matchEntire(relPath)?.groupValues?.get(1) ?: continue
            if (slug.equals("page", ignoreCase = true) || bySlug.containsKey(slug)) continue

            val cleanedAnchor = anchor.clone().apply {
                select("[aria-hidden=true], .tabular-nums").remove()
            }
            val titleSpan = cleanedAnchor.selectFirst(".font-semibold")?.text()?.trim().orEmpty()
            val fallbackLabel = cleanedAnchor.text().trim().replace(TRAILING_COUNT_REGEX, "").trim()
            val label = titleSpan.ifBlank { fallbackLabel }.ifBlank { slug }
            bySlug[slug] = FilterOption(name = label, slug = slug)
        }
        return bySlug.values.toList()
    }

    internal fun cleanSeriesTitle(rawTitle: String): String =
        rawTitle.trim().replace(TRAILING_UPDATING_REGEX, "").trim()

    private data class RawRscEpisode(
        val rawSerial: Int,
        val isLocked: Boolean,
    )

    private data class RscEpisodeBundle(
        val bookId: String,
        val firstSerial: Int,
        val rawCatalog: Boolean,
        val episodes: List<RawRscEpisode>,
    )

    private fun extractRscEpisodeBundle(html: String): RscEpisodeBundle? {
        val bookObjects = mutableListOf<JsonObject>()
        for (element in parseFlightElements(html)) {
            collectJsonObjects(element, bookObjects) { obj -> obj.containsKey("bookId") }
        }
        if (bookObjects.isEmpty()) return null

        val bookId = bookObjects.firstNotNullOfOrNull { obj ->
            obj["bookId"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: return null

        val firstSerial = bookObjects.firstNotNullOfOrNull { obj ->
            obj["firstSerial"]?.jsonPrimitiveOrNull()?.intOrNull
        } ?: 1

        val rawCatalog = bookObjects.any { obj ->
            obj["rawCatalog"]?.jsonPrimitiveOrNull()?.booleanOrNull == true
        }

        val episodeArrays = bookObjects.mapNotNull { it["episodes"] as? JsonArray }.filter { it.isNotEmpty() }
        val chosenArray = episodeArrays.firstOrNull { arr ->
            val firstObj = arr.firstOrNull() as? JsonObject
            firstObj != null && (firstObj.containsKey("is_locked") || firstObj.containsKey("chapter_id"))
        } ?: episodeArrays.firstOrNull()

        val parsedEpisodes = chosenArray?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val serial = obj["serial_number"]?.jsonPrimitiveOrNull()?.intOrNull ?: return@mapNotNull null
            val locked = obj["is_locked"]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false
            RawRscEpisode(rawSerial = serial, isLocked = locked)
        }.orEmpty()

        return RscEpisodeBundle(
            bookId = bookId,
            firstSerial = firstSerial,
            rawCatalog = rawCatalog,
            episodes = parsedEpisodes,
        )
    }

    private fun extractUpstreamProvider(doc: org.jsoup.nodes.Document, html: String): String? {
        val bodyDoc = doc.clone().apply { select("header, footer, nav").remove() }
        val badgeProvider = bodyDoc.selectFirst("a[href*='/source/']")?.text()?.trim()?.takeIf { it.isNotBlank() }
        return badgeProvider ?: extractRscSourceFirstProvider(html)
    }

    private fun extractTvSeriesJsonLd(doc: org.jsoup.nodes.Document): JsonObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: continue
            findJsonObject(root) { obj ->
                obj["@type"]?.jsonPrimitiveOrNull()?.contentOrNull == "TVSeries"
            }?.let { return it }
        }
        return null
    }

    private fun extractRscSourceFirstProvider(html: String): String? {
        for (element in parseFlightElements(html)) {
            val found = findJsonObject(element) { obj ->
                obj.containsKey("sourceFirst") && obj["sourceFirst"] is JsonObject
            }
            val source = found?.get("sourceFirst")?.jsonObject?.get("source")?.jsonPrimitiveOrNull()?.contentOrNull
            if (!source.isNullOrBlank()) return source
        }
        return null
    }

    internal fun parseFlightElements(html: String): List<JsonElement> {
        val merged = buildString {
            var searchFrom = 0
            while (true) {
                val startIdx = html.indexOf(NEXT_FLIGHT_PREFIX, searchFrom)
                if (startIdx < 0) break
                val quoteStart = startIdx + NEXT_FLIGHT_PREFIX.length - 1
                var cursor = quoteStart + 1
                while (cursor < html.length) {
                    val ch = html[cursor]
                    if (ch == '\\') {
                        cursor += 2
                    } else if (ch == '"') {
                        break
                    } else {
                        cursor++
                    }
                }
                if (cursor >= html.length) break
                val rawJsonLiteral = html.substring(quoteStart, cursor + 1)
                val decoded = runCatching { json.decodeFromString<String>(rawJsonLiteral) }.getOrNull()
                if (decoded != null) append(decoded)
                searchFrom = cursor + 1
            }
        }
        if (merged.isEmpty()) return emptyList()

        val elements = mutableListOf<JsonElement>()
        for (line in merged.lineSequence()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0 || colonIdx + 1 >= line.length) continue
            val payload = line.substring(colonIdx + 1).trim()
            if (!payload.startsWith('[') && !payload.startsWith('{')) continue
            runCatching { json.parseToJsonElement(payload) }.getOrNull()?.let(elements::add)
        }
        return elements
    }

    internal fun findJsonObject(root: JsonElement, predicate: (JsonObject) -> Boolean): JsonObject? {
        when (root) {
            is JsonObject -> {
                if (predicate(root)) return root
                for (value in root.values) {
                    findJsonObject(value, predicate)?.let { return it }
                }
            }
            is JsonArray -> {
                for (item in root) {
                    findJsonObject(item, predicate)?.let { return it }
                }
            }
            else -> {}
        }
        return null
    }

    private fun collectJsonObjects(
        root: JsonElement,
        out: MutableList<JsonObject>,
        predicate: (JsonObject) -> Boolean,
    ) {
        when (root) {
            is JsonObject -> {
                if (predicate(root)) out.add(root)
                for (value in root.values) collectJsonObjects(value, out, predicate)
            }
            is JsonArray -> {
                for (item in root) collectJsonObjects(item, out, predicate)
            }
            else -> {}
        }
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun normalizeRelativePath(href: String, baseUrl: String): String? {
        if (href.isBlank()) return null
        val stripped = if (href.startsWith(baseUrl)) {
            href.removePrefix(baseUrl)
        } else if (href.startsWith("http://") || href.startsWith("https://")) {
            return null
        } else {
            href
        }
        val withoutQueryOrFragment = stripped.substringBefore('#').substringBefore('?')
        return if (withoutQueryOrFragment.startsWith('/')) withoutQueryOrFragment else "/$withoutQueryOrFragment"
    }
}
