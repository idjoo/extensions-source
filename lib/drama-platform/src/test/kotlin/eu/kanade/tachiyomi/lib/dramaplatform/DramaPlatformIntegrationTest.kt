package eu.kanade.tachiyomi.lib.dramaplatform

import eu.kanade.tachiyomi.lib.streamproxy.LoopbackStreamProxy
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class DramaPlatformIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var proxy: LoopbackStreamProxy

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        val base = server.url("").toString().trimEnd('/')
        proxy = LoopbackStreamProxy(
            httpClient = client,
            siteBaseUrlProvider = { base },
            cdnProxyBase = "$base/cdn-proxy",
            bunnyProxyBase = "$base/bunny-proxy",
            allowedInsecureHosts = setOf(server.hostName, "127.0.0.1", "localhost"),
        )
    }

    @After
    fun tearDown() {
        proxy.close()
        server.shutdown()
    }

    @Test
    fun `defines all 16 DramaTip Locales with distinct Aniyomi languages, prefixes, and source IDs`() {
        val locales = DramaPlatformLocales.ALL
        assertEquals(16, locales.size)
        assertEquals(16, locales.map { it.siteLocale }.toSet().size)
        assertEquals(16, locales.map { it.aniyomiLang }.toSet().size)

        val en = locales.first { it.siteLocale == "en" }
        assertEquals("en", en.aniyomiLang)
        assertEquals("", en.pathPrefix)

        val pt = locales.first { it.siteLocale == "pt" }
        assertEquals("pt-BR", pt.aniyomiLang)
        assertEquals("/pt", pt.pathPrefix)

        val generatedIds = locales.map { loc ->
            val key = "dramatip/${loc.aniyomiLang}/1"
            val md5 = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            (0..7).map { md5[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }.toSet()
        assertEquals(16, generatedIds.size)

        assertEquals(
            "https://dramatip.net/category/popular",
            DramaPlatformCatalogRoutes.popularUrl("https://dramatip.net", en, page = 1),
        )
        assertEquals(
            "https://dramatip.net/de/category/trending",
            DramaPlatformCatalogRoutes.popularUrl("https://dramatip.net", locales.first { it.siteLocale == "de" }, page = 1),
        )
        assertEquals(
            "https://dramatip.net/id/category/populer/page/2",
            DramaPlatformCatalogRoutes.popularUrl("https://dramatip.net", locales.first { it.siteLocale == "id" }, page = 2),
        )
        assertEquals(
            "https://dramatip.net/pt/category/newly-added",
            DramaPlatformCatalogRoutes.latestUrl("https://dramatip.net", pt, page = 1),
        )
        assertEquals(
            "https://dramatip.net/pt/series/page/3",
            DramaPlatformCatalogRoutes.allSeriesFallbackUrl("https://dramatip.net", pt, page = 3),
        )
        assertEquals(
            "https://dramatip.net/es/search?q=ceo+revenge&page=2",
            DramaPlatformCatalogRoutes.searchUrl(
                baseUrl = "https://dramatip.net",
                locale = locales.first { it.siteLocale == "es" },
                page = 2,
                query = "ceo revenge",
                categorySlug = null,
                upstreamProviderSlug = null,
            ),
        )
        assertEquals(
            "https://dramatip.net/es/source/vigloo/page/2",
            DramaPlatformCatalogRoutes.searchUrl(
                baseUrl = "https://dramatip.net",
                locale = locales.first { it.siteLocale == "es" },
                page = 2,
                query = "",
                categorySlug = null,
                upstreamProviderSlug = "vigloo",
            ),
        )
    }

    @Test
    fun `resolves Episode streams end-to-end with refresh=1 token recovery, 9x16 HLS parsing, and subtitle proxy`() {
        val baseUrl = server.url("").toString().trimEnd('/')
        val masterUrl = "$baseUrl/hls/ep1/master.m3u8"
        val subUrl = "$baseUrl/subs/ep1.srt"

        val cipherHelper = TestAesGcmEncryptor(DramaPlatformCipher.DEFAULT_KEY_BASE64)
        val staleMasterEnc = cipherHelper.encrypt("$baseUrl/hls/expired/master.m3u8")
        val freshMasterEnc = cipherHelper.encrypt(masterUrl)
        val freshSubEnc = cipherHelper.encrypt(subUrl)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/episode-source/bk_77/0?lang=en&raw=1" -> {
                        assertEquals("$baseUrl/series/test-drama/episode-1", request.getHeader("Referer"))
                        MockResponse().setResponseCode(200).setBody(
                            """{"descriptor":{"source":"Vigloo","chain":[{"enc":"$staleMasterEnc","type":"hls","source":"Vigloo"}]}}""",
                        )
                    }
                    path == "/hls/expired/master.m3u8" -> MockResponse().setResponseCode(403)
                    path == "/api/episode-source/bk_77/0?lang=en&raw=1&refresh=1" ->
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "descriptor": {
                                "source": "Vigloo",
                                "chain": [
                                  {"enc": "$freshMasterEnc", "type": "hls", "source": "Vigloo"}
                                ],
                                "subtitle": {
                                  "enc": "$freshSubEnc",
                                  "format": "srt",
                                  "language": "en"
                                }
                              }
                            }
                            """.trimIndent(),
                        )
                    path == "/hls/ep1/master.m3u8" ->
                        MockResponse().setResponseCode(200).setBody(
                            """
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1080x1920
                            1080p.m3u8
                            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=720x1280
                            720p.m3u8
                            """.trimIndent(),
                        )
                    path == "/subs/ep1.srt" ->
                        MockResponse().setResponseCode(200).setBody("1\n00:00:01,000 --> 00:00:02,500\nWelcome back\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val resolver = DramaPlatformStreamResolver(
            httpClient = client,
            cipher = DramaPlatformCipher(),
            loopbackProxy = proxy,
            userAgent = LoopbackStreamProxy.DEFAULT_USER_AGENT,
        )

        val videos = resolver.resolveEpisodeVideos(
            episodeUrl = "/series/test-drama/episode-1#bookId=bk_77&serial=0&raw=1",
            baseUrl = baseUrl,
            locale = DramaPlatformLocales.ALL.first { it.siteLocale == "en" },
            preferredQuality = "720p",
            fastHlsMode = false,
        )

        assertEquals(2, videos.size)
        assertEquals("Vigloo - 720p", videos[0].quality)
        assertEquals("$baseUrl/hls/ep1/720p.m3u8", videos[0].videoUrl)
        assertEquals("Vigloo - 1080p", videos[1].quality)
        assertEquals(1, videos[0].subtitleTracks.size)
        assertTrue(videos[0].subtitleTracks[0].url.contains("/sub.vtt?"))
    }

    @Test
    fun `omits cross-origin Referer on Catalog Source headers and refuses Locked Episode playback without API calls`() {
        val catalogHeaders = DramaPlatformCdnRouter.buildCatalogHeaders(LoopbackStreamProxy.DEFAULT_USER_AGENT).build()
        assertEquals(LoopbackStreamProxy.DEFAULT_USER_AGENT, catalogHeaders["User-Agent"])
        assertEquals(null, catalogHeaders["Referer"])

        val htmlWithLocked = """
            <html><body>
            <script>self.__next_f.push([1,"1:{\"bookId\":\"bk_locked\",\"firstSerial\":1,\"rawCatalog\":false,\"episodes\":[{\"serial_number\":1,\"is_locked\":false},{\"serial_number\":2,\"is_locked\":true}]}\n"])</script>
            </body></html>
        """.trimIndent()
        val shown = DramaPlatformParser.parseEpisodeList(htmlWithLocked, "/series/locked-test", showLockedEpisodes = true)
        val lockedEp = shown.first { it.episodeNumber == 2f }
        assertTrue(lockedEp.url.contains("locked=1"))

        val resolver = DramaPlatformStreamResolver(
            httpClient = client,
            cipher = DramaPlatformCipher(),
            loopbackProxy = proxy,
            userAgent = LoopbackStreamProxy.DEFAULT_USER_AGENT,
        )

        val baseUrl = server.url("").toString().trimEnd('/')
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            resolver.resolveEpisodeVideos(
                episodeUrl = lockedEp.url,
                baseUrl = baseUrl,
                locale = DramaPlatformLocales.ALL.first { it.siteLocale == "en" },
                preferredQuality = "1080p",
                fastHlsMode = false,
            )
        }
        assertTrue(error.message.orEmpty().contains("Locked Episode"))
        assertEquals(0, server.requestCount)

        val resolverBytecode = DramaPlatformStreamResolver::class.java
            .getResourceAsStream("DramaPlatformStreamResolver.class")!!
            .use { String(it.readBytes(), Charsets.ISO_8859_1) }
        org.junit.Assert.assertFalse(
            "Video is not a data class in Aniyomi extensions-lib 15; must not invoke copy\$default",
            resolverBytecode.contains("copy\$default"),
        )
    }

    private class TestAesGcmEncryptor(keyB64: String) {
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        private val keyBytes = kotlin.io.encoding.Base64.decode(keyB64)
        private var counter = 1

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun encrypt(plain: String): String {
            val iv = ByteArray(12) { idx -> (counter * 13 + idx).toByte() }
            counter++
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return kotlin.io.encoding.Base64.encode(iv + ct)
        }
    }
}
