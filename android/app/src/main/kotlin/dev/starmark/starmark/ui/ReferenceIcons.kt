package dev.starmark.starmark.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Geometry transcribed from the supplied MiMediaEditor APK; provenance is recorded in
 * docs/research/gallery-ui-audit.md. Every path is monochrome so Icon's caller owns tint.
 * Alignment uses the checked path geometry at full opacity; the caller supplies state tint.
 */
object ReferenceIcons {
    /** Filled navigation variant of bundled Miuix Settings.Regular.
     * Exact outer hexagon and the visible central circle's OUTER contour as its hole.
     * Original viewport and 24dp size; original group Y flip is baked in.
     */
    val SettingsFilled: ImageVector by lazy {
        referenceVector("StarMark.SettingsFilled", 1239.6f, intrinsicSize = 24f, paths = listOf(
            ReferencePath("M643.8,1131.3Q653.8,1129.3,666.8,1123.3Q679.8,1117.3,694.8,1108.3L712.8,1098.3L993.8,940.3Q1023.8,923.3,1037.3,914.8Q1050.8,906.3,1059.8,896.3Q1077.8,877.3,1084.8,853.3Q1088.8,841.3,1089.8,821.3Q1090.8,801.3,1090.8,774.3V465.3Q1090.8,438.3,1089.8,418.8Q1088.8,399.3,1084.8,387.3Q1077.8,364.3,1059.8,343.3Q1051.8,334.3,1038.3,325.8Q1024.8,317.3,1005.8,307.3L993.8,300.3L712.8,141.3Q690.8,128.3,673.3,119.8Q655.8,111.3,643.8,109.3Q619.8,103.3,595.8,109.3Q583.8,111.3,566.3,119.8Q548.8,128.3,526.8,141.3L245.8,300.3Q238.8,304.3,230.8,308.3Q213.8,318.3,200.3,326.8Q186.8,335.3,179.8,343.3Q161.8,364.3,154.8,387.3Q150.8,399.3,149.8,418.8Q148.8,438.3,148.8,465.3V774.3Q148.8,801.3,149.8,821.3Q150.8,841.3,154.8,853.3Q161.8,877.3,179.8,896.3Q187.8,905.3,201.8,914.3Q215.8,923.3,234.8,934.3L245.8,940.3L526.8,1098.3L544.8,1108.3Q559.8,1117.3,572.8,1123.3Q585.8,1129.3,595.8,1131.3Q619.8,1136.3,643.8,1131.3ZM819.8,620.3Q819.8,565.3,792.8,519.8Q765.8,474.3,720.3,447.3Q674.8,420.3,619.8,420.3Q565.8,420.3,519.8,447.3Q473.8,474.3,446.8,519.8Q419.8,565.3,419.8,620.3Q419.8,674.3,446.8,720.3Q473.8,766.3,519.8,793.3Q565.8,820.3,619.8,820.3Q674.8,820.3,720.3,793.3Q765.8,766.3,792.8,720.3Q819.8,674.3,819.8,620.3Z", evenOdd = true),
        ))
    }

    // drawable/common_icon_back_normal (res/jm9.xml); viewport 28 × 28.
    // The open chevron's triangular envelope is right-heavy inside a circular
    // button even when its rectangular bounds are centered. Move the artwork
    // toward its point; retain the APK contour, caller size and full hit area.
    // The vector's automatic mirroring also mirrors this optical correction.
    val Back: ImageVector by lazy {
        referenceVector("Reference.Back", 28f, autoMirror = true, opticalOffsetX = -1.75f, paths = listOf(
            ReferencePath("M8.127,14.146C8.127,13.992 8.176,13.866 8.274,13.768L18.291,3.751C18.403,3.639 18.529,3.583 18.669,3.583C18.809,3.569 18.928,3.611 19.026,3.709L19.635,4.297C19.733,4.409 19.782,4.542 19.782,4.696C19.796,4.85 19.747,4.983 19.635,5.095L10.584,14.146L19.635,23.218C19.747,23.33 19.796,23.456 19.782,23.596C19.768,23.736 19.698,23.869 19.572,23.995L19.194,24.373C18.998,24.569 18.823,24.667 18.669,24.667C18.529,24.681 18.389,24.618 18.249,24.478L8.295,14.545C8.183,14.433 8.127,14.3 8.127,14.146Z"),
        ))
    }

