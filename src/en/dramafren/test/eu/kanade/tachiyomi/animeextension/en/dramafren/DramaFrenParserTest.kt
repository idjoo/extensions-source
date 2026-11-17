package eu.kanade.tachiyomi.animeextension.en.dramafren

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DramaFrenParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private val base = DramaFren.BASE_URL

    @Test
    fun `parses Series cards and next page from catalog`() {
        val page = DramaFrenParser.parseSeriesPage(fixture("catalog.html"), base)
        assertEquals(24, page.series.size)
        assertTrue(page.hasNextPage)
        val first = page.series.first()
        assertEquals("42000033258", first.bookId)
        assertEquals("Eight-Year-Old Demon Hunter", first.title)
        assertEquals(60, first.episodeCount)
        assertTrue(first.coverUrl!!.startsWith("https://thwztchapter.dramaboxdb.com/"))
    }

    @Test
    fun `parses search results without pagination`() {
        val page = DramaFrenParser.parseSeriesPage(fixture("search.html"), base)
        assertFalse(page.series.isEmpty())
        assertEquals("Hide-and-Seek with the CEO", page.series.first().title)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `parses Series details`() {
        val details = DramaFrenParser.parseSeriesDetails(fixture("book.html"), base)
        assertEquals("Eight-Year-Old Demon Hunter", details.title)
        assertEquals("DRAMABOX", details.upstreamProvider)
        assertTrue("Rebirth" in details.categories)
        assertEquals(6, details.categories.size)
        assertTrue(details.synopsis!!.startsWith("Legendary hunter Lincoln"))
        assertTrue(details.coverUrl!!.contains("42000033258"))
    }

    @Test
    fun `parses playlist and flags Episodes without sources as Locked`() {
        val episodes = DramaFrenParser.parsePlaylist(fixture("playlist.json"))
        assertEquals(4, episodes.size)
        assertEquals("701897713", episodes[0].chapterId)
        assertEquals("EP 1", episodes[0].name)
        assertEquals(listOf("1080", "720", "540"), episodes[0].streams.map { it.quality })
        assertFalse(episodes[0].isLocked)
        assertTrue(episodes[3].isLocked)
    }

    @Test
    fun `parses video sources best first and drops non https`() {
        val streams = DramaFrenParser.parseVideo(fixture("video.json"))
        assertEquals("1080", streams.first().quality)
        assertTrue(streams.all { it.url.startsWith("https://") && it.url.endsWith(".mp4") })

        val onlyAuto = DramaFrenParser.parseVideo(
            """{"success":true,"data":{"sources":{"auto":"https://a.example/x.mp4","720":"http://insecure/x.mp4"}}}""",
        )
        assertEquals(listOf("auto"), onlyAuto.map { it.quality })
    }

    @Test
    fun `surfaces site error message on failed ajax`() {
        val error = assertThrows(IllegalStateException::class.java) {
            DramaFrenParser.parseVideo("""{"success":false,"data":{"message":"Episode not found"}}""")
        }
        assertEquals("Episode not found", error.message)
    }
}
