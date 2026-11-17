package eu.kanade.tachiyomi.animeextension.en.dramatap

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DramaTapLiveSmokeTest {

    private val client = OkHttpClient()

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).header("User-Agent", DramaTap.USER_AGENT).build())
            .execute().use { res ->
                assertTrue("Expected 200 from $url, got ${res.code}", res.isSuccessful)
                res.body.string()
            }

    @Test
    fun `fetches live popular Series, details, Episodes and a playable stream from dramatap_app`() {
        assumeTrue("Pass -PliveSmoke=true to run", System.getProperty("liveSmoke") == "true")
        val base = DramaTap.BASE_URL

        val popular = DramaTapParser.parseListing(get("$base/"), null, 1).series
        assertFalse(popular.isEmpty())
        val latest = DramaTapParser.parseListing(get("$base/new"), "/new", 1)
        assertTrue(latest.series.isNotEmpty() && latest.hasNextPage)
        assertFalse(DramaTapParser.parseSearch(get("$base/api/search?q=love")).isEmpty())

        val series = popular.first()
        val details = DramaTapParser.parseSeriesDetails(get(base + series.path))
        assertFalse(details.title.isNullOrBlank())

        val episodes = DramaTapParser.parseEpisodes(get("$base/api/episodes?id=${series.id}")).episodes
        val free = episodes.first { !it.locked }
        val variants = VerticalHlsParser.parseMaster(get(free.streamUrl), free.streamUrl)
        assertFalse(variants.isEmpty())
        val media = get(variants.first().url)
        assertTrue(media.contains("#EXTINF"))
        println("Live: ${popular.size} popular, '${details.title}' ${episodes.size} eps, ${variants.map { it.qualityLabel }}")
    }
}
