package eu.kanade.tachiyomi.animeextension.all.dramatip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DramaTipCipherTest {

    @Test
    fun `decrypts Stream Descriptor chain, quality map, top-level fallback, and subtitle while skipping corrupted ciphertext`() {
        val v1Hls = "AAECAwQFBgcICQoLEFGlklBikDwPdB1ZI4JyoQ6fqM+OUcIrx4FJbyepLqiqayRvev6vtqCPMS0PAxODRc0sUdYjVlR+u6BDIyI="
        val v2Mp41080 = "EBESExQVFhcYGRobyjeUXJZxz/Q8S3KIzN3rvrlbexlPbx/hweWWITa+AKuP9ZGJR/r90W/PvelJcOjxeROrQDu/s0TuSZSB+qY="
        val v2Mp4720 = "ICEiIyQlJicoKSor1h7htNwkNhiT0ty524IGnTEskctRLx/mWTX4I1FAHo+7LE0C4uUXxl6DvjKkd4HidA5MwMa6plnsgW3+BQ=="
        val v3Sub = "MDEyMzQ1Njc4OTo7i/0iBUQsnm96gpTIC+EfWcGxzKRxYeHK1KFJrsh9lcEWjgiPf06JTCWBuOrkmc02iyQ5+lIwCQ=="

        val apiJson = """
            {
              "descriptor": {
                "source": "Vigloo",
                "chain": [
                  {
                    "enc": "$v1Hls",
                    "type": "hls",
                    "source": "Vigloo"
                  },
                  {
                    "enc": "$v2Mp4720",
                    "encByQuality": {
                      "1080": "$v2Mp41080",
                      "720": "$v2Mp4720"
                    },
                    "type": "mp4",
                    "source": "DramaBox"
                  },
                  {
                    "enc": "AAAAbadciphertextthatfailsgcmtagcheck====",
                    "type": "mp4",
                    "source": "BrokenMirror"
                  }
                ],
                "subtitle": {
                  "enc": "$v3Sub",
                  "format": "srt",
                  "language": "en-US"
                }
              }
            }
        """.trimIndent()

        val cipher = DramaTipCipher()
        val result = cipher.decryptStreamDescriptor(apiJson)

        assertTrue(result.hasEncryptedPayload)
        assertEquals(3, result.streamChain.size)

        assertEquals(
            DecryptedStreamCandidate(
                chainIndex = 0,
                upstreamProvider = "Vigloo",
                streamType = "hls",
                url = "https://cdn.example.com/vigloo/ep1/master.m3u8",
                qualityLabel = null,
            ),
            result.streamChain[0],
        )
        assertEquals(
            DecryptedStreamCandidate(
                chainIndex = 1,
                upstreamProvider = "DramaBox",
                streamType = "mp4",
                url = "https://cdn.example.com/dramabox/ep1-1080p.mp4",
                qualityLabel = "1080p",
            ),
            result.streamChain[1],
        )
        assertEquals(
            DecryptedStreamCandidate(
                chainIndex = 1,
                upstreamProvider = "DramaBox",
                streamType = "mp4",
                url = "https://cdn.example.com/dramabox/ep1-720p.mp4",
                qualityLabel = "720p",
            ),
            result.streamChain[2],
        )

        assertNotNull(result.subtitle)
        assertEquals("https://cdn.example.com/subs/ep1.en.srt", result.subtitle?.url)
        assertEquals("srt", result.subtitle?.format)
        assertEquals("en-US", result.subtitle?.language)

        val fallbackJson = """
            {
              "descriptor": {
                "source": "Vigloo",
                "type": "hls",
                "enc": "$v1Hls",
                "chain": []
              }
            }
        """.trimIndent()
        val fallbackResult = cipher.decryptStreamDescriptor(fallbackJson)
        assertEquals(1, fallbackResult.streamChain.size)
        assertEquals("https://cdn.example.com/vigloo/ep1/master.m3u8", fallbackResult.streamChain[0].url)
    }

    @Test
    fun `recovers rotated AES-GCM key lazily from client JavaScript chunks and caches it for subsequent descriptors`() {
        val rotatedKeyBase64 = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        val rotatedEnc = "QEFCQ0RFRkdISUpLsuCoARx1I7Hf+QyLcQ55bsgVVH5XXdW3U5EFnLuw671rlvEa2xOZwXOQLCB3ZESaIRGyE1S8vfE4oNVd+adL"

        val apiJson = """
            {
              "descriptor": {
                "source": "Vigloo",
                "chain": [
                  {
                    "enc": "$rotatedEnc",
                    "type": "hls",
                    "source": "Vigloo"
                  }
                ]
              }
            }
        """.trimIndent()

        var rotatedCallbackValue: String? = null
        var chunksRead = 0
        val cipher = DramaTipCipher(onKeyRotated = { rotatedCallbackValue = it })

        val withoutRecovery = cipher.decryptStreamDescriptor(apiJson)
        assertTrue(withoutRecovery.hasEncryptedPayload)
        assertTrue(withoutRecovery.streamChain.isEmpty())

        val recovered = cipher.decryptStreamDescriptor(apiJson) {
            sequence {
                chunksRead++
                yield("""console.log("unrelated vendor chunk");""")
                chunksRead++
                yield(
                    """
                    async function u(e){let t=await(!l&&(l=crypto.subtle.importKey("raw",a("$rotatedKeyBase64"),{name:"AES-GCM"},!1,["encrypt","decrypt"])),l);return t}
                    """.trimIndent(),
                )
                chunksRead++
                yield("""throw new Error("should stop lazily once key succeeds");""")
            }
        }

        assertEquals(2, chunksRead)
        assertEquals(rotatedKeyBase64, rotatedCallbackValue)
        assertEquals(1, recovered.streamChain.size)
        assertEquals("https://cdn.example.com/rotated/ep2/master.m3u8", recovered.streamChain[0].url)

        val cachedCall = cipher.decryptStreamDescriptor(apiJson)
        assertEquals(1, cachedCall.streamChain.size)
        assertEquals("https://cdn.example.com/rotated/ep2/master.m3u8", cachedCall.streamChain[0].url)
    }
}
