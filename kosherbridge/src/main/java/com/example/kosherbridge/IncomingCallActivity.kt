package com.example.kosherbridge

import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.bluetooth.CallState
import com.example.kosherbridge.ui.IncomingCallScreen
import com.example.kosherbridge.ui.theme.KosherBridgeTheme

/** Shown when a call arrives so the user can answer / decline / hang up. */
class IncomingCallActivity : ComponentActivity() {

  companion object {
    private const val EXTRA_NUMBER = "number"
    private const val EXTRA_NAME = "name"

    fun createIntent(context: Context, number: String?, name: String?): Intent =
      Intent(context, IncomingCallActivity::class.java)
        .putExtra(EXTRA_NUMBER, number)
        .putExtra(EXTRA_NAME, name)
  }

  private var ringtone: Ringtone? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      KosherBridgeTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          val state by BridgeHub.state.collectAsStateWithLifecycle()
          val call = state.call

          LaunchedEffect(call?.state) {
            when (call?.state) {
              null, CallState.IDLE, CallState.TERMINATED -> finish()
              CallState.INCOMING, CallState.WAITING -> playRingtone()
              else -> stopRingtone()
            }
          }

          IncomingCallScreen(
            number = call?.number,
            name = intent.getStringExtra(EXTRA_NAME),
            state = call,
            onAnswer = { BridgeHub.service?.answer() },
            onReject = { BridgeHub.service?.reject() },
            onHangup = { BridgeHub.service?.hangup() },
            onToggleAudio = { BridgeHub.service?.toggleAudio() },
          )
        }
      }
    }
  }

  override fun onDestroy() {
    stopRingtone()
    super.onDestroy()
  }

  private fun playRingtone() {
    if (ringtone != null) return
    runCatching {
      val r = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
      ringtone = r
      r?.play()
    }
  }

  private fun stopRingtone() {
    runCatching { ringtone?.stop() }
    ringtone = null
  }
}
