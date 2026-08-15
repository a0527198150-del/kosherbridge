package com.example.kosherbridge.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.BridgeService
import com.example.kosherbridge.bluetooth.BridgeUiState
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.ChannelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * All connection controls previously shown under the "חיבור" card on the main
 * settings screen, plus entries into the diagnostics and connection-log pages.
 */
@Composable
fun ConnectionSettingsScreen(
  state: BridgeUiState,
  onSnackbar: (String) -> Unit,
  onBack: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  onOpenConnectionLog: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = ServiceLocator.settings
  val autoConnect by settings.autoConnect.collectAsStateWithLifecycle(true)
  val profileGuard by settings.profileGuard.collectAsStateWithLifecycle(true)

  // Connection channel for THIS player + in-app Bluetooth pairing.
  val fp = remember { Build.FINGERPRINT }
  val channelState by settings.channelState(fp).collectAsStateWithLifecycle(ChannelState("AUTO", "AUTO", ""))
  var showDevices by remember { mutableStateOf(false) }
  var showChannelDialog by remember { mutableStateOf(false) }
  var showPairDialog by remember { mutableStateOf(false) }
  var discovered by remember { mutableStateOf(listOf<Pair<String, String>>()) }
  var scanning by remember { mutableStateOf(false) }

  val startScan: () -> Unit = {
    discovered = emptyList()
    scanning = true
    showPairDialog = true
  }
  val scanPermLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { grants ->
    if (grants.values.all { it }) startScan() else onSnackbar("נדרשת הרשאת סריקת בלוטוס לצימוד")
  }
  val requestScanPermissions: () -> Unit = {
    val perms = if (Build.VERSION.SDK_INT >= 31) {
      arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val missing = perms.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) startScan() else scanPermLauncher.launch(missing.toTypedArray())
  }
  val pairDevice: (String) -> Unit = { address ->
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    val d = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
    if (d == null) {
      onSnackbar("המכשיר לא זמין")
    } else {
      val ok = runCatching { d.createBond() }.getOrDefault(false)
      onSnackbar(if (ok) "הצימוד החל - אשר את הזיווג במכשיר ובטלפון" else "הצימוד לא החל - נסה שוב")
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageHeader("הגדרות חיבור", onBack)

    SettingsCard("חיבור") {
      SettingSwitch(
        title = "חיבור אוטומטי",
        subtitle = "התחבר לטלפון הכשר בהפעלה, ואחרי ניתוק אקראי",
        checked = autoConnect,
      ) { v -> scope.launch { settings.setAutoConnect(v) } }
      SettingRow("בחר מכשיר", state.deviceName ?: "לא נבחר") { showDevices = true }
      SettingRow("התאמת מכשיר חדש", null) {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
      }
      SettingRow("ערוץ חיבור", channelLabel(channelState)) { showChannelDialog = true }
      SettingSwitch(
        title = "ניטרול פרופילי המערכת",
        subtitle = "כיבוי הדיבורית/מדיה של המערכת לפני חיבור RFCOMM (כבה רק לבדיקת ניתוקים)",
        checked = profileGuard,
      ) { v -> scope.launch { settings.setProfileGuard(v) } }
      SettingRow(
        "צימוד מכשיר חדש (סריקה)",
        "סורק בלוטוס ומצמיד מכשיר חדש מהאפליקציה",
      ) { requestScanPermissions() }
      SettingRow(
        "התחבר דרך Shizuku",
        "עוקף חסימת אנדרואיד 12+ (דורש Shizuku פעיל עם הרשאה)",
      ) {
        BridgeHub.service?.bindShizuku()
      }
      SettingRow(
        "חיבור ישיר (ללא הרשאות)",
        "שליטה וזיהוי שיחה ישירות מהאפליקציה; שמע תלוי בנגן",
      ) {
        val addr = state.deviceAddress
        if (addr != null) {
          BridgeService.requestConnectRaw(context, addr)
        } else {
          showDevices = true
        }
      }
    }

    SettingsCard("כלים") {
      SettingRow("אבחון", "מצב המכשיר, ערוץ פעיל ובדיקות") { onOpenDiagnostics() }
      SettingRow("יומן חיבור בלוטוס", "ניסיונות החיבור שנרשמו במכשיר") { onOpenConnectionLog() }
    }
  }

  // Discovery for in-app pairing: register the receiver and scan while the
  // pairing dialog is open.
  DisposableEffect(scanning) {
    if (!scanning) return@DisposableEffect onDispose { }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context?, i: Intent?) {
        val intent = i ?: return
        val d = if (Build.VERSION.SDK_INT >= 33) {
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (d == null) return
        when (intent.action) {
          BluetoothDevice.ACTION_FOUND -> {
            val entry = (d.name ?: d.address) to d.address
            if (discovered.none { it.second == entry.second }) {
              discovered = discovered + entry
            }
          }
          BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
            val state = intent.getIntExtra(
              BluetoothDevice.EXTRA_BOND_STATE,
              BluetoothDevice.BOND_NONE,
            )
            if (state == BluetoothDevice.BOND_BONDED) {
              // Pairing is not the end of the flow: immediately ask the
              // bridge to connect to the newly bonded phone.
              scope.launch {
                delay(700)
                BridgeService.requestConnect(context, d.address)
                onSnackbar("הזיווג הושלם — מנסה להתחבר לטלפון")
              }
            }
          }
        }
      }
    }
    val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
      addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    }
    runCatching {
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
      }
    }
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    runCatching { adapter?.startDiscovery() }
    onDispose {
      runCatching { context.unregisterReceiver(receiver) }
      runCatching { adapter?.cancelDiscovery() }
      scanning = false
    }
  }

  if (showDevices) {
    DevicePickerDialog(
      onDismiss = { showDevices = false },
      onPick = { address ->
        showDevices = false
        BridgeService.requestConnect(context, address)
      },
    )
  }

  if (showChannelDialog) {
    AlertDialog(
      onDismissRequest = { showChannelDialog = false },
      title = { Text("ערוץ חיבור") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          listOf(
            "AUTO" to "אוטומטי - האפליקציה בוחרת לבד",
            "DIRECT" to "ישיר (ללא Shizuku)",
            "SHIZUKU" to "דרך Shizuku",
            "RAW" to "חיבור ישיר RFCOMM",
          ).forEach { (mode, label) ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                  scope.launch { settings.setChannel(fp, mode) }
                  showChannelDialog = false
                }
                .padding(vertical = 10.dp, horizontal = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
              if (channelState.effective == mode) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showChannelDialog = false }) { Text("סגור") } },
    )
  }

  if (showPairDialog) {
    AlertDialog(
      onDismissRequest = { showPairDialog = false; scanning = false },
      title = { Text("מכשירים שנמצאו") },
      text = {
        if (discovered.isEmpty()) {
          Text(if (scanning) "סורק... (הפעל מצב זיווג במכשיר האחר)" else "לא נמצאו מכשירים חדשים")
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            discovered.forEach { (name, address) ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .clickable { pairDevice(address) }
                  .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(Modifier.weight(1f)) {
                  Text(name, style = MaterialTheme.typography.bodyLarge)
                  Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Text("צמד", color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
              }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showPairDialog = false; scanning = false }) { Text("סגור") } },
    )
  }
}

/** Short label for the "ערוץ חיבור" settings row. */
private fun channelLabel(cs: ChannelState): String {
  val name = when (cs.effective) {
    "DIRECT" -> "ישיר"
    "SHIZUKU" -> "Shizuku"
    "RAW" -> "RFCOMM ישיר"
    else -> "אוטומטי"
  }
  return when {
    cs.manual != "AUTO" -> "$name (ידני)"
    cs.learned.isNotBlank() -> "$name (זוכר)"
    else -> name
  }
}
