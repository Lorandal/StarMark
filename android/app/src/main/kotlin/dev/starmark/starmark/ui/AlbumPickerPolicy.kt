package dev.starmark.starmark.ui

internal enum class AlbumAccess { Full, Limited, Denied }

internal fun shouldRequestMediaLocation(sdk: Int, granted: Boolean, alreadyRequested: Boolean): Boolean =
    sdk >= 29 && !granted && !alreadyRequested

internal fun albumAccess(sdk: Int, imagesGranted: Boolean, selectedGranted: Boolean): AlbumAccess = when {
    imagesGranted -> AlbumAccess.Full
    sdk >= 34 && selectedGranted -> AlbumAccess.Limited
    else -> AlbumAccess.Denied
}

internal fun toggleAlbumSelection(current: List<String>, uri: String, multiple: Boolean): List<String> = when {
    uri in current -> current - uri
    !multiple -> listOf(uri)
    current.size >= 30 -> current
    else -> current + uri
}

internal fun availableAlbumSelection(current: List<String>, available: Set<String>, multiple: Boolean): List<String> =
    current.distinct().filter { it in available }.take(if (multiple) 30 else 1)
