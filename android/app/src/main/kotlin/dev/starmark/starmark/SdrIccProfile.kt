package dev.starmark.starmark

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/** Bounded ICC matrix/TRC inspection, independent of EXIF and Android decoders.
 * Only well-understood SDR transfer functions qualify. A profile name (including
 * "Display P3") alone never proves dynamic range. ICC.1:2022 sections 7 and 10. */
internal object SdrIccProfile {
    fun pngChunk(data: ByteArray): Boolean { return try {
        val end = data.indexOf(0)
        if (end !in 1..79 || end + 2 >= data.size || data[end + 1].toInt() != 0) false
        else {
            val inflater = Inflater()
            try {
                inflater.setInput(data, end + 2, data.size - end - 2)
                val decoded = ByteArrayOutputStream()
                val block = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(block)
                    if (count == 0 || decoded.size() + count > 262144) return false
                    decoded.write(block, 0, count)
                }
                inflater.remaining == 0 && profile(decoded.toByteArray())
            } finally { inflater.end() }
        }
    } catch (_: Exception) { false } }

    private fun profile(b: ByteArray): Boolean {
        if (b.size !in 132..262144) return false
        fun u32(at: Int): Long = (0..3).fold(0L) { v, i -> (v shl 8) or (b[at + i].toLong() and 255) }
        fun signature(at: Int) = String(b, at, 4, Charsets.US_ASCII)
        fun fixed(at: Int) = u32(at).toInt() / 65536.0
        if (u32(0) != b.size.toLong() || b[8].toInt() !in setOf(2, 4) ||
            signature(12) !in setOf("mntr", "scnr", "spac") || signature(16) != "RGB " ||
            signature(20) != "XYZ " || signature(36) != "acsp") return false
        val count = u32(128)
        if (count !in 1..64 || 132 + count * 12 > b.size) return false
        val tags = mutableMapOf<String, Pair<Int, Int>>()
        val allowed = setOf("desc", "cprt", "wtpt", "bkpt", "chad", "chrm", "dmnd", "dmdd", "tech",
            "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC")
        for (i in 0 until count.toInt()) {
            val at = 132 + i * 12
            val key = signature(at); val offset = u32(at + 4); val size = u32(at + 8)
            if (key !in allowed || key in tags || offset % 4 != 0L || offset < 132 + count * 12 ||
                size < 8 || offset + size > b.size) return false
            tags[key] = offset.toInt() to size.toInt()
        }
        for (key in listOf("rXYZ", "gXYZ", "bXYZ", "wtpt")) {
            val (at, size) = tags[key] ?: return false
            if (size != 20 || signature(at) != "XYZ " ||
                (0..2).any { abs(fixed(at + 8 + it * 4)) > 4 }) return false
        }
        for (key in listOf("rTRC", "gTRC", "bTRC")) {
            val (at, size) = tags[key] ?: return false
            if (size < 12) return false
            when (signature(at)) {
                "curv" -> when (u32(at + 8)) {
                    0L -> if (size != 12) return false
                    1L -> {
                        if (size !in 14..16) return false
                        val gamma = ((b[at + 12].toInt() and 255) * 256 + (b[at + 13].toInt() and 255)) / 256.0
                        if (gamma !in 1.0..3.0) return false
                    }
                    else -> return false // Unknown sampled transfer, possibly HDR.
                }
                "para" -> {
                    val kind = ((b[at + 8].toInt() and 255) shl 8) or (b[at + 9].toInt() and 255)
                    if (kind == 0) {
                        if (size != 16 || fixed(at + 12) !in 1.0..3.0) return false
                    } else {
                        val expected = doubleArrayOf(2.4, 1 / 1.055, .055 / 1.055, 1 / 12.92, .04045, 0.0, 0.0)
                        val parameters = when (kind) { 3 -> 5; 4 -> 7; else -> return false }
                        if (size != 12 + parameters * 4 || (0 until parameters).any {
                                abs(fixed(at + 12 + it * 4) - expected[it]) > 2.0 / 65536
                            }) return false
                    }
                }
                else -> return false
            }
        }
        return true
    }
}
