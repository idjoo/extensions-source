package eu.kanade.tachiyomi.lib.streamproxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal object DramaBoxMp4Decryptor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class BoxRange(
        val start: Int,
        val end: Int,
        val dataStart: Int,
    )

    private data class StscEntry(
        val firstChunk: Int,
        val samplesPerChunk: Int,
    )

    fun masterSecretForUrl(rawUrl: String): ByteArray? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val master = when {
            host == "dramaboxdb.com" || host.endsWith(".dramaboxdb.com") -> "6eba09062155dcd2"
            host == "storeel.app" || host.endsWith(".storeel.app") -> "d240c6a9a2d68888"
            else -> return null
        }
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery.orEmpty()
        val isEncryptedPath = path.endsWith("encrypt.mp4", ignoreCase = true)
        val hasEncryptedQuery = query.split('&').any {
            it == "etavirp_nuyila" || it.startsWith("etavirp_nuyila=")
        }
        return if (isEncryptedPath || hasEncryptedQuery) {
            master.toByteArray(Charsets.UTF_8)
        } else {
            null
        }
    }

    fun decryptMp4(rawMp4: ByteArray, upstreamUrl: String): ByteArray {
        val masterBytes = masterSecretForUrl(upstreamUrl) ?: return rawMp4
        val moov = findBox(rawMp4, 0, rawMp4.size, "moov") ?: return rawMp4
        val privateKeyUri = extractPrivateKeyUri(rawMp4, moov.start, moov.end) ?: return rawMp4
        val mediaKey = runCatching { deriveMediaKey(privateKeyUri, masterBytes) }.getOrNull()
            ?.takeIf { it.size == 16 } ?: return rawMp4

        val samples = mutableListOf<LongArray>()
        var cursor = moov.dataStart
        while (cursor + 8 <= moov.end) {
            val boxSize = readUInt32(rawMp4, cursor)
            if (boxSize < 8L) break
            val boxEnd = minOf((cursor + boxSize).toInt(), moov.end)
            if (boxEnd <= cursor) break
            if (matchesFourCc(rawMp4, cursor + 4, "trak")) {
                val mdia = findBox(rawMp4, cursor + 8, boxEnd, "mdia")
                val minf = mdia?.let { findBox(rawMp4, it.dataStart, it.end, "minf") }
                val stbl = minf?.let { findBox(rawMp4, it.dataStart, it.end, "stbl") }
                if (stbl != null) {
                    samples.addAll(extractStblSamples(rawMp4, stbl.dataStart, stbl.end))
                }
            }
            cursor = boxEnd
        }

        if (samples.isEmpty()) return rawMp4

        val output = rawMp4.copyOf()
        val ecbCipher = Cipher.getInstance("AES/ECB/NoPadding")
        ecbCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(mediaKey, "AES"))

        for (sample in samples) {
            val offsetLong = sample[0]
            val sizeLong = sample[1]
            val alignedLen = ((sizeLong / 16L) * 16L).toInt()
            if (alignedLen <= 0 || offsetLong < 0L || offsetLong + alignedLen > output.size) continue
            val offset = offsetLong.toInt()
            ecbCipher.doFinal(output, offset, alignedLen, output, offset)
        }
        return output
    }

    private fun deriveMediaKey(privateKeyUriB64: String, masterBytes: ByteArray): ByteArray {
        val outerPlain = aesCbcDecrypt(masterBytes, masterBytes, Base64.decode(privateKeyUriB64.trim()))
            .toString(Charsets.UTF_8)
        val obj = json.parseToJsonElement(outerPlain) as JsonObject
        val clientRand = (obj["ClientRand"] as JsonPrimitive).contentOrNull ?: error("Missing ClientRand")
        val serverRandB64 = (obj["ServerRand"] as JsonPrimitive).contentOrNull ?: error("Missing ServerRand")
        val plainTextB64 = (obj["PlainText"] as JsonPrimitive).contentOrNull ?: error("Missing PlainText")

        val cKey = md5Hex(clientRand.toByteArray(Charsets.UTF_8)).substring(8, 24).toByteArray(Charsets.UTF_8)
        val serverRand = aesCbcDecrypt(cKey, cKey, Base64.decode(serverRandB64.trim())).toString(Charsets.UTF_8)
        val fKey = md5Hex((clientRand + serverRand).toByteArray(Charsets.UTF_8)).substring(8, 24).toByteArray(Charsets.UTF_8)
        val mediaKeyB64 = aesCbcDecrypt(fKey, cKey, Base64.decode(plainTextB64.trim())).toString(Charsets.UTF_8)
        return Base64.decode(mediaKeyB64.trim())
    }

    private fun extractPrivateKeyUri(bytes: ByteArray, moovStart: Int, moovEnd: Int): String? {
        val keysTagPos = indexOfFourCc(bytes, moovStart, moovEnd, "keys")
        if (keysTagPos < moovStart + 4) return null
        val keysBoxStart = keysTagPos - 4
        val keysBoxSize = readUInt32(bytes, keysBoxStart)
        if (keysBoxSize <= 20L || keysBoxSize >= 10_000L || keysBoxStart + 16 > moovEnd) return null

        val entryCount = readUInt32(bytes, keysBoxStart + 12).toInt()
        val keyNames = ArrayList<String>(entryCount.coerceAtMost(256))
        var c = keysBoxStart + 16
        val keysEnd = minOf((keysBoxStart + keysBoxSize).toInt(), moovEnd)
        for (idx in 0 until entryCount) {
            if (c + 8 > keysEnd) break
            val entryLen = readUInt32(bytes, c).toInt()
            if (entryLen < 8 || c + entryLen > keysEnd) break
            keyNames.add(bytes.copyOfRange(c + 8, c + entryLen).toString(Charsets.UTF_8))
            c += entryLen
        }

        val ilstTagPos = indexOfFourCc(bytes, keysBoxStart, moovEnd, "ilst")
        if (ilstTagPos < keysBoxStart + 4) return null
        val ilstBoxStart = ilstTagPos - 4
        val ilstBoxSize = readUInt32(bytes, ilstBoxStart).toInt()
        if (ilstBoxSize < 8) return null
        var d = ilstBoxStart + 8
        val ilstEnd = minOf(ilstBoxStart + ilstBoxSize, moovEnd)

        while (d + 16 <= ilstEnd) {
            val itemSize = readUInt32(bytes, d).toInt()
            if (itemSize < 8 || d + itemSize > ilstEnd) break
            val keyIdx = readUInt32(bytes, d + 4).toInt()
            val dataSize = readUInt32(bytes, d + 8).toInt()
            if (matchesFourCc(bytes, d + 12, "data") &&
                dataSize > 16 &&
                d + 8 + dataSize <= ilstEnd &&
                keyNames.getOrNull(keyIdx - 1)?.contains("PrivateKeyUri") == true
            ) {
                return bytes.copyOfRange(d + 24, d + 8 + dataSize).toString(Charsets.UTF_8).trim()
            }
            d += itemSize
        }
        return null
    }

    private fun extractStblSamples(bytes: ByteArray, stblStart: Int, stblEnd: Int): List<LongArray> {
        val stsz = findBox(bytes, stblStart, stblEnd, "stsz") ?: return emptyList()
        val stsc = findBox(bytes, stblStart, stblEnd, "stsc") ?: return emptyList()
        val stco = findBox(bytes, stblStart, stblEnd, "stco")
        val co64 = if (stco == null) findBox(bytes, stblStart, stblEnd, "co64") else null
        if (stco == null && co64 == null) return emptyList()

        if (stsz.dataStart + 12 > stsz.end || stsc.dataStart + 8 > stsc.end) return emptyList()
        val fixedSize = readUInt32(bytes, stsz.dataStart + 4)
        val sampleCount = readUInt32(bytes, stsz.dataStart + 8).toInt()
        if (sampleCount <= 0) return emptyList()

        val sampleSizes = LongArray(sampleCount)
        for (i in 0 until sampleCount) {
            sampleSizes[i] = if (fixedSize != 0L) {
                fixedSize
            } else {
                val pos = stsz.dataStart + 12 + 4 * i
                if (pos + 4 > stsz.end) return emptyList()
                readUInt32(bytes, pos)
            }
        }

        val chunkOffsets: LongArray = if (stco != null) {
            if (stco.dataStart + 8 > stco.end) return emptyList()
            val count = readUInt32(bytes, stco.dataStart + 4).toInt()
            LongArray(count) { i ->
                val pos = stco.dataStart + 8 + 4 * i
                if (pos + 4 <= stco.end) readUInt32(bytes, pos) else 0L
            }
        } else {
            val box64 = co64!!
            if (box64.dataStart + 8 > box64.end) return emptyList()
            val count = readUInt32(bytes, box64.dataStart + 4).toInt()
            LongArray(count) { i ->
                val pos = box64.dataStart + 8 + 8 * i
                if (pos + 8 <= box64.end) ByteBuffer.wrap(bytes, pos, 8).long else 0L
            }
        }

        val stscCount = readUInt32(bytes, stsc.dataStart + 4).toInt()
        val stscEntries = ArrayList<StscEntry>(stscCount)
        for (i in 0 until stscCount) {
            val pos = stsc.dataStart + 8 + 12 * i
            if (pos + 12 > stsc.end) break
            stscEntries.add(
                StscEntry(
                    firstChunk = readUInt32(bytes, pos).toInt(),
                    samplesPerChunk = readUInt32(bytes, pos + 4).toInt(),
                ),
            )
        }

        val out = ArrayList<LongArray>(sampleCount)
        var sampleIdx = 0
        for (chunkIdx in chunkOffsets.indices) {
            var perChunk = 1
            for (r in stscEntries.indices.reversed()) {
                if (chunkIdx + 1 >= stscEntries[r].firstChunk) {
                    perChunk = stscEntries[r].samplesPerChunk
                    break
                }
            }
            var offset = chunkOffsets[chunkIdx]
            var k = 0
            while (k < perChunk && sampleIdx < sampleSizes.size) {
                val sz = sampleSizes[sampleIdx]
                out.add(longArrayOf(offset, sz))
                offset += sz
                sampleIdx++
                k++
            }
        }
        return out
    }

    private fun findBox(bytes: ByteArray, start: Int, end: Int, type: String): BoxRange? {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readUInt32(bytes, pos)
            val headerLen: Int
            val totalSize: Long
            if (size32 == 1L) {
                if (pos + 16 > end) break
                headerLen = 16
                totalSize = ByteBuffer.wrap(bytes, pos + 8, 8).long
            } else {
                headerLen = 8
                totalSize = size32
            }
            if (totalSize < headerLen) break
            val boxEnd = minOf((pos + totalSize).coerceAtMost(end.toLong()).toInt(), end)
            if (matchesFourCc(bytes, pos + 4, type)) {
                return BoxRange(pos, boxEnd, pos + headerLen)
            }
            if (boxEnd <= pos) break
            pos = boxEnd
        }
        return null
    }

    private fun indexOfFourCc(bytes: ByteArray, start: Int, end: Int, fourCc: String): Int {
        val b0 = fourCc[0].code.toByte()
        val b1 = fourCc[1].code.toByte()
        val b2 = fourCc[2].code.toByte()
        val b3 = fourCc[3].code.toByte()
        var i = start
        while (i <= end - 4) {
            if (bytes[i] == b0 && bytes[i + 1] == b1 && bytes[i + 2] == b2 && bytes[i + 3] == b3) {
                return i
            }
            i++
        }
        return -1
    }

    private fun matchesFourCc(bytes: ByteArray, offset: Int, fourCc: String): Boolean =
        offset + 4 <= bytes.size &&
            bytes[offset] == fourCc[0].code.toByte() &&
            bytes[offset + 1] == fourCc[1].code.toByte() &&
            bytes[offset + 2] == fourCc[2].code.toByte() &&
            bytes[offset + 3] == fourCc[3].code.toByte()

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun md5Hex(input: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }
}
