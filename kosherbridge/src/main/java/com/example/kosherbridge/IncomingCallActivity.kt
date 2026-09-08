package com.example.kosherbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.bluetooth.CallState
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.ui.IncomingCallScreen
import com.example.kosherbridge.ui.theme.KosherBridgeTheme
import com.example.kosherbridge.ui.theme.ThemeMode

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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val themeMode by ServiceLocator.settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM.name)
      KosherBridgeTheme(
        themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
      ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          val state by BridgeHub.state.collectAsStateWithLifecycle()
          val call = state.call
          var photoUri by remember { mutableStateOf<String?>(null) }
          var resolvedName by remember { mutableStateOf<String?>(null) }

          // Resolve photo AND name from the contacts DB by number, so the
          // full-screen call UI always shows the contact name even when the
          // launching Intent (e.g. the notification's full-screen intent)
          // carried only the number.
          LaunchedEffect(call?.number) {
            val contact = ServiceLocator.contacts.contactFor(call?.number)
            photoUri = contact?.photoUri
            resolvedName = contact?.name
          }

          // The ringtone is owned by BridgeService: this activity is often
          // never started at all (Android blocks background activity starts),
          // and a ringtone that only plays here meant a silent incoming call.
          LaunchedEffect(call?.state) {
            if (call == null || call?.state == CallState.IDLE ||
              call?.state == CallState.TERMINATED
            ) {
              finish()
            }
          }

          IncomingCallScreen(
            number = call?.number,
            name = intent.getStringExtra(EXTRA_NAME) ?: resolvedName,
            photoUri = photoUri,
            state = call,
            audioOutcome = state.audioOutcome,
            // Routed through the service intent, not BridgeHub.service:
            // the latter is null whenever the system has killed the service,
            // and the button then did nothing at all while the call kept
            // ringing.
            onAnswer = { BridgeService.requestAnswer(this@IncomingCallActivity) },
            onReject = { BridgeService.requestReject(this@IncomingCallActivity) },
            onHangup = { BridgeService.requestHangup(this@IncomingCallActivity) },
            onToggleAudio = { BridgeService.requestToggleAudio(this@IncomingCallActivity) },
          )
        }
      }
    }
  }
}
