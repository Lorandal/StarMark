package dev.starmark.starmark

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone

/** Convert the EXIF civil day, without shifting it through the viewer's zone.
 * These are the four lunar substitutions used by the audited OEM templates. */
internal fun xiaomiLunarDate(year: Int, month: Int, day: Int): Map<String, String> {
    require(year in 1..9999 && month in 1..12 && day in 1..31) { "无效照片日期" }
    val zone = TimeZone.getTimeZone("Asia/Shanghai")
    val civil = GregorianCalendar(zone).apply {
        isLenient = false
        clear()
        set(year, month - 1, day, 12, 0, 0)
    }
    val lunar = ChineseCalendar(zone).apply { timeInMillis = civil.timeInMillis }
    val cycleYear = lunar.get(Calendar.YEAR) - 1
    val months = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    val days = listOf("初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十")
    return mapOf(
        "yearGan" to "甲乙丙丁戊己庚辛壬癸"[cycleYear % 10].toString(),
        "yearZhi" to "子丑寅卯辰巳午未申酉戌亥"[cycleYear % 12].toString(),
        "monthInChinese" to (if (lunar.get(Calendar.IS_LEAP_MONTH) == 1) "闰" else "") + months[lunar.get(Calendar.MONTH)],
        "dayInChinese" to days[lunar.get(Calendar.DAY_OF_MONTH) - 1],
    )
}
