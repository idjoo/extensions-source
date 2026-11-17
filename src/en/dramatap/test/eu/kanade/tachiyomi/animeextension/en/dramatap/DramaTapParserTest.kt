package eu.kanade.tachiyomi.animeextension.en.dramatap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DramaTapParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("dramatap/$name")!!.readText()

    @Test
    fun `parses home page Series cards without duplicates or continue-watching cards`() {
        val listing = DramaTapParser.parseListing(fixture("home.html"), pagePath = null, page = 1)
        assertTrue(listing.series.size >= 40)
        assertEquals(listing.series.size, listing.series.map { it.id }.toSet().size)
        val first = listing.series.first()
        assertEquals(2000247L, first.id)
        assertEquals("/d/2000247/the-mafia-boss-and-his-hacker-bride", first.path)
        assertEquals("The Mafia BOSS and His Hacker Bride", first.title)
        assertEquals("https://img.dramatap.app/extra/2000247.webp", first.coverUrl)
        assertFalse(listing.hasNextPage)
    }

    @Test
    fun `parses New Series pages with pagination`() {
        val page1 = DramaTapParser.parseListing(fixture("new.html"), "/new", page = 1)
        assertEquals(48, page1.series.size)
        assertEquals("Wrong Enemy, Right Love", page1.series.first().title)
        assertTrue(page1.hasNextPage)
        assertFalse(DramaTapParser.parseListing(fixture("new.html"), "/new", page = 6).hasNextPage)
    }

    @Test
    fun `parses Category pages`() {
        val listing = DramaTapParser.parseListing(fixture("tag.html"), "/tag/revenge", page = 1)
        assertTrue(listing.series.isNotEmpty())
        assertTrue(listing.hasNextPage)
    }

    @Test
    fun `parses Series details from JSON-LD and Category links`() {
        val details = DramaTapParser.parseSeriesDetails(fixture("series.html"))
        assertEquals("Wrong Enemy, Right Love", details.title)
        assertEquals(60, details.episodeCount)
        assertEquals(listOf("Dark Romance", "Plot"), details.categories)
        assertTrue(details.synopsis!!.startsWith("Ten years ago"))
        assertTrue(details.synopsis!!.contains("families' safety"))
        assertTrue(details.coverUrl!!.startsWith("https://img.dramatap.app/"))
    }

    @Test
    fun `parses search results`() {
        val cards = DramaTapParser.parseSearch(fixture("search.json"))
        assertEquals(24, cards.size)
        assertEquals(SeriesCard(2552, "just-love-you", "Just Love You", cards[0].coverUrl), cards[0])
    }

    @Test
    fun `free Series has no Locked Episodes`() {
        val list = DramaTapParser.parseEpisodes(fixture("episodes.json"))
        assertNull(list.freeEpisodes)
        assertEquals(52, list.episodes.size)
        assertEquals((1..52).toList(), list.episodes.map { it.number })
        assertTrue(list.episodes.none { it.locked })
        assertTrue(list.episodes[0].streamUrl.endsWith(".m3u8"))
    }

    @Test
    fun `VIP Series locks every Episode after free_eps`() {
        val list = DramaTapParser.parseEpisodes(fixture("episodes_vip.json"))
        assertEquals(15, list.freeEpisodes)
        assertEquals(56, list.episodes.size)
        assertTrue(list.episodes.filter { it.number <= 15 }.none { it.locked })
        assertTrue(list.episodes.filter { it.number > 15 }.all { it.locked })
    }

    @Test
    fun `resolves relative stream URLs and handles missing Series`() {
        val list = DramaTapParser.parseEpisodes("""{"items":[{"ep":2,"url":"/v/b.m3u8"},{"ep":1,"url":"/v/a.m3u8"},{"ep":3}]}""")
        assertEquals(listOf(1, 2), list.episodes.map { it.number })
        assertEquals("https://chartdrama.com/v/a.m3u8", list.episodes[0].streamUrl)
        assertTrue(DramaTapParser.parseEpisodes("""{"notfound":1}""").episodes.isEmpty())
    }

    @Test
    fun `reads ids from Series and Episode URLs`() {
        assertEquals(149175L, DramaTapParser.parseSeriesId("/d/149175/wrong-enemy-right-love"))
        assertEquals(149175L, DramaTapParser.parseSeriesId("https://dramatap.app/d/149175#ep=3"))
        assertEquals(3, DramaTapParser.parseEpisodeNumber("/d/149175#ep=3"))
        assertNull(DramaTapParser.parseEpisodeNumber("/d/149175"))
    }
}
