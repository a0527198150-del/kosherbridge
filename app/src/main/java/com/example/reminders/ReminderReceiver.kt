package com.example.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import com.example.data.ReminderHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Fires once a day (scheduled by ReminderScheduler). Checks all recurring rules for
 * ones that are due for a "2 days before" reminder, sends a notification if any are
 * due, then re-schedules itself for the same time tomorrow.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val hour = prefs.getInt(KEY_REMINDER_HOUR, DEFAULT_HOUR)
                val minute = prefs.getInt(KEY_REMINDER_MINUTE, DEFAULT_MINUTE)

                val db = AppDatabase.getDatabase(appContext)
                val rules = db.recurringRuleDao().getAllRecurringRules().first()
                val dueRules = ReminderHelper.rulesDueToday(rules, Calendar.getInstance())

                if (dueRules.isNotEmpty()) {
                    sendNotification(appContext, dueRules)
                }

                // Re-schedule for the same time tomorrow (this alarm is single-shot)
                ReminderScheduler.scheduleDailyCheck(appContext, hour, minute)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun sendNotification(context: Context, dueRules: List<com.example.data.RecurringRuleEntity>) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "תזכורות לרשומות קבועות",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "תזכורת יומיים לפני שהוצאה/הכנסה קבועה עומדת להירשם"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val firstTitle = dueRules.first().title
        val title = "תזכורת: רשומה קבועה בעוד יומיים"
        val text = if (dueRules.size == 1) {
            "\"$firstTitle\" עומדת להירשם בעוד יומיים"
        } else {
            "${dueRules.size} רשומות קבועות עומדות להירשם בעוד יומיים, כולל \"$firstTitle\""
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    companion object {
        // Shared with BudgetViewModel - both read/write these same keys in the same prefs file
        const val PREFS_NAME = "hebrew_budget_prefs"
        const val KEY_REMINDER_HOUR = "reminder_hour"
        const val KEY_REMINDER_MINUTE = "reminder_minute"
        const val DEFAULT_HOUR = 9
        const val DEFAULT_MINUTE = 0

        private const val CHANNEL_ID = "recurring_reminders"
        private const val NOTIFICATION_ID = 78234
    }
}
