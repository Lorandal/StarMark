package dev.starmark.starmark

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.starmark.starmark.ui.AlbumPickerActivity

/** Exported by the supplied Gallery 5.4.2.29 APK; no private provider access. */
internal object XiaomiGalleryPicker {
    const val PACKAGE = "com.miui.gallery"

    fun request(multiple: Boolean) = Intent(Intent.ACTION_GET_CONTENT).apply {
        setPackage(PACKAGE)
        type = "image/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        if (multiple) putExtra("pick-upper-bound", 30)
        // Gallery's own picker argument (present in this APK's AOT strings).
        putExtra("pick-need-origin", true)
    }

    fun resolve(context: Context, multiple: Boolean, theme: String): Intent {
        val request = request(multiple)
        val target = context.packageManager.resolveActivity(request, PackageManager.MATCH_DEFAULT_ONLY)
        if (target?.activityInfo?.exported == true && target.activityInfo.enabled) return request
        // Disabled/missing Gallery on other ROMs must not strand the import flow.
        return Intent(context, AlbumPickerActivity::class.java).apply {
            putExtra(AlbumPickerActivity.EXTRA_MULTIPLE, multiple)
            putExtra(AlbumPickerActivity.EXTRA_THEME, theme)
        }
    }
}
