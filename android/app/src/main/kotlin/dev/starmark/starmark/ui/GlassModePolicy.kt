package dev.starmark.starmark.ui

/** Selection is retained separately from capability so temporary unavailability
 * does not silently overwrite a user's preference. */
internal enum class GlassMode(val id: String, val title: String) {
    Frosted("frost", "柔光磨砂"),
    SystemGlass("glass", "柔光玻璃"),
    Off("off", "关"),
}

internal fun resolveGlassMode(requested: String?, legacy: Boolean?, supported: Boolean): GlassMode {
    val mode = requested ?: if (legacy == false) "off" else "auto"
    return when (mode) {
        "auto" -> if (supported) GlassMode.SystemGlass else GlassMode.Frosted
        "glass" -> if (supported) GlassMode.SystemGlass else GlassMode.Frosted
        "off" -> GlassMode.Off
        else -> GlassMode.Frosted
    }
}

internal fun glassModeChoices(supported: Boolean): List<GlassMode> =
    if (supported) listOf(GlassMode.Frosted, GlassMode.SystemGlass, GlassMode.Off)
    else listOf(GlassMode.Frosted, GlassMode.Off)

internal fun isSystemGlassReady(sdk: Int, blur: Boolean, bionic: Boolean, enabled: Boolean, materialStyle: Int, methods: Boolean): Boolean =
    sdk >= 33 && blur && bionic && enabled && materialStyle == 1 && methods

private val hyperOsLabel = Regex("""^(?:Xiaomi\s+)?(?:Hyper)?OS\s*""", RegexOption.IGNORE_CASE)
private val majorVersion = Regex("""^\d+(?=\.|$)""")
private val xiaomiBuildPrefix = Regex("""^\d+\.\d+\.\d+\.\d+\.[A-Za-z]""")
private val legacyMiuiLabel = Regex("""^(?:MIUI\s*|V)(\d+)(?=\.|$)""", RegexOption.IGNORE_CASE)

/** Prefer the ROM's own version fields. Android's generic incremental value is a
 * fallback only when it looks like a named HyperOS or a Xiaomi OTA build. */
internal fun usesHyperOsNavigationOptics(
    brand: String,
    osVersions: List<String?>,
    androidIncremental: String,
    legacyMiuiVersion: String? = null,
): Boolean {
    val productBrand = brand.trim()
    if (!productBrand.equals("Xiaomi", true) && !productBrand.equals("Redmi", true)) return false
    fun major(value: String?) = value?.trim()?.let { majorVersion.find(it.replaceFirst(hyperOsLabel, ""))?.value?.toIntOrNull() }
    val declaredMajor = osVersions.firstNotNullOfOrNull(::major)
    if (declaredMajor != null) return declaredMajor >= 4
    val build = androidIncremental.trim()
    val buildMajor = major(build) ?: return false
    if (buildMajor < 4) return false
    if (hyperOsLabel.containsMatchIn(build)) return true
    // A legacy MIUI numeric OTA can have the same four-part spelling.
    val miuiMajor = legacyMiuiLabel.find(legacyMiuiVersion.orEmpty().trim())?.groupValues?.get(1)?.toIntOrNull()
    return xiaomiBuildPrefix.containsMatchIn(build) && buildMajor != miuiMajor
}

internal enum class PortableMaterialBackend { Solid, Native, Haze, Miuix }
/** Runtime result only: never writes or normalizes the persisted request. */
internal fun portableMaterialBackend(enabled: Boolean, native: Boolean, source: Boolean, shader: Boolean, genericEnabled: Boolean): PortableMaterialBackend = when {
    !enabled -> PortableMaterialBackend.Solid
    native -> PortableMaterialBackend.Native
    !source || !shader -> PortableMaterialBackend.Solid
    genericEnabled -> PortableMaterialBackend.Haze
    else -> PortableMaterialBackend.Miuix
}
