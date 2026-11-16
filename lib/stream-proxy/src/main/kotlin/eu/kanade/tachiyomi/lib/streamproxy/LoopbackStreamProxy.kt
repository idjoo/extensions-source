package eu.kanade.tachiyomi.lib.streamproxy

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class LoopbackStreamProxy(
    private val httpClient: OkHttpClient,
    private val siteBaseUrlProvider: () -> String = { "https://dramatip.net" },
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val cdnProxyBase: String = "https://cdn.dramadizilerim.xyz",
    private val bunnyProxyBase: String = "https://hls-proxy-i4ic7.b-cdn.net",
    private val idleTimeoutMs: Long = 10 * 60 * 1000L,
    private val allowedInsecureHosts: Set<String> = emptySet(),
) : Closeable {

    private val lock = Any()
    private val upstreamClient: OkHttpClient = httpClient.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.build()
    private val normalizedAllowedHosts = allowedInsecureHosts.map { it.lowercase() }.toSet()
    private val sessionToken: String = ByteArray(16).let { bytes ->
        SecureRandom().nextBytes(bytes)
        Base64.UrlSafe.encode(bytes).trimEnd('=')
    }

    private val idleScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dramatip-stream-proxy-idle").apply { isDaemon = true }
    }
    private var idleFuture: ScheduledFuture<*>? = null
    private var server: InternalHttpServer? = null

    private val mp4CacheLock = Any()
    private val decryptedMp4Cache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 2
    }

    fun proxyGoodShortUrl(upstreamUrl: String): String = buildLoopbackUrl("/goodshort.m3u8", upstreamUrl)

    fun proxyIdramaUrl(upstreamUrl: String): String = buildLoopbackUrl("/idrama.m3u8", upstreamUrl)

    fun proxyShortTvUrl(upstreamUrl: String): String = buildLoopbackUrl("/shorttv.m3u8", upstreamUrl)

    fun proxyDramaBoxMp4Url(upstreamUrl: String): String = buildLoopbackUrl("/dbx.mp4", upstreamUrl)

    fun proxySubtitleUrl(upstreamUrl: String, format: String? = null): String {
        val extra = if (!format.isNullOrBlank()) "fmt=${URLEncoder.encode(format, "UTF-8")}" else ""
        return buildLoopbackUrl("/sub.vtt", upstreamUrl, extra)
    }

    override fun close() {
        synchronized(lock) {
            idleFuture?.cancel(false)
            idleFuture = null
            server?.stop()
            server = null
        }
        synchronized(mp4CacheLock) {
            decryptedMp4Cache.clear()
        }
        idleScheduler.shutdownNow()
    }

    private fun buildLoopbackUrl(routePath: String, upstreamUrl: String, extraQuery: String = ""): String {
        val port = ensureRunning()
        val encodedUrl = URLEncoder.encode(upstreamUrl, "UTF-8")
        val suffix = if (extraQuery.isNotEmpty()) "&$extraQuery" else ""
        return "http://127.0.0.1:$port$routePath?t=$sessionToken&u=$encodedUrl$suffix"
    }

    private fun ensureRunning(): Int = synchronized(lock) {
        val existing = server
        if (existing != null && existing.isAlive) {
            scheduleIdleStopLocked()
            return existing.listeningPort
        }
        val created = InternalHttpServer()
        created.start(SOCKET_READ_TIMEOUT_MS, true)
        server = created
        scheduleIdleStopLocked()
        created.listeningPort
    }

    private fun touchActivity() = synchronized(lock) {
        if (server != null) {
            scheduleIdleStopLocked()
        }
    }

    private fun scheduleIdleStopLocked() {
        idleFuture?.cancel(false)
        idleFuture = idleScheduler.schedule(
            {
                synchronized(lock) {
                    server?.stop()
                    server = null
                }
            },
            idleTimeoutMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private inner class InternalHttpServer : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            touchActivity()
            val method = session.method
            if (method != Method.GET && method != Method.HEAD) {
                return plainError(Response.Status.METHOD_NOT_ALLOWED, "Method Not Allowed")
            }

            val params = parseQueryParams(session.queryParameterString.orEmpty())
            val providedToken = params["t"].orEmpty()
            if (!MessageDigest.isEqual(
                    providedToken.toByteArray(Charsets.UTF_8),
                    sessionToken.toByteArray(Charsets.UTF_8),
                )
            ) {
                return plainError(Response.Status.FORBIDDEN, "Forbidden")
            }

            val rawUpstreamUrl = params["u"]?.takeIf(String::isNotBlank)
                ?: return plainError(Response.Status.BAD_REQUEST, "Missing upstream URL")
            if (!isAllowedUpstreamUrl(rawUpstreamUrl)) {
                return plainError(Response.Status.FORBIDDEN, "Disallowed upstream URL")
            }

            val isHead = method == Method.HEAD
            return runCatching {
                when (session.uri) {
                    "/goodshort.m3u8" -> handleGoodShortPlaylist(rawUpstreamUrl, isHead)
                    "/idrama.m3u8" -> handleIdramaPlaylist(rawUpstreamUrl, isHead)
                    "/shorttv.m3u8" -> handleShortTvPlaylist(rawUpstreamUrl, isHead)
                    "/shorttv.ts" -> handleShortTvSegment(rawUpstreamUrl, isHead)
                    "/dbx.mp4" -> handleDramaBoxMp4(rawUpstreamUrl, session.headers["range"], isHead)
                    "/sub.vtt" -> handleSubtitleVtt(rawUpstreamUrl, params["fmt"], isHead)
                    else -> plainError(Response.Status.NOT_FOUND, "Not Found")
                }
            }.getOrElse {
                plainError(BAD_GATEWAY_STATUS, "Upstream error")
            }
        }
    }

    private fun handleGoodShortPlaylist(upstreamUrl: String, isHead: Boolean): NanoHTTPD.Response {
        val normalizedHostUrl = GOODSHORT_ACFS_REGEX.replace(upstreamUrl, "://v2-akm.goodreels.com")
        val primaryWrapped = "$cdnProxyBase/?url=${URLEncoder.encode(normalizedHostUrl, "UTF-8")}"
        val fallbackWrapped = "$cdnProxyBase/?url=${URLEncoder.encode(upstreamUrl, "UTF-8")}"

        val manifestText = fetchTextWithFallback(primaryWrapped, fallbackWrapped)
            ?: return plainError(BAD_GATEWAY_STATUS, "Upstream GoodShort manifest failed")

        val siteBase = siteBaseUrlProvider().trimEnd('/')
        val rewritten = manifestText.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> ""
                line.startsWith("#") -> {
                    OFFLINE_KEY_URI_REGEX.replace(line) { """URI="$siteBase/api/goodshort-key"""" }
                }
                line.startsWith("https://dramadizilerim.com/ts-proxy.php?url=", ignoreCase = true) -> {
                    val innerEncoded = line.substringAfter("?url=").substringBefore('&')
                    "$cdnProxyBase/?url=$innerEncoded"
                }
                else -> {
                    val absolute = resolveRelativeUrl(normalizedHostUrl, line)
                    if (isM3u8Url(absolute)) {
                        val unwrapped = unwrapCdnProxyUrl(absolute)
                        proxyGoodShortUrl(unwrapped)
                    } else if (absolute.startsWith("$cdnProxyBase/")) {
                        absolute
                    } else {
                        "$cdnProxyBase/?url=${URLEncoder.encode(absolute, "UTF-8")}"
                    }
                }
            }
        }
        return textResponse(MIME_HLS, rewritten, isHead)
    }

    private fun handleIdramaPlaylist(upstreamUrl: String, isHead: Boolean): NanoHTTPD.Response {
        val manifestText = fetchText(upstreamUrl)
            ?: return plainError(BAD_GATEWAY_STATUS, "Upstream IDrama manifest failed")
        val siteBase = siteBaseUrlProvider().trimEnd('/')

        val rewritten = manifestText.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> ""
                line.startsWith("#") -> {
                    IDRAMA_KEY_REGEX.replace(line) { match ->
                        "$siteBase/api/idrama-key?${match.groupValues[1]}"
                    }
                }
                else -> {
                    val absolute = resolveRelativeUrl(upstreamUrl, line)
                    if (isM3u8Url(absolute)) {
                        proxyIdramaUrl(absolute)
                    } else {
                        IDRAMA_KEY_REGEX.replace(absolute) { match ->
                            "$siteBase/api/idrama-key?${match.groupValues[1]}"
                        }
                    }
                }
            }
        }
        return textResponse(MIME_HLS, rewritten, isHead)
    }

    private fun handleShortTvPlaylist(upstreamUrl: String, isHead: Boolean): NanoHTTPD.Response {
        val wrappedManifestUrl = mediaProxyWrap(upstreamUrl)
        val fallbackManifestUrl = "$bunnyProxyBase/?url=${URLEncoder.encode(upstreamUrl, "UTF-8")}"
        val manifestText = fetchTextWithFallback(wrappedManifestUrl, fallbackManifestUrl)
            ?: return plainError(BAD_GATEWAY_STATUS, "Upstream ShortTv manifest failed")

        val rewritten = manifestText.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> ""
                line.startsWith("#") -> line
                else -> {
                    val absolute = resolveRelativeUrl(upstreamUrl, line)
                    when {
                        isM3u8Url(absolute) -> proxyShortTvUrl(absolute)
                        absolute.contains("hls-encrypted", ignoreCase = true) && TS_SEGMENT_REGEX.containsMatchIn(absolute) ->
                            buildLoopbackUrl("/shorttv.ts", absolute)
                        else -> mediaProxyWrap(absolute)
                    }
                }
            }
        }
        return textResponse(MIME_HLS, rewritten, isHead)
    }

    private fun handleShortTvSegment(upstreamUrl: String, isHead: Boolean): NanoHTTPD.Response {
        if (isHead) {
            return binaryResponse(MIME_MPEG_TS, ByteArray(0), isHead = true)
        }
        val wrappedSegmentUrl = mediaProxyWrap(upstreamUrl)
        val fallbackSegmentUrl = "$bunnyProxyBase/?url=${URLEncoder.encode(upstreamUrl, "UTF-8")}"
        val rawBytes = fetchBytesWithFallback(wrappedSegmentUrl, fallbackSegmentUrl)
            ?: return plainError(BAD_GATEWAY_STATUS, "Upstream ShortTv segment failed")

        val decrypted = decryptShortTvSegmentBytes(rawBytes, upstreamUrl)
        return binaryResponse(MIME_MPEG_TS, decrypted, isHead = false)
    }

    private fun handleDramaBoxMp4(
        upstreamUrl: String,
        rangeHeader: String?,
        isHead: Boolean,
    ): NanoHTTPD.Response {
        if (isHead) {
            return binaryResponse(MIME_MP4, ByteArray(0), isHead = true).apply {
                addHeader("Accept-Ranges", "bytes")
            }
        }

        val decrypted = getOrDecryptMp4(upstreamUrl)
            ?: return plainError(BAD_GATEWAY_STATUS, "Upstream DramaBox MP4 failed")

        val total = decrypted.size
        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true) && total > 0) {
            val spec = rangeHeader.substring(6).trim().substringBefore(',')
            val dashIdx = spec.indexOf('-')
            if (dashIdx >= 0) {
                val startPart = spec.substring(0, dashIdx).trim()
                val endPart = spec.substring(dashIdx + 1).trim()
                val start = startPart.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val end = endPart.toIntOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                if (start in 0 until total && end >= start) {
                    val sliceLen = end - start + 1
                    val stream = ByteArrayInputStream(decrypted, start, sliceLen)
                    return NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                        MIME_MP4,
                        stream,
                        sliceLen.toLong(),
                    ).apply {
                        addHeader("Accept-Ranges", "bytes")
                        addHeader("Content-Range", "bytes $start-$end/$total")
                        addHeader("Cache-Control", "no-store")
                    }
                }
            }
        }

        return binaryResponse(MIME_MP4, decrypted, isHead = false).apply {
            addHeader("Accept-Ranges", "bytes")
        }
    }

    private fun getOrDecryptMp4(upstreamUrl: String): ByteArray? {
        synchronized(mp4CacheLock) {
            decryptedMp4Cache[upstreamUrl]?.let { return it }
        }
        val primaryWrapped = "$cdnProxyBase/?url=${URLEncoder.encode(upstreamUrl, "UTF-8")}"
        val fallbackWrapped = "$bunnyProxyBase/?url=${URLEncoder.encode(upstreamUrl, "UTF-8")}"
        val rawBytes = fetchBytesWithFallback(primaryWrapped, fallbackWrapped) ?: return null
        val decrypted = DramaBoxMp4Decryptor.decryptMp4(rawBytes, upstreamUrl)
        if (decrypted.size <= MAX_CACHED_MP4_BYTES) {
            synchronized(mp4CacheLock) {
                decryptedMp4Cache[upstreamUrl] = decrypted
            }
        }
        return decrypted
    }

    private fun handleSubtitleVtt(
        upstreamUrl: String,
        formatHint: String?,
        isHead: Boolean,
    ): NanoHTTPD.Response {
        if (isHead) {
            return textResponse(MIME_VTT, "", isHead = true)
        }
        val host = runCatching { URI(upstreamUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        val preferWrapped = host == "mydramawave.com" ||
            host.endsWith(".mydramawave.com") ||
            host == "cdn1.hahip.com"
        val wrapped = mediaProxyWrap(upstreamUrl)
        val rawSub = if (preferWrapped) {
            fetchTextWithFallback(wrapped, upstreamUrl)
        } else {
            fetchTextWithFallback(upstreamUrl, wrapped)
        } ?: return plainError(BAD_GATEWAY_STATUS, "Upstream subtitle fetch failed")

        val normalized = normalizeSubtitleToVtt(rawSub, formatHint)
        return textResponse(MIME_VTT, normalized, isHead = false)
    }

    private fun normalizeSubtitleToVtt(rawText: String, formatHint: String?): String {
        val cleaned = rawText
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (cleaned.startsWith("WEBVTT", ignoreCase = true)) {
            return "$cleaned\n"
        }
        val isSrt = formatHint.equals("srt", ignoreCase = true) || SRT_ARROW_REGEX.containsMatchIn(cleaned)
        if (!isSrt) {
            return "WEBVTT\n\n$cleaned\n"
        }
        val converted = cleaned.lineSequence().joinToString("\n") { line ->
            if (line.contains("-->")) {
                SRT_TIMESTAMP_COMMA_REGEX.replace(line, "$1.$2")
            } else {
                line
            }
        }
        return "WEBVTT\n\n$converted\n"
    }

    private fun decryptShortTvSegmentBytes(bytes: ByteArray, segmentUrl: String): ByteArray {
        if (bytes.size < 1040) return bytes
        val headerAscii = bytes.copyOfRange(0, 1024).toString(Charsets.UTF_8)
        val keyOffset = bytes.copyOfRange(16, 20).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: return bytes
        val cipherLen = bytes.copyOfRange(20, 24).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: return bytes
        if (keyOffset < 0 || keyOffset + 16 > headerAscii.length || cipherLen <= 0) return bytes

        val isFull = segmentUrl.contains("hls-encrypted-full", ignoreCase = true)
        if (!isFull && 1024 + cipherLen > bytes.size) return bytes

        val keyBytes = headerAscii.substring(keyOffset, keyOffset + 16).toByteArray(Charsets.UTF_8)
        if (keyBytes.size != 16) return bytes

        val cipherSlice = if (isFull) {
            bytes.copyOfRange(1024, bytes.size)
        } else {
            bytes.copyOfRange(1024, 1024 + cipherLen)
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(SHORTMAX_IV_BYTES),
        )
        val plainPrefix = cipher.doFinal(cipherSlice)
        if (isFull || 1024 + cipherLen >= bytes.size) {
            return plainPrefix
        }
        val tailLen = bytes.size - (1024 + cipherLen)
        return ByteArray(plainPrefix.size + tailLen).also { out ->
            System.arraycopy(plainPrefix, 0, out, 0, plainPrefix.size)
            System.arraycopy(bytes, 1024 + cipherLen, out, plainPrefix.size, tailLen)
        }
    }

    private fun unwrapCdnProxyUrl(url: String): String = when {
        url.startsWith("$cdnProxyBase/https/") -> "https://${url.removePrefix("$cdnProxyBase/https/")}"
        url.startsWith("$cdnProxyBase/http/") -> "http://${url.removePrefix("$cdnProxyBase/http/")}"
        else -> url
    }

    private fun mediaProxyWrap(url: String): String {
        if (url.startsWith("$cdnProxyBase/") || url.startsWith("$bunnyProxyBase/?url=")) return url
        return when {
            url.startsWith("https://", ignoreCase = true) -> "$cdnProxyBase/https/${url.substring(8)}"
            url.startsWith("http://", ignoreCase = true) -> "$cdnProxyBase/http/${url.substring(7)}"
            else -> url
        }
    }

    private fun fetchBytesWithFallback(primaryUrl: String, fallbackUrl: String): ByteArray? {
        fetchBytes(primaryUrl)?.let { return it }
        if (fallbackUrl != primaryUrl) {
            return fetchBytes(fallbackUrl)
        }
        return null
    }

    private fun fetchBytes(url: String): ByteArray? {
        val request = buildUpstreamRequest(url)
        upstreamClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return null
            return res.body.bytes()
        }
    }

    private fun fetchTextWithFallback(primaryUrl: String, fallbackUrl: String): String? {
        fetchText(primaryUrl)?.let { return it }
        if (fallbackUrl != primaryUrl) {
            return fetchText(fallbackUrl)
        }
        return null
    }

    private fun fetchText(url: String): String? {
        val request = buildUpstreamRequest(url)
        upstreamClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return null
            return res.body.string()
        }
    }

    private fun buildUpstreamRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
        if (url.startsWith(cdnProxyBase)) {
            builder.header("Origin", "https://dramatip.net")
            builder.header("Referer", "https://dramatip.net/")
        } else {
            builder.header("Referer", "${siteBaseUrlProvider().trimEnd('/')}/")
        }
        return builder.build()
    }

    private fun isAllowedUpstreamUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        if (host.isEmpty()) return false

        if (host in normalizedAllowedHosts) {
            return scheme == "http" || scheme == "https"
        }
        if (scheme != "https") return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false

        if (IPV4_OR_IPV6_LITERAL_REGEX.matches(host)) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                return false
            }
        }
        return true
    }

    private fun resolveRelativeUrl(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true)) {
            return ref
        }
        return runCatching { URI(baseUrl).resolve(ref).toString() }.getOrDefault(ref)
    }

    private fun isM3u8Url(url: String): Boolean =
        url.substringBefore('#').substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in rawQuery.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = URLDecoder.decode(part.substring(0, eq), "UTF-8")
            val v = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            out[k] = v
        }
        return out
    }

    private fun textResponse(mimeType: String, content: String, isHead: Boolean): NanoHTTPD.Response {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val bodyStream = ByteArrayInputStream(if (isHead) ByteArray(0) else bytes)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            bodyStream,
            if (isHead) 0L else bytes.size.toLong(),
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun binaryResponse(mimeType: String, bytes: ByteArray, isHead: Boolean): NanoHTTPD.Response {
        val bodyStream = ByteArrayInputStream(if (isHead) ByteArray(0) else bytes)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            bodyStream,
            if (isHead) 0L else bytes.size.toLong(),
        ).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun plainError(status: NanoHTTPD.Response.IStatus, message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", message)

    private object BAD_GATEWAY_STATUS : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = 502
        override fun getDescription(): String = "502 Bad Gateway"
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val MIME_HLS = "application/vnd.apple.mpegurl"
        private const val MIME_MPEG_TS = "video/mp2t"
        private const val MIME_MP4 = "video/mp4"
        private const val MIME_VTT = "text/vtt; charset=utf-8"
        private const val MAX_CACHED_MP4_BYTES = 64 * 1024 * 1024
        private const val SOCKET_READ_TIMEOUT_MS = 30_000

        fun isEncryptedMp4Url(url: String): Boolean = DramaBoxMp4Decryptor.masterSecretForUrl(url) != null
        private val SHORTMAX_IV_BYTES = "shortmax00000000".toByteArray(Charsets.UTF_8)
        private val TS_SEGMENT_REGEX = Regex("""\.ts(?:\?|#|$)""", RegexOption.IGNORE_CASE)
        private val SRT_ARROW_REGEX = Regex("""\d{2}:\d{2}:\d{2},\d{3}\s*-->""")
        private val SRT_TIMESTAMP_COMMA_REGEX = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})""")

        private val GOODSHORT_ACFS_REGEX = Regex("""://acfs\d*\.goodreels\.com""", RegexOption.IGNORE_CASE)
        private val OFFLINE_KEY_URI_REGEX = Regex("""URI="[^"]*offline-key[^"]*"""")
        private val IDRAMA_KEY_REGEX = Regex(
            """https://api\.idrama\.video/shorts/open/drm/kds/api/v1/keys\?([^"\s]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val IPV4_OR_IPV6_LITERAL_REGEX = Regex("""^(?:\d{1,3}(?:\.\d{1,3}){3}|[0-9a-f:]+)$""", RegexOption.IGNORE_CASE)
    }
}
