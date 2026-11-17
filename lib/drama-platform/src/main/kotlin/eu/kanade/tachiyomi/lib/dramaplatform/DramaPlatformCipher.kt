package eu.kanade.tachiyomi.lib.dramaplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class DecryptedStreamCandidate(
    val chainIndex: Int,
    val upstreamProvider: String?,
    val streamType: String,
    val url: String,
    val qualityLabel: String?,
)

data class DecryptedSubtitle(
    val url: String,
    val format: String?,
    val language: String?,
)

data class DecryptedStreamDescriptor(
    val streamChain: List<DecryptedStreamCandidate>,
    val subtitle: DecryptedSubtitle?,
    val hasEncryptedPayload: Boolean,
)

@OptIn(ExperimentalEncodingApi::class)
class DramaPlatformCipher(
    initialKeyBase64: String = DEFAULT_KEY_BASE64,
    private val onKeyRotated: ((String) -> Unit)? = null,
) {
    @Volatile
    private var activeKeyBase64: String = initialKeyBase64

    @Volatile
    private var activeKeyBytes: ByteArray = decodeKeyOrDefault(initialKeyBase64)

    fun decryptStreamDescriptor(
        apiJson: String,
        jsBundleSupplier: (() -> Sequence<String>)? = null,
    ): DecryptedStreamDescriptor {
        val root = runCatching { json.parseToJsonElement(apiJson) }.getOrNull() as? JsonObject
            ?: return DecryptedStreamDescriptor(emptyList(), null, hasEncryptedPayload = false)
        val descriptor = (root["descriptor"] as? JsonObject) ?: root

        val initial = decryptDescriptorObject(descriptor, activeKeyBytes)
        if (!initial.hasEncryptedPayload || initial.streamChain.isNotEmpty() || initial.subtitle != null || jsBundleSupplier == null) {
            return initial
        }

        val triedKeys = LinkedHashSet<String>().apply { add(activeKeyBase64) }
        if (triedKeys.add(DEFAULT_KEY_BASE64)) {
            val defaultBytes = Base64.decode(DEFAULT_KEY_BASE64)
            val withDefault = decryptDescriptorObject(descriptor, defaultBytes)
            if (withDefault.streamChain.isNotEmpty() || withDefault.subtitle != null) {
                activeKeyBase64 = DEFAULT_KEY_BASE64
                activeKeyBytes = defaultBytes
                onKeyRotated?.invoke(DEFAULT_KEY_BASE64)
                return withDefault
            }
        }

        for (jsChunk in jsBundleSupplier.invoke()) {
            for (candidateKeyB64 in extractCandidateKeysFromJs(jsChunk)) {
                if (!triedKeys.add(candidateKeyB64)) continue
                val candidateBytes = runCatching { Base64.decode(candidateKeyB64) }.getOrNull()
                    ?.takeIf { it.size == 32 } ?: continue
                val attempt = decryptDescriptorObject(descriptor, candidateBytes)
                if (attempt.streamChain.isNotEmpty() || attempt.subtitle != null) {
                    activeKeyBase64 = candidateKeyB64
                    activeKeyBytes = candidateBytes
                    onKeyRotated?.invoke(candidateKeyB64)
                    return attempt
                }
            }
        }

        return initial
    }

    private fun decryptDescriptorObject(
        descriptor: JsonObject,
        keyBytes: ByteArray,
    ): DecryptedStreamDescriptor {
        val fallbackSource = descriptor["source"]?.stringOrNull()
        val rawChain = (descriptor["chain"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(descriptor.takeIf { it.hasAnyEncryptedToken() })

        var sawEncryptedToken = false
        val candidates = mutableListOf<DecryptedStreamCandidate>()

        for ((index, item) in rawChain.withIndex()) {
            val provider = item["source"]?.stringOrNull() ?: fallbackSource
            val streamType = item["type"]?.stringOrNull() ?: "hls"
            val qualityObj = item["encByQuality"] as? JsonObject
            val seenItemUrls = LinkedHashSet<String>()

            if (qualityObj != null && qualityObj.isNotEmpty()) {
                val sortedEntries = qualityObj.entries.sortedByDescending { (k, _) ->
                    k.filter(Char::isDigit).toIntOrNull() ?: 0
                }
                for ((rawQuality, encEl) in sortedEntries) {
                    val token = encEl.stringOrNull()?.takeIf(String::isNotBlank) ?: continue
                    sawEncryptedToken = true
                    val url = decryptToken(token, keyBytes) ?: continue
                    if (seenItemUrls.add(url)) {
                        val normalizedQuality = if (rawQuality.endsWith("p", ignoreCase = true)) {
                            rawQuality.lowercase()
                        } else {
                            "${rawQuality}p"
                        }
                        candidates.add(
                            DecryptedStreamCandidate(
                                chainIndex = index,
                                upstreamProvider = provider,
                                streamType = streamType,
                                url = url,
                                qualityLabel = normalizedQuality,
                            ),
                        )
                    }
                }
            }

            val fallbackToken = item["enc"]?.stringOrNull()?.takeIf(String::isNotBlank)
            if (fallbackToken != null) {
                sawEncryptedToken = true
                val url = decryptToken(fallbackToken, keyBytes)
                if (url != null && seenItemUrls.add(url)) {
                    candidates.add(
                        DecryptedStreamCandidate(
                            chainIndex = index,
                            upstreamProvider = provider,
                            streamType = streamType,
                            url = url,
                            qualityLabel = null,
                        ),
                    )
                }
            }
        }

        val subtitleObj = descriptor["subtitle"] as? JsonObject
        val subtitleToken = subtitleObj?.get("enc")?.stringOrNull()?.takeIf(String::isNotBlank)
        if (subtitleToken != null) sawEncryptedToken = true
        val subtitle = subtitleToken?.let { decryptToken(it, keyBytes) }?.let { subUrl ->
            DecryptedSubtitle(
                url = subUrl,
                format = subtitleObj["format"]?.stringOrNull(),
                language = subtitleObj["language"]?.stringOrNull(),
            )
        }

        return DecryptedStreamDescriptor(
            streamChain = candidates,
            subtitle = subtitle,
            hasEncryptedPayload = sawEncryptedToken,
        )
    }

    private fun extractCandidateKeysFromJs(jsText: String): Sequence<String> = sequence {
        if (!jsText.contains("AES-GCM")) return@sequence
        for (match in IMPORT_KEY_AES_GCM_REGEX.findAll(jsText)) {
            yield(match.groupValues[1])
        }
        for (gcmMatch in AES_GCM_MARKER_REGEX.findAll(jsText)) {
            val start = (gcmMatch.range.first - 300).coerceAtLeast(0)
            val end = (gcmMatch.range.last + 300).coerceAtMost(jsText.length)
            val window = jsText.substring(start, end)
            for (b64Match in BASE64_256_KEY_REGEX.findAll(window)) {
                yield(b64Match.groupValues[1])
            }
        }
    }

    private fun decryptToken(tokenBase64: String, keyBytes: ByteArray): String? = runCatching {
        if (keyBytes.size != 32) return null
        val payload = Base64.decode(tokenBase64.trim())
        if (payload.size <= GCM_IV_LENGTH + GCM_TAG_BYTES) return null
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val cipherAndTag = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.doFinal(cipherAndTag).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun decodeKeyOrDefault(base64Key: String): ByteArray =
        runCatching { Base64.decode(base64Key.trim()) }.getOrNull()?.takeIf { it.size == 32 }
            ?: Base64.decode(DEFAULT_KEY_BASE64)

    private fun JsonObject.hasAnyEncryptedToken(): Boolean {
        if (!this["enc"]?.stringOrNull().isNullOrBlank()) return true
        val q = this["encByQuality"] as? JsonObject
        return q != null && q.values.any { !it.stringOrNull().isNullOrBlank() }
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEFAULT_KEY_BASE64 = "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc="
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128

        private val IMPORT_KEY_AES_GCM_REGEX = Regex(
            """importKey\(\s*["']raw["']\s*,\s*[A-Za-z0-9_$]+\(\s*["']([A-Za-z0-9+/]{43}=)["']\s*\)\s*,\s*\{\s*name\s*:\s*["']AES-GCM["']""",
        )
        private val AES_GCM_MARKER_REGEX = Regex("""AES-GCM""")
        private val BASE64_256_KEY_REGEX = Regex("""["']([A-Za-z0-9+/]{43}=)["']""")

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
