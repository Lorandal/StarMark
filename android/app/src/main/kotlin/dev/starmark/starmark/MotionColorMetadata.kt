package dev.starmark.starmark

import java.io.File
import java.io.RandomAccessFile

/** Preserve Display-P3/sRGB RGB coordinates through the SDR, geometry-only Media3 path.
 * Media3's ColorInfo has no P3 enum. Its SDR texture path preserves those RGB coordinates,
 * but the muxer labels them BT.709. Keep the encoder's YUV matrix/range and restore only
 * the RGB primaries/transfer, consistently in nclx and HEVC SPS. Never applied to HDR or overlays.
 */
internal object MotionColorMetadata {
    private data class Entry(val bytes: ByteArray, val fileOffset: Long, val color: Int, val hvcc: Int)
    fun restoreCroppedP3(source: File, output: File) {
        val original = entry(source) ?: return
        if (u16(original.bytes, original.color) != 12 || u16(original.bytes, original.color + 2) != 13) return
        val target = requireNotNull(entry(output)) { "裁框视频缺少色彩描述" }
        val bytes = target.bytes
        require(u16(bytes, target.color) == 1 && u16(bytes, target.color + 2) == 1 &&
            u16(bytes, target.color + 4) == 1) { "裁框视频色彩路径与预期不符" }
        var p = target.hvcc + 23; var updated = 0
        repeat(bytes[target.hvcc + 22].toInt() and 255) {
            val type = bytes[p].toInt() and 63; val count = u16(bytes, p + 1); p += 3
            repeat(count) {
                val length = u16(bytes, p); p += 2
                require(length > 0 && p + length <= bytes.size)
                if (type == 33) {
                    val replacement = restoreSps(bytes.copyOfRange(p, p + length))
                    require(replacement.size == length) { "色彩修复不得改变视频索引长度" }
                    replacement.copyInto(bytes, p); updated++
                }
                p += length
            }
        }
        require(updated > 0) { "裁框视频缺少 HEVC SPS" }
        bytes[target.color + 1] = 12; bytes[target.color + 3] = 13
        RandomAccessFile(output, "rw").use { it.seek(target.fileOffset); it.write(bytes) }
    }

    private fun entry(file: File): Entry? = RandomAccessFile(file, "r").use { input ->
        var pos = 0L
        while (pos + 8 <= input.length()) {
            input.seek(pos); val size32 = input.readInt().toLong() and 0xffffffffL
            val type = input.readInt()
            val size = when (size32) {
                1L -> { if (pos + 16 > input.length()) return@use null; input.readLong() }
                0L -> input.length() - pos
                else -> size32
            }
            if (size < (if (size32 == 1L) 16 else 8) || size > input.length() - pos) return@use null
            if (type == 0x6d6f6f76) {
                require(size32 != 1L) { "不支持扩展大小的视频索引" }
                require(size <= 4 * 1024 * 1024) { "视频索引过大" }
                input.seek(pos); val bytes = ByteArray(size.toInt()); input.readFully(bytes)
                var color = -1; var hvcc = -1; var videos = 0
                fun walk(start: Int, end: Int, depth: Int) {
                    require(depth < 10); var p = start
                    while (p + 8 <= end) {
                        val n = u32(bytes, p); require(n >= 8 && n <= end - p)
                        val name = String(bytes, p + 4, 4, Charsets.US_ASCII)
                        when (name) {
                            "moov", "trak", "mdia", "minf", "stbl" -> walk(p + 8, p + n, depth + 1)
                            "stsd" -> { require(n >= 16); walk(p + 16, p + n, depth + 1) }
                            "hvc1" -> { require(n >= 86); videos++; walk(p + 86, p + n, depth + 1) }
                            "hvcC" -> { require(n >= 31); hvcc = p + 8 }
                            "colr" -> if (n >= 19 && String(bytes, p + 8, 4, Charsets.US_ASCII) == "nclx") color = p + 12
                        }
                        p += n
                    }
                    require(p == end)
                }
                walk(0, bytes.size, 0)
                return@use if (videos == 1 && color >= 0 && hvcc >= 0) Entry(bytes, pos, color, hvcc) else null
            }
            pos += size
        }
        null
    }
    private fun u16(b: ByteArray, p: Int): Int {
        require(p >= 0 && p + 2 <= b.size)
        return ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
    }
    private fun u32(b: ByteArray, p: Int): Int = (u16(b,p) shl 16) or u16(b,p+2)

