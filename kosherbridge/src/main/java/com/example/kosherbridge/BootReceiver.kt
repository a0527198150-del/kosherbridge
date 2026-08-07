package com.example.kosherbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Restarts the bridge after a reboot when auto-connect is enabled. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val autoConnect = runCatching {
      runBlocking { ServiceLocator.settings.autoConnect.first() }
    }.getOrDefault(false)
    if (autoConnect) {
      BridgeService.start(context)
    }
  }
}
