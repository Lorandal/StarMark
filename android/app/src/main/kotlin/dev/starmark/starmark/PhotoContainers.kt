package dev.starmark.starmark

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

data class ByteRange(val offset: Long, val length: Long)
data class MotionInfo(val range: ByteRange, val presentationTimestampUs: Long?, val format: String)
data class ReversibleInfo(val originalRange: ByteRange, val documentJson: String, val sha256: String, val version: Int)
data class PhotoContainerInfo(val motion: MotionInfo?, val reversible: ReversibleInfo?, val jpegEnd: Long)
data class Restoration(val documentJson: String, val originalSha256: String)
data class PackageResult(val info: PhotoContainerInfo, val originalSha256: String)
class PhotoContainerException(message: String, cause: Throwable? = null) : IOException(message, cause)

object PhotoContainers {
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val STAR = "https://starmark.dev/ns/photo/1.0/"
    private const val HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val MAX_FILE = 2L * 1024 * 1024 * 1024
    private const val MAX_DOCUMENT = 1024 * 1024
    private const val HEADER_SIZE = 88L
    private val MAGIC = "STARMK01".toByteArray(Charsets.US_ASCII)
    private val XMP_PREFIX = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val ISO_GAINMAP_PREFIX = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.US_ASCII)
    private data class Xmp(val range: ByteRange, val document: Document)
    private data class Jpeg(val end: Long, val packets: List<Xmp>, val mpfSegments: List<ByteRange> = emptyList(),
        val exifSegments: List<ByteRange> = emptyList(), val isoGainmapSegments: List<ByteRange> = emptyList(),
        val supportedIsoGainmapVersion: Boolean = false)
    private data class Mpf(val range: ByteRange, val bytes: ByteArray, val order: ByteOrder, val entries: IntArray, val images: List<ByteRange>)
    private data class DirectoryItem(val semantic: String, val mime: String, val range: ByteRange, val padding: Long)

    /**
     * Grafts a freshly Bitmap.compress-encoded Android gainmap onto a standalone streamed JPEG.
     * The caller must render the carrier in the primary's color space, with its gainmap covering
     * the same normalized canvas. No pixels are decoded here. Primary pixels, ICC, EXIF and XMP
     * remain byte-identical; only HDR declarations and the exact secondary JPEG are imported.
     * This accepts one adjacent MPF-indexed Adobe/ISO gainmap, not arbitrary HDR camera trailers.
     */
    fun attachGainmap(primary: File, carrier: File, destination: File) = attachGainmap(primary, carrier, destination, false)

    /** Recovery keeps the raw canvas unchanged, so the validated camera gainmap can be copied exactly. */
    internal fun attachRecoveryGainmap(primary: File, camera: File, destination: File) =
        attachGainmap(primary, camera, destination, true)

    private fun attachGainmap(primary: File, carrier: File, destination: File, cameraCarrier: Boolean): Unit = guarded {
        requireDifferent(primary, destination); requireDifferent(carrier, destination)
        checkFile(primary); checkFile(carrier)
        val base = readJpeg(primary)
        ensure(base.end > 0 && base.end == primary.length() && base.mpfSegments.isEmpty() &&
            base.isoGainmapSegments.isEmpty() && base.packets.none {
                hasContainerMetadata(it.document) || hasNamespace(it.document, HDRGM)
            }, "Gainmap primary must be a freshly encoded standalone SDR JPEG")
        val source = readJpeg(carrier)
        ensure(source.end > 0, "Gainmap carrier must be a JPEG")
        ensure(source.packets.none { hasNamespace(it.document, STAR) || (!cameraCarrier && hasNamespace(it.document, CAMERA)) },
            "Gainmap carrier must not contain motion or recovery metadata")
        val indexes = readMpf(carrier, source, allowStaleCameraPrimaryLength = cameraCarrier)
        val coverEnd = if (cameraCarrier) validatedCoverEnd(carrier) else carrier.length()
        val gain = coverGainmap(carrier, source, coverEnd, indexes)
            ?: throw PhotoContainerException("Carrier has no identified gainmap")
        ensure(gain.offset == source.end, "Carrier gainmap must immediately follow its primary")
        val directory = readDirectory(carrier, source)
        ensure(cameraCarrier || directory.isEmpty() || (directory.map { it.semantic } == listOf("Primary", "GainMap") &&
            directory.all { it.padding == 0L }), "Unsupported carrier directory")
        ensure(source.isoGainmapSegments.size <= 1 && (source.isoGainmapSegments.isEmpty() || source.supportedIsoGainmapVersion),
            "Unsupported carrier ISO gainmap version")
        val secondary = readJpeg(carrier, gain.offset, gain.length)
        ensure(secondary.end == gain.offset + gain.length, "Gainmap JPEG has unexplained trailing data")
        val adobeGainmap = property(source.packets.map { it.document }, HDRGM, "Version") == "1.0" &&
            property(secondary.packets.map { it.document }, HDRGM, "Version") == "1.0"
        val isoGainmap = source.supportedIsoGainmapVersion && secondary.isoGainmapSegments.size == 1 &&
            secondary.isoGainmapSegments.single().length > ISO_GAINMAP_PREFIX.size + 8
        ensure(adobeGainmap || isoGainmap, "Carrier secondary has no supported gainmap parameter declaration")
        val primaryHash = hash(primary, ByteRange(2, base.end - 2))
        val gainHash = hash(carrier, gain)
        // Regenerate the directory with implicit primary length (0). This avoids importing a
        // thumbnail's explicit length, padding or dimensions and remains valid after EXIF edits.
        val declarations = listOf(xmpSegment(outputXmp(0, 0, null, 0, gain.length, adobeGainmap))) +
            source.packets.mapNotNull { gainmapXmp(it.document) } +
            source.isoGainmapSegments.map { readBytes(carrier, it) }
        val index = indexes.single()
        val declarationSize = declarations.sumOf { it.size.toLong() }
        val inserted = declarationSize + index.bytes.size
        val primaryLength = base.end + inserted
        ensure(primaryLength + gain.length <= MAX_FILE, "Gainmap output exceeds file size limit")
        // MPF is inserted after the declarations: offsets are relative to its TIFF byte-order word.
        val tiffOffset = 2 + declarationSize + 8
        val mpf = index.bytes.copyOf()
        ByteBuffer.wrap(mpf).order(index.order).apply {
            putInt(index.entries[0] + 4, primaryLength.toInt())
            putInt(index.entries[0] + 8, 0)
            putInt(index.entries[1] + 4, gain.length.toInt())
            putInt(index.entries[1] + 8, (primaryLength - tiffOffset).toInt())
        }
        val temporary = temporaryFor(destination)
        try {
            temporary.outputStream().buffered().use { out ->
                writePatched(primary, out, declarations + listOf(mpf), emptyList(), base.end)
                copyRange(carrier, gain, out)
            }
            ensure(temporary.length() == primaryLength + gain.length, "Gainmap output length changed while writing")
            val verified = readJpeg(temporary)
            val verifiedGain = coverGainmap(temporary, verified, temporary.length(), readMpf(temporary, verified))
            ensure(verified.end == primaryLength && verifiedGain == ByteRange(primaryLength, gain.length),
                "Gainmap output boundaries changed while writing")
            ensure(hash(temporary, ByteRange(2 + inserted, base.end - 2)).contentEquals(primaryHash),
                "Primary image changed while grafting gainmap")
            ensure(hash(temporary, verifiedGain!!).contentEquals(gainHash), "Gainmap changed while writing")
            // Same-directory rename: a failed validation never damages an existing destination.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun readBytes(file: File, range: ByteRange): ByteArray = ByteArray(range.length.toInt()).also { bytes ->
        RandomAccessFile(file, "r").use { it.seek(range.offset); it.readFully(bytes) }
    }

    private fun gainmapXmp(doc: Document): ByteArray? {
        if (!hasNamespace(doc, HDRGM)) return null
        // Keep only HDR properties and their RDF structure. Carrier EXIF/TIFF/XMP dimensions,
        // camera identity and thumbnails must never override the full-resolution primary.
        val allowed = setOf("adobe:ns:meta/", RDF, HDRGM)
        for (element in elements(doc, "*", "*").asReversed()) {
            if (element.namespaceURI !in allowed) element.parentNode?.removeChild(element)
            else for (i in element.attributes.length - 1 downTo 0) {
                val attribute = element.attributes.item(i)
                if (attribute.namespaceURI !in allowed && attribute.namespaceURI != "http://www.w3.org/2000/xmlns/") {
                    element.removeAttributeNode(attribute as org.w3c.dom.Attr)
                }
            }
        }
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }
            .transform(DOMSource(doc), StreamResult(writer))
        return xmpSegment(writer.toString())
    }

    /**
     * EXIF libraries may insert APP1 without updating MPF's primary size. Run their writer on an
     * isolated primary JPEG, transplant ONLY its EXIF APP1 into the original, then repair MPF.
     * Intended solely for freshly encoded covers, before packagePhoto. The callback must edit
     * the supplied scratch file. Pixel, XMP, ICC and auxiliary data remain from the input cover.
     */
    fun rewriteExifPreservingAuxiliary(file: File, rewrite: (File) -> Unit): Unit = guarded {
        val info = inspect(file)
        ensure(info.jpegEnd > 0 && info.reversible == null && info.motion == null, "EXIF update requires a freshly encoded static JPEG cover")
        val jpeg = readJpeg(file)
        val indexes = readMpf(file, jpeg)
        for (packet in jpeg.packets) for (directory in elements(packet.document, CONTAINER, "Directory")) {
            val primary = elements(directory, CONTAINER, "Item").firstOrNull()
            val length = primary?.getAttributeNS(ITEM, "Length")
            ensure(length.isNullOrBlank() || length == "0", "Explicit primary directory length cannot be retained across EXIF insertion")
        }
        val originalHash = hash(file, ByteRange(0, file.length()))
        val auxiliaryHashes = indexes.flatMap { it.images.drop(1) }.map { hash(file, it) }
        val scratch = temporaryFor(file)
        val rebuilt = temporaryFor(file)
        try {
            // XMP and MPF are omitted from the isolated JPEG to keep the EXIF writer independent
            // of auxiliary image declarations. Only the temporary copy is passed to the callback.
            scratch.outputStream().buffered().use { out ->
                val removed = (jpeg.mpfSegments + jpeg.packets.map { it.range }).map { it to neutralSegment(it) }
                writePatched(file, out, emptyList(), removed, jpeg.end)
            }
            rewrite(scratch)
            checkFile(scratch)
            val edited = readJpeg(scratch)
            ensure(edited.end > 0 && edited.end == scratch.length(), "EXIF writer did not return a standalone JPEG")
            ensure(edited.exifSegments.size <= 1, "EXIF writer emitted multiple EXIF segments")
            val newExif = edited.exifSegments.map { range ->
                ByteArray(range.length.toInt()).also { bytes -> RandomAccessFile(scratch, "r").use { it.seek(range.offset); it.readFully(bytes) } }
            }
            val insertedLength = newExif.sumOf { it.size.toLong() }
            ensure(file.length() + insertedLength <= MAX_FILE, "EXIF update exceeds file size limit")
            val patches = jpeg.exifSegments.map { it to neutralSegment(it) } +
                indexes.map { it.range to rewriteMpf(it, insertedLength, 0) }
            rebuilt.outputStream().buffered().use { out -> writePatched(file, out, newExif, patches, file.length()) }
            ensure(hash(file, ByteRange(0, file.length())).contentEquals(originalHash), "Cover changed during EXIF update")
            val updatedJpeg = readJpeg(rebuilt)
            ensure(updatedJpeg.end == jpeg.end + insertedLength, "EXIF insertion changed JPEG pixel boundaries")
            val updatedImages = readMpf(rebuilt, updatedJpeg).flatMap { it.images.drop(1) }
            ensure(updatedImages.size == auxiliaryHashes.size, "EXIF update changed auxiliary image count")
            for ((index, range) in updatedImages.withIndex()) ensure(hash(rebuilt, range).contentEquals(auxiliaryHashes[index]), "EXIF update changed auxiliary image bytes")
            // Also protect unindexed tails, including future auxiliary formats, byte for byte.
            ensure(hash(file, ByteRange(jpeg.end, file.length() - jpeg.end)).contentEquals(
                hash(rebuilt, ByteRange(updatedJpeg.end, rebuilt.length() - updatedJpeg.end))), "EXIF update changed photo trailer")
            replace(rebuilt, file)
        } finally { scratch.delete(); rebuilt.delete() }
    }

    private fun neutralSegment(range: ByteRange): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { it.writeShort(0xffef); it.writeShort((range.length - 2).toInt()); it.write(ByteArray((range.length - 4).toInt())) }
    }.toByteArray()

    private fun writePatched(file: File, out: OutputStream, insertions: List<ByteArray>, patches: List<Pair<ByteRange, ByteArray>>, end: Long) {
        out.write(byteArrayOf(-1, -40))
        insertions.forEach { out.write(it) }
        var cursor = 2L
        for ((range, bytes) in patches.sortedBy { it.first.offset }) {
            ensure(range.offset >= cursor && range.offset + range.length <= end && bytes.size.toLong() == range.length, "Invalid JPEG patch range")
            copyRange(file, ByteRange(cursor, range.offset - cursor), out)
            out.write(bytes)
            cursor = range.offset + range.length
        }
        copyRange(file, ByteRange(cursor, end - cursor), out)
    }

    /** Parses only the primary JPEG's metadata. Embedded originals are never searched for tags. */
    fun inspect(file: File): PhotoContainerInfo = guarded {
        checkFile(file)
        val jpeg = readJpeg(file)
        if (jpeg.end == 0L) return@guarded PhotoContainerInfo(null, null, 0)
        val motion = readMotion(file, jpeg)
        val reversible = readReversible(file, jpeg, motion)
        PhotoContainerInfo(motion, reversible, jpeg.end)
    }

    /** End of the verified still image, including its MPF-indexed HDR gainmap. */
    fun validatedCoverEnd(file: File): Long = guarded {
        checkFile(file)
        val jpeg = readJpeg(file)
        val cameraMetadata = property(jpeg.packets.map { it.document },
            "http://ns.xiaomi.com/photos/1.0/camera/", "XMPMeta") != null
        val indexes = readMpf(file, jpeg, allowStaleCameraPrimaryLength = cameraMetadata)
        val end = indexes.flatMap { it.images.drop(1) }.maxOfOrNull { it.offset + it.length } ?: jpeg.end
        coverGainmap(file, jpeg, end, indexes)
        end
    }

    /** Validated secondary JPEG range; callers can decode the gainmap without the full base. */
    fun gainmapImageRange(file: File): ByteRange? = guarded {
        checkFile(file)
        val jpeg = readJpeg(file)
        val cameraMetadata = property(jpeg.packets.map { it.document },
            "http://ns.xiaomi.com/photos/1.0/camera/", "XMPMeta") != null
        val indexes = readMpf(file, jpeg, allowStaleCameraPrimaryLength = cameraMetadata)
        val end = indexes.flatMap { it.images.drop(1) }.maxOfOrNull { it.offset + it.length } ?: jpeg.end
        coverGainmap(file, jpeg, end, indexes)
    }

    /** Reattach the camera video, or a verified frame-cropped MP4, without nesting the original. */
    fun appendMotionToCover(cover: File, motionSource: File, destination: File, croppedVideo: File? = null): Unit = guarded {
        checkFile(cover); checkFile(motionSource)
        ensure(destination.canonicalFile != cover.canonicalFile && destination.canonicalFile != motionSource.canonicalFile,
            "Motion output must not replace its inputs")
        val motion = inspect(motionSource).motion ?: throw PhotoContainerException("Source has no motion video")
        val videoFile = croppedVideo ?: motionSource
        val videoRange = if (croppedVideo == null) motion.range else {
            checkFile(croppedVideo)
            ensure(croppedVideo.canonicalFile != destination.canonicalFile, "Motion output must not replace video input")
            ByteRange(0, croppedVideo.length()).also { validateMp4(croppedVideo, it) }
        }
        val coverInfo = inspect(cover)
        ensure(coverInfo.motion == null && coverInfo.reversible == null, "Restored cover must be a standalone image")
        val jpeg = readJpeg(cover)
        val indexes = readMpf(cover, jpeg)
        val end = validatedCoverEnd(cover)
        ensure(end == cover.length(), "Restored cover contains unexplained trailing data")
        val gainmap = coverGainmap(cover, jpeg, end, indexes)
        val packets = jpeg.packets.filter { hasContainerMetadata(it.document) }
        val relocated = packets.mapNotNull { cleanMetadata(it.document) }
        val xmp = xmpSegment(outputXmp(0, videoRange.length, motion.presentationTimestampUs, 0, gainmap?.length ?: 0,
            property(jpeg.packets.map { it.document }, HDRGM, "Version") == "1.0"))
        val inserted = xmp.size + relocated.sumOf { it.size.toLong() }
        ensure(end + inserted + videoRange.length <= MAX_FILE, "Packaged photo exceeds size limit")
        val replacements = packets.map { packet -> packet.range to ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeShort(0xffef); it.writeShort((packet.range.length - 2).toInt())
                it.write(ByteArray((packet.range.length - 4).toInt()))
            }
        }.toByteArray() } + indexes.map { it.range to rewriteMpf(it, inserted, 0) }
        destination.absoluteFile.parentFile?.mkdirs()
        val temporary = File.createTempFile("motion-cover-", ".tmp", destination.absoluteFile.parentFile)
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { out ->
                out.writeShort(0xffd8); out.write(xmp); relocated.forEach { out.write(it) }
                var cursor = 2L
                for ((range, bytes) in replacements.sortedBy { it.first.offset }) {
                    copyRange(cover, ByteRange(cursor, range.offset - cursor), out)
                    out.write(bytes); cursor = range.offset + range.length
                }
                copyRange(cover, ByteRange(cursor, end - cursor), out)
                copyRange(videoFile, videoRange, out)
            }
            val verified = inspect(temporary)
            ensure(temporary.length() == end + inserted + videoRange.length && verified.reversible == null,
                "Motion cover length or metadata changed while writing")
            ensure(validatedCoverEnd(temporary) == end + inserted, "Motion cover lost its still image extent")
            ensure(hash(videoFile, videoRange).contentEquals(hash(temporary, verified.motion!!.range)),
                "Motion video changed while writing")
            replace(temporary, destination)
        } finally { temporary.delete() }
    }

    /** The target is replaced only after a full checksum-validated copy succeeds. */
    fun restoreOriginal(file: File, targetFile: File): Restoration? = guarded {
        requireDifferent(file, targetFile)
        val reversible = inspect(file).reversible ?: return@guarded null
        val temporary = temporaryFor(targetFile)
        try {
            temporary.outputStream().buffered().use { copyRange(file, reversible.originalRange, it) }
            ensure(hash(temporary, ByteRange(0, temporary.length())).hex() == reversible.sha256, "Original changed while restoring")
            replace(temporary, targetFile)
            Restoration(reversible.documentJson, reversible.sha256)
        } finally { temporary.delete() }
    }

    /**
     * Layout: primary JPEG, recovery block as Primary Padding, gainmap if present, then MP4.
     * New APP1 precedes MPF; primary image size grows by APP1 length and secondary TIFF-relative
     * offsets grow by the recovery block length. Call AFTER all Exif/bitmap writers finish.
     */
    fun packagePhoto(cover: File, original: File, destination: File, documentJson: String, preserveMotion: Boolean,
        transformMotion: ((File, MotionInfo) -> File)? = null): PackageResult = guarded {
        requireDifferent(cover, destination)
        requireDifferent(original, destination)
        ensure(documentJson.length <= MAX_DOCUMENT, "Editing document is too large")
        val document = documentJson.toByteArray(Charsets.UTF_8)
        ensure(document.size <= MAX_DOCUMENT, "Editing document is too large")
        val canonicalCopies = mutableListOf<File>()
        val temporary = temporaryFor(destination)
        try {
            var canonical = original
            var originalInfo = inspect(canonical)
            // Our writer produces depth one. Bounded flattening also repairs earlier nested packages.
            while (originalInfo.reversible != null) {
                ensure(canonicalCopies.size < 4, "Too many nested recovery packages")
                val unpacked = temporaryFor(destination)
                canonicalCopies.add(unpacked)
                restoreOriginal(canonical, unpacked)
                canonical = unpacked
                originalInfo = inspect(canonical)
            }
            val coverInfo = inspect(cover)
            ensure(coverInfo.jpegEnd > 0, "Edited cover must be a JPEG")
            ensure(coverInfo.reversible == null, "Edited cover must be freshly encoded before packaging")
            val coverJpeg = readJpeg(cover)
            val coverLength = coverInfo.motion?.range?.offset ?: cover.length()
            ensure(coverLength >= coverJpeg.end, "Cover data overlaps its JPEG")
            val indexes = readMpf(cover, coverJpeg)
            val gainmap = coverGainmap(cover, coverJpeg, coverLength, indexes)
            val tailLength = coverLength - coverJpeg.end
            val originalHash = hash(canonical, ByteRange(0, canonical.length()))
            val documentHash = MessageDigest.getInstance("SHA-256").digest(document)
            val blockLength = HEADER_SIZE + canonical.length() + document.size
            val motion = originalInfo.motion.takeIf { preserveMotion }
            // Transform only the playback copy. Recovery always embeds the canonical original,
            // so a second framed export cannot accumulate borders or video generations.
            val motionFile = if (motion != null && transformMotion != null) transformMotion(canonical, motion) else canonical
            val motionRange = if (motionFile != canonical) {
                checkFile(motionFile)
                ByteRange(0, motionFile.length()).also { validateMp4(motionFile, it) }
            } else motion?.range
            val videoLength = motionRange?.length ?: 0
            val cleanPackets = coverJpeg.packets.filter { hasContainerMetadata(it.document) }
            val relocated = cleanPackets.mapNotNull { cleanMetadata(it.document) }
            val newXmp = xmpSegment(outputXmp(blockLength, videoLength, motion?.presentationTimestampUs,
                blockLength + tailLength - (gainmap?.length ?: 0), gainmap?.length ?: 0,
                property(coverJpeg.packets.map { it.document }, HDRGM, "Version") == "1.0"))
            val insertedLength = newXmp.size + relocated.sumOf { it.size.toLong() }
            val outputLength = coverLength + insertedLength + blockLength + videoLength
            ensure(outputLength <= MAX_FILE, "Packaged photo exceeds size limit")
            val replacements = cleanPackets.map { packet ->
                packet.range to ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
                    it.writeShort(0xffef); it.writeShort((packet.range.length - 2).toInt()); it.write(ByteArray((packet.range.length - 4).toInt()))
                } }.toByteArray()
            } + indexes.map { index -> index.range to rewriteMpf(index, insertedLength, blockLength) }
            DataOutputStream(temporary.outputStream().buffered()).use { out ->
                out.writeShort(0xffd8)
                out.write(newXmp)
                relocated.forEach { out.write(it) }
                var cursor = 2L
                for ((range, bytes) in replacements.sortedBy { it.first.offset }) {
                    copyRange(cover, ByteRange(cursor, range.offset - cursor), out)
                    out.write(bytes)
                    cursor = range.offset + range.length
                }
                copyRange(cover, ByteRange(cursor, coverJpeg.end - cursor), out)
                out.write(MAGIC)
                out.writeInt(1)
                out.writeLong(canonical.length())
                out.writeInt(document.size)
                out.write(originalHash)
                out.write(documentHash)
                copyRange(canonical, ByteRange(0, canonical.length()), out)
                out.write(document)
                copyRange(cover, ByteRange(coverJpeg.end, tailLength), out)
                if (motionRange != null) copyRange(motionFile, motionRange, out)
            }
            ensure(temporary.length() == outputLength, "Container length changed while writing")
            val verified = inspect(temporary)
            readMpf(temporary, readJpeg(temporary)) // Re-read patched sizes, ranges, and actual SOIs.
            if (motion != null) {
                ensure(hash(motionFile, motionRange!!).contentEquals(hash(temporary, verified.motion!!.range)), "Motion video changed while writing")
            }
            replace(temporary, destination)
            PackageResult(verified, originalHash.hex())
        } finally {
            temporary.delete()
            canonicalCopies.forEach { it.delete() }
        }
    }

    private fun readMpf(file: File, jpeg: Jpeg, allowStaleCameraPrimaryLength: Boolean = false): List<Mpf> {
        ensure(jpeg.mpfSegments.size <= 1, "Multiple MPF indexes are unsupported")
        return jpeg.mpfSegments.map { range ->
            val bytes = ByteArray(range.length.toInt())
            RandomAccessFile(file, "r").use { it.seek(range.offset); it.readFully(bytes) }
            ensure(bytes.size >= 16, "Truncated MPF TIFF header")
            val order = when (String(bytes, 8, 2, Charsets.US_ASCII)) {
                "II" -> ByteOrder.LITTLE_ENDIAN
                "MM" -> ByteOrder.BIG_ENDIAN
                else -> throw PhotoContainerException("Invalid MPF byte order")
            }
            val data = ByteBuffer.wrap(bytes).order(order)
            fun ushort(at: Int): Int { ensure(at >= 8 && at <= bytes.size - 2, "MPF short outside segment"); return data.getShort(at).toInt() and 65535 }
            fun uint(at: Int): Long { ensure(at >= 8 && at <= bytes.size - 4, "MPF integer outside segment"); return data.getInt(at).toLong() and 0xffffffffL }
            fun address(relative: Long, length: Int): Int {
                ensure(relative >= 8 && relative <= bytes.size - 8L - length, "MPF address outside segment")
                return relative.toInt() + 8
            }
            ensure(ushort(10) == 42, "Invalid MPF TIFF signature")
            val ifd = address(uint(12), 2)
            val count = ushort(ifd)
            ensure(count in 1..128 && ifd.toLong() + 2 + count * 12 + 4 <= bytes.size, "Invalid MPF index size")
            var imageCount: Long? = null
            var entriesAt: Int? = null
            var entryBytes: Long? = null
            for (i in 0 until count) {
                val at = ifd + 2 + i * 12
                when (ushort(at)) {
                    0xb001 -> {
                        ensure(imageCount == null && ushort(at + 2) == 4 && uint(at + 4) == 1L, "Invalid MPF image count field")
                        imageCount = uint(at + 8)
                    }
                    0xb002 -> {
                        ensure(entriesAt == null && ushort(at + 2) == 7, "Invalid MPEntry field")
                        entryBytes = uint(at + 4)
                        ensure(entryBytes in 16..1024, "MPEntry array exceeds limit")
                        entriesAt = address(uint(at + 8), entryBytes.toInt())
                    }
                }
            }
            val number = imageCount ?: throw PhotoContainerException("Missing MPF image count")
            val first = entriesAt ?: throw PhotoContainerException("Missing MPEntry array")
            ensure(number in 1..64 && entryBytes == number * 16, "MPEntry count mismatch")
            val entries = IntArray(number.toInt()) { first + it * 16 }
            val images = entries.mapIndexed { i, at ->
                val declaredLength = uint(at + 4)
                val relative = uint(at + 8)
                val start = if (i == 0) 0L else range.offset + 8 + relative
                // Camera files keep the pre-metadata primary length. Accept this only for
                // Xiaomi inspection; actual parsed EOI and adjacent gainmap still determine bounds.
                val stalePrimary = i == 0 && allowStaleCameraPrimaryLength && declaredLength in 4 until jpeg.end
                val length = if (stalePrimary) jpeg.end else declaredLength
                ensure(length >= 4 && start <= file.length() && length <= file.length() - start, "MPF image outside file")
                if (i == 0) ensure(relative == 0L && length == jpeg.end, "MPF primary size does not match JPEG")
                else ensure(start >= jpeg.end, "MPF secondary overlaps primary")
                RandomAccessFile(file, "r").use {
                    it.seek(start); ensure(it.readUnsignedShort() == 0xffd8, "MPF image has no SOI")
                    it.seek(start + length - 2); ensure(it.readUnsignedShort() == 0xffd9, "MPF image has no EOI at its boundary")
                }
                ByteRange(start, length)
            }
            ensure(images.drop(1).zipWithNext().all { (a, b) -> a.offset + a.length <= b.offset }, "Overlapping MPF secondary images")
            if (allowStaleCameraPrimaryLength && uint(entries[0] + 4) != jpeg.end) {
                ensure(images.size == 2 && images[1].offset == jpeg.end, "Stale camera MPF lacks adjacent gainmap")
            }
            Mpf(range, bytes, order, entries, images)
        }
    }

    private fun rewriteMpf(index: Mpf, insertedLength: Long, recoveryLength: Long): ByteArray {
        val bytes = index.bytes.copyOf()
        val data = ByteBuffer.wrap(bytes).order(index.order)
        data.putInt(index.entries[0] + 4, (index.images[0].length + insertedLength).toInt())
        for (at in index.entries.drop(1)) {
            val old = data.getInt(at + 8).toLong() and 0xffffffffL
            ensure(old + recoveryLength <= 0xffffffffL, "MPF secondary offset overflow")
            data.putInt(at + 8, (old + recoveryLength).toInt())
        }
        return bytes
    }

    private fun readDirectory(file: File, jpeg: Jpeg): List<DirectoryItem> {
        val directories = jpeg.packets.flatMap { elements(it.document, CONTAINER, "Directory") }
        val layouts = directories.map { directory ->
            val items = elements(directory, CONTAINER, "Item")
            ensure(items.size in 1..64, "Invalid container directory")
            var cursor = jpeg.end
            items.mapIndexed { i, item ->
                val semantic = item.getAttributeNS(ITEM, "Semantic")
                val mime = item.getAttributeNS(ITEM, "Mime")
                val length = item.getAttributeNS(ITEM, "Length").takeIf { it.isNotBlank() }?.let { number(it, "Item Length") } ?: 0
                val padding = item.getAttributeNS(ITEM, "Padding").takeIf { it.isNotBlank() }?.let { number(it, "Item Padding") } ?: 0
                ensure(semantic.isNotBlank() && mime.isNotBlank(), "Missing container item type")
                if (i == 0) {
                    ensure(semantic == "Primary" && mime == "image/jpeg" && (length == 0L || length == jpeg.end), "Unsupported primary item")
                    ensure(padding <= file.length() - cursor, "Primary padding exceeds file")
                    cursor += padding
                    DirectoryItem(semantic, mime, ByteRange(0, jpeg.end), padding)
                } else {
                    ensure(semantic != "Primary" && length > 0 && length <= file.length() - cursor && padding <= file.length() - cursor - length, "Invalid secondary item boundary")
                    val range = ByteRange(cursor, length)
                    cursor += length + padding
                    DirectoryItem(semantic, mime, range, padding)
                }
            }
        }
        ensure(layouts.distinct().size <= 1, "Conflicting container directories")
        return layouts.firstOrNull() ?: emptyList()
    }

    private fun coverGainmap(file: File, jpeg: Jpeg, coverLength: Long, indexes: List<Mpf>): ByteRange? {
        val declared = readDirectory(file, jpeg).filter { it.semantic == "GainMap" }
        ensure(declared.size <= 1 && declared.all { it.mime == "image/jpeg" && it.padding == 0L }, "Unsupported gainmap layout")
        val images = indexes.flatMap { it.images.drop(1) }
        if (images.isEmpty()) {
            ensure(declared.isEmpty(), "Gainmap directory has no MPF index")
            return null
        }
        ensure(images.size == 1, "Only one gainmap secondary image is supported")
        val gain = images.single()
        ensure(gain.offset + gain.length == coverLength, "Unrecognized data after gainmap")
        ensure(declared.isNotEmpty() || property(jpeg.packets.map { it.document }, HDRGM, "Version") != null ||
            jpeg.supportedIsoGainmapVersion, "MPF secondary is not identified as a gainmap")
        if (declared.isNotEmpty()) ensure(declared.single().range == gain, "Gainmap directory disagrees with MPF")
        return gain
    }

    private fun readMotion(file: File, jpeg: Jpeg): MotionInfo? {
        val docs = jpeg.packets.map { it.document }
        val micro = property(docs, CAMERA, "MicroVideo")
        val modern = property(docs, CAMERA, "MotionPhoto")
        for (flag in listOf(micro, modern)) ensure(flag == null || flag == "0" || flag == "1", "Unsupported motion flag")
        if (micro != "1" && modern != "1") return null
        for (key in listOf("MicroVideoVersion", "MotionPhotoVersion")) {
            property(docs, CAMERA, key)?.let { ensure(it == "1", "Unsupported motion version") }
        }
        val candidates = mutableListOf<ByteRange>()
        property(docs, CAMERA, "MicroVideoOffset")?.let {
            val length = number(it, "MicroVideoOffset")
            candidates.add(ByteRange(file.length() - length, length))
        }
        val directory = readDirectory(file, jpeg)
        for ((i, item) in directory.withIndex()) if (item.semantic == "MotionPhoto") {
            ensure(item.mime == "video/mp4", "Unsupported motion MIME")
            ensure(i == directory.lastIndex && item.padding == 0L, "Motion video must be the final item")
            candidates.add(motionRange(file, jpeg, directory, docs, item.range))
        }
        ensure(candidates.isNotEmpty(), "Motion photo has no supported video location")
        ensure(candidates.distinct().size == 1, "Conflicting motion video locations")
        val range = candidates.first()
        ensure(range.offset >= jpeg.end && range.length > 0 && range.offset + range.length == file.length(), "Invalid motion range")
        validateMp4(file, range)
        val times = listOfNotNull(property(docs, CAMERA, "MicroVideoPresentationTimestampUs"), property(docs, CAMERA, "MotionPhotoPresentationTimestampUs")).map {
            it.toLongOrNull()?.takeIf { value -> value >= -1 } ?: throw PhotoContainerException("Invalid motion timestamp")
        }
        ensure(times.distinct().size <= 1, "Conflicting motion timestamps")
        return MotionInfo(range, times.firstOrNull(), if (modern == "1") "MotionPhoto" else "MicroVideo")
    }

    private fun motionRange(file: File, jpeg: Jpeg, directory: List<DirectoryItem>, docs: List<Document>, declared: ByteRange): ByteRange {
        if (declared.offset + declared.length == file.length()) return declared
        // Xiaomi's camera omits its watermark recovery subimage from the Google
        // Directory. MediaEditor Lzv/a.c locates the final MP4 from EOF - Length.
        // Accept that layout only when the private metadata accounts for the
        // entire gap; never scan arbitrary JPEG bytes for an ftyp signature.
        val videoStart = file.length() - declared.length
        val metadata = property(docs, "http://ns.xiaomi.com/photos/1.0/camera/", "XMPMeta")
        if (metadata != null) {
            val recovery = XiaomiRecoveryMetadata.parsePayload(metadata)
            ensure(!recovery.unsupported, "Unsupported Xiaomi motion gap metadata")
            recovery.validatedRanges(file.length(), declared.offset, videoStart)
            return ByteRange(videoStart, declared.length)
        }
        // Some HDR editors retain only Primary/MotionPhoto in the directory, omitting
        // the MPF-indexed gainmap. Metadata insertion also leaves the primary MPEntry
        // length stale, while its TIFF-relative secondary offset remains correct.
        // Accept only a complete, independently identified HDR JPEG filling the exact
        // gap. Do not infer a video from an arbitrary ftyp signature or ignore bytes.
        ensure(directory.size == 2 && directory.first().padding == 0L &&
            declared.offset == jpeg.end && jpeg.mpfSegments.size == 1, "Invalid motion range")
        val indexes = readMpf(file, jpeg, allowStaleCameraPrimaryLength = true)
        val gain = coverGainmap(file, jpeg, videoStart, indexes)
            ?: throw PhotoContainerException("Motion gap has no identified gainmap")
        ensure(gain.offset == jpeg.end, "Motion gainmap must immediately follow its primary")
        val secondary = readJpeg(file, gain.offset, gain.length)
        ensure(secondary.end == videoStart, "Motion gainmap has unexplained trailing data")
        val adobe = property(docs, HDRGM, "Version") == "1.0" &&
            property(secondary.packets.map { it.document }, HDRGM, "Version") == "1.0"
        val iso = jpeg.supportedIsoGainmapVersion && secondary.isoGainmapSegments.size == 1 &&
            secondary.isoGainmapSegments.single().length > ISO_GAINMAP_PREFIX.size + 8
        ensure(adobe || iso, "Motion gap lacks supported gainmap parameters")
        return ByteRange(videoStart, declared.length)
    }

    private fun validateMp4(file: File, range: ByteRange) {
        RandomAccessFile(file, "r").use { input ->
            var cursor = range.offset
            val end = range.offset + range.length
            var count = 0
            while (cursor < end) {
                ensure(++count <= 100_000 && end - cursor >= 8, "Invalid MP4 box boundary")
                input.seek(cursor)
                var size = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                var header = 8L
                if (size == 1L) {
                    ensure(end - cursor >= 16, "Truncated MP4 extended box")
                    size = input.readLong()
                    header = 16
                } else if (size == 0L) size = end - cursor
                ensure(size >= header && size <= end - cursor, "MP4 box exceeds video range")
                if (count == 1) ensure(type == "ftyp" && size >= header + 8, "Motion video is not an ISO MP4")
                cursor += size
            }
            ensure(count >= 2, "MP4 has no media boxes")
        }
    }

    private fun readReversible(file: File, jpeg: Jpeg, motion: MotionInfo?): ReversibleInfo? {
        val docs = jpeg.packets.map { it.document }
        val hasStar = docs.any { hasNamespace(it, STAR) }
        if (!hasStar) return null
        val version = property(docs, STAR, "Version")
        ensure(version == "1", "Unsupported or missing recovery version")
        val offset = number(property(docs, STAR, "Offset") ?: "", "Recovery Offset")
        val length = number(property(docs, STAR, "Length") ?: "", "Recovery Length")
        ensure(offset <= file.length() && length >= HEADER_SIZE && length <= offset, "Invalid recovery range")
        val start = file.length() - offset
        val end = start + length
        ensure(start == jpeg.end && end <= (motion?.range?.offset ?: file.length()), "Recovery block overlaps media")
        val directory = readDirectory(file, jpeg)
        ensure(directory.isNotEmpty() && directory.first().padding >= length, "Recovery block is not described by Primary Padding")
        ensure(directory.drop(1).all { it.range.offset >= end }, "Recovery block overlaps a secondary item")
        val last = directory.last()
        ensure(last.range.offset + last.range.length + last.padding == file.length(), "Recovery container has unaccounted trailing data")
        return RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
            ensure(magic.contentEquals(MAGIC) && input.readInt() == 1, "Invalid recovery header")
            val originalLength = input.readLong()
            val documentLength = input.readInt()
            ensure(originalLength > 0 && originalLength <= MAX_FILE && documentLength in 0..MAX_DOCUMENT, "Invalid recovery payload sizes")
            ensure(HEADER_SIZE + originalLength + documentLength == length, "Recovery payload length mismatch")
            val originalHash = ByteArray(32).also { input.readFully(it) }
            val documentHash = ByteArray(32).also { input.readFully(it) }
            val originalRange = ByteRange(start + HEADER_SIZE, originalLength)
            ensure(hash(file, originalRange).contentEquals(originalHash), "Original checksum mismatch")
            input.seek(originalRange.offset + originalRange.length)
            val document = ByteArray(documentLength).also { input.readFully(it) }
            ensure(MessageDigest.getInstance("SHA-256").digest(document).contentEquals(documentHash), "Editing document checksum mismatch")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            ReversibleInfo(originalRange, decoder.decode(ByteBuffer.wrap(document)).toString(), originalHash.hex(), 1)
        }
    }

    private fun readJpeg(file: File, offset: Long = 0, length: Long = file.length() - offset): Jpeg {
        ensure(offset >= 0 && length >= 2 && offset <= file.length() && length <= file.length() - offset, "Invalid JPEG range")
        val end = offset + length
        file.inputStream().buffered(64 * 1024).use { input ->
            ensure(input.skip(offset) == offset, "Cannot seek JPEG range")
            if (input.read() != 0xff || input.read() != 0xd8) return Jpeg(0, emptyList())
            var position = offset + 2
            var segments = 0
            var metadataBytes = 0L
            var xmpBytes = 0L
            var inScan = false
            var hasDimensions = false
            val packets = mutableListOf<Xmp>()
            val mpfs = mutableListOf<ByteRange>()
            val exifs = mutableListOf<ByteRange>()
            val isoGainmaps = mutableListOf<ByteRange>()
            var supportedIsoGainmapVersion = false
            fun byte(): Int {
                ensure(position < end, "Truncated JPEG")
                val value = input.read()
                ensure(value >= 0, "Truncated JPEG")
                position++
                return value
            }
            while (true) {
                var value = byte()
                if (inScan && value != 0xff) continue
                ensure(value == 0xff, "Invalid JPEG marker")
                var markerStart = position - 1
                do { value = byte(); if (value == 0xff) markerStart++ } while (value == 0xff)
                if (inScan && (value == 0 || value in 0xd0..0xd7)) continue
                ensure(value != 0 && value != 0xd8, "Unexpected JPEG marker")
                if (value == 0xd9) {
                    ensure(hasDimensions, "JPEG has no valid dimensions")
                    return Jpeg(position, packets, mpfs, exifs, isoGainmaps, supportedIsoGainmapVersion)
                }
                ensure(++segments <= 4096, "Too many JPEG segments")
                ensure(value !in 0xd0..0xd7 && value != 1, "Unexpected standalone JPEG marker")
                val length = (byte() shl 8) or byte()
                ensure(length >= 2 && length - 2 <= end - position, "Invalid JPEG segment length")
                metadataBytes += length
                ensure(metadataBytes <= 16 * 1024 * 1024, "JPEG metadata exceeds limit")
                val payload = ByteArray(length - 2)
                var consumed = 0
                while (consumed < payload.size) {
                    val read = input.read(payload, consumed, payload.size - consumed)
                    ensure(read > 0, "Truncated JPEG segment")
                    consumed += read
                    position += read
                }
                if (value in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                    ensure(payload.size >= 6, "Truncated JPEG dimensions")
                    val height = ((payload[1].toInt() and 255) shl 8) or (payload[2].toInt() and 255)
                    val width = ((payload[3].toInt() and 255) shl 8) or (payload[4].toInt() and 255)
                    ensure(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 520_000_000, "JPEG dimensions exceed limit")
                    hasDimensions = true
                }
                if (value == 0xe1 && payload.startsWith(XMP_PREFIX)) {
                    xmpBytes += payload.size
                    ensure(xmpBytes <= 2 * 1024 * 1024, "XMP metadata exceeds limit")
                    val xml = payload.copyOfRange(XMP_PREFIX.size, payload.size).toString(Charsets.UTF_8)
                    packets.add(Xmp(ByteRange(markerStart, length.toLong() + 2), parseXml(xml)))
                }
                if (value == 0xe2 && payload.startsWith(byteArrayOf(77, 80, 70, 0))) mpfs.add(ByteRange(markerStart, length.toLong() + 2))
                if (value == 0xe2 && payload.startsWith(ISO_GAINMAP_PREFIX)) {
                    isoGainmaps.add(ByteRange(markerStart, length.toLong() + 2))
                    if (payload.size == ISO_GAINMAP_PREFIX.size + 4 && payload.takeLast(4).all { it == 0.toByte() }) {
                        supportedIsoGainmapVersion = true
                    }
                }
                if (value == 0xe1 && payload.startsWith(byteArrayOf(69, 120, 105, 102, 0, 0))) exifs.add(ByteRange(markerStart, length.toLong() + 2))
                inScan = value == 0xda
            }
        }
    }

    private fun parseXml(xml: String): Document {
        // Reject declarations before invoking any platform parser, including Android parsers
        // that do not implement all JAXP feature switches. No DTD means no custom entities.
        ensure(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true) && !xml.contains('\u0000'), "XML declarations are not allowed")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
            try { factory.setFeature(feature, false) } catch (_: javax.xml.parsers.ParserConfigurationException) { /* Lexical DTD ban remains enforced. */ }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw PhotoContainerException("External XML entities are forbidden") }
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
        })
        val doc = builder.parse(InputSource(StringReader(xml)))
        val stack = java.util.ArrayDeque<Pair<Node, Int>>()
        stack.push(doc to 0)
        var count = 0
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.pop()
            ensure(++count <= 20_000 && depth <= 64, "XML structure exceeds limit")
            for (i in 0 until node.childNodes.length) stack.push(node.childNodes.item(i) to depth + 1)
        }
        return doc
    }

    private fun property(docs: List<Document>, namespace: String, name: String): String? {
        val values = mutableSetOf<String>()
        for (doc in docs) {
            for (element in elements(doc, "*", "*")) {
                if (element.hasAttributeNS(namespace, name)) values.add(element.getAttributeNS(namespace, name).trim())
                if (element.namespaceURI == namespace && element.localName == name) values.add(element.textContent.trim())
            }
        }
        ensure(values.size <= 1, "Conflicting XMP property: $name")
        return values.firstOrNull()
    }

    private fun elements(node: Node, namespace: String, name: String): List<Element> {
        val nodes = if (node is Document) node.getElementsByTagNameNS(namespace, name) else (node as Element).getElementsByTagNameNS(namespace, name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun hasNamespace(doc: Document, namespace: String): Boolean = elements(doc, "*", "*").any { element ->
        element.namespaceURI == namespace || (0 until element.attributes.length).any { element.attributes.item(it).namespaceURI == namespace }
    }

    private fun isContainerNamespace(namespace: String?): Boolean = namespace in setOf(STAR, CAMERA, CONTAINER, ITEM) || namespace?.startsWith("http://ns.xiaomi.com/photos/1.0/camera/") == true
    private fun hasContainerMetadata(doc: Document): Boolean = elements(doc, "*", "*").any { element ->
        isContainerNamespace(element.namespaceURI) || (0 until element.attributes.length).any { isContainerNamespace(element.attributes.item(it).namespaceURI) }
    }

    private fun cleanMetadata(doc: Document): ByteArray? {
        for (element in elements(doc, "*", "*").asReversed()) {
            if (isContainerNamespace(element.namespaceURI)) {
                element.parentNode?.removeChild(element)
            } else {
                for (i in element.attributes.length - 1 downTo 0) {
                    val attribute = element.attributes.item(i)
                    if (isContainerNamespace(attribute.namespaceURI) || (attribute.namespaceURI == "http://www.w3.org/2000/xmlns/" && isContainerNamespace(attribute.nodeValue))) element.removeAttributeNode(attribute as org.w3c.dom.Attr)
                }
            }
        }
        // Preserve unrelated XMP (including HDR namespaces) in a newly inserted packet.
        val hasContent = elements(doc, "*", "*").any { element ->
            element.namespaceURI !in setOf("adobe:ns:meta/", "http://www.w3.org/1999/02/22-rdf-syntax-ns#") ||
                (0 until element.attributes.length).any { element.attributes.item(it).namespaceURI !in setOf("http://www.w3.org/2000/xmlns/", "http://www.w3.org/1999/02/22-rdf-syntax-ns#") }
        }
        if (!hasContent) return null
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }.transform(DOMSource(doc), StreamResult(writer))
        return xmpSegment(writer.toString())
    }

    private fun outputXmp(blockLength: Long, videoLength: Long, timestamp: Long?, padding: Long, gainmapLength: Long,
        adobeGainmap: Boolean = false): String {
        val motionAttributes = if (videoLength > 0) "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"$videoLength\"" +
            (timestamp?.let { " GCamera:MotionPhotoPresentationTimestampUs=\"$it\" GCamera:MicroVideoPresentationTimestampUs=\"$it\"" } ?: "") else ""
        val gainmapItem = if (gainmapLength > 0) "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"GainMap\" Item:Length=\"$gainmapLength\"/></rdf:li>" else ""
        val videoItem = if (videoLength > 0) "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\"/></rdf:li>" else ""
        val directory = "<Container:Directory><rdf:Seq><rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"$padding\"/></rdf:li>$gainmapItem$videoItem</rdf:Seq></Container:Directory>"
        val recovery = if (blockLength > 0) "StarMark:Version=\"1\" StarMark:Offset=\"${padding + gainmapLength + videoLength}\" StarMark:Length=\"$blockLength\"" else ""
        // Android 14's JPEG decoder reads only the first standard XMP packet. Its primary
        // declaration must identify the real Adobe gainmap; parameters remain in the exact
        // secondary JPEG. Never synthesize Adobe metadata for an ISO-only source.
        val hdr = if (adobeGainmap && gainmapLength > 0) "xmlns:hdrgm=\"$HDRGM\" hdrgm:Version=\"1.0\"" else ""
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:StarMark=\"$STAR\" xmlns:GCamera=\"$CAMERA\" xmlns:Container=\"$CONTAINER\" xmlns:Item=\"$ITEM\" $hdr $recovery $motionAttributes>$directory</rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    private fun xmpSegment(xml: String): ByteArray {
        val body = XMP_PREFIX + xml.toByteArray(Charsets.UTF_8)
        ensure(body.size + 2 <= 65535, "XMP packet exceeds JPEG segment limit")
        return ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { it.writeShort(0xffe1); it.writeShort(body.size + 2); it.write(body) } }.toByteArray()
    }

    private fun copyRange(file: File, range: ByteRange, out: OutputStream) {
        ensure(range.offset >= 0 && range.length >= 0 && range.offset <= file.length() && range.length <= file.length() - range.offset, "Invalid copy range")
        RandomAccessFile(file, "r").use { input ->
            input.seek(range.offset)
            val bytes = ByteArray(64 * 1024)
            var remaining = range.length
            while (remaining > 0) {
                val read = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                ensure(read > 0, "Source changed while copying")
                out.write(bytes, 0, read)
                remaining -= read
            }
        }
    }

    private fun hash(file: File, range: ByteRange): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        copyRange(file, range, object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { digest.update(bytes, offset, length) }
        })
        return digest.digest()
    }
    private fun number(value: String, label: String): Long = value.toLongOrNull()?.takeIf { it >= 0 && it <= MAX_FILE } ?: throw PhotoContainerException("Invalid $label")
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun ensure(condition: Boolean, message: String) { if (!condition) throw PhotoContainerException(message) }
    private fun checkFile(file: File) { ensure(file.isFile && file.length() in 1..MAX_FILE, "Photo is missing, empty, or too large") }
    private fun requireDifferent(source: File, destination: File) { ensure(source.canonicalFile != destination.canonicalFile, "Output must not overwrite its source") }
    private fun temporaryFor(destination: File): File = File.createTempFile(".starmark-", ".tmp", destination.absoluteFile.parentFile)
    private fun replace(temporary: File, destination: File) { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    private inline fun <T> guarded(action: () -> T): T = try { action() } catch (e: PhotoContainerException) { throw e } catch (e: Exception) { throw PhotoContainerException("Invalid or unreadable photo container: ${e.message}", e) }
}
