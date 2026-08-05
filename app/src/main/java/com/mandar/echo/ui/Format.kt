package com.mandar.echo.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayLong = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
    private val dayShort = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun clock(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(hhmm)

    fun hour(epochMs: Long): Int =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).hour

    fun dayTitle(date: LocalDate): String = when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> date.format(dayLong)
    }

    fun daySubtitle(date: LocalDate): String = date.format(dayLong)

    fun dayShort(date: LocalDate): String = date.format(dayShort)

    /** Compact elapsed duration: 0:07, 1:42:09. */
    fun elapsed(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val total = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun duration(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return when {
            minutes < 1 -> "<1 min"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }

    fun bytes(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1f GB".format(value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.0f MB".format(value / 1_000_000.0)
        value >= 1_000 -> "%.0f KB".format(value / 1_000.0)
        else -> "$value B"
    }

    fun count(value: Int): String = "%,d".format(value)
}
