package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.lib.streamproxy.LoopbackStreamProxy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DramaTipLiveSmokeTest {

    @Test
    fun `fetches live Popular Series, Episode list, and playable Stream Chain from dramatip_net`() {
        assumeTrue(
            "Pass -PliveSmoke=true to run live smoke check against dramatip.net",
            System.getProperty("liveSmoke") == "true",
        )

        val client = OkHttpClient()
        val ua = LoopbackStreamProxy.DEFAULT_USER_AGENT
        val baseUrl = DramaTip.DEFAULT_BASE_URL
        val enLocale = DramaTipLocales.ALL.first { it.siteLocale == "en" }

        val popularHtml = client.newCall(
            Request.Builder()
                .url(DramaTipCatalogRoutes.popularUrl(baseUrl, enLocale, page = 1))
                .header("User-Agent", ua)
                .build(),
        ).execute().use { res ->
            assertTrue("Expected 200 on popular page, got ${res.code}", res.isSuccessful)
            res.body.string()
        }

        val seriesPage = DramaTipParser.parseSeriesPage(popularHtml, currentPage = 1, baseUrl = baseUrl)
        assertFalse("Expected non-empty Popular Series list", seriesPage.series.isEmpty())

        val firstSeries = seriesPage.series.first()
        val seriesHtml = client.newCall(
            Request.Builder()
                .url("$baseUrl${firstSeries.url}")
                .header("User-Agent", ua)
                .build(),
        ).execute().use { res ->
            assertTrue("Expected 200 on series page, got ${res.code}", res.isSuccessful)
            res.body.string()
        }

        val details = DramaTipParser.parseSeriesDetails(seriesHtml, firstSeries.url, baseUrl)
        assertTrue("Expected Series title", details.title.isNotBlank())

        val episodes = DramaTipParser.parseEpisodeList(seriesHtml, firstSeries.url, showLockedEpisodes = false, baseUrl = baseUrl)
        assertFalse("Expected unlocked Episodes on popular Series", episodes.isEmpty())

        val episode1 = episodes.last()
        LoopbackStreamProxy(httpClient = client, userAgent = ua).use { proxy ->
            val resolver = DramaTipStreamResolver(
                httpClient = client,
                cipher = DramaTipCipher(),
                loopbackProxy = proxy,
                userAgent = ua,
            )
            val videos = resolver.resolveEpisodeVideos(
                episodeUrl = episode1.url,
                baseUrl = baseUrl,
                locale = enLocale,
                preferredQuality = "1080p",
                fastHlsMode = false,
            )
            assertFalse("Expected non-empty resolved Video list for ${episode1.url}", videos.isEmpty())
        }
    }
}
