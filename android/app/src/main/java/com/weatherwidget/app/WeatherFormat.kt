package com.weatherwidget.app

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shared formatting for both the widget and MainActivity, so the two never drift apart. */
object WeatherFormat {
    // 24-hour, zero-padded, no AM/PM — by request, everywhere a clock time shows up
    // in the app or widget.
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val lastUpdatedFormatter = DateTimeFormatter.ofPattern("HH:mm · MMM d · zzz", Locale.getDefault())

    /** Open-Meteo returns sunrise/sunset as local time already (timezone=auto), e.g. "2026-08-22T06:32". */
    fun clockTime(isoLocalDateTime: String): String = try {
        LocalDateTime.parse(isoLocalDateTime).format(clockFormatter)
    } catch (e: Exception) {
        "--:--"
    }

    /** Everything is fetched/stored in Fahrenheit; this is the only place a Celsius view ever gets computed. */
    fun tempString(tempF: Double, fahrenheit: Boolean): String {
        val value = if (fahrenheit) tempF else (tempF - 32.0) * 5.0 / 9.0
        return "${Math.round(value)}°"
    }

    fun highLowString(highF: Double, lowF: Double, fahrenheit: Boolean): String =
        "H:${tempString(highF, fahrenheit)}  L:${tempString(lowF, fahrenheit)}"

    /** -1 means "unknown" — MET Norway (the backup source) has no rain chance outside the Nordics. */
    fun rainChanceString(percent: Int): String = if (percent < 0) "Rain: --%" else "Rain: $percent%"

    /** For the tiny widget status line: "Open-Meteo unavailable — averaging …" → "Open-Meteo down",
     *  "… — showing MET Norway" → "MET Norway only". */
    fun shortNotice(notice: String): String = when {
        "averaging" in notice -> notice.substringBefore(" unavailable") + " down"
        "showing " in notice -> notice.substringAfter("showing ") + " only"
        else -> notice
    }

    fun rainPct(percent: Int): String = if (percent < 0) "--%" else "$percent%"
    fun pct(value: Double?): String = value?.let { "${Math.round(it)}%" } ?: "--"
    fun mph(value: Double?): String = value?.let { "${Math.round(it)} mph" } ?: "--"

    private val compass = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun compass(degrees: Double): String = compass[(Math.round(degrees / 22.5) % 16).toInt()]

    /** Minutes after midnight of a local "yyyy-MM-ddTHH:mm" time, or null. */
    fun minutesOf(iso: String): Int? = try {
        LocalDateTime.parse(iso).let { it.hour * 60 + it.minute }
    } catch (e: Exception) {
        null
    }

    // Wind speed (mph) rather than a percentage — there's no such thing as "wind
    // percentage" in the data Open-Meteo provides, so this reports the real number.
    fun windString(speedMph: Double): String = "Wind: ${Math.round(speedMph)}mph"

    /** "Updated just now" / "Updated 14m ago" / "Updated 3h ago" — no need to pull in a date library for this. */
    fun updatedAgoString(fetchedAt: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = (nowMillis - fetchedAt) / 60_000
        return when {
            minutes < 1 -> "Updated just now"
            minutes < 60 -> "Updated ${minutes}m ago"
            else -> "Updated ${minutes / 60}h ago"
        }
    }

    /** e.g. "15:45 · Aug 23 · PDT" — the widget's bottom status line once data has loaded. */
    fun lastUpdatedTimestamp(fetchedAt: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(fetchedAt), ZoneId.systemDefault()).format(lastUpdatedFormatter)
}
