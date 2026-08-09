package com.example.kosherbridge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.bluetooth.BridgeUiState
import kotlinx.coroutines.launch

@Composable
fun MainScreen() {
  val state by BridgeHub.state.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var tab by rememberSaveable { mutableIntStateOf(0) }

  Scaffold(
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = tab == 0,
          onClick = { tab = 0 },
          icon = { Icon(Icons.Filled.Home, contentDescription = null) },
          label = { Text("בית") },
        )
        NavigationBarItem(
          selected = tab == 1,
          onClick = { tab = 1 },
          icon = { Icon(Icons.Filled.Dialpad, contentDescription = null) },
          label = { Text("חיוג") },
        )
        NavigationBarItem(
          selected = tab == 2,
          onClick = { tab = 2 },
          icon = { Icon(Icons.Filled.Contacts, contentDescription = null) },
          label = { Text("אנשי קשר") },
        )
        NavigationBarItem(
          selected = tab == 3,
          onClick = { tab = 3 },
          icon = { Icon(Icons.Filled.History, contentDescription = null) },
          label = { Text("יומן") },
        )
        NavigationBarItem(
          selected = tab == 4,
          onClick = { tab = 4 },
          icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
          label = { Text("הגדרות") },
        )
      }
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    // Wide screens (tablets, Android boxes, landscape) get a centered column so
    // lists and the dialer never stretch edge-to-edge.
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
      val contentWidth = if (maxWidth > 640.dp) 640.dp else maxWidth
      Box(
        Modifier
          .fillMaxSize()
          .padding(horizontal = ((maxWidth - contentWidth) / 2).coerceAtLeast(0.dp)),
      ) {
        val snack: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
        when (tab) {
          0 -> HomeScreen(state, onGoToDialer = { tab = 1 })
          1 -> DialerScreen(onSnackbar = snack)
          2 -> ContactsScreen(onSnackbar = snack)
          3 -> CallLogScreen(onSnackbar = snack)
          4 -> SettingsScreen(state, onSnackbar = snack)
        }
      }
    }
  }
}

internal fun connectionText(state: BridgeUiState): String = when (state.connectionState) {
  android.bluetooth.BluetoothProfile.STATE_CONNECTED ->
    "מחובר ל-${state.deviceName ?: "טלפון כשר"}"
  android.bluetooth.BluetoothProfile.STATE_CONNECTING -> "מתחבר..."
  android.bluetooth.BluetoothProfile.STATE_DISCONNECTING -> "מתנתק..."
  else -> if (state.deviceName != null) "מנותק" else "לא מחובר למכשיר"
}
