package eu.kanade.tachiyomi.animeextension.all.dramaexpress

import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformCatalogRoutes
import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformCdnRouter
import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformLocales
import eu.kanade.tachiyomi.lib.dramaplatform.DramaPlatformParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/** Parses HTML recorded from dramaexpress.net on 2026-11-17. */
class DramaExpressParserTest {

    private val baseUrl = DramaExpress.DEFAULT_BASE_URL

    private fun fixture(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: error("Missing fixture $name")
        return GZIPInputStream(stream).bufferedReader().use { it.readText() }
    }

    @Test
    fun `parses the Popular Category listing into Series`() {
        val page = DramaPlatformParser.parseSeriesPage(fixture("category-popular.html.gz"), currentPage = 1, baseUrl = baseUrl)

        assertTrue("Expected a full shelf, got ${page.series.size}", page.series.size >= 20)
        assertEquals("/series/while-my-wifes-away", page.series.first().url)
        assertEquals(page.series.size, page.series.map { it.url }.toSet().size)
        page.series.forEach { series ->
            assertTrue(series.url.startsWith("/series/"))
            assertTrue(series.title.isNotBlank())
            assertTrue("Thumbnail should be absolute: ${series.thumbnailUrl}", series.thumbnailUrl.orEmpty().startsWith("https://"))
        }
    }

    @Test
    fun `parses Series details and free Episodes with bookId fragments`() {
        val html = fixture("series-while-my-wifes-away.html.gz")
        val path = "/series/while-my-wifes-away"

        val details = DramaPlatformParser.parseSeriesDetails(html, path, baseUrl)
        assertTrue(details.title.isNotBlank())
        assertTrue(details.description.isNotBlank())
        assertEquals("Vigloo", details.upstreamProvider)
        assertNotNull(details.thumbnailUrl)

        val episodes = DramaPlatformParser.parseEpisodeList(html, path, showLockedEpisodes = false, baseUrl = baseUrl)
        assertEquals(50, episodes.size)
        assertEquals(50, DramaPlatformParser.parseEpisodeList(html, path, showLockedEpisodes = true, baseUrl = baseUrl).size)
        assertEquals((1..50).map(Int::toFloat).toSet(), episodes.map { it.episodeNumber }.toSet())

        val first = episodes.first { it.episodeNumber == 1f }
        assertTrue(first.url, first.url.startsWith("$path/"))
        val ref = DramaPlatformParser.parseEpisodeUrl(first.url)
        val target = ref.playbackTarget ?: DramaPlatformParser.recoverPlaybackTarget(html, ref.displayNumber)
        assertNotNull(target)
        assertEquals("15003872", target!!.bookId)
        assertFalse(target.isLocked)
    }

    @Test
    fun `builds DramaExpress catalog routes and sends its own origin to the shared CDN`() {
        val id = DramaPlatformLocales.ALL.first { it.siteLocale == "id" }
        assertEquals(
            "https://dramaexpress.net/id/category/populer/page/2",
            DramaPlatformCatalogRoutes.popularUrl(baseUrl, id, page = 2),
        )

        val headers = DramaPlatformCdnRouter.buildStreamHeaders(
            targetUrl = "https://cdn.dramadizilerim.xyz/https/video-v6.mydramawave.com/vt/x.m3u8",
            baseUrl = baseUrl,
            userAgent = "UA",
            cdnOrigin = baseUrl,
        )
        assertEquals("https://dramaexpress.net", headers["Origin"])
        assertEquals("https://dramaexpress.net/", headers["Referer"])

        val noReferer = DramaPlatformCdnRouter.buildStreamHeaders("https://video.reelintv.com/a.mp4", baseUrl, "UA", baseUrl)
        assertNull(noReferer["Referer"])
        assertNull(noReferer["Origin"])
    }
}
