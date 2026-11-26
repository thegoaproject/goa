package com.goa.ui.savestate

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How save state dates read on screen: "Today 21:14", "Yesterday 09:30", "3 Nov 18:02", "3 hours ago". */
object SaveStateFormat {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    fun date(millis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = at.toLocalDate()
        val time = TIME.format(at)
        return when {
            day == today -> "Today $time"
            day == today.minusDays(1) -> "Yesterday $time"
            day.year == today.year -> "${DAY.format(at)} $time"
            else -> DAY_YEAR.format(at)
        }
    }

    /** Short date for list rows: "Today 21:14", "Yesterday 09:30", "3 Nov". */
    fun shortDate(millis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (day == today || day == today.minusDays(1)) date(millis, now, zone)
        else if (day.year == today.year) DAY.format(day) else DAY_YEAR.format(day)
    }

    fun age(millis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = ((now - millis) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> plural(minutes, "minute")
            minutes < 60 * 24 -> plural(minutes / 60, "hour")
            minutes < 60 * 24 * 30 -> plural(minutes / (60 * 24), "day")
            minutes < 60 * 24 * 365 -> plural(minutes / (60 * 24 * 30), "month")
            else -> plural(minutes / (60 * 24 * 365), "year")
        }
    }

    private fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
}
