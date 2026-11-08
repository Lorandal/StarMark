package dev.starmark.starmark

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal data class XiaomiRecoveryRange(val start: Long, val length: Long)
internal data class XiaomiFrameCrop(val x: Int, val y: Int, val width: Int, val height: Int, val video: Boolean = false) {
    fun validate(parentWidth: Int, parentHeight: Int) {
        require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x.toLong() + width <= parentWidth && y.toLong() + height <= parentHeight &&
            (width < parentWidth || height < parentHeight)) { "相机外框原图区域无效" }
    }
}
internal data class XiaomiRecoveryBlock(val name: String, val offset: Long, val length: Long) {
    fun range(fileLength: Long, jpegEnd: Long): XiaomiRecoveryRange {
        require(offset in 1..fileLength && length in 1..(64L * 1024 * 1024) && length <= offset) { "恢复块长度或 EOF 偏移无效" }
        val start = fileLength - offset
        require(start >= jpegEnd && length <= fileLength - start) { "恢复块越界或覆盖主图" }
        return XiaomiRecoveryRange(start, length)
    }
}

internal data class XiaomiRecoveryPatch(
    val block: XiaomiRecoveryBlock, val paddingX: Int, val paddingY: Int,
    val width: Int, val height: Int, val rotation: Int, val type: Int, val editorInfo: String,
) {
    /** Lqw/t.y: decoded size → declared size → padding/rotation → upright parent coordinates. */
    fun matrix(parentWidth: Int, parentHeight: Int, parentRotation: Int, decodedWidth: Int, decodedHeight: Int): FloatArray {
        require(editorInfo.isBlank() && type == 0) { "现代 info_editor 或特殊局部恢复类型尚未验证" }
        require(parentWidth > 0 && parentHeight > 0 && decodedWidth > 0 && decodedHeight > 0) { "恢复尺寸无效" }
        require(parentRotation in listOf(0, 90, 180, 270) && rotation in listOf(-1, 0, 90, 180, 270)) { "恢复旋转无效" }
        require(paddingX >= 0 && paddingY >= 0 && width >= 0 && height >= 0) { "恢复区域无效" }
        val w = if (width == 0) decodedWidth else width
        val h = if (height == 0) decodedHeight else height
        val outW = if (parentRotation % 180 == 0) parentWidth else parentHeight
        val outH = if (parentRotation % 180 == 0) parentHeight else parentWidth
        val sx = w.toFloat() / decodedWidth; val sy = h.toFloat() / decodedHeight
        val x = paddingX.toFloat(); val y = paddingY.toFloat()
        val angle = if (rotation == -1) parentRotation else rotation
        val values = when (angle) {
            90 -> floatArrayOf(0f, -sy, outW - y, sx, 0f, x, 0f, 0f, 1f)
            180 -> floatArrayOf(-sx, 0f, outW - x, 0f, -sy, outH - y, 0f, 0f, 1f)
            270 -> floatArrayOf(0f, sy, y, -sx, 0f, outH - x, 0f, 0f, 1f)
            else -> floatArrayOf(sx, 0f, x, 0f, sy, y, 0f, 0f, 1f)
        }
        for ((px, py) in listOf(0 to 0, decodedWidth to 0, 0 to decodedHeight, decodedWidth to decodedHeight)) {
            val tx = values[0] * px + values[1] * py + values[2]
            val ty = values[3] * px + values[4] * py + values[5]
            require(tx.isFinite() && ty.isFinite() && tx >= 0f && ty >= 0f && tx <= outW && ty <= outH) { "恢复区域超出照片" }
        }
        return values
    }
}

