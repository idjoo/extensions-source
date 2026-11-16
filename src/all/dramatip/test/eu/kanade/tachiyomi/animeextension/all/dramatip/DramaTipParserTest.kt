package eu.kanade.tachiyomi.animeextension.all.dramatip

import eu.kanade.tachiyomi.animesource.model.SAnime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DramaTipParserTest {

    @Test
    fun `parses Series cards and pagination from Category and Search HTML`() {
        val categoryPageHtml = """
            <!DOCTYPE html>
            <html><body>
              <header><a href="/series">Browse</a></header>
              <div class="grid">
                <a class="relative block aspect-[2/3]" href="/series/while-my-wifes-away">
                  <img alt="While My Wife&#x27;s Away" src="https://asset.vigloo.com/US000PC6S01/en.png"/>
                  <h2>While My Wife&#x27;s Away</h2>
                </a>
                <a class="relative block aspect-[2/3]" href="/series/my-mafia-husband-lost-control-updating">
                  <img alt="My Mafia Husband Lost Control updating" src="https://cdn.example.com/mafia.jpg"/>
                  <h2>My Mafia Husband Lost Control updating</h2>
                </a>
              </div>
              <nav aria-label="Pagination">
                <a href="/category/popular/page/2">2</a>
                <a href="/category/popular/page/84">84</a>
              </nav>
            </body></html>
        """.trimIndent()

        val page1 = DramaTipParser.parseSeriesPage(categoryPageHtml, currentPage = 1)
        assertEquals(2, page1.series.size)
        assertEquals("/series/while-my-wifes-away", page1.series[0].url)
        assertEquals("While My Wife's Away", page1.series[0].title)
        assertEquals("https://asset.vigloo.com/US000PC6S01/en.png", page1.series[0].thumbnailUrl)
        assertEquals("/series/my-mafia-husband-lost-control-updating", page1.series[1].url)
        assertEquals("My Mafia Husband Lost Control", page1.series[1].title)
        assertTrue(page1.hasNextPage)

        val lastPage = DramaTipParser.parseSeriesPage(categoryPageHtml, currentPage = 84)
        assertFalse(lastPage.hasNextPage)

        val searchHtml = """
            <html><body>
              <a href="/id/series/cinta-rahasia">
                <img alt="Cinta Rahasia" src="/posters/cinta.webp"/>
                <h2>Cinta Rahasia</h2>
              </a>
              <a href="/id/search?q=cinta&amp;page=2">Next</a>
            </body></html>
        """.trimIndent()

        val searchPage = DramaTipParser.parseSeriesPage(
            html = searchHtml,
            currentPage = 1,
            baseUrl = "https://dramatip.net",
        )
        assertEquals(1, searchPage.series.size)
        assertEquals("/id/series/cinta-rahasia", searchPage.series[0].url)
        assertEquals("https://dramatip.net/posters/cinta.webp", searchPage.series[0].thumbnailUrl)
        assertTrue(searchPage.hasNextPage)
    }

    @Test
    fun `parses Series details from JSON-LD, body badges, and ongoing slug heuristic`() {
        val completedHtml = """
            <!DOCTYPE html>
            <html><head>
              <script type="application/ld+json">
                {"@context":"https://schema.org","@graph":[{"@type":"TVSeries","name":"While My Wife's Away","description":"A tense family drama.","image":"https://asset.vigloo.com/poster.png","numberOfEpisodes":50,"genre":["Romance","Family Drama"]}]}
              </script>
            </head><body>
              <header><a href="/source/aura">AuraReel</a></header>
              <h1>While My Wife's Away</h1>
              <div class="mb-4 mt-2 flex flex-wrap items-center gap-2 text-sm">
                <span class="inline-flex items-center gap-1 font-bold"><svg class="text-rating"></svg>9.3</span>
                <span>50 Episodes</span>
                <a href="/source/vigloo">Vigloo</a>
              </div>
              <footer><a href="/source/dramabox">DramaBox</a></footer>
            </body></html>
        """.trimIndent()

        val completed = DramaTipParser.parseSeriesDetails(completedHtml, "/series/while-my-wifes-away")
        assertEquals("While My Wife's Away", completed.title)
        assertEquals("Vigloo", completed.upstreamProvider)
        assertEquals("https://asset.vigloo.com/poster.png", completed.thumbnailUrl)
        assertEquals(listOf("Romance", "Family Drama"), completed.categories)
        assertEquals(SAnime.COMPLETED, completed.status)
        assertTrue(completed.description.contains("A tense family drama."))
        assertTrue(completed.description.contains("9.3"))
        assertTrue(completed.description.contains("50"))

        val updatingHtml = """
            <!DOCTYPE html>
            <html><head>
              <script type="application/ld+json">
                {"@type":"TVSeries","name":"My Mafia Husband Lost Control updating","description":"High stakes.","image":"https://cdn.example.com/m.jpg","numberOfEpisodes":130,"genre":"Revenge"}
              </script>
            </head><body>
              <h1>My Mafia Husband Lost Control updating</h1>
              <script>self.__next_f.push([1,"3d:[\"$\",\"div\",null,{\"bookId\":\"my-mafia-husband-lost-control-updating\",\"sourceFirst\":{\"source\":\"JoyReels\"}}]\n"])</script>
            </body></html>
        """.trimIndent()

        val ongoing = DramaTipParser.parseSeriesDetails(
            updatingHtml,
            "/series/my-mafia-husband-lost-control-updating",
        )
        assertEquals("My Mafia Husband Lost Control", ongoing.title)
        assertEquals("JoyReels", ongoing.upstreamProvider)
        assertEquals(listOf("Revenge"), ongoing.categories)
        assertEquals(SAnime.ONGOING, ongoing.status)
    }

    @Test
    fun `parses Episode list with zero-based serials, Locked Episode filtering, fragment metadata, and DOM fallback`() {
        val rscHtml = """
            <!DOCTYPE html>
            <html><body>
              <h1>Secret CEO</h1>
              <div><a href="/pt/source/goodshort">GoodShort</a></div>
              <script>self.__next_f.push([1,"3d:[\"$\",\"div\",null,{\"bookId\":\"gs-book/99\",\"firstSerial\":0,\"rawCatalog\":true,\"sourceFirst\":{\"source\":\"GoodShort\"}}]\n"])</script>
              <script>self.__next_f.push([1,"48:[\"$\",\"section\",null,{\"bookId\":\"gs-book/99\",\"firstSerial\":0,\"episodes\":[{\"chapter_id\":\"c0\",\"serial_number\":0,\"is_locked\":false,\"duration\":62},{\"chapter_id\":\"c1\",\"serial_number\":1,\"is_locked\":false,\"duration\":58},{\"chapter_id\":\"c2\",\"serial_number\":2,\"is_locked\":true,\"duration\":60}]}]\n"])</script>
            </body></html>
        """.trimIndent()

        val unlockedOnly = DramaTipParser.parseEpisodeList(
            html = rscHtml,
            seriesPath = "/pt/series/secret-ceo",
            showLockedEpisodes = false,
        )
        assertEquals(2, unlockedOnly.size)
        assertEquals("Episode 2", unlockedOnly[0].name)
        assertEquals(2f, unlockedOnly[0].episodeNumber)
        assertEquals("GoodShort", unlockedOnly[0].upstreamProvider)
        assertEquals(
            "/pt/series/secret-ceo/episode-2#bookId=gs-book%2F99&serial=1&raw=1",
            unlockedOnly[0].url,
        )
        assertEquals("Episode 1", unlockedOnly[1].name)
        assertEquals(
            "/pt/series/secret-ceo/episode-1#bookId=gs-book%2F99&serial=0&raw=1",
            unlockedOnly[1].url,
        )

        val withLocked = DramaTipParser.parseEpisodeList(
            html = rscHtml,
            seriesPath = "/pt/series/secret-ceo",
            showLockedEpisodes = true,
        )
        assertEquals(3, withLocked.size)
        assertEquals("[Locked] Episode 3", withLocked[0].name)
        assertEquals(3f, withLocked[0].episodeNumber)

        val parsedRef = DramaTipParser.parseEpisodeUrl(unlockedOnly[1].url)
        assertEquals("/pt/series/secret-ceo/episode-1", parsedRef.episodePath)
        assertEquals(1, parsedRef.displayNumber)
        assertNotNull(parsedRef.playbackTarget)
        assertEquals("gs-book/99", parsedRef.playbackTarget?.bookId)
        assertEquals(0, parsedRef.playbackTarget?.rawSerial)
        assertEquals(true, parsedRef.playbackTarget?.rawCatalog)

        val legacyRef = DramaTipParser.parseEpisodeUrl("/pt/series/secret-ceo/episode-2")
        assertNull(legacyRef.playbackTarget)
        val recovered = DramaTipParser.recoverPlaybackTarget(rscHtml, displayNumber = 2)
        assertNotNull(recovered)
        assertEquals("gs-book/99", recovered?.bookId)
        assertEquals(1, recovered?.rawSerial)
        assertEquals(true, recovered?.rawCatalog)

        val domOnlyHtml = """
            <html><head>
              <script type="application/ld+json">
                {"@type":"TVSeries","name":"Fallback Drama","numberOfEpisodes":3}
              </script>
            </head><body><h1>Fallback Drama</h1></body></html>
        """.trimIndent()
        val fallbackEpisodes = DramaTipParser.parseEpisodeList(
            html = domOnlyHtml,
            seriesPath = "/series/fallback-drama",
            showLockedEpisodes = false,
        )
        assertEquals(listOf(3f, 2f, 1f), fallbackEpisodes.map { it.episodeNumber })
        assertEquals("/series/fallback-drama/episode-3", fallbackEpisodes[0].url)
    }

    @Test
    fun `parses localized Category and Upstream Provider filter lists and direct slug or URL queries`() {
        val categoriesHtml = """
            <html><body>
              <a href="/id/category/populer">
                <span class="text-sm font-semibold text-foreground">Populer</span>
                <span class="mt-3 text-xs tabular-nums text-muted">2.000 judul</span>
              </a>
              <a href="/id/category/revenge">
                <span class="text-sm font-semibold text-foreground">Balas Dendam &amp; Karma</span>
                <span class="mt-3 text-xs tabular-nums text-muted">1.200 judul</span>
              </a>
              <footer><a href="/id/category/populer">Populer</a></footer>
            </body></html>
        """.trimIndent()

        val categories = DramaTipParser.parseCategories(categoriesHtml)
        assertEquals(
            listOf(
                FilterOption(name = "Populer", slug = "populer"),
                FilterOption(name = "Balas Dendam & Karma", slug = "revenge"),
            ),
            categories,
        )

        val providersHtml = """
            <html><body>
              <a href="/source/flareshort">
                <span aria-hidden="true">F</span>
                <span class="text-sm font-semibold text-foreground">Flareshort</span>
                <span class="text-xs tabular-nums text-muted">210 titles</span>
              </a>
              <a href="/source/goodshort">
                <img src="/source-icons/goodshort.webp" alt="GoodShort"/>
                <span class="text-sm font-semibold text-foreground">GoodShort</span>
                <span class="text-xs tabular-nums text-muted">3,433 titles</span>
              </a>
            </body></html>
        """.trimIndent()

        val providers = DramaTipParser.parseUpstreamProviders(providersHtml)
        assertEquals(
            listOf(
                FilterOption(name = "Flareshort", slug = "flareshort"),
                FilterOption(name = "GoodShort", slug = "goodshort"),
            ),
            providers,
        )

        assertEquals(
            "/pt/series/while-my-wifes-away",
            DramaTipParser.resolveDirectSeriesPath("slug:while-my-wifes-away", localePrefix = "/pt"),
        )
        assertEquals(
            "/id/series/cinta-rahasia",
            DramaTipParser.resolveDirectSeriesPath(
                "https://dramatip.net/id/series/cinta-rahasia/episode-4#bookId=1",
                localePrefix = "",
            ),
        )
        assertNull(DramaTipParser.resolveDirectSeriesPath("secret billionaire", localePrefix = ""))
    }

    @Test
    fun `parses oversized Next Flight RSC chunks without regex stack overflow`() {
        val longPadding = "a".repeat(150_000)
        val payload = "2f:{\"bookId\":\"bk_big\",\"firstSerial\":1,\"rawCatalog\":false,\"pad\":\"$longPadding\",\"episodes\":[{\"serial_number\":1,\"is_locked\":false}]}\n"
        val escapedPayload = payload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val html = """<html><body><script>self.__next_f.push([1,"$escapedPayload"])</script></body></html>"""

        val episodes = DramaTipParser.parseEpisodeList(html, "/series/big-drama", showLockedEpisodes = false)
        assertEquals(1, episodes.size)
        assertEquals("/series/big-drama/episode-1#bookId=bk_big&serial=1&raw=0", episodes[0].url)
    }
}
