package dev.starmark.starmark.updates

import android.content.Context
import android.os.Build
import android.os.Process
import dev.starmark.starmark.BuildConfig
import java.io.IOException
import java.util.zip.ZipFile

/** Reads only this application's APKs. Call off the main thread. */
fun installedAppUpdateInfo(context: Context): Map<String, Any> {
    val application = context.applicationInfo
    val abis = inspectInstalledAppAbis(
        apkPaths = listOfNotNull(application.sourceDir) + application.splitSourceDirs.orEmpty(),
        nativeLibraryDir = application.nativeLibraryDir,
        is64Bit = Process.is64Bit(),
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
    )
    return mapOf(
        "versionName" to BuildConfig.VERSION_NAME,
        "channel" to BuildConfig.BUILD_CHANNEL,
        "versionCode" to BuildConfig.VERSION_CODE,
        "installedAbis" to abis.installedAbis,
        "runtimeAbi" to abis.runtimeAbi,
    )
}

internal data class InstalledAppAbiInfo(val installedAbis: List<String>, val runtimeAbi: String)

private val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
private val flutterLibraryEntry = Regex("lib/([^/]+)/(?:libflutter|libapp)\\.so")

internal fun inspectInstalledAppAbis(
    apkPaths: List<String>,
    nativeLibraryDir: String?,
    is64Bit: Boolean,
    supportedAbis: List<String>,
): InstalledAppAbiInfo {
    val packagedAbis = mutableSetOf<String>()
    for (path in apkPaths.filter { it.isNotBlank() }.distinct()) {
        try {
            ZipFile(path).use { apk ->
                val entries = apk.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val abi = flutterLibraryEntry.matchEntire(entry.name)?.groupValues?.get(1)
                    if (abi in knownAbis) packagedAbis.add(abi!!)
                }
            }
        } catch (_: IOException) {
            // A missing or unreadable base must not hide usable evidence in a split.
        } catch (_: SecurityException) {
            // Process bitness and the application's native directory remain available.
        }
    }

    fun matchesProcess(abi: String): Boolean = abi in knownAbis &&
        (abi == "arm64-v8a" || abi == "x86_64") == is64Bit

    val directoryAbi = when (nativeLibraryDir?.trimEnd('/')?.substringAfterLast('/')) {
        "arm64", "arm64-v8a" -> "arm64-v8a"
        "arm", "armeabi-v7a" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> null
    }
    val runtimeAbi = directoryAbi?.takeIf(::matchesProcess)
        ?: supportedAbis.firstOrNull { it in packagedAbis && matchesProcess(it) }
        ?: knownAbis.firstOrNull { it in packagedAbis && matchesProcess(it) }
        ?: supportedAbis.firstOrNull(::matchesProcess)
        ?: ""
    val installedAbis = knownAbis.filter { it in packagedAbis }.ifEmpty {
        listOfNotNull(runtimeAbi.takeIf { it.isNotEmpty() })
    }
    return InstalledAppAbiInfo(installedAbis, runtimeAbi)
}
