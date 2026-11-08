package dev.starmark.starmark.ui

internal fun buildTitle(channel: String, versionName: String): String =
    if (channel == "Beta" || channel == "Release") "StarMark\n$versionName" else "StarMark $versionName"

internal fun buildDetails(channel: String, sourceSha256: String, timestamp: String): String {
    when (channel) {
        "Beta" -> return "Beta测试版"
        "Release" -> return "正式版"
    }
    // Delivery injects the hash of the frozen source archive. Studio builds
    // without a snapshot show only the time instead of an unrelated Git HEAD.
    val shortHash = sourceSha256.takeIf {
        channel == "Dev" && it.matches(Regex("[0-9a-f]{64}"))
    }?.take(7)
    return "内部构建版本\n${if (shortHash != null) "$shortHash · " else ""}$timestamp"
}
