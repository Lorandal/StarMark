package dev.starmark.starmark.ui

/** System glass only needs a texture when a local Miuix fallback can consume it. */
internal fun backdropRecordingNeeded(
    effectsEnabled: Boolean,
    systemGlass: Boolean,
    shaderSupported: Boolean,
    systemGlassLocalFallbackPossible: Boolean,
): Boolean = effectsEnabled && shaderSupported && (!systemGlass || systemGlassLocalFallbackPossible)
