package eu.kanade.tachiyomi.animeextension.en.dramatap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAndCapTest {

    @Test
    fun `labels vertical HLS variants by short edge and binds demuxed audio`() {
        val text = javaClass.classLoader!!.getResource("dramatap/master.m3u8")!!.readText()
        val base = "https://video-v1.mydramawave.com/vt/abc/h264-xyz.m3u8"
        assertTrue(VerticalHlsParser.isMasterPlaylist(text))
        val variants = VerticalHlsParser.parseMaster(text, base)
        assertEquals(listOf("1080p", "720p", "540p", "480p", "360p", "240p"), variants.map { it.qualityLabel })
        assertTrue(variants[0].url.startsWith("https://video-v1.mydramawave.com/vt/abc/"))
        assertEquals(1, variants[0].audioUrls.size)
        assertEquals("en-US", variants[0].audioUrls[0].second)
        assertTrue(variants[0].audioUrls[0].first.endsWith(".aac.m3u8"))
    }

    @Test
    fun `media playlist yields no variants`() {
        assertTrue(VerticalHlsParser.parseMaster("#EXTM3U\n#EXTINF:5,\nseg0.ts\n", "https://x.test/a.m3u8").isEmpty())
    }

    private class MemoryStore : CapStore {
        var value: String? = null
        override fun load() = value
        override fun save(value: String) { this.value = value }
    }

    @Test
    fun `daily cap locks new Episodes after the limit but allows rewatching`() {
        var now = 1_000L
        var day = "2026-11-17"
        val cap = DailyEpisodeCap(MemoryStore(), { now }, { day }, limit = 3, lockMillis = 100)
        assertTrue(cap.tryConsume(1, 1))
        assertTrue(cap.tryConsume(1, 1))
        assertTrue(cap.tryConsume(1, 2))
        assertEquals(0L, cap.lockedForMillis())
        assertTrue(cap.tryConsume(2, 1))
        assertEquals(100L, cap.lockedForMillis())
        assertFalse(cap.tryConsume(2, 2))
        assertTrue(cap.tryConsume(1, 2))
        day = "2026-11-18"
        assertFalse("lock outlives day change", cap.tryConsume(2, 2))
        now += 100
        assertTrue(cap.tryConsume(2, 2))
    }

    @Test
    fun `daily counter resets on a new day when not locked`() {
        var day = "d1"
        val cap = DailyEpisodeCap(MemoryStore(), { 0L }, { day }, limit = 2, lockMillis = 100)
        assertTrue(cap.tryConsume(1, 1))
        day = "d2"
        assertTrue(cap.tryConsume(1, 2))
        assertEquals(0L, cap.lockedForMillis())
    }
}
