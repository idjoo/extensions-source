package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ExternalSubtitleTrack(
    val url: String,
    val label: String,
    val languageCode: String?,
)

object VerticalHlsParser {

    private val ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,\s]+)""")
    private val RESOLUTION_REGEX = Regex("""^(\d+)x(\d+)$""", RegexOption.IGNORE_CASE)
    private val PATH_HEIGHT_REGEX = Regex("""(?:^|[/_-])(\d{3,4})p?(?:[/_.-]|$)""", RegexOption.IGNORE_CASE)

    private data class MediaRendition(
        val groupId: String,
        val name: String,
        val language: String?,
        val uri: String,
        val isDefault: Boolean,
    )

    private data class VariantStream(
        val url: String,
        val shortEdgePixels: Int?,
        val qualityLabel: String,
        val bandwidth: Long,
        val audioGroupId: String?,
        val subtitleGroupId: String?,
    )

    fun parseMasterPlaylist(
        manifestText: String,
        manifestUrl: String,
        providerLabel: String,
        activeLocale: String,
        headers: Headers? = null,
        externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
    ): List<Video> {
        val lines = manifestText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val audioGroups = LinkedHashMap<String, MutableList<MediaRendition>>()
        val subtitleGroups = LinkedHashMap<String, MutableList<MediaRendition>>()
        val variants = mutableListOf<VariantStream>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = parseAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                    val type = attrs["TYPE"]?.uppercase()
                    val groupId = attrs["GROUP-ID"]
                    val rawUri = attrs["URI"]
                    if (!groupId.isNullOrBlank() && !rawUri.isNullOrBlank()) {
                        val resolvedUri = resolveHlsUri(manifestUrl, rawUri)
                        val language = attrs["LANGUAGE"]
                        val name = attrs["NAME"]?.takeIf { it.isNotBlank() }
                            ?: language?.takeIf { it.isNotBlank() }
                            ?: "Track"
                        val rendition = MediaRendition(
                            groupId = groupId,
                            name = name,
                            language = language,
                            uri = resolvedUri,
                            isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                        )
                        when (type) {
                            "AUDIO" -> audioGroups.getOrPut(groupId) { mutableListOf() }.add(rendition)
                            "SUBTITLES" -> subtitleGroups.getOrPut(groupId) { mutableListOf() }.add(rendition)
                        }
                    }
                }

                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
                    var nextIdx = i + 1
                    while (nextIdx < lines.size && lines[nextIdx].startsWith("#")) {
                        nextIdx++
                    }
                    if (nextIdx < lines.size) {
                        val variantRawUri = lines[nextIdx]
                        val resolvedVariantUrl = resolveHlsUri(manifestUrl, variantRawUri)
                        val shortEdge = attrs["RESOLUTION"]?.let { res ->
                            val match = RESOLUTION_REGEX.matchEntire(res)
                            if (match != null) {
                                val w = match.groupValues[1].toIntOrNull()
                                val h = match.groupValues[2].toIntOrNull()
                                if (w != null && h != null) minOf(w, h) else null
                            } else {
                                null
                            }
                        } ?: inferResolutionFromNameOrPath(attrs["NAME"], variantRawUri)

                        val qualitySuffix = when {
                            shortEdge != null -> "${shortEdge}p"
                            !attrs["NAME"].isNullOrBlank() -> attrs["NAME"]!!
                            else -> "Auto"
                        }
                        variants.add(
                            VariantStream(
                                url = resolvedVariantUrl,
                                shortEdgePixels = shortEdge,
                                qualityLabel = "$providerLabel - $qualitySuffix",
                                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                                audioGroupId = attrs["AUDIO"],
                                subtitleGroupId = attrs["SUBTITLES"],
                            ),
                        )
                        i = nextIdx
                    }
                }
            }
            i++
        }

        val sortedExternalSubs = externalSubtitles
            .sortedWith(compareByDescending { matchesLocale(it.languageCode, activeLocale) })

        if (variants.isEmpty()) {
            val tracks = sortedExternalSubs.map { Track(it.url, it.label) }
            return listOf(
                Video(
                    manifestUrl,
                    "$providerLabel - Auto",
                    manifestUrl,
                    headers,
                    tracks,
                    emptyList(),
                ),
            )
        }

        val sortedVariants = variants.sortedWith(
            compareByDescending<VariantStream> { it.shortEdgePixels ?: 0 }
                .thenByDescending { it.bandwidth },
        )

        return sortedVariants.map { variant ->
            val audioTracks = variant.audioGroupId
                ?.let { audioGroups[it] }
                .orEmpty()
                .sortedWith(
                    compareByDescending<MediaRendition> { matchesLocale(it.language, activeLocale) }
                        .thenByDescending { it.isDefault },
                )
                .map { Track(it.uri, it.name) }

            val hlsSubs = variant.subtitleGroupId
                ?.let { subtitleGroups[it] }
                ?: subtitleGroups.values.flatten()

            data class CandidateSub(val url: String, val label: String, val langCode: String?, val isExternal: Boolean)

            val mergedSubs = buildList {
                for (ext in externalSubtitles) {
                    add(CandidateSub(ext.url, ext.label, ext.languageCode, isExternal = true))
                }
                for (sub in hlsSubs) {
                    add(CandidateSub(sub.uri, sub.name, sub.language, isExternal = false))
                }
            }.sortedWith(
                compareByDescending<CandidateSub> { matchesLocale(it.langCode, activeLocale) }
                    .thenByDescending { it.isExternal },
            ).distinctBy { it.url }
                .map { Track(it.url, it.label) }

            Video(
                variant.url,
                variant.qualityLabel,
                variant.url,
                headers,
                mergedSubs,
                audioTracks,
            )
        }
    }

    internal fun resolveHlsUri(baseManifestUrl: String, refUri: String): String {
        val trimmed = refUri.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }

        val queryUrlIdx = baseManifestUrl.indexOf("?url=")
        if (queryUrlIdx > 0) {
            val wrapperPrefix = baseManifestUrl.substring(0, queryUrlIdx + 5)
            val encodedInner = baseManifestUrl.substring(queryUrlIdx + 5).substringBefore('&')
            val decodedInner = runCatching { URLDecoder.decode(encodedInner, "UTF-8") }.getOrNull()
            if (!decodedInner.isNullOrBlank()) {
                val resolvedInner = runCatching { URI(decodedInner).resolve(trimmed).toString() }.getOrNull()
                if (!resolvedInner.isNullOrBlank()) {
                    return wrapperPrefix + URLEncoder.encode(resolvedInner, "UTF-8")
                }
            }
        }

        return runCatching { URI(baseManifestUrl).resolve(trimmed).toString() }.getOrDefault(trimmed)
    }

    private fun parseAttributes(attrString: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (match in ATTRIBUTE_REGEX.findAll(attrString)) {
            val key = match.groupValues[1]
            val rawVal = match.groupValues[3].ifEmpty { match.groupValues[2].trim('"') }
            map[key] = rawVal
        }
        return map
    }

    private fun inferResolutionFromNameOrPath(name: String?, uri: String): Int? {
        if (!name.isNullOrBlank()) {
            name.trim().removeSuffix("p").removeSuffix("P").toIntOrNull()?.let { return it }
        }
        return PATH_HEIGHT_REGEX.find(uri)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun matchesLocale(trackLang: String?, activeLocale: String): Boolean {
        if (trackLang.isNullOrBlank() || activeLocale.isBlank()) return false
        val normTrack = trackLang.trim().lowercase().replace('_', '-')
        val normActive = activeLocale.trim().lowercase().replace('_', '-')
        return normTrack == normActive ||
            normTrack.startsWith("$normActive-") ||
            normActive.startsWith("$normTrack-")
    }
}
