package eu.kanade.tachiyomi.lib.streamproxy

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class LoopbackStreamProxyTest {

    private lateinit var upstream: MockWebServer
    private lateinit var client: OkHttpClient
    private val proxiesToClose = mutableListOf<LoopbackStreamProxy>()

    @Before
    fun setUp() {
        upstream = MockWebServer()
        upstream.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        proxiesToClose.forEach { it.close() }
        proxiesToClose.clear()
        upstream.shutdown()
    }

    private fun newProxy(): LoopbackStreamProxy {
        val base = upstream.url("").toString().trimEnd('/')
        return LoopbackStreamProxy(
            httpClient = client,
            siteBaseUrlProvider = { "https://dramatip.net" },
            cdnProxyBase = "$base/cdn-proxy",
            bunnyProxyBase = "$base/bunny-proxy",
            allowedInsecureHosts = setOf(upstream.hostName, "127.0.0.1", "localhost"),
        ).also(proxiesToClose::add)
    }

    @Test
    fun `enforces 127_0_0_1 binding, session token, and SSRF allowlist while rewriting GoodShort and IDrama HLS manifests`() {
        val recordedPaths = mutableListOf<String>()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(recordedPaths) { recordedPaths.add(path) }
                return when {
                    path.startsWith("/cdn-proxy/?url=https%3A%2F%2Fv2-akm.goodreels.com%2Fplay%2Fep1%2Fmaster.m3u8") ->
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                #EXTM3U
                                #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=720x1280
                                720p/index.m3u8
                                """.trimIndent(),
                            )

                    path.startsWith("/cdn-proxy/?url=https%3A%2F%2Fv2-akm.goodreels.com%2Fplay%2Fep1%2F720p%2Findex.m3u8") ->
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                #EXTM3U
                                #EXT-X-VERSION:3
                                #EXT-X-KEY:METHOD=AES-128,URI="https://acfs2.goodreels.com/offline-key?id=42",IV=0x00000000000000000000000000000001
                                #EXTINF:4.000,
                                https://dramadizilerim.com/ts-proxy.php?url=https%3A%2F%2Fv2-akm.goodreels.com%2Fplay%2Fep1%2F720p%2Fseg0.ts
                                #EXTINF:4.000,
                                seg1.ts
                                #EXT-X-ENDLIST
                                """.trimIndent(),
                            )

                    path == "/idrama/ep1/index.m3u8" ->
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                #EXTM3U
                                #EXT-X-KEY:METHOD=AES-128,URI="https://api.idrama.video/shorts/open/drm/kds/api/v1/keys?source=idrama&ak=tok99"
                                #EXTINF:5.000,
                                seg0.ts
                                #EXT-X-ENDLIST
                                """.trimIndent(),
                            )

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val proxy = newProxy()
        val goodShortProxyUrl = proxy.proxyGoodShortUrl("https://acfs2.goodreels.com/play/ep1/master.m3u8")
        val parsedUri = URI(goodShortProxyUrl)
        assertEquals("127.0.0.1", parsedUri.host)
        assertTrue(parsedUri.port > 0)

        val missingTokenUrl = "http://127.0.0.1:${parsedUri.port}/goodshort.m3u8?u=https%3A%2F%2Facfs2.goodreels.com%2Fplay%2Fep1%2Fmaster.m3u8"
        client.newCall(Request.Builder().url(missingTokenUrl).build()).execute().use { res ->
            assertEquals(403, res.code)
        }

        val tokenParam = parsedUri.rawQuery.split('&').first { it.startsWith("t=") }
        val ssrfAttemptUrl = "http://127.0.0.1:${parsedUri.port}/goodshort.m3u8?$tokenParam&u=http%3A%2F%2F169.254.169.254%2Flatest%2Fmeta-data"
        client.newCall(Request.Builder().url(ssrfAttemptUrl).build()).execute().use { res ->
            assertEquals(403, res.code)
        }

        client.newCall(Request.Builder().url(goodShortProxyUrl).head().build()).execute().use { headRes ->
            assertEquals(200, headRes.code)
            assertTrue(headRes.header("Content-Type").orEmpty().contains("application/vnd.apple.mpegurl"))
        }

        val masterBody = client.newCall(Request.Builder().url(goodShortProxyUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            res.body.string()
        }
        val nestedVariantUrl = masterBody.lineSequence().first { it.startsWith("http://127.0.0.1:") }

        val mediaBody = client.newCall(Request.Builder().url(nestedVariantUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            res.body.string()
        }
        assertTrue(mediaBody.contains("""URI="https://dramatip.net/api/goodshort-key""""))
        assertTrue(mediaBody.contains("/cdn-proxy/?url=https%3A%2F%2Fv2-akm.goodreels.com%2Fplay%2Fep1%2F720p%2Fseg0.ts"))
        assertTrue(mediaBody.contains("/cdn-proxy/?url=https%3A%2F%2Fv2-akm.goodreels.com%2Fplay%2Fep1%2F720p%2Fseg1.ts"))

        val idramaUpstreamUrl = upstream.url("/idrama/ep1/index.m3u8").toString()
        val idramaProxyUrl = proxy.proxyIdramaUrl(idramaUpstreamUrl)
        val idramaBody = client.newCall(Request.Builder().url(idramaProxyUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            res.body.string()
        }
        assertTrue(idramaBody.contains("""URI="https://dramatip.net/api/idrama-key?source=idrama&ak=tok99""""))
    }

    @Test
    fun `rewrites ShortTv hls-enc playlists and decrypts 1024-byte header AES-CBC MPEG-TS segments`() {
        val header1024 = (
            "0".repeat(16) +
                "  40" +
                "  32" +
                "0".repeat(16) +
                "K9mP2xQ7vL4nR8wZ" +
                "0".repeat(968)
            ).toByteArray(Charsets.US_ASCII)
        assertEquals(1024, header1024.size)

        val partialCipherAndTail = hexToBytes(
            "73067c93153ed2b7f08e6f22ab06cf80e53edac158a666bb759d363583b4829affffffffffffffff",
        )
        val fullCipherOnly = hexToBytes(
            "73067c93153ed2b7f08e6f22ab06cf806a5a2f2bdd52ca5600158527d7e9559b",
        )
        val partialSegmentBytes = header1024 + partialCipherAndTail
        val fullSegmentBytes = header1024 + fullCipherOnly
        val expectedTsHex = "474000100000b00d0001c100000001f0002ab104b2ffffffffffffffff"

        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/cdn-proxy/https/shortmax.example/hls-encrypted/ep1.m3u8" ->
                        MockResponse().setResponseCode(200).setBody(
                            """
                            #EXTM3U
                            #EXT-X-VERSION:3
                            #EXTINF:3.0,
                            seg0.ts
                            #EXTINF:3.0,
                            https://shortmax.example/hls-encrypted-full/seg1.ts?sig=abc
                            #EXT-X-ENDLIST
                            """.trimIndent(),
                        )

                    path == "/cdn-proxy/https/shortmax.example/hls-encrypted/seg0.ts" ->
                        MockResponse().setResponseCode(200).setBody(Buffer().write(partialSegmentBytes))

                    path == "/cdn-proxy/https/shortmax.example/hls-encrypted-full/seg1.ts?sig=abc" ->
                        MockResponse().setResponseCode(200).setBody(Buffer().write(fullSegmentBytes))

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val proxy = newProxy()
        val m3u8Url = proxy.proxyShortTvUrl("https://shortmax.example/hls-encrypted/ep1.m3u8")
        val m3u8Text = client.newCall(Request.Builder().url(m3u8Url).build()).execute().use { res ->
            assertEquals(200, res.code)
            res.body.string()
        }

        val segmentUrls = m3u8Text.lineSequence().filter { it.contains("/shorttv.ts?") }.toList()
        assertEquals(2, segmentUrls.size)

        for (segUrl in segmentUrls) {
            val decryptedHex = client.newCall(Request.Builder().url(segUrl).build()).execute().use { res ->
                assertEquals(200, res.code)
                assertEquals("video/mp2t", res.header("Content-Type"))
                res.body.bytes().joinToString("") { "%02x".format(it) }
            }
            assertEquals(expectedTsHex, decryptedHex)
        }
    }

    @Test
    fun `decrypts DramaBox encrypted MP4 moov PrivateKeyUri and stbl samples with HTTP 206 Range caching`() {
        val encryptedMp4 = Base64.decode(
            "AAAAFGZ0eXBpc29tMDAwMDAyMDAAAAGhbW9vdgAAAS11ZHRhAAABJW1ldGEAAAAAAAAAOWtleXMA" +
                "AAAAAAAAAQAAACltZHRhY29tLmFwcGxlLnF1aWNrdGltZS5Qcml2YXRlS2V5VXJpAAAA4Glsc3QA" +
                "AADYAAAAAQAAANBkYXRhAAAAAQAAAAAycTZqM2UvYnVsbUxEY0F4eUcveXRaK3pyK0tGUTBVVlJs" +
                "akhwSW0xZ01WMzVSSGNRa1BUa1p4ODBaR3NoeVIzeWtSbFhERWFBdk1YN1FvN3FQNHdhWC9NMzQ4" +
                "VnhiTDJtblZhWG12SnRwQXRTWDhtVUw3SXBNSC9vYkxnQ25SRm8zSDAvcnJ4U0JHTzhJYlNhdDZB" +
                "ZUtCNWFTa05EQ0xWRGNGbDFaakRLM1dzTVl3YTNEa29uWmh1Rk1BSTRtMEEAAABsdHJhawAAAGRt" +
                "ZGlhAAAAXG1pbmYAAABUc3RibAAAABxzdHN6AAAAAAAAAAAAAAACAAAAJQAAABQAAAAUc3RjbwAA" +
                "AAAAAAABAAABvQAAABxzdHNjAAAAAAAAAAEAAAABAAAAAgAAAAEAAABBbWRhdL9lKb5tdVOr57Tg" +
                "SMZbMTUx/dE6WHPAnfdsXBmUOiLR3q2+70J3VKHX2tiV2eHJSombl0BKyv66vg==",
        )
        val expectedPlainMp4 = Base64.decode(
            "AAAAFGZ0eXBpc29tMDAwMDAyMDAAAAGhbW9vdgAAAS11ZHRhAAABJW1ldGEAAAAAAAAAOWtleXMA" +
                "AAAAAAAAAQAAACltZHRhY29tLmFwcGxlLnF1aWNrdGltZS5Qcml2YXRlS2V5VXJpAAAA4Glsc3QA" +
                "AADYAAAAAQAAANBkYXRhAAAAAQAAAAAycTZqM2UvYnVsbUxEY0F4eUcveXRaK3pyK0tGUTBVVlJs" +
                "akhwSW0xZ01WMzVSSGNRa1BUa1p4ODBaR3NoeVIzeWtSbFhERWFBdk1YN1FvN3FQNHdhWC9NMzQ4" +
                "VnhiTDJtblZhWG12SnRwQXRTWDhtVUw3SXBNSC9vYkxnQ25SRm8zSDAvcnJ4U0JHTzhJYlNhdDZB" +
                "ZUtCNWFTa05EQ0xWRGNGbDFaakRLM1dzTVl3YTNEa29uWmh1Rk1BSTRtMEEAAABsdHJhawAAAGRt" +
                "ZGlhAAAAXG1pbmYAAABUc3RibAAAABxzdHN6AAAAAAAAAAAAAAACAAAAJQAAABQAAAAUc3RjbwAA" +
                "AAAAAAABAAABvQAAABxzdHNjAAAAAAAAAAEAAAABAAAAAgAAAAEAAABBbWRhdAECAwQFBgcICQoL" +
                "DA0ODxAREhMUFRYXGBkaGxwdHh8g3q2+70KhoqOkpaanqKmqq6ytrq+wyv66vg==",
        )

        var upstreamHits = 0
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/cdn-proxy/?url=https%3A%2F%2Fvod.dramaboxdb.com%2Fep1.encrypt.mp4")) {
                    upstreamHits++
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(encryptedMp4))
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val proxy = newProxy()
        assertTrue(LoopbackStreamProxy.isEncryptedMp4Url("https://vod.dramaboxdb.com/ep1.encrypt.mp4"))
        assertTrue(LoopbackStreamProxy.isEncryptedMp4Url("https://cdn.storeel.app/v/1.mp4?etavirp_nuyila=1"))
        assertFalse(LoopbackStreamProxy.isEncryptedMp4Url("https://vod.dramaboxdb.com/ep1.mp4"))

        val dbxUrl = proxy.proxyDramaBoxMp4Url("https://vod.dramaboxdb.com/ep1.encrypt.mp4")

        val fullDecrypted = client.newCall(Request.Builder().url(dbxUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            assertEquals("video/mp4", res.header("Content-Type"))
            assertEquals("bytes", res.header("Accept-Ranges"))
            res.body.bytes()
        }
        assertArrayEquals(expectedPlainMp4, fullDecrypted)

        val rangeReq = Request.Builder()
            .url(dbxUrl)
            .header("Range", "bytes=445-481")
            .build()
        val sample1Slice = client.newCall(rangeReq).execute().use { res ->
            assertEquals(206, res.code)
            assertEquals("bytes 445-481/${expectedPlainMp4.size}", res.header("Content-Range"))
            res.body.bytes()
        }
        assertArrayEquals(expectedPlainMp4.copyOfRange(445, 482), sample1Slice)
        assertEquals(1, upstreamHits)
    }

    @Test
    fun `normalizes UTF-8 BOM SRT subtitles to WebVTT and wraps mydramawave subtitle hosts`() {
        val rawSrt = "\uFEFF1\r\n00:00:01,250 --> 00:00:03,900\r\nHello, world!\r\n\r\n2\r\n00:00:04,010 --> 00:00:06,500\r\n Episode 1, scene 2\r\n"

        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path == "/cdn-proxy/https/sub.mydramawave.com/subs/ep1.srt") {
                    MockResponse().setResponseCode(200).setBody(rawSrt)
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }

        val proxy = newProxy()
        val subUrl = proxy.proxySubtitleUrl("https://sub.mydramawave.com/subs/ep1.srt", format = "srt")
        val vtt = client.newCall(Request.Builder().url(subUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            assertTrue(res.header("Content-Type").orEmpty().startsWith("text/vtt"))
            res.body.string()
        }

        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.250 --> 00:00:03.900"))
        assertTrue(vtt.contains("00:00:04.010 --> 00:00:06.500"))
        assertTrue(vtt.contains("Hello, world!"))
        assertTrue(vtt.contains("Episode 1, scene 2"))
    }

    @Test
    fun `does not deadlock when caller OkHttpClient has a synchronized interceptor and avoids double-wrapping GoodShort CDN segments`() {
        val base = upstream.url("").toString().trimEnd('/')
        val cdnBase = "$base/cdn-proxy"
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.startsWith("/cdn-proxy/?url=https%3A%2F%2Fgoodshort.goodbos.online%2Fhls%2F73704661")) {
                    MockResponse().setResponseCode(200).setBody(
                        """
                        #EXTM3U
                        #EXTINF:5.0,
                        $cdnBase/https/v3.goodshort.com/mts/books/seg_000.ts
                        #EXT-X-ENDLIST
                        """.trimIndent(),
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }

        val syncInterceptor = object : okhttp3.Interceptor {
            @Synchronized
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response =
                chain.proceed(chain.request())
        }
        val callerClient = client.newBuilder()
            .addInterceptor(syncInterceptor)
            .callTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val proxy = LoopbackStreamProxy(
            httpClient = callerClient,
            siteBaseUrlProvider = { "https://dramatip.net" },
            cdnProxyBase = cdnBase,
            bunnyProxyBase = "$base/bunny-proxy",
            allowedInsecureHosts = setOf(upstream.hostName, "127.0.0.1", "localhost"),
        ).also(proxiesToClose::add)

        val proxiedUrl = proxy.proxyGoodShortUrl("https://goodshort.goodbos.online/hls/73704661?bookId=1&q=720p")
        val body = callerClient.newCall(Request.Builder().url(proxiedUrl).build()).execute().use { res ->
            assertEquals(200, res.code)
            res.body.string()
        }
        assertTrue(body.contains("$cdnBase/https/v3.goodshort.com/mts/books/seg_000.ts"))
        assertFalse(body.contains("?url=http"))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { idx -> hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
}
