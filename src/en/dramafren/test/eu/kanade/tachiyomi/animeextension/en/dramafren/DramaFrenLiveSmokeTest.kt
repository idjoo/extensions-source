package eu.kanade.tachiyomi.animeextension.en.dramafren

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DramaFrenLiveSmokeTest {

    private val client = OkHttpClient()
    private val ua = DramaFren.USER_AGENT
    private val base = DramaFren.BASE_URL

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use {
            assertTrue("GET $url -> ${it.code}", it.isSuccessful)
            it.body.string()
        }

    private fun ajax(vararg pairs: Pair<String, String>): String {
        val body = FormBody.Builder().apply { pairs.forEach { add(it.first, it.second) } }.build()
        val request = Request.Builder().url("$base/wp-admin/admin-ajax.php").post(body)
            .header("User-Agent", ua).header("Referer", "$base/").build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    @Test
    fun `fetches live catalog, details, Episodes and a playable stream`() {
        assumeTrue("Pass -PliveSmoke=true to run live smoke check", System.getProperty("liveSmoke") == "true")

        val catalog = DramaFrenParser.parseSeriesPage(get("$base/?msdb_catalog=1&msdb_page=1"), base)
        assertFalse(catalog.series.isEmpty())
        val book = catalog.series.first().bookId

        val details = DramaFrenParser.parseSeriesDetails(get(base + DramaFren.seriesPath(book)), base)
        assertTrue(details.title.isNotBlank())

        val episodes = DramaFrenParser.parsePlaylist(
            ajax("action" to "msdb_playlist", "book" to book, "chapter" to "", "lang" to "en"),
        ).filterNot { it.isLocked }
        assertFalse(episodes.isEmpty())

        val streams = DramaFrenParser.parseVideo(
            ajax("action" to "msdb_video", "book" to book, "chapter" to episodes.first().chapterId, "lang" to "en"),
        )
        assertFalse(streams.isEmpty())
        val probe = Request.Builder().url(streams.first().url).header("User-Agent", ua).header("Range", "bytes=0-1023").build()
        client.newCall(probe).execute().use {
            assertTrue("stream probe -> ${it.code}", it.code == 200 || it.code == 206)
            assertTrue(it.header("Content-Type").orEmpty().startsWith("video/"))
        }
    }
}
