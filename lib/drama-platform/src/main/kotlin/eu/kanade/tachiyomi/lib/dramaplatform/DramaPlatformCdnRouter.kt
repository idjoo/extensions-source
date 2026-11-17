package eu.kanade.tachiyomi.lib.dramaplatform

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object DramaPlatformCdnRouter {

    const val CDN_PROXY_BASE = "https://cdn.dramadizilerim.xyz"
    const val BUNNY_HLS_PROXY_PREFIX = "https://hls-proxy-i4ic7.b-cdn.net/?url="

    private val NO_REFERER_HOSTS = setOf(
        "video.reelintv.com",
        "cfcdn.petadrama.com",
    )

    private val QUALITY_NUMBER_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)

    fun resolveDirectStreamUrl(candidate: DecryptedStreamCandidate): String {
        val normalized = normalizeLegacyProxyUrl(candidate.url.trim())
        val streamType = candidate.streamType.lowercase()
        val provider = candidate.upstreamProvider.orEmpty()
        val host = extractHost(normalized)

        return when {
            streamType == "dramawave" || streamType == "storyreel" -> mediaProxyWrap(normalized)
            provider.equals("FlickReels", ignoreCase = true) &&
                !normalized.contains("pl.shortdizilab.com", ignoreCase = true) -> mediaProxyWrap(normalized)
            streamType == "hls" && (
                host == "tqshort.com" || host.endsWith(".tqshort.com") ||
                    host == "toonshort.com" || host.endsWith(".toonshort.com")
                ) -> bunnyWrap(normalized)
            else -> normalized
        }
    }

    fun mediaProxyWrap(url: String): String {
        if (url.startsWith("$CDN_PROXY_BASE/") || url.startsWith(BUNNY_HLS_PROXY_PREFIX)) {
            return url
        }
        return when {
            url.startsWith("https://", ignoreCase = true) ->
                "$CDN_PROXY_BASE/https/${url.substring(8)}"
            url.startsWith("http://", ignoreCase = true) ->
                "$CDN_PROXY_BASE/http/${url.substring(7)}"
            else -> url
        }
    }

    fun bunnyWrap(url: String): String {
        if (url.startsWith(BUNNY_HLS_PROXY_PREFIX)) return url
        return BUNNY_HLS_PROXY_PREFIX + URLEncoder.encode(url, "UTF-8")
    }

    fun bunnyFallbackUrl(url: String): String? = when {
        url.startsWith("$CDN_PROXY_BASE/https/") -> {
            val inner = "https://" + url.removePrefix("$CDN_PROXY_BASE/https/")
            bunnyWrap(inner)
        }
        url.startsWith("$CDN_PROXY_BASE/http/") -> {
            val inner = "http://" + url.removePrefix("$CDN_PROXY_BASE/http/")
            bunnyWrap(inner)
        }
        url.startsWith("$CDN_PROXY_BASE/?url=") -> {
            BUNNY_HLS_PROXY_PREFIX + url.removePrefix("$CDN_PROXY_BASE/?url=")
        }
        else -> null
    }

    fun buildCatalogHeaders(userAgent: String): Headers.Builder =
        Headers.Builder().add("User-Agent", userAgent)

    fun buildStreamHeaders(
        targetUrl: String,
        baseUrl: String,
        userAgent: String,
        cdnOrigin: String = "https://dramatip.net",
    ): Headers {
        val host = extractHost(targetUrl)
        return when {
            host in NO_REFERER_HOSTS -> Headers.headersOf("User-Agent", userAgent)
            host == "cdn.dramadizilerim.xyz" -> Headers.headersOf(
                "User-Agent",
                userAgent,
                "Origin",
                cdnOrigin.trimEnd('/'),
                "Referer",
                "${cdnOrigin.trimEnd('/')}/",
            )
            else -> Headers.headersOf(
                "User-Agent",
                userAgent,
                "Referer",
                "${baseUrl.trimEnd('/')}/",
            )
        }
    }

    fun sortStreamChainVideos(
        chainGroups: List<Pair<Int, List<Video>>>,
        preferredQuality: String,
    ): List<Video> {
        val grouped = LinkedHashMap<Int, MutableList<Video>>()
        for ((idx, list) in chainGroups) {
            grouped.getOrPut(idx) { mutableListOf() }.addAll(list)
        }

        val normalizedPref = preferredQuality.trim().lowercase()
        val result = mutableListOf<Video>()
        for ((_, videos) in grouped.entries.sortedBy { it.key }) {
            val sortedGroup = videos.sortedWith(
                compareByDescending<Video> { video ->
                    normalizedPref.isNotEmpty() && video.quality.lowercase().contains(normalizedPref)
                }.thenByDescending { video ->
                    QUALITY_NUMBER_REGEX.find(video.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
            )
            result.addAll(sortedGroup)
        }
        return result
    }

    private fun normalizeLegacyProxyUrl(url: String): String {
        val isLegacy = url.startsWith("https://dizi1.dramadizilerim.com/", ignoreCase = true) ||
            url.startsWith("https://dramadizilerim.com/ts-proxy.php", ignoreCase = true)
        if (!isLegacy) return url

        val query = url.substringAfter('?', missingDelimiterValue = "")
        for (part in query.split('&')) {
            if (part.startsWith("url=")) {
                val decoded = runCatching { URLDecoder.decode(part.removePrefix("url="), "UTF-8") }.getOrNull()
                if (!decoded.isNullOrBlank()) {
                    return bunnyWrap(decoded)
                }
            }
        }
        return url
    }

    internal fun extractHost(url: String): String =
        runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
}
