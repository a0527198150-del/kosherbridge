package com.example.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(ReminderReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val hour = prefs.getInt(ReminderReceiver.KEY_REMINDER_HOUR, ReminderReceiver.DEFAULT_HOUR)
            val minute = prefs.getInt(ReminderReceiver.KEY_REMINDER_MINUTE, ReminderReceiver.DEFAULT_MINUTE)
            ReminderScheduler.scheduleDailyCheck(appContext, hour, minute)
        }
    }
}