    // drawable/common_navigator_icon_adjust_normal (res/WCg.xml); viewport 28 × 28.
    val Adjust: ImageVector by lazy {
        referenceVector("Reference.Adjust", 28f, intrinsicSize = 24f, paths = listOf(
            ReferencePath("M10.41,16.316C12.195,16.316 13.691,17.549 14.095,19.21H23.488C23.801,19.21 23.959,19.21 24.079,19.271C24.184,19.325 24.269,19.41 24.323,19.516C24.384,19.635 24.384,19.794 24.384,20.11C24.384,20.426 24.384,20.584 24.323,20.704C24.269,20.809 24.184,20.896 24.079,20.949C23.959,21.01 23.801,21.01 23.488,21.01H14.095C13.691,22.671 12.195,23.904 10.41,23.904C8.624,23.904 7.128,22.671 6.724,21.01H4.512C4.199,21.01 4.041,21.01 3.921,20.949C3.816,20.896 3.731,20.809 3.677,20.704C3.616,20.584 3.616,20.426 3.616,20.11C3.616,19.794 3.616,19.635 3.677,19.516C3.731,19.41 3.816,19.325 3.921,19.271C4.041,19.21 4.199,19.21 4.512,19.21H6.724C7.128,17.549 8.625,16.316 10.41,16.316ZM10.41,18.116C9.308,18.116 8.416,19.009 8.416,20.11C8.416,21.212 9.308,22.104 10.41,22.104C11.511,22.104 12.404,21.212 12.404,20.11C12.404,19.009 11.511,18.116 10.41,18.116Z", evenOdd = true),
            ReferencePath("M17.589,4.096C19.374,4.096 20.871,5.329 21.275,6.989H23.488C23.801,6.989 23.959,6.99 24.079,7.051C24.184,7.104 24.269,7.19 24.323,7.295C24.384,7.415 24.384,7.573 24.384,7.89C24.384,8.206 24.384,8.364 24.323,8.483C24.269,8.589 24.184,8.675 24.079,8.729C23.959,8.79 23.801,8.789 23.488,8.789H21.275C20.871,10.45 19.375,11.684 17.589,11.684C15.804,11.684 14.308,10.45 13.904,8.789H4.512C4.199,8.789 4.041,8.79 3.921,8.729C3.816,8.675 3.731,8.589 3.677,8.483C3.616,8.364 3.616,8.206 3.616,7.89C3.616,7.573 3.616,7.415 3.677,7.295C3.731,7.19 3.816,7.104 3.921,7.051C4.041,6.99 4.199,6.989 4.512,6.989H13.904C14.308,5.329 15.804,4.096 17.589,4.096ZM17.589,5.896C16.488,5.896 15.595,6.788 15.595,7.89C15.595,8.991 16.488,9.884 17.589,9.884C18.691,9.884 19.583,8.991 19.583,7.89C19.583,6.788 18.691,5.896 17.589,5.896Z", evenOdd = true),
        ))
    }

    /** Ring only: render at 64dp for the source glyph diameter 24.57dp; tint separately from its background. */
    // drawable/photo_watermark_menu_option_remove_icon (res/YF9.xml); viewport 64 × 64.
    val None: ImageVector by lazy {
        referenceVector("Reference.None", 64f, paths = listOf(
            ReferencePath("M32,19.714C38.785,19.714 44.285,25.215 44.285,32C44.285,38.785 38.785,44.285 32,44.285C25.215,44.285 19.714,38.785 19.714,32C19.714,25.215 25.215,19.714 32,19.714ZM24.19,25.354C22.665,27.144 21.744,29.464 21.743,32C21.743,37.664 26.335,42.256 32,42.256C34.535,42.256 36.854,41.333 38.644,39.809L24.19,25.354ZM32,21.743C29.602,21.744 27.398,22.568 25.652,23.946L40.052,38.346C41.43,36.6 42.256,34.397 42.256,32C42.256,26.335 37.664,21.743 32,21.743Z", evenOdd = true),
        ))
    }

