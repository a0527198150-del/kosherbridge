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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
  // Set when something outside Settings asks for the readiness page. The
  // battery card promises "לחץ כאן כדי לאשר פטור - פעולה של שתי שניות" and
  // then only switched to the Settings tab root, leaving the user to hunt for
  // the page and the row inside it. Consumed by SettingsScreen so returning to
  // the tab later lands on the settings root as usual.
  var openSetupPage by remember { mutableStateOf(false) }

  // Snackbar once per distinct hint. It must NOT clear the hint: the Settings
  // and Diagnostics tabs are meant to keep showing it, and clearing it here
  // meant the persistent warning those screens document never appeared at all.
  // MainActivity clears it for real, once the permission is actually granted.
  var shownHint by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(state.permissionHint) {
    val hint = state.permissionHint
    if (hint == null) {
      shownHint = null
    } else if (hint != shownHint) {
      shownHint = hint
      snackbarHostState.showSnackbar(hint)
    }
  }

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
    // Content always fills the screen; only truly huge displays (TV boxes
    // wider than ~1000dp) get a centered column so lists don't stretch absurdly.
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
      val contentWidth = if (maxWidth > 1000.dp) 1000.dp else maxWidth
      Box(
        Modifier
          .fillMaxSize()
          .padding(horizontal = ((maxWidth - contentWidth) / 2).coerceAtLeast(0.dp)),
      ) {
        val snack: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
        when (tab) {
          0 -> HomeScreen(
            state,
            onGoToDialer = { tab = 1 },
            // Settings owns the setup page; jumping there is the whole point
            // of the warning card, so the user is one tap from fixing it.
            onOpenSetup = {
              openSetupPage = true
              tab = 4
            },
          )
          // The dialer reports the connection state on screen, not through
          // the snackbar - it never had anything to say through one.
          1 -> DialerScreen()
          2 -> ContactsScreen(onSnackbar = snack)
          3 -> CallLogScreen(onSnackbar = snack)
          4 -> SettingsScreen(
            state,
            onSnackbar = snack,
            openSetup = openSetupPage,
            onSetupOpened = { openSetupPage = false },
          )
        }
      }
    }
  }
}

/**
 * Whether a usable link to the kosher phone exists right now.
 *
 * The single source of truth for every "are we connected?" decision in the UI.
 * Screens used to each test `connectionState == STATE_CONNECTED` on their own,
 * which is the PROFILE-shaped state: on the direct RFCOMM channel the live
 * link is `rawLinkActive`, so a perfectly working bridge left the dial button
 * disabled, the home card showing "disconnected" and the diagnostics page
 * offering pairing advice for a phone that was already connected.
 */
internal fun linkUp(state: BridgeUiState): Boolean =
  state.connectionState == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
    state.rawLinkActive

/**
 * What the user is told about the connection.
 *
 * Reading only `connectionState` produced the "it is connected but the app
 * says disconnected" reports: on the direct RFCOMM channel the live link is
 * `rawLinkActive`, and the profile-shaped connectionState can lag behind it or
 * be overwritten by a poll that does not own the link. A retry in progress
 * also used to render as a flat "מנותק", which reads as broken rather than
 * busy. Every one of those facts now has its own wording.
 */
internal fun connectionText(state: BridgeUiState): String {
  val name = state.deviceName ?: "טלפון כשר"
  return when {
    state.connectionState == android.bluetooth.BluetoothProfile.STATE_CONNECTED ->
      "מחובר ל-$name"
    state.rawLinkActive -> "מחובר ל-$name (ערוץ ישיר)"
    // (both branches above are linkUp(state) - kept apart only for the wording)
    state.connectionState == android.bluetooth.BluetoothProfile.STATE_CONNECTING ->
      "מתחבר..."
    state.connectionState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTING ->
      "מתנתק..."
    state.reconnecting -> "מנותק - מנסה להתחבר מחדש..."
    state.deviceName != null -> "מנותק"
    else -> "לא מחובר למכשיר"
  }
}
