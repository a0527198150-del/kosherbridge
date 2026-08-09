package com.example.kosherbridge.ui

import android.Manifest
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.kosherbridge.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(state: BridgeUiState, onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings = ServiceLocator.settings
  val autoConnect by settings.autoConnect.collectAsStateWithLifecycle(true)
  val fullScreen by settings.fullScreen.collectAsStateWithLifecycle(true)
  val vibrate by settings.vibrate.collectAsStateWithLifecycle(true)
  val keyTone by settings.keyTone.collectAsStateWithLifecycle(true)
  val autoAudio by settings.autoAudio.collectAsStateWithLifecycle(true)
  val volumeBoost by settings.volumeBoost.collectAsStateWithLifecycle(true)
  val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM.name)
  var showDevices by remember { mutableStateOf(false) }
  var showClearCallsConfirm by remember { mutableStateOf(false) }
  var showClearContactsConfirm by remember { mutableStateOf(false) }
  var micResult by remember { mutableStateOf<String?>(null) }

  val micPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    micResult = if (granted) {
      BridgeHub.service?.checkMicrophone { micResult = it }
      "בודק..."
    } else {
      "אין הרשאת מיקרופון"
    }
  }

  // Export / import contacts as a JSON backup file (SAF document pickers).
  val exportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/json"),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val n = ServiceLocator.contacts.exportContactsTo(uri)
        onSnackbar("ייצאו $n אנשי קשר")
      }
    }
  }
  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        runCatching { ServiceLocator.contacts.importContactsFrom(uri) }
          .onSuccess { n -> onSnackbar(if (n > 0) "יובאו $n אנשי קשר" else "לא נמצאו אנשי קשר חדשים") }
          .onFailure { e -> onSnackbar("הייבוא נכשל: ${e.message ?: "שגיאה"}") }
      }
    }
  }

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
      SettingSwitch(
        title = "רטט",
        subtitle = "רטט בעת שיחה נכנסת",
        checked = vibrate,
      ) { v -> scope.launch { settings.setVibrate(v) } }
      SettingSwitch(
        title = "צליל מקשים",
        subtitle = "צליל DTMF בעת לחיצה על מקשי החייגן",
        checked = keyTone,
      ) { v -> scope.launch { settings.setKeyTone(v) } }
      SettingSwitch(
        title = "שמע אוטומטי בשיחה",
        subtitle = "העבר את הקול (גם המיקרופון) לנגן אוטומטית והשאר אותו חי לאורך השיחה",
        checked = autoAudio,
      ) { v -> scope.launch { settings.setAutoAudio(v) } }
      SettingSwitch(
        title = "הגברת עוצמה בשיחה",
        subtitle = "עוצמת השיחה למקסימום בזמן שיחה פעילה",
        checked = volumeBoost,
      ) { v -> scope.launch { settings.setVolumeBoost(v) } }
    }
    SettingsCard("אנשי קשר") {
      SettingRow(
        "ייצוא אנשי קשר",
        "שמירת כל אנשי הקשר לקובץ גיבוי (JSON)",
      ) {
        exportLauncher.launch("kosherbridge-contacts-${System.currentTimeMillis()}.json")
      }
      SettingRow(
        "ייבוא אנשי קשר",
        "שחזור מגיבוי JSON שייצאת בעבר",
      ) {
        importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
      }
      SettingRow(
        "מחיקת כל אנשי הקשר",
        "מחיקה לצמיתות של כל אנשי הקשר (אפשר לשחזר מגיבוי)",
        error = true,
      ) { showClearContactsConfirm = true }
    }
    SettingsCard("מראה") {
      Text("מצב תצוגה", style = MaterialTheme.typography.bodyLarge)
      Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ThemeMode.entries.forEach { mode ->
          FilterChip(
            selected = themeMode == mode.name,
            onClick = { scope.launch { settings.setThemeMode(mode.name) } },
            label = { Text(mode.label) },
          )
        }
      }
    }
    SettingsCard("יומן שיחות") {
      SettingRow(
        "נקה יומן שיחות",
        "מחיקת כל השיחות שנרשמו ביומן",
      ) { showClearCallsConfirm = true }
    }
    SettingsCard("אבחון") {
      DiagRow("פרופיל דיבורית (HFP Client)", if (state.profileReady) "נתמך" else "לא נתמך", state.profileReady)
      DiagRow("ערוץ פעיל", state.backendLabel ?: "לא פעיל", state.profileReady)
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
      state.audioRoute?.let {
        DiagRow("ניתוב שמע", it, it.startsWith("מנותב"))
      }
      state.deviceInfo?.let { DiagRow("מכשיר", it, true) }
      DiagRow("API נסתר (HFP)", if (state.hiddenApiAvailable) "זמין" else "לא זמין", state.hiddenApiAvailable)
      DiagRow(
        "חסימת הרשאות",
        if (state.privilegedBlocked) "נחסמה - דרוש Shizuku" else "לא נחסמה",
        !state.privilegedBlocked,
      )
      DiagRow(
        "Shizuku",
        when {
          state.shizukuGranted -> "פעיל + הרשאה"
          state.shizukuAvailable -> "מותקן, בלי הרשאה"
          else -> "לא מותקן/לא פעיל"
        },
        state.shizukuGranted,
      )
      state.scoSupport?.let {
        DiagRow("שמע (SCO)", it, it.startsWith("מחובר") || it.startsWith("נתמך"))
      }
      state.scoTechnique?.let {
        DiagRow("טכניקת שמע אחרונה", it, true)
      }
      SettingRow("בדיקת מיקרופון", micResult ?: "מוודא שהמיקרופון קולט קול לשיחה") {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
        ) {
          micResult = "בודק..."
          BridgeHub.service?.checkMicrophone { micResult = it }
        } else {
          micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
      }
      state.lastError?.let {
        Spacer(Modifier.height(4.dp))
        Text(
          it,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      SettingRow(
        "העתק דוח אבחון",
        "מעתיק דוח מלא של המכשיר והחיבור - הדבק אותו בתמיכה",
      ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kosherbridge-diagnostics", buildDiagnosticsReport(state)))
        onSnackbar("דוח האבחון הועתק - הדבק אותו בהודעה")
      }
    }
    SettingsCard("אודות") {
      DiagRow("גרסת אפליקציה", "${com.example.kosherbridge.BuildConfig.VERSION_NAME}", true)
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

  if (showClearCallsConfirm) {
    AlertDialog(
      onDismissRequest = { showClearCallsConfirm = false },
      title = { Text("לנקות את יומן השיחות?") },
      text = { Text("כל השיחות ביומן יימחקו לצמיתות.") },
      confirmButton = {
        TextButton(
          onClick = {
            scope.launch { ServiceLocator.contacts.clearCallLog() }
            showClearCallsConfirm = false
            onSnackbar("יומן השיחות נוקה")
          },
        ) { Text("מחק הכול", fontWeight = FontWeight.Bold) }
      },
      dismissButton = { TextButton(onClick = { showClearCallsConfirm = false }) { Text("ביטול") } },
    )
  }

  if (showClearContactsConfirm) {
    AlertDialog(
      onDismissRequest = { showClearContactsConfirm = false },
      title = { Text("למחוק את כל אנשי הקשר?") },
      text = { Text("כל אנשי הקשר יימחקו לצמיתות. מומלץ לייצא גיבוי קודם.") },
      confirmButton = {
        TextButton(
          onClick = {
            scope.launch { ServiceLocator.contacts.clearAllContacts() }
            showClearContactsConfirm = false
            onSnackbar("כל אנשי הקשר נמחקו")
          },
        ) { Text("מחק הכול", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { showClearContactsConfirm = false }) { Text("ביטול") } },
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
private fun SettingRow(title: String, subtitle: String?, error: Boolean = false, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
      subtitle?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = if (error) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Icon(
      Icons.Filled.ChevronRight,
      contentDescription = null,
      tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/** Builds the full local capability report copied by "העתק דוח אבחון". */
private fun buildDiagnosticsReport(state: BridgeUiState): String = buildString {
  appendLine("KosherBridge - דוח אבחון")
  appendLine("=======================")
  appendLine("גרסת אפליקציה: ${com.example.kosherbridge.BuildConfig.VERSION_NAME}")
  appendLine("מכשיר: ${state.deviceInfo ?: "-"}")
  appendLine("API נסתר (HFP): ${if (state.hiddenApiAvailable) "זמין" else "לא זמין"}")
  appendLine("פרופיל דיבורית: ${if (state.profileReady) "נתמך" else "לא נתמך"}")
  appendLine("ערוץ פעיל: ${state.backendLabel ?: "לא פעיל"}")
  appendLine("חסימת הרשאות: ${if (state.privilegedBlocked) "נחסמה - דרוש Shizuku" else "לא נחסמה"}")
  appendLine(
    "Shizuku: ${
      when {
        state.shizukuGranted -> "פעיל + הרשאה"
        state.shizukuAvailable -> "מותקן, בלי הרשאה"
        else -> "לא מותקן/לא פעיל"
      }
    }",
  )
  appendLine("בלוטוס: ${if (state.adapterOn) "פועל" else "כבוי"}")
  appendLine("חיבור: ${connectionText(state)}")
  appendLine(
    "שמע: ${
      when (state.audioState) {
        2 -> "פעיל"
        1 -> "מתחבר"
        else -> "מנותק"
      }
    }",
  )
  state.audioRoute?.let { appendLine("ניתוב שמע: $it") }
  state.scoSupport?.let { appendLine("שמע (SCO): $it") }
  state.scoTechnique?.let { appendLine("טכניקת שמע אחרונה: $it") }
  state.lastError?.let { appendLine("שגיאה אחרונה: $it") }
}
