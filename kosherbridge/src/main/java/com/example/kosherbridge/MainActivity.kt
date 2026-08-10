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
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
      // Start the bridge only after the user actually answered the permission
      // dialog - not on a fixed delay. Android 14+ crashes the whole process
      // if the foreground service (type connectedDevice) starts before
      // BLUETOOTH_CONNECT is granted, so the permission result drives the start.
      if (pendingServiceStart) {
        pendingServiceStart = false
        val btOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
          grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (btOk) {
          runCatching { BridgeService.start(this) }
        } else {
          BridgeHub.update {
            it.copy(
              permissionHint =
                "אין הרשאת בלוטוס - האפליקציה לא יכולה להתחבר לטלפון הכשר. " +
                  "אשר אותה בהגדרות המערכת → אפליקציות → גשר כשר → הרשאות.",
            )
          }
        }
      }
    }

  private var serviceStartScheduled = false
  private var pendingServiceStart = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ServiceLocator.init(applicationContext)
    requestNeededPermissions()

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
    // still considered idle). If runtime permissions are still missing, the
    // permission dialog's result drives the start; otherwise start now.
    if (!serviceStartScheduled) {
      serviceStartScheduled = true
      if (neededPermissions().isEmpty()) {
        runCatching { BridgeService.start(this) }
      } else {
        pendingServiceStart = true
      }
    }
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

  private fun requestNeededPermissions() {
    val needed = neededPermissions()
    if (needed.isNotEmpty()) {
      permissionLauncher.launch(needed.toTypedArray())
    }
  }
}
