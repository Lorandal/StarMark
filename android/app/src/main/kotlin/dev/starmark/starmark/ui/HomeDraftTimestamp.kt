package dev.starmark.starmark.ui

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val draftTimestampFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT)

/** Dart's local ISO timestamps have no offset; imported UTC/offset values use the device zone. */
internal fun homeDraftTimestamp(raw: String, zone: ZoneId = ZoneId.systemDefault()): String {
    if (raw.isBlank()) return ""
    return runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone).format(draftTimestampFormat) }
        .recoverCatching { LocalDateTime.parse(raw).format(draftTimestampFormat) }
        .getOrDefault("")
}