    /** The original 64dp rounded backdrop, split from None to allow independent theme tint. */
    // drawable/photo_watermark_menu_option_remove_icon (res/YF9.xml); viewport 64 × 64.
    val NoneBackground: ImageVector by lazy {
        referenceVector("Reference.NoneBackground", 64f, paths = listOf(
            ReferencePath("M0,19.2C0,12.479 0,9.119 1.308,6.552C2.458,4.294 4.294,2.458 6.552,1.308C9.119,0 12.479,0 19.2,0H44.8C51.521,0 54.881,0 57.448,1.308C59.706,2.458 61.542,4.294 62.692,6.552C64,9.119 64,12.479 64,19.2V44.8C64,51.521 64,54.881 62.692,57.448C61.542,59.706 59.706,61.542 57.448,62.692C54.881,64 51.521,64 44.8,64H19.2C12.479,64 9.119,64 6.552,62.692C4.294,61.542 2.458,59.706 1.308,57.448C0,54.881 0,51.521 0,44.8V19.2Z"),
        ))
    }

    // drawable/wm_layout_vertical_top_checked_view (res/xDQ.xml); viewport 20 × 20.
    val AlignTop: ImageVector by lazy {
        referenceVector("Reference.AlignTop", 20f, paths = listOf(
            ReferencePath("M2.56,2L17.44,2A0.56,0.56 0,0 1,18 2.56L18,3.44A0.56,0.56 0,0 1,17.44 4L2.56,4A0.56,0.56 0,0 1,2 3.44L2,2.56A0.56,0.56 0,0 1,2.56 2z"),
            ReferencePath("M9.563,6.24C9.812,6.168 10.076,6.158 10.329,6.213L10.436,6.24L10.587,6.293C10.728,6.352 10.841,6.426 10.926,6.489C11.03,6.565 11.142,6.661 11.234,6.74L14.15,9.241C14.569,9.601 14.618,10.231 14.258,10.65C13.899,11.069 13.269,11.118 12.849,10.759L11,9.173V18.104C11,18.418 11,18.575 10.939,18.694C10.885,18.8 10.799,18.886 10.694,18.94C10.574,19 10.417,19 10.104,19H9.896C9.582,19 9.425,19 9.305,18.94C9.2,18.886 9.115,18.8 9.061,18.694C9,18.575 9,18.418 9,18.104V9.173L7.15,10.759C6.731,11.118 6.1,11.07 5.741,10.65C5.382,10.231 5.43,9.601 5.849,9.241L8.765,6.74C8.857,6.661 8.969,6.565 9.073,6.489C9.187,6.406 9.349,6.303 9.563,6.24Z"),
        ))
    }

