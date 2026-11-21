package com.weatherwidget.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Refreshes every placed widget at exactly 12:00 AM and 12:00 PM in the phone's own
 * local time, the way a calendar widget flips over right at midnight.
 *
 * Only one alarm is ever pending: the next 00:00 or 12:00. When it fires, the widgets
 * refresh and the alarm after that gets booked. It uses an exact, allow-while-idle
 * alarm, so Doze can't push it back by minutes or hours the way an inexact alarm or
 * updatePeriodMillis would.
 *
 * Changing the clock or timezone moves "midnight", so those broadcasts (and reboot,
 * app update and DATE_CHANGED) book the alarm again from the new local time. Without
 * that, an alarm set for the old midnight would fire late or early.
 */
object WidgetRefreshScheduler {

    const val ACTION_SCHEDULED_REFRESH = "com.weatherwidget.app.ACTION_SCHEDULED_REFRESH"
    private const val REQUEST_CODE = 1200

    /** Next 00:00:00 or 12:00:00 local time that's strictly in the future. */
    fun nextTriggerMillis(now: Calendar = Calendar.getInstance()): Long {
        val next = (now.clone() as Calendar).apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, if (now.get(Calendar.HOUR_OF_DAY) < 12) 12 else 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis
    }

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = nextTriggerMillis()
        val pi = pendingIntent(context)
        alarms.cancel(pi)
        // USE_EXACT_ALARM (API 33+) / SCHEDULE_EXACT_ALARM (API 31-32) are granted at
        // install, but if exact alarms are ever turned off for the app, fall back to
        // the closest thing so the widget still updates instead of never.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ScheduledRefreshReceiver::class.java).setAction(ACTION_SCHEDULED_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/**
 * Gets the 12 AM / 12 PM alarm plus the system broadcasts that move or wipe it
 * (reboot, clock or timezone change, date rollover, app update). Each one repaints
 * from cache straight away, so the forecast days move over even before the network
 * answers, books the next alarm, and then (for the alarm itself) reloads every widget
 * from the Weather Donkey Hub server, the same way the ↻ tap does. The phone
 * never calls the weather sources directly.
 */
class ScheduledRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetRefreshScheduler.schedule(context)
        WeatherWidgetProvider.repaintAllWidgets(context)

        // Only the alarm itself asks the server; DATE_CHANGED lands at the same
        // midnight, so it just repaints rather than doubling the server call.
        if (intent.action != WidgetRefreshScheduler.ACTION_SCHEDULED_REFRESH) return

        val pending = goAsync()
        Thread {
            try {
                WeatherWidgetProvider.refreshAllWidgets(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