internal data class XiaomiRecoveryMetadata(val blocks: List<XiaomiRecoveryBlock>, val patch: XiaomiRecoveryPatch?, val unsupported: Boolean,
    val frame: XiaomiFrameCrop? = null) {
    val original get() = blocks.firstOrNull { it.name == "original_image" }

    /** All private blocks must fill exactly the verified still cover → verified video interval. */
    fun validatedRanges(fileLength: Long, coverEnd: Long, tailEnd: Long): List<XiaomiRecoveryRange> {
        require(coverEnd in 4..fileLength && tailEnd in coverEnd..fileLength) { "恢复附件边界无效" }
        val ranges = blocks.map { it.range(fileLength, coverEnd) }.sortedBy { it.start }
        var end = coverEnd
        for (range in ranges) {
            require(range.start == end && range.length <= tailEnd - end) { "原片有未识别附件或恢复块重叠" }
            end += range.length
        }
        require(end == tailEnd) { "原片有未识别附件，不能确认保真恢复" }
        return ranges
    }
    companion object {
        const val NAMESPACE = "http://ns.xiaomi.com/photos/1.0/camera/"
        private const val GOOGLE = "http://ns.google.com/photos/1.0/camera/"

        fun parse(xmp: String): XiaomiRecoveryMetadata {
            val outer = elements(xmp)
            val payloads = mutableListOf<String>()
            for (element in outer) {
                if (element.namespaceURI == NAMESPACE && element.localName == "XMPMeta") payloads += element.textContent
                val attributes = element.attributes
                for (i in 0 until attributes.length) {
                    val a = attributes.item(i)
                    if (a.namespaceURI == NAMESPACE && a.localName == "XMPMeta") payloads += a.nodeValue
                }
            }
            require(payloads.size == 1) { "缺少或重复的小米 XMPMeta 命名空间" }
            return parsePayload(payloads.single())
        }

        /** The camera serializes XML fragments (siblings), not an XML document. */
        fun parsePayload(payload: String): XiaomiRecoveryMetadata {
            val fragment = payload.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                .replaceFirst(Regex("^<\\?xml\\s[^?]*\\?>"), "")
            val nodes = elements("<root>$fragment</root>")
            val allowed = setOf("root", "xmpmeta", "subimage", "original_image", "lenswatermark", "timewatermark", "madrid_image", "video_madrid_image")
            var unsupported = nodes.any { !it.namespaceURI.isNullOrBlank() || it.tagName !in allowed }
            val blocks = mutableListOf<XiaomiRecoveryBlock>()
            var patch: XiaomiRecoveryPatch? = null
            var frame: XiaomiFrameCrop? = null
            for (node in nodes.filter { it.tagName in setOf("madrid_image", "video_madrid_image") }) {
                require(frame == null) { "外框恢复定义重复" }
                // Verified camera type 701 records the original image rectangle directly.
                // Edited/cloud variants with their own payload need their own restoration plan.
                require(node.getAttribute("type") == "701" && node.getAttribute("offset") == "0" &&
                    node.getAttribute("length") == "0" && node.getAttribute("info_editor").isBlank()) { "外框恢复类型尚未验证" }
                fun int(name: String) = requireNotNull(node.getAttribute(name).toIntOrNull()) { "外框属性 $name 无效" }
                // MiMediaEditor Lqw/t.k dispatches both names to the same rectangle parser,
                // but video_madrid_image also means the motion clip carries that frame.
                frame = XiaomiFrameCrop(int("paddingx"), int("paddingy"), int("width"), int("height"), node.tagName == "video_madrid_image")
            }
            for (node in nodes.filter { it.tagName in setOf("subimage", "original_image", "lenswatermark", "timewatermark") }) {
                require(blocks.none { it.name == node.tagName }) { "恢复块重复" }
                fun long(name: String) = requireNotNull(node.getAttribute(name).toLongOrNull()) { "恢复属性 $name 无效" }
                fun int(name: String, default: Int) = if (!node.hasAttribute(name)) default else
                    requireNotNull(node.getAttribute(name).toIntOrNull()) { "恢复属性 $name 无效" }
                val block = XiaomiRecoveryBlock(node.tagName, long("offset"), long("length"))
                blocks += block
                if (node.getAttribute("watermarkId") == "143" ||
                    Regex("\"watermarkId\"\\s*:\\s*\"?143\\b").containsMatchIn(node.getAttribute("info_editor"))) unsupported = true
                if (node.tagName == "subimage") patch = XiaomiRecoveryPatch(block, int("paddingx", 0), int("paddingy", 0),
                    int("width", 0), int("height", 0), int("rotation", -1), int("type", 0), node.getAttribute("info_editor"))
            }
            require(blocks.isNotEmpty() || frame != null) { "没有相机保存的恢复块" }
            return XiaomiRecoveryMetadata(blocks, patch, unsupported, frame)
        }

        fun hasMotionDeclaration(xmp: String): Boolean {
            if (xmp.isBlank()) return false
            return elements(xmp).any { node ->
                node.namespaceURI == GOOGLE || (0 until node.attributes.length).any { node.attributes.item(it).namespaceURI == GOOGLE }
            }
        }

        private fun elements(xml: String): List<Element> {
            require(xml.length in 1..262144 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true) && !xml.contains('\u0000')) { "恢复 XML 不安全或过大" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true; isExpandEntityReferences = false
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IllegalArgumentException("不允许外部 XML 实体") }
            val document = builder.parse(InputSource(StringReader(xml)))
            val nodes = document.getElementsByTagName("*")
            require(nodes.length <= 256) { "恢复 XML 节点过多" }
            return (0 until nodes.length).map { nodes.item(it) as Element }.onEach {
                var depth = 0; var parent = it.parentNode
                while (parent != null) { require(++depth <= 16) { "恢复 XML 层级过深" }; parent = parent.parentNode }
            }
        }
    }
}