    // drawable/wm_layout_vertical_center_checked_view (res/-vY.xml); viewport 20 × 20.
    val AlignVerticalCenter: ImageVector by lazy {
        referenceVector("Reference.AlignVerticalCenter", 20f, paths = listOf(
            ReferencePath("M2.56,9L17.44,9A0.56,0.56 0,0 1,18 9.56L18,10.44A0.56,0.56 0,0 1,17.44 11L2.56,11A0.56,0.56 0,0 1,2 10.44L2,9.56A0.56,0.56 0,0 1,2.56 9z"),
            ReferencePath("M9.563,6.759C9.813,6.832 10.076,6.841 10.329,6.787L10.436,6.759L10.587,6.707C10.728,6.647 10.841,6.573 10.927,6.51C11.031,6.434 11.142,6.338 11.234,6.259L14.15,3.758C14.569,3.399 14.618,2.768 14.259,2.349C13.899,1.93 13.269,1.881 12.849,2.241L11,3.827V1.895C11,1.582 11,1.425 10.939,1.305C10.885,1.2 10.8,1.114 10.694,1.06C10.574,0.999 10.417,1 10.104,1H9.896C9.583,1 9.425,0.999 9.305,1.06C9.2,1.114 9.115,1.2 9.061,1.305C9,1.425 9,1.582 9,1.895V3.827L7.15,2.241C6.731,1.882 6.1,1.93 5.741,2.349C5.382,2.768 5.43,3.399 5.849,3.758L8.765,6.259C8.857,6.338 8.969,6.434 9.073,6.51C9.187,6.594 9.349,6.697 9.563,6.759Z"),
            ReferencePath("M9.563,13.558C9.813,13.485 10.076,13.476 10.329,13.53L10.436,13.558L10.587,13.61C10.728,13.67 10.841,13.744 10.927,13.807C11.031,13.883 11.142,13.979 11.234,14.058L14.15,16.559C14.57,16.918 14.618,17.548 14.259,17.968C13.899,18.387 13.269,18.435 12.85,18.076L11,16.49V18.422C11,18.735 11,18.892 10.939,19.012C10.886,19.117 10.8,19.203 10.694,19.257C10.575,19.318 10.418,19.317 10.104,19.317H9.896C9.583,19.317 9.425,19.318 9.306,19.257C9.2,19.203 9.115,19.117 9.062,19.012C9.001,18.892 9,18.735 9,18.422V16.49L7.15,18.076C6.731,18.435 6.101,18.387 5.741,17.968C5.382,17.549 5.431,16.918 5.85,16.559L8.766,14.058C8.858,13.979 8.969,13.883 9.073,13.807C9.187,13.723 9.35,13.62 9.563,13.558Z"),
        ))
    }

    // drawable/wm_layout_vertical_bottom_checked_view (res/pgD.xml); viewport 20 × 20.
    val AlignBottom: ImageVector by lazy {
        referenceVector("Reference.AlignBottom", 20f, paths = listOf(
            ReferencePath("M2.56,18L17.44,18A0.56,0.56 0,0 0,18 17.44L18,16.56A0.56,0.56 0,0 0,17.44 16L2.56,16A0.56,0.56 0,0 0,2 16.56L2,17.44A0.56,0.56 0,0 0,2.56 18z"),
            ReferencePath("M9.563,13.76C9.812,13.832 10.076,13.842 10.329,13.787L10.436,13.76L10.586,13.707C10.728,13.648 10.841,13.573 10.926,13.511C11.03,13.434 11.142,13.339 11.234,13.26L14.15,10.759C14.569,10.399 14.618,9.769 14.258,9.35C13.899,8.931 13.268,8.882 12.849,9.241L11,10.827V1.896C11,1.582 11,1.425 10.939,1.306C10.885,1.2 10.799,1.114 10.694,1.061C10.574,1 10.417,1 10.104,1H9.896C9.582,1 9.425,1 9.305,1.061C9.2,1.114 9.115,1.2 9.061,1.306C9,1.425 9,1.582 9,1.896V10.827L7.15,9.241C6.731,8.882 6.1,8.93 5.741,9.35C5.382,9.769 5.43,10.399 5.849,10.759L8.765,13.26C8.857,13.339 8.969,13.434 9.073,13.511C9.187,13.594 9.349,13.697 9.563,13.76Z"),
        ))
    }

