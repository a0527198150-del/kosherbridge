package com.example.kosherbridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Brings the bridge back when the player killed it behind our back.
 *
 * START_STICKY is the documented way a foreground service is restarted, and on
 * stock Android it is enough. The cheap players this app targets are not stock:
 * their vendor "power managers" kill background processes and then do not honour
 * the restart, so the bridge simply ceases to exist. Nothing on the device says
 * so - the phone rings, the player is silent, and the user concludes the app is
 * broken.
 *
 * A repeating alarm is the standard answer: it survives the process dying, it
 * needs no permission, and each firing costs a service start that is a no-op
 * when the service is already alive. [INTERVAL_MS] is deliberately loose - this
 * is a safety net for a process that should not have died, not a poll.
 *
 * There is deliberately no way to switch this off: the bridge has no "stop"
 * action, so an armed watchdog never fights a decision the user made. A real
 * force-stop puts the app in the stopped state and cancels its alarms, so this
 * cannot resurrect an app the user genuinely shut down either.
 *
 * `setAndAllowWhileIdle` is used rather than an exact alarm: it fires through
 * Doze, and unlike `setExactAndAllowWhileIdle` it needs no SCHEDULE_EXACT_ALARM
 * permission, which Android 13+ would otherwise make the user grant by hand for
 * no benefit at this granularity.
 */
object BridgeWatchdog {

  private const val TAG = "BridgeWatchdog"
  private const val ACTION_TICK = "com.example.kosherbridge.action.WATCHDOG"
  private const val REQUEST_CODE = 7

  /** Loose on purpose: a safety net, not a poll. */
  private const val INTERVAL_MS = 15 * 60 * 1000L

  /**
   * Arms the next check. Safe to call repeatedly - the alarm is replaced, not
   * duplicated, because the PendingIntent is identical each time.
   */
  fun schedule(context: Context) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val pending = tickIntent(context)
    val at = SystemClock.elapsedRealtime() + INTERVAL_MS
    runCatching {
      am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
    }.onFailure { Log.w(TAG, "could not schedule the watchdog", it) }
  }

  private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    REQUEST_CODE,
    Intent(context, WatchdogReceiver::class.java).setAction(ACTION_TICK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )

  /**
   * Fires on each tick: restarts the bridge if it is gone, then arms the next
   * check. Re-arming from here rather than from a repeating alarm keeps the
   * chain alive across the process death this exists to recover from.
   */
  class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action != ACTION_TICK) return
      if (BridgeService.instance == null) {
        Log.i(TAG, "bridge service is gone - restarting it")
        // BOOT_COMPLETED-style background starts are allowed for an alarm's
        // broadcast, but a vendor build can still refuse; never crash the
        // receiver over it.
        runCatching { BridgeService.start(context) }
      }
      schedule(context)
    }
  }
}
