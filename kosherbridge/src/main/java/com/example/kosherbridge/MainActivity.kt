package com.example.kosherbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* handled per feature */ }

  private var serviceStartScheduled = false

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
    // Start the foreground service only once the activity is actually visible.
    // Starting it from onCreate can be killed on Android 8-9 when the app is
    // still considered "idle" (fresh install / stopped state).
    if (!serviceStartScheduled) {
      serviceStartScheduled = true
      Handler(Looper.getMainLooper()).postDelayed(
        { runCatching { BridgeService.start(this) } },
        1500,
      )
    }
  }

  private fun requestNeededPermissions() {
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
    if (needed.isNotEmpty()) {
      permissionLauncher.launch(needed.toTypedArray())
    }
  }
}
