package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerticalHlsParserTest {

    @Test
    fun `parses 9x16 portrait HLS master manifest short-edge resolutions, audio groups, and locale-prioritized subtitles`() {
        val masterManifest = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-main",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en/prog.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-main",NAME="Bahasa Indonesia",LANGUAGE="id-ID",DEFAULT=NO,URI="audio/id/prog.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Indonesian",LANGUAGE="id-ID",URI="subs/id.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Spanish",LANGUAGE="es",URI="subs/es.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1080x1920,AUDIO="aud-main",SUBTITLES="subs"
            video/1080/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=720x1280,AUDIO="aud-main",SUBTITLES="subs"
            video/720/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=540x960,AUDIO="aud-main",SUBTITLES="subs"
            video/540/prog.m3u8
        """.trimIndent()

        val headers = Headers.headersOf("User-Agent", "Mozilla/5.0")
        val externalSub = ExternalSubtitleTrack(
            url = "http://127.0.0.1:45123/sub.vtt?t=tok&u=enc",
            label = "Indonesian (SRT)",
            languageCode = "id",
        )

        val videos = VerticalHlsParser.parseMasterPlaylist(
            manifestText = masterManifest,
            manifestUrl = "https://cdn.vigloo.example/dramas/ep1/master.m3u8",
            providerLabel = "Vigloo",
            activeLocale = "id",
            headers = headers,
            externalSubtitles = listOf(externalSub),
        )

        assertEquals(3, videos.size)
        assertEquals("Vigloo - 1080p", videos[0].quality)
        assertEquals("https://cdn.vigloo.example/dramas/ep1/video/1080/prog.m3u8", videos[0].videoUrl)
        assertEquals("Vigloo - 720p", videos[1].quality)
        assertEquals("https://cdn.vigloo.example/dramas/ep1/video/720/prog.m3u8", videos[1].videoUrl)
        assertEquals("Vigloo - 540p", videos[2].quality)
        assertEquals("https://cdn.vigloo.example/dramas/ep1/video/540/prog.m3u8", videos[2].videoUrl)

        val audioTracks = videos[0].audioTracks
        assertEquals(2, audioTracks.size)
        assertEquals("Bahasa Indonesia", audioTracks[0].lang)
        assertEquals("https://cdn.vigloo.example/dramas/ep1/audio/id/prog.m3u8", audioTracks[0].url)
        assertEquals("English", audioTracks[1].lang)

        val subtitleTracks = videos[0].subtitleTracks
        assertEquals(4, subtitleTracks.size)
        assertEquals("Indonesian (SRT)", subtitleTracks[0].lang)
        assertEquals("Indonesian", subtitleTracks[1].lang)
        assertEquals("https://cdn.vigloo.example/dramas/ep1/subs/id.m3u8", subtitleTracks[1].url)
        assertEquals("English", subtitleTracks[2].lang)
        assertEquals("Spanish", subtitleTracks[3].lang)
    }

    @Test
    fun `applies Upstream Provider CDN wrapping, host-specific headers, and Stream Chain preferred quality ordering`() {
        val ua = "Mozilla/5.0 (Linux; Android 14)"

        val dramawaveUrl = DramaTipCdnRouter.resolveDirectStreamUrl(
            DecryptedStreamCandidate(0, "DramaWave", "dramawave", "https://media.mydramawave.com/v1/ep1.m3u8", null),
        )
        assertEquals("https://cdn.dramadizilerim.xyz/https/media.mydramawave.com/v1/ep1.m3u8", dramawaveUrl)
        assertEquals(
            "https://hls-proxy-i4ic7.b-cdn.net/?url=https%3A%2F%2Fmedia.mydramawave.com%2Fv1%2Fep1.m3u8",
            DramaTipCdnRouter.bunnyFallbackUrl(dramawaveUrl),
        )

        val flickReelsWrapped = DramaTipCdnRouter.resolveDirectStreamUrl(
            DecryptedStreamCandidate(0, "FlickReels", "hls", "https://akm.flickreels.net/play/1.m3u8", null),
        )
        assertEquals("https://cdn.dramadizilerim.xyz/https/akm.flickreels.net/play/1.m3u8", flickReelsWrapped)

        val flickReelsDirect = DramaTipCdnRouter.resolveDirectStreamUrl(
            DecryptedStreamCandidate(0, "FlickReels", "hls", "https://pl.shortdizilab.com/play/1.m3u8", null),
        )
        assertEquals("https://pl.shortdizilab.com/play/1.m3u8", flickReelsDirect)

        val happyShortBunny = DramaTipCdnRouter.resolveDirectStreamUrl(
            DecryptedStreamCandidate(0, "HappyShort", "hls", "https://vod.tqshort.com/hls/ep1.m3u8", null),
        )
        assertEquals("https://hls-proxy-i4ic7.b-cdn.net/?url=https%3A%2F%2Fvod.tqshort.com%2Fhls%2Fep1.m3u8", happyShortBunny)

        val legacyUnwrapped = DramaTipCdnRouter.resolveDirectStreamUrl(
            DecryptedStreamCandidate(
                0,
                "Legacy",
                "hls",
                "https://dramadizilerim.com/ts-proxy.php?url=https%3A%2F%2Forigin.example.com%2Fep1.m3u8",
                null,
            ),
        )
        assertEquals("https://hls-proxy-i4ic7.b-cdn.net/?url=https%3A%2F%2Forigin.example.com%2Fep1.m3u8", legacyUnwrapped)

        val cdnHeaders = DramaTipCdnRouter.buildStreamHeaders(dramawaveUrl, "https://dramatip.net", ua)
        assertEquals("https://dramatip.net", cdnHeaders["Origin"])
        assertEquals("https://dramatip.net/", cdnHeaders["Referer"])

        val reelInTvHeaders = DramaTipCdnRouter.buildStreamHeaders("https://video.reelintv.com/stream/1.mp4", "https://dramatip.net", ua)
        assertNull(reelInTvHeaders["Referer"])
        assertNull(reelInTvHeaders["Origin"])
        assertEquals(ua, reelInTvHeaders["User-Agent"])

        val petadramaHeaders = DramaTipCdnRouter.buildStreamHeaders("https://cfcdn.petadrama.com/stream/1.m3u8", "https://dramatip.net", ua)
        assertNull(petadramaHeaders["Referer"])
        assertNull(petadramaHeaders["Origin"])

        val ordered = DramaTipCdnRouter.sortStreamChainVideos(
            chainGroups = listOf(
                1 to listOf(
                    Video("https://mirror/1080.mp4", "DramaBox - 1080p", "https://mirror/1080.mp4"),
                    Video("https://mirror/720.mp4", "DramaBox - 720p", "https://mirror/720.mp4"),
                ),
                0 to listOf(
                    Video("https://primary/1080.m3u8", "Vigloo - 1080p", "https://primary/1080.m3u8"),
                    Video("https://primary/720.m3u8", "Vigloo - 720p", "https://primary/720.m3u8"),
                    Video("https://primary/540.m3u8", "Vigloo - 540p", "https://primary/540.m3u8"),
                ),
            ),
            preferredQuality = "720p",
        )

        assertEquals(
            listOf(
                "Vigloo - 720p",
                "Vigloo - 1080p",
                "Vigloo - 540p",
                "DramaBox - 720p",
                "DramaBox - 1080p",
            ),
            ordered.map { it.quality },
        )
    }
}
