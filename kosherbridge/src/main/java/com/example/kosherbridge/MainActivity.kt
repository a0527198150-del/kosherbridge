package com.example.kosherbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.ui.MainScreen
import com.example.kosherbridge.ui.theme.KosherBridgeTheme
import com.example.kosherbridge.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      // Start the bridge only after the user actually answered the permission
      // dialog - not on a fixed delay. Android 14+ crashes the whole process
      // if the foreground service (type connectedDevice) starts before
      // BLUETOOTH_CONNECT is granted, so the permission result drives the start.
      if (pendingServiceStart) {
        pendingServiceStart = false
        startBridgeIfAllowed()
      }
    }

  /** True once the runtime-permission dialog was shown in this session, so a
   * denial does not turn every return to the app into another prompt. */
  private var permissionPromptShown = false
  private var pendingServiceStart = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ServiceLocator.init(applicationContext)

    setContent {
      val themeMode by ServiceLocator.settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM.name)
      KosherBridgeTheme(
        themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
      ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          MainScreen()
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Start the foreground service only once the activity is actually visible
    // (starting from onCreate can be killed on Android 8-9 when the app is
    // still considered idle). If runtime permissions are missing, launch the
    // dialog AFTER arming pendingServiceStart (the result may arrive
    // synchronously when the system suppresses the dialog), and let the
    // dialog's result drive the service start.
    // Evaluated on EVERY resume, not once per process. The old one-shot latch
    // meant that a user who denied a permission, granted it later in system
    // settings and came back found a permanently dead app: the latch was spent,
    // so the service was never started and nothing on screen ever changed until
    // the app was force-stopped.
    val needed = neededPermissions()
    if (needed.isEmpty()) {
      // Everything granted - allow a future revoke to prompt again.
      permissionPromptShown = false
    } else if (!permissionPromptShown) {
      permissionPromptShown = true
      pendingServiceStart = true
      permissionLauncher.launch(needed.toTypedArray())
      return // the launcher result starts the bridge
    }
    startBridgeIfAllowed()
  }

  /**
   * Starts the bridge when the one permission it genuinely cannot run without
   * is granted.
   *
   * The distinction matters: [neededPermissions] also asks for notifications
   * and Bluetooth scanning, which are conveniences. Gating the service on the
   * full set meant that permanently denying the notification prompt - which
   * many people do reflexively - left the bridge unable to start at all, with
   * nothing on screen explaining why.
   */
  private fun startBridgeIfAllowed() {
    val btOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
      checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    if (!btOk) {
      BridgeHub.update {
        it.copy(
          permissionHint =
            "אין הרשאת בלוטוס - האפליקציה לא יכולה להתחבר לטלפון הכשר. " +
              "אשר אותה בהגדרות המערכת → אפליקציות → גשר כשר → הרשאות.",
        )
      }
      return
    }
    BridgeHub.update { if (it.permissionHint == null) it else it.copy(permissionHint = null) }
    if (BridgeService.instance == null) runCatching { BridgeService.start(this) }
  }

  private fun neededPermissions(): List<String> {
    val needed = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        needed += Manifest.permission.BLUETOOTH_CONNECT
      }
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
        needed += Manifest.permission.BLUETOOTH_SCAN
      }
    } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      needed += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      needed += Manifest.permission.POST_NOTIFICATIONS
    }
    return needed
  }
}
