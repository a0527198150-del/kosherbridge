package com.example.kosherbridge.ui

import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.bluetooth.BridgeUiState
import com.example.kosherbridge.data.ServiceLocator
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(state: BridgeUiState, onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = ServiceLocator.settings
  val autoConnect by settings.autoConnect.collectAsStateWithLifecycle(true)
  val fullScreen by settings.fullScreen.collectAsStateWithLifecycle(true)
  var showDevices by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
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
          BridgeHub.service?.connectRaw(addr)
        } else {
          showDevices = true
        }
      }
    }
    SettingsCard("שיחות") {
      SettingSwitch(
        title = "מסך מלא לשיחה נכנסת",
        subtitle = "הצג מסך שיחה נכנסת גם כשהנגן נעול",
        checked = fullScreen,
      ) { v -> scope.launch { settings.setFullScreen(v) } }
    }
    SettingsCard("אבחון") {
      DiagRow("פרופיל דיבורית (HFP Client)", if (state.profileReady) "נתמך" else "לא נתמך", state.profileReady)
      DiagRow("בלוטוס", if (state.adapterOn) "פועל" else "כבוי", state.adapterOn)
      DiagRow("חיבור", connectionText(state), state.connectionState == BluetoothProfile.STATE_CONNECTED)
      DiagRow(
        "שמע",
        when (state.audioState) {
          2 -> "פעיל"
          1 -> "מתחבר"
          else -> "מנותק"
        },
        state.audioState == 2,
      )
      state.lastError?.let {
        Spacer(Modifier.height(4.dp))
        Text(
          it,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    SettingsCard("אודות") {
      Text(
        "גשר כשר מחבר את הנגן לטלפון הכשר דרך בלוטוס (פרוטוקול HFP - דיבורית). " +
          "הניחו את הטלפון הכשר במקום עם קליטה, זווגו אותו עם הנגן ובחרו אותו בהגדרות. " +
          "שיחות יופיעו על הנגן, וניתן לענות, לדחות ולחייג דרכו. " +
          "לתפקוד מלא נדרשת תמיכת דיבורית במכשיר (קופסאות אנדרואיד, נגני רכב, טאבלטים).",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }

  if (showDevices) {
    DevicePickerDialog(
      onDismiss = { showDevices = false },
      onPick = { address ->
        showDevices = false
        BridgeHub.service?.connectTo(address)
      },
    )
  }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(),
  ) {
    Column(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(4.dp))
      content()
    }
  }
}

@Composable
private fun SettingRow(title: String, subtitle: String?, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onChecked)
  }
}

@Composable
private fun DiagRow(label: String, value: String, ok: Boolean) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Text(
      value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
  }
}