    // drawable/wm_layout_horizontal_left_checked_view (res/Xnq.xml); viewport 20 × 20.
    val AlignLeft: ImageVector by lazy {
        referenceVector("Reference.AlignLeft", 20f, paths = listOf(
            ReferencePath("M10,9.56L10,10.44A0.56,0.56 0,0 1,9.44 11L2.56,11A0.56,0.56 0,0 1,2 10.44L2,9.56A0.56,0.56 0,0 1,2.56 9L9.44,9A0.56,0.56 0,0 1,10 9.56z"),
            ReferencePath("M2.56,2L17.44,2A0.56,0.56 0,0 1,18 2.56L18,3.44A0.56,0.56 0,0 1,17.44 4L2.56,4A0.56,0.56 0,0 1,2 3.44L2,2.56A0.56,0.56 0,0 1,2.56 2z"),
            ReferencePath("M2.56,16L17.44,16A0.56,0.56 0,0 1,18 16.56L18,17.44A0.56,0.56 0,0 1,17.44 18L2.56,18A0.56,0.56 0,0 1,2 17.44L2,16.56A0.56,0.56 0,0 1,2.56 16z"),
        ))
    }

    // drawable/wm_layout_horizontal_middle_checked_view (res/jKY.xml); viewport 20 × 20.
    val AlignCenter: ImageVector by lazy {
        referenceVector("Reference.AlignCenter", 20f, paths = listOf(
            ReferencePath("M14,9.56L14,10.44A0.56,0.56 0,0 1,13.44 11L6.56,11A0.56,0.56 0,0 1,6 10.44L6,9.56A0.56,0.56 0,0 1,6.56 9L13.44,9A0.56,0.56 0,0 1,14 9.56z"),
            ReferencePath("M2.56,2L17.44,2A0.56,0.56 0,0 1,18 2.56L18,3.44A0.56,0.56 0,0 1,17.44 4L2.56,4A0.56,0.56 0,0 1,2 3.44L2,2.56A0.56,0.56 0,0 1,2.56 2z"),
            ReferencePath("M2.56,16L17.44,16A0.56,0.56 0,0 1,18 16.56L18,17.44A0.56,0.56 0,0 1,17.44 18L2.56,18A0.56,0.56 0,0 1,2 17.44L2,16.56A0.56,0.56 0,0 1,2.56 16z"),
        ))
    }

    // drawable/wm_layout_horizontal_right_checked_view (res/fcq.xml); viewport 20 × 20.
    val AlignRight: ImageVector by lazy {
        referenceVector("Reference.AlignRight", 20f, paths = listOf(
            ReferencePath("M18,9.56L18,10.44A0.56,0.56 0,0 1,17.44 11L10.56,11A0.56,0.56 0,0 1,10 10.44L10,9.56A0.56,0.56 0,0 1,10.56 9L17.44,9A0.56,0.56 0,0 1,18 9.56z"),
            ReferencePath("M2.56,2L17.44,2A0.56,0.56 0,0 1,18 2.56L18,3.44A0.56,0.56 0,0 1,17.44 4L2.56,4A0.56,0.56 0,0 1,2 3.44L2,2.56A0.56,0.56 0,0 1,2.56 2z"),
            ReferencePath("M2.56,16L17.44,16A0.56,0.56 0,0 1,18 16.56L18,17.44A0.56,0.56 0,0 1,17.44 18L2.56,18A0.56,0.56 0,0 1,2 17.44L2,16.56A0.56,0.56 0,0 1,2.56 16z"),
        ))
    }
}

private data class ReferencePath(val data: String, val evenOdd: Boolean = false)

private fun referenceVector(
    name: String,
    size: Float,
    autoMirror: Boolean = false,
    intrinsicSize: Float = size,
    opticalOffsetX: Float = 0f,
    paths: List<ReferencePath>,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = intrinsicSize.dp,
    defaultHeight = intrinsicSize.dp,
    viewportWidth = size,
    viewportHeight = size,
    autoMirror = autoMirror,
).apply {
    if (opticalOffsetX != 0f) addGroup(name = "opticalAlignment", translationX = opticalOffsetX)
    paths.forEach { path ->
        addPath(
            pathData = PathParser().parsePathString(path.data).toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = if (path.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        )
    }
    if (opticalOffsetX != 0f) clearGroup()
}.build()
