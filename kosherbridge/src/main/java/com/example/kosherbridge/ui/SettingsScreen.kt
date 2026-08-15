package com.example.kosherbridge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.bluetooth.BridgeUiState
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Sub-pages reachable from the Settings tab. */
private enum class SettingsSubPage { MAIN, CONNECTION, DIAGNOSTICS, LOG }

@Composable
fun SettingsScreen(state: BridgeUiState, onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val settings = ServiceLocator.settings
  val fullScreen by settings.fullScreen.collectAsStateWithLifecycle(true)
  val vibrate by settings.vibrate.collectAsStateWithLifecycle(true)
  val keyTone by settings.keyTone.collectAsStateWithLifecycle(true)
  val autoAudio by settings.autoAudio.collectAsStateWithLifecycle(true)
  val volumeBoost by settings.volumeBoost.collectAsStateWithLifecycle(true)
  val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM.name)
  var showClearCallsConfirm by remember { mutableStateOf(false) }
  var showClearContactsConfirm by remember { mutableStateOf(false) }
  var subPage by rememberSaveable { mutableStateOf(SettingsSubPage.MAIN) }

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

  when (subPage) {
    SettingsSubPage.MAIN -> {
      Column(
        modifier = modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SettingsCard("חיבור") {
          SettingRow(
            "כל הגדרות החיבור",
            connectionText(state),
          ) { subPage = SettingsSubPage.CONNECTION }
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
    }
    SettingsSubPage.CONNECTION -> ConnectionSettingsScreen(
      state = state,
      onSnackbar = onSnackbar,
      onBack = { subPage = SettingsSubPage.MAIN },
      onOpenDiagnostics = { subPage = SettingsSubPage.DIAGNOSTICS },
      onOpenConnectionLog = { subPage = SettingsSubPage.LOG },
    )
    SettingsSubPage.DIAGNOSTICS -> DiagnosticsScreen(
      state = state,
      onSnackbar = onSnackbar,
      onBack = { subPage = SettingsSubPage.CONNECTION },
    )
    SettingsSubPage.LOG -> ConnectionLogScreen(
      state = state,
      onSnackbar = onSnackbar,
      onBack = { subPage = SettingsSubPage.CONNECTION },
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