    /** Single-layer encoder SPS subset, following H.265 7.3.2.2.1 and 7.3.6.1.
     * Unsupported layouts fail closed instead of searching for byte patterns in a bitstream.
     * Syntax reference: androidx/media NalUnitUtil.parseH265SpsNalUnitPayload (Apache-2.0).
     */
    internal fun restoreSps(nal: ByteArray): ByteArray {
        require(nal.size >= 4 && (nal[0].toInt() and 126) shr 1 == 33)
        require(nal[0].toInt() and 1 == 0 && nal[1].toInt() and 0xf8 == 0)
        val rbsp = ArrayList<Byte>(); var zeros = 0
        for (i in 2 until nal.size) {
            val v = nal[i].toInt() and 255
            if (zeros == 2 && v == 3) { zeros = 0; continue }
            rbsp.add(nal[i]); zeros = if (v == 0) zeros + 1 else 0
        }
        val data = rbsp.toByteArray(); val bits = Bits(data)
        bits.skip(4); val layers = bits.read(3); bits.skip(1); bits.skip(96)
        val profile = BooleanArray(layers); val level = BooleanArray(layers)
        repeat(layers) { profile[it] = bits.flag(); level[it] = bits.flag() }
        if (layers > 0) bits.skip((8 - layers) * 2)
        repeat(layers) { if (profile[it]) bits.skip(88); if (level[it]) bits.skip(8) }
        bits.ue(); val chroma = bits.ue(); require(chroma <= 3)
        if (chroma == 3) bits.skip(1)
        bits.ue(); bits.ue(); if (bits.flag()) repeat(4) { bits.ue() }
        bits.ue(); bits.ue(); val poc = bits.ue() + 4; require(poc <= 32)
        val allOrdering = bits.flag()
        for (i in (if (allOrdering) 0 else layers)..layers) repeat(3) { bits.ue() }
        repeat(6) { bits.ue() }
        if (bits.flag()) require(!bits.flag()) { "不支持的 SPS scaling list" }
        bits.skip(2)
        if (bits.flag()) { bits.skip(8); bits.ue(); bits.ue(); bits.skip(1) }
        val referenceSets = bits.ue(); require(referenceSets <= 64)
        repeat(referenceSets) { index ->
            if (index > 0) require(!bits.flag()) { "不支持的 SPS predicted reference set" }
            val negative = bits.ue(); val positive = bits.ue()
            require(negative + positive <= 64)
            repeat(negative + positive) { bits.ue(); bits.skip(1) }
        }
        if (bits.flag()) { val n = bits.ue(); require(n <= 32); repeat(n) { bits.skip(poc + 1) } }
        bits.skip(2); require(bits.flag()) { "SPS 缺少 VUI" }
        if (bits.flag() && bits.read(8) == 255) bits.skip(32)
        if (bits.flag()) bits.skip(1)
        require(bits.flag()); bits.skip(4); require(bits.flag())
        val position = bits.position
        require(bits.read(8) == 1 && bits.read(8) == 1 && bits.read(8) == 1)
        bits.write(position, 12); bits.write(position + 8, 13)
        val escaped = ArrayList<Byte>(); escaped.add(nal[0]); escaped.add(nal[1]); zeros = 0
        for (byte in data) {
            val v = byte.toInt() and 255
            if (zeros == 2 && v <= 3) { escaped.add(3); zeros = 0 }
            escaped.add(byte); zeros = if (v == 0) zeros + 1 else 0
        }
        return escaped.toByteArray()
    }
    private class Bits(val data: ByteArray) {
        var position = 0
        fun skip(n: Int) { require(n >= 0 && position + n <= data.size * 8); position += n }
        fun read(n: Int): Int {
            require(n in 1..31 && position + n <= data.size * 8)
            var result = 0
            repeat(n) { result = (result shl 1) or ((data[position / 8].toInt() shr (7 - position % 8)) and 1); position++ }
            return result
        }
        fun flag() = read(1) == 1
        fun ue(): Int {
            var zeros = 0
            while (!flag()) { zeros++; require(zeros <= 24) }
            return if (zeros == 0) 0 else (1 shl zeros) - 1 + read(zeros)
        }
        fun write(start: Int, value: Int) {
            for (i in 0..7) {
                val p = start + i; val mask = 1 shl (7 - p % 8)
                data[p/8] = ((data[p/8].toInt() and mask.inv()) or (((value shr (7-i)) and 1) * mask)).toByte()
            }
        }
    }
}
