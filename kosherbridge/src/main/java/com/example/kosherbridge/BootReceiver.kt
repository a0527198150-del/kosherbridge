package com.example.kosherbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Brings the bridge back up after a reboot or an app update.
 *
 * Three triggers, all of them cases where the service is gone and nothing else
 * would ever start it:
 *  - BOOT_COMPLETED, the normal path.
 *  - QUICKBOOT_POWERON / HTC_QUICKBOOT, sent instead of BOOT_COMPLETED by the
 *    "fast boot" implementations common on cheap players.
 *  - MY_PACKAGE_REPLACED: an app update kills the service, and without this
 *    the bridge stays dead - no calls, no ringing - until someone happens to
 *    open the app.
 *
 * BroadcastReceiver.onReceive runs on the main thread inside a limited time
 * window, so the DataStore read must not block it (a cold disk right after
 * boot on cheap players can stall runBlocking and the app then silently fails
 * to start). goAsync() + a real coroutine keeps the window open instead.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
      "android.intent.action.QUICKBOOT_POWERON",
      "com.htc.intent.action.QUICKBOOT_POWERON",
      -> Unit
      else -> return
    }
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        // Deliberately NOT gated on the auto-connect setting. That setting
        // governs whether the bridge dials out to the phone by itself - not
        // whether the bridge exists. Gating the service start on it meant a
        // user who turned auto-connect off got no service after a reboot, and
        // therefore no incoming calls at all, which is not what that switch
        // says. maybeAutoConnect() inside the service still honours it.
        BridgeService.start(context)
      } finally {
        pending.finish()
      }
    }
  }
}
