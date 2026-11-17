package eu.kanade.tachiyomi.animeextension.en.dramatap

import java.net.URI

/** One playable rendition of an Episode stream. */
data class StreamVariant(
    val url: String,
    /** Short edge in pixels, so 9:16 `1080x1920` reads as `1080p`. */
    val height: Int?,
    val bandwidth: Long,
    val audioUrls: List<Pair<String, String>>,
) {
    val qualityLabel: String get() = height?.let { "${it}p" } ?: "Auto"
}

/**
 * Minimal `#EXTM3U` master parser for vertical microdrama streams. Labels variants by
 * `min(width, height)` and binds demuxed `#EXT-X-MEDIA:TYPE=AUDIO` groups to each variant.
 */
object VerticalHlsParser {

    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
    private val RESOLUTION = Regex("""^(\d+)x(\d+)$""", RegexOption.IGNORE_CASE)

    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line.substringAfter(':')).associate { m ->
            m.groupValues[1] to (m.groups[3]?.value ?: m.groupValues[2])
        }

    fun resolve(base: String, ref: String): String =
        runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref)

    fun isMasterPlaylist(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    /** Returns variants sorted best first. Empty when [text] is a media playlist. */
    fun parseMaster(text: String, manifestUrl: String): List<StreamVariant> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val audioGroups = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        lines.filter { it.startsWith("#EXT-X-MEDIA:") }.forEach { line ->
            val a = attributes(line)
            val uri = a["URI"]
            if (a["TYPE"] == "AUDIO" && uri != null) {
                val label = a["NAME"] ?: a["LANGUAGE"] ?: "Audio"
                audioGroups.getOrPut(a["GROUP-ID"].orEmpty()) { mutableListOf() }
                    .add(resolve(manifestUrl, uri) to label)
            }
        }
        val variants = mutableListOf<StreamVariant>()
        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed
            val uri = lines.getOrNull(i + 1)?.takeUnless { it.startsWith("#") } ?: return@forEachIndexed
            val a = attributes(line)
            val height = a["RESOLUTION"]?.let(RESOLUTION::find)?.let {
                minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
            variants += StreamVariant(
                url = resolve(manifestUrl, uri),
                height = height,
                bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                audioUrls = a["AUDIO"]?.let { audioGroups[it] }.orEmpty(),
            )
        }
        return variants.sortedWith(compareByDescending<StreamVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth })
    }
}
