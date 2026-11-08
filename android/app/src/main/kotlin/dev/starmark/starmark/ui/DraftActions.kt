package dev.starmark.starmark.ui

/** A monotonic three-second guard; a resumed dialog cannot enable early. */
internal fun draftDeleteRemainingSeconds(elapsedMillis: Long): Int {
    val remaining = (3_000L - elapsedMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
    return ((remaining + 999L) / 1_000L).toInt()
}
