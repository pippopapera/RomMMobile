package com.rommmobile.app.core.util

import java.util.Locale

object Format {
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        val digits = if (v >= 100) 0 else if (v >= 10) 1 else 2
        return String.format(Locale.getDefault(), "%.${digits}f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun eta(seconds: Long): String {
        if (seconds < 0) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
            else -> String.format(Locale.getDefault(), "%d:%02d", m, s)
        }
    }

    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)

    /**
     * RomM reports `first_release_date` in milliseconds (an IGDB seconds value would print as
     * year 14966). Seconds are still accepted so an older server does not show a wrong year.
     */
    fun year(epochValue: Long?): String? {
        if (epochValue == null || epochValue <= 0) return null
        val millis = if (epochValue < 100_000_000_000L) epochValue * 1000 else epochValue
        val year = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).year
        return if (year in 1950..2100) year.toString() else null
    }
}
