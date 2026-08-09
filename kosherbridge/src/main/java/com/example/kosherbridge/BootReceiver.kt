package com.example.kosherbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.kosherbridge.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts the bridge after a reboot when auto-connect is enabled.
 *
 * BroadcastReceiver.onReceive runs on the main thread inside a limited time
 * window, so the DataStore read must not block it (a cold disk right after
 * boot on cheap players can stall runBlocking and the app then silently fails
 * to start). goAsync() + a real coroutine keeps the window open instead.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val autoConnect = ServiceLocator.settings.autoConnect.first()
        if (autoConnect) BridgeService.start(context)
      } finally {
        pending.finish()
      }
    }
  }
}
