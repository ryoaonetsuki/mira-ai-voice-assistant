package io.github.ryoaonetsuki.mira.network

import io.github.ryoaonetsuki.mira.voice.LanguageUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Provides authoritative date/time info from device clock and default timezone.
 * Injected as the first line of all system prompts to prevent model date hallucinations.
 */
object DateTimeInfo {

    private val BENGALI_DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    fun toBengaliDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(BENGALI_DIGITS[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun getBengaliMonthName(monthZeroIndexed: Int): String {
        return when (monthZeroIndexed) {
            0 -> "জানুয়ারি"
            1 -> "ফেব্রুয়ারি"
            2 -> "মার্চ"
            3 -> "এপ্রিল"
            4 -> "মে"
            5 -> "জুন"
            6 -> "জুলাই"
            7 -> "আগস্ট"
            8 -> "সেপ্টেম্বর"
            9 -> "অক্টোবর"
            10 -> "নভেম্বর"
            11 -> "ডিসেম্বর"
            else -> ""
        }
    }
}

/**
 * Top-level helper function returning the formatted current date/time line:
 * e.g. "Today is Friday, 6 November 2026. Current time is 10:54 AM (Asia/Dhaka, UTC+06:00). Bengali date: ৬ নভেম্বর, ২০২৬."
 * When a non-Bangla language is selected, the Bengali date line is omitted to avoid leaking Bangla into the prompt.
 */
fun currentDateTimeLine(language: String = "Bangla (Bengali)"): String {
    val cal = Calendar.getInstance()
    val now = cal.time

    // Weekday, day, month, year in English
    val weekday = SimpleDateFormat("EEEE", Locale.ENGLISH).format(now)
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val month = SimpleDateFormat("MMMM", Locale.ENGLISH).format(now)
    val year = cal.get(Calendar.YEAR)

    // Current time
    val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(now)

    // Timezone & offset
    val tz = TimeZone.getDefault()
    val zoneId = tz.id
    val offsetMs = tz.getOffset(cal.timeInMillis)
    val sign = if (offsetMs >= 0) "+" else "-"
    val absOffset = Math.abs(offsetMs)
    val hours = absOffset / 3600000
    val minutes = (absOffset % 3600000) / 60000
    val offsetStr = String.format(Locale.ENGLISH, "UTC%s%02d:%02d", sign, hours, minutes)

    val base = "Today is $weekday, $day $month $year. Current time is $time ($zoneId, $offsetStr)."

    return if (LanguageUtils.isBangla(language)) {
        // Bengali date with manual Bengali digits
        val bnDay = DateTimeInfo.toBengaliDigits(day.toString())
        val bnMonth = DateTimeInfo.getBengaliMonthName(cal.get(Calendar.MONTH))
        val bnYear = DateTimeInfo.toBengaliDigits(year.toString())
        "$base Bengali date: $bnDay $bnMonth, $bnYear."
    } else {
        base
    }
}
