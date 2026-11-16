package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamproxy.LoopbackStreamProxy
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

class DramaTipStreamResolver(
    private val httpClient: OkHttpClient,
    private val cipher: DramaTipCipher,
    private val loopbackProxy: LoopbackStreamProxy,
    private val userAgent: String,
) {

    fun resolveEpisodeVideos(
        episodeUrl: String,
        baseUrl: String,
        locale: DramaTipLocale,
        preferredQuality: String,
        fastHlsMode: Boolean,
    ): List<Video> {
        val cleanBase = baseUrl.trimEnd('/')
        val urlRef = DramaTipParser.parseEpisodeUrl(episodeUrl)
        val normalizedPath = if (urlRef.episodePath.startsWith("/")) {
            urlRef.episodePath
        } else {
            "/${urlRef.episodePath}"
        }
        val refererPageUrl = "$cleanBase$normalizedPath"

        val playbackTarget = urlRef.playbackTarget ?: run {
            val pageHtml = fetchString(refererPageUrl, referer = "$cleanBase/") ?: return emptyList()
            DramaTipParser.recoverPlaybackTarget(pageHtml, urlRef.displayNumber)
        } ?: return emptyList()

        if (playbackTarget.isLocked) {
            throw IllegalStateException("This is a Locked Episode on DramaTip and is unavailable for free playback.")
        }

        val initialVideos = fetchAndResolveDescriptor(
            target = playbackTarget,
            cleanBase = cleanBase,
            refererPageUrl = refererPageUrl,
            locale = locale,
            preferredQuality = preferredQuality,
            fastHlsMode = fastHlsMode,
            refresh = false,
        )
        if (initialVideos.isNotEmpty()) {
            return initialVideos
        }

        return fetchAndResolveDescriptor(
            target = playbackTarget,
            cleanBase = cleanBase,
            refererPageUrl = refererPageUrl,
            locale = locale,
            preferredQuality = preferredQuality,
            fastHlsMode = fastHlsMode,
            refresh = true,
        )
    }

    private fun fetchAndResolveDescriptor(
        target: PlaybackTarget,
        cleanBase: String,
        refererPageUrl: String,
        locale: DramaTipLocale,
        preferredQuality: String,
        fastHlsMode: Boolean,
        refresh: Boolean,
    ): List<Video> {
        val encodedBookId = URLEncoder.encode(target.bookId, "UTF-8")
        val encodedLang = URLEncoder.encode(locale.siteLocale, "UTF-8")
        val query = buildString {
            append("lang=").append(encodedLang)
            if (target.rawCatalog) append("&raw=1")
            if (refresh) append("&refresh=1")
        }
        val apiUrl = "$cleanBase/api/episode-source/$encodedBookId/${target.rawSerial}?$query"
        val apiJson = fetchString(apiUrl, referer = refererPageUrl) ?: return emptyList()

        val decrypted = cipher.decryptStreamDescriptor(apiJson) {
            lazyClientChunksSequence(refererPageUrl, cleanBase)
        }
        if (decrypted.streamChain.isEmpty()) return emptyList()

        val externalSubtitles = decrypted.subtitle?.let { sub ->
            val proxiedSubUrl = loopbackProxy.proxySubtitleUrl(sub.url, sub.format)
            val langCode = sub.language?.takeIf(String::isNotBlank) ?: locale.siteLocale
            listOf(
                ExternalSubtitleTrack(
                    url = proxiedSubUrl,
                    label = "$langCode (Subtitle)",
                    languageCode = langCode,
                ),
            )
        }.orEmpty()
        val externalTracks = externalSubtitles.map { Track(it.url, it.label) }

        val verifiedGroups = mutableListOf<Pair<Int, List<Video>>>()
        val unverifiedFallbackGroups = mutableListOf<Pair<Int, List<Video>>>()

        for (candidate in decrypted.streamChain) {
            val providerLabel = candidate.upstreamProvider?.takeIf(String::isNotBlank) ?: "DramaTip"
            val streamType = candidate.streamType.lowercase()

            when {
                streamType == "goodshort" -> {
                    val proxied = loopbackProxy.proxyGoodShortUrl(candidate.url)
                    val resolved = resolveLoopbackHls(
                        proxiedUrl = proxied,
                        cleanBase = cleanBase,
                        providerLabel = providerLabel,
                        activeLocale = locale.siteLocale,
                        externalSubtitles = externalSubtitles,
                        fastHlsMode = fastHlsMode,
                    )
                    if (resolved.isNotEmpty()) verifiedGroups.add(candidate.chainIndex to resolved)
                }

                candidate.upstreamProvider.equals("IDrama", ignoreCase = true) && !isMp4Stream(streamType, candidate.url) -> {
                    val proxied = loopbackProxy.proxyIdramaUrl(candidate.url)
                    val resolved = resolveLoopbackHls(
                        proxiedUrl = proxied,
                        cleanBase = cleanBase,
                        providerLabel = providerLabel,
                        activeLocale = locale.siteLocale,
                        externalSubtitles = externalSubtitles,
                        fastHlsMode = fastHlsMode,
                    )
                    if (resolved.isNotEmpty()) verifiedGroups.add(candidate.chainIndex to resolved)
                }

                streamType == "hls-enc" -> {
                    val proxied = loopbackProxy.proxyShortTvUrl(candidate.url)
                    val resolved = resolveLoopbackHls(
                        proxiedUrl = proxied,
                        cleanBase = cleanBase,
                        providerLabel = providerLabel,
                        activeLocale = locale.siteLocale,
                        externalSubtitles = externalSubtitles,
                        fastHlsMode = fastHlsMode,
                    )
                    if (resolved.isNotEmpty()) verifiedGroups.add(candidate.chainIndex to resolved)
                }

                isMp4Stream(streamType, candidate.url) && LoopbackStreamProxy.isEncryptedMp4Url(candidate.url) -> {
                    val proxied = loopbackProxy.proxyDramaBoxMp4Url(candidate.url)
                    val qLabel = "$providerLabel - ${candidate.qualityLabel ?: "Auto"}"
                    verifiedGroups.add(
                        candidate.chainIndex to listOf(
                            Video(proxied, qLabel, proxied, null, externalTracks, emptyList()),
                        ),
                    )
                }

                isMp4Stream(streamType, candidate.url) -> {
                    val directUrl = DramaTipCdnRouter.resolveDirectStreamUrl(candidate)
                    val headers = DramaTipCdnRouter.buildStreamHeaders(directUrl, cleanBase, userAgent)
                    val qLabel = "$providerLabel - ${candidate.qualityLabel ?: "Auto"}"
                    verifiedGroups.add(
                        candidate.chainIndex to listOf(
                            Video(directUrl, qLabel, directUrl, headers, externalTracks, emptyList()),
                        ),
                    )
                }

                else -> {
                    val directUrl = DramaTipCdnRouter.resolveDirectStreamUrl(candidate)
                    val primaryHeaders = DramaTipCdnRouter.buildStreamHeaders(directUrl, cleanBase, userAgent)
                    val qLabel = "$providerLabel - ${candidate.qualityLabel ?: "Auto"}"

                    if (fastHlsMode) {
                        verifiedGroups.add(
                            candidate.chainIndex to listOf(
                                Video(directUrl, qLabel, directUrl, primaryHeaders, externalTracks, emptyList()),
                            ),
                        )
                    } else {
                        val fetched = fetchHlsWithBunnyFallback(directUrl, primaryHeaders, cleanBase)
                        if (fetched != null) {
                            val (effectiveUrl, manifestText, effectiveHeaders) = fetched
                            val parsed = VerticalHlsParser.parseMasterPlaylist(
                                manifestText = manifestText,
                                manifestUrl = effectiveUrl,
                                providerLabel = providerLabel,
                                activeLocale = locale.siteLocale,
                                headers = effectiveHeaders,
                                externalSubtitles = externalSubtitles,
                            ).map { v ->
                                val variantTarget = v.videoUrl ?: v.url
                                val variantHeaders = DramaTipCdnRouter.buildStreamHeaders(variantTarget, cleanBase, userAgent)
                                Video(v.url, v.quality, v.videoUrl, variantHeaders, v.subtitleTracks, v.audioTracks)
                            }
                            verifiedGroups.add(candidate.chainIndex to parsed)
                        } else if (refresh) {
                            unverifiedFallbackGroups.add(
                                candidate.chainIndex to listOf(
                                    Video(directUrl, qLabel, directUrl, primaryHeaders, externalTracks, emptyList()),
                                ),
                            )
                        }
                    }
                }
            }
        }

        val chosenGroups = verifiedGroups.ifEmpty { unverifiedFallbackGroups }
        return DramaTipCdnRouter.sortStreamChainVideos(chosenGroups, preferredQuality)
    }

    private fun resolveLoopbackHls(
        proxiedUrl: String,
        cleanBase: String,
        providerLabel: String,
        activeLocale: String,
        externalSubtitles: List<ExternalSubtitleTrack>,
        fastHlsMode: Boolean,
    ): List<Video> {
        val externalTracks = externalSubtitles.map { Track(it.url, it.label) }
        val loopbackSegmentHeaders = Headers.headersOf(
            "User-Agent",
            userAgent,
            "Origin",
            "https://dramatip.net",
            "Referer",
            "$cleanBase/",
        )
        if (fastHlsMode) {
            return listOf(Video(proxiedUrl, "$providerLabel - Auto", proxiedUrl, loopbackSegmentHeaders, externalTracks, emptyList()))
        }
        val manifestText = fetchString(proxiedUrl, referer = null) ?: return emptyList()
        return VerticalHlsParser.parseMasterPlaylist(
            manifestText = manifestText,
            manifestUrl = proxiedUrl,
            providerLabel = providerLabel,
            activeLocale = activeLocale,
            headers = loopbackSegmentHeaders,
            externalSubtitles = externalSubtitles,
        )
    }

    private fun fetchHlsWithBunnyFallback(
        primaryUrl: String,
        primaryHeaders: Headers,
        cleanBase: String,
    ): Triple<String, String, Headers>? {
        fetchStringWithHeaders(primaryUrl, primaryHeaders)?.let { body ->
            return Triple(primaryUrl, body, primaryHeaders)
        }
        val fallbackUrl = DramaTipCdnRouter.bunnyFallbackUrl(primaryUrl) ?: return null
        val fallbackHeaders = DramaTipCdnRouter.buildStreamHeaders(fallbackUrl, cleanBase, userAgent)
        val fallbackBody = fetchStringWithHeaders(fallbackUrl, fallbackHeaders) ?: return null
        return Triple(fallbackUrl, fallbackBody, fallbackHeaders)
    }

    private fun lazyClientChunksSequence(pageUrl: String, cleanBase: String): Sequence<String> = sequence {
        val html = fetchString(pageUrl, referer = "$cleanBase/") ?: return@sequence
        val doc = Jsoup.parse(html, cleanBase)
        val scriptUrls = doc.select("script[src*='/_next/static/chunks/']")
            .mapNotNull { el -> el.absUrl("src").takeIf(String::isNotBlank) }
            .distinct()
        for (scriptUrl in scriptUrls) {
            val js = fetchString(scriptUrl, referer = pageUrl)
            if (!js.isNullOrBlank()) {
                yield(js)
            }
        }
    }

    private fun fetchString(url: String, referer: String?): String? {
        val headersBuilder = Headers.Builder().add("User-Agent", userAgent)
        if (!referer.isNullOrBlank()) {
            headersBuilder.add("Referer", referer)
        }
        return fetchStringWithHeaders(url, headersBuilder.build())
    }

    private fun fetchStringWithHeaders(url: String, headers: Headers): String? = runCatching {
        val req = Request.Builder().url(url).headers(headers).build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null else res.body.string()
        }
    }.getOrNull()

    private fun isMp4Stream(streamType: String, url: String): Boolean {
        if (streamType == "mp4" || streamType == "dramapops-mp4") return true
        if (streamType == "hls" || streamType == "hls-enc" || streamType == "goodshort") return false
        return url.substringBefore('#').substringBefore('?').endsWith(".mp4", ignoreCase = true)
    }
}
