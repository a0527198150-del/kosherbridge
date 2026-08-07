package com.example.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Schedules the single daily inexact alarm that triggers ReminderReceiver.
 * The receiver re-schedules the next day's alarm itself once it fires, so this
 * only needs to be called: on app launch, whenever the user changes the reminder
 * time in settings, and on device boot (see BootReceiver).
 */
object ReminderScheduler {
    const val ACTION_DAILY_REMINDER_CHECK = "com.example.ACTION_DAILY_REMINDER_CHECK"
    private const val REQUEST_CODE = 4201

    fun scheduleDailyCheck(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DAILY_REMINDER_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If that time already passed today, the first check should be tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        try {
            // Inexact-but-Doze-aware: fires close to the chosen time without requiring the
            // special "exact alarm" permission dance needed for setExactAndAllowWhileIdle.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerCal.timeInMillis, pendingIntent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
