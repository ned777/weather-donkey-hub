package com.weatherwidget.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The app's only timer. Every 3 hours on the clock (12, 3, 6, 9 …) it picks up the
 * server's scheduled snapshot for every tab (Current at its last known spot, plus each
 * saved city), then repaints the widgets. Opening the app never fetches. Pull-down and
 * widget taps fetch right away, but they never move this schedule.
 */
object ScheduledRefresh {
    const val EVERY_HOURS = 3
    private const val ACTION = "com.weatherwidget.app.ACTION_SCHEDULED_REFRESH"
    // A little after the hour, so the server's own scheduled fetch is done first.
    private const val DELAY_AFTER_HOUR_MS = 60_000L

    /** When the current 3-hour slot started, in epoch ms. */
    fun slotStartMillis(now: LocalDateTime = LocalDateTime.now()): Long {
        val start = now.withMinute(0).withSecond(0).withNano(0).minusHours((now.hour % EVERY_HOURS).toLong())
        return start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun nextSlotMillis(): Long = slotStartMillis() + EVERY_HOURS * 3_600_000L

    /** Sets (or resets) the alarm for the next slot. Safe to call as often as you like. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Fires even while the phone is idle; Android may hold it a few minutes to save battery.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextSlotMillis() + DELAY_AFTER_HOUR_MS, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ScheduledRefreshReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** True when some tab's weather is from before the current slot (e.g. the phone was off at 3:00). */
    fun isBehind(context: Context): Boolean {
        val start = slotStartMillis()
        val ids = LocationStore.list(context).map { it.id } +
            listOfNotNull(WeatherCache.CURRENT_LOCATION_ID.takeIf { WeatherCache.readCurrentCoords(context) != null })
        return ids.any { id -> WeatherCache.read(context, id)?.let { it.fetchedAt < start } ?: false }
    }

    /**
     * Picks up the scheduled snapshot for every tab (no `fresh`, so the server answers from
     * its 3-hour cache). Blocking — call from a background thread.
     */
    fun runBlocking(context: Context) {
        val workers = mutableListOf<Thread>()
        WeatherCache.readCurrentCoords(context)?.let { (lat, lon) ->
            val name = WeatherCache.read(context, WeatherCache.CURRENT_LOCATION_ID)?.cityName
            workers += Thread { fetchAndSave(context, WeatherCache.CURRENT_LOCATION_ID, name, lat, lon) }
        }
        LocationStore.list(context).forEach { loc ->
            workers += Thread { fetchAndSave(context, loc.id, loc.displayName, loc.lat, loc.lon) }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        WeatherWidgetProvider.repaintAllWidgets(context)
    }

    private fun fetchAndSave(context: Context, id: String, name: String?, lat: Double, lon: Double) {
        val outcome = WeatherClient.fetchWeather(id, name, lat, lon)
        if (outcome is WeatherClient.Outcome.Ok) {
            WeatherCache.save(context, id, outcome.value.copy(cityName = name ?: outcome.value.cityName))
        }
    }
}

/** Woken by the 3-hour alarm, and after a reboot or app update to set the alarm again. */
class ScheduledRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ScheduledRefresh.schedule(app) // next slot first, so a failed fetch never breaks the chain
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!ScheduledRefresh.isBehind(app)) return
            }
        }
        val pending = goAsync()
        Thread {
            try {
                ScheduledRefresh.runBlocking(app)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
