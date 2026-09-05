package com.example.kosherbridge.ui

import android.Manifest
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.bluetooth.BridgeUiState

/**
 * Device/connection diagnostics previously shown under the "אבחון" card on the
 * main settings screen, now a dedicated page inside the connection settings.
 */
@Composable
fun DiagnosticsScreen(
  state: BridgeUiState,
  onSnackbar: (String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var micResult by remember { mutableStateOf<String?>(null) }

  // Re-read the HFP connection-policy row each time this screen opens. The read
  // is a blocking binder round trip, so it runs off the main thread (see
  // BridgeService.refreshHeadsetClientPolicy) and the previously cached value
  // renders until it completes.
  LaunchedEffect(Unit) {
    BridgeHub.service?.refreshHeadsetClientPolicy()
  }

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

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageHeader("אבחון", onBack)

    SettingsCard("אבחון") {
      val guidance = buildGuidance(state)
      if (guidance != null) {
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
          Text(
            guidance,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
          )
        }
        Spacer(Modifier.height(4.dp))
      }
      DiagRow("פרופיל דיבורית (HFP Client)", if (state.profileReady) "נתמך" else "לא נתמך", state.profileReady)
      // The live-link fact is independent of profile support: on a player whose
      // stack lacks the HFP-Client profile, a raw RFCOMM link can still be up
      // (call control works), and both facts must be visible side by side
      // instead of the link masquerading as profile support.
      DiagRow(
        "קישור פעיל",
        when {
          state.profileReady && state.connectionState == BluetoothProfile.STATE_CONNECTED -> "כן (פרופיל)"
          state.rawLinkActive -> "כן (RFCOMM ישיר)"
          state.connectionState == BluetoothProfile.STATE_CONNECTED -> "כן"
          else -> "לא"
        },
        state.profileReady || state.rawLinkActive,
      )
      DiagRow("ערוץ פעיל", state.backendLabel ?: "לא פעיל", state.profileReady || state.rawLinkActive)
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
      DiagRow("Root (su)", if (state.rootAvailable) "זמין" else "לא זמין", state.rootAvailable)
      state.scoSupport?.let {
        DiagRow("שמע (SCO)", it, it.startsWith("מחובר") || it.startsWith("נתמך"))
      }
      state.scoTechnique?.let {
        DiagRow("טכניקת שמע אחרונה", it, true)
      }
      state.rawDropInfo?.let {
        DiagRow("ניתוקי קישור", it, it.startsWith("אין") || it.startsWith("ניתוק אחד"))
      }
      state.rawConnectionDiagnostics?.let {
        DiagRow("ניסיונות SDP/RFCOMM", it, false)
      }
      state.headsetClientPolicy?.let {
        DiagRow("מדיניות חיבור (פרופיל דיבורית)", it, it == "מאושר")
      }
      state.fullScreenAllowed?.let { allowed ->
        DiagRow(
          "מסך שיחה מלא",
          if (allowed) "מותר" else "חסום - השיחה תופיע כהודעה מוקפצת",
          allowed,
        )
        if (!allowed) {
          SettingRow(
            "אפשר מסך שיחה מלא",
            "פתח את הגדרות המערכת כדי לאשר הצגת שיחה נכנסת במסך מלא",
          ) {
            // Android 14+: the dedicated full-screen-intent settings page.
            val fsIntent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
              .setData(Uri.parse("package:${context.packageName}"))
            val opened = runCatching {
              context.startActivity(fsIntent)
              true
            }.getOrDefault(false)
            if (!opened) {
              onSnackbar("לא ניתן לפתוח את המסך הזה במכשיר הזה")
            }
          }
        }
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
      state.permissionHint?.let {
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
  }
}

/**
 * Tells the user in plain Hebrew what to do next, based on the current state.
 * Returns null when everything is fine (nothing to guide).
 */
private fun buildGuidance(state: BridgeUiState): String? = when {
  !state.adapterOn -> "הדלק את הבלוטוס בהגדרות המערכת וחזור לכאן."
  state.connectionState != BluetoothProfile.STATE_CONNECTED -> {
    when {
      !state.hiddenApiAvailable && !state.shizukuAvailable && state.rootAvailable ->
        "המכשיר חוסם את פרופיל הדיבורית, אבל יש בו הרשאת רוט. בחר 'ערוץ חיבור' → 'דרך הרשאת רוט (su)' " +
          "ואשר את בקשת ההרשאה (Magisk) - הערוץ יעבוד בלי Shizuku ובלי adb."
      !state.hiddenApiAvailable && !state.shizukuAvailable ->
        "המכשיר חוסם את פרופיל הדיבורית. שתי דרכים:\n" +
          "1) התקן Shizuku והפעל אותו פעם אחת (adb אלחוטי), ובחר 'דרך Shizuku' ב'ערוץ חיבור'.\n" +
          "2) בלי התקנות: זווג את הטלפון הכשר ובחר אותו באפליקציה - החיבור הישיר ינסה לבד."
      state.privilegedBlocked && state.rootAvailable ->
        "הגישה הישירה נחסמה, אבל יש רוט: בחר 'ערוץ חיבור' → 'דרך הרשאת רוט (su)' - " +
          "או התקן את מודול ה-Magisk לעבודה קבועה."
      !state.profileReady && state.rawDropInfo != null ->
        "הקישור נופל שוב ושוב. מחק את זיווג הטלפון וזווג אותו מחדש - עכשיו האפליקציה תכבה אוטומטית את החיבורים המערכתיים שמתחרים על הקישור."
      !state.profileReady ->
        "זווג את הטלפון הכשר ('צימוד מכשיר חדש' או הגדרות בלוטוס) ובחר אותו ב'בחר מכשיר'."
      else -> "בחר את הטלפון הכשר ב'בחר מכשיר'."
    }
  }
  state.audioState != 2 ->
    "מחובר. אם אין קול: רוץ 'בדיקת מיקרופון', ואם הקול לא עובר - נסה לשנות את 'ערוץ חיבור' ל-RFCOMM ישיר."
  else -> null
}

/** Builds the full local capability report copied by "העתק דוח אבחון". */
private fun buildDiagnosticsReport(state: BridgeUiState): String = buildString {
  appendLine("KosherBridge - דוח אבחון")
  appendLine("=======================")
  appendLine("גרסת אפליקציה: ${com.example.kosherbridge.BuildConfig.VERSION_NAME}")
  appendLine("מכשיר: ${state.deviceInfo ?: "-"}")
  appendLine("API נסתר (HFP): ${if (state.hiddenApiAvailable) "זמין" else "לא זמין"}")
  appendLine("פרופיל דיבורית (HFP Client): ${if (state.profileReady) "נתמך" else "לא נתמך"}")
  appendLine(
    "קישור פעיל: ${
      when {
        state.profileReady && state.connectionState == BluetoothProfile.STATE_CONNECTED -> "כן (פרופיל)"
        state.rawLinkActive -> "כן (RFCOMM ישיר)"
        state.connectionState == BluetoothProfile.STATE_CONNECTED -> "כן"
        else -> "לא"
      }
    }",
  )
  appendLine("ערוץ פעיל: ${state.backendLabel ?: "לא פעיל"}")
  if (!state.profileReady && state.rawLinkActive) {
    appendLine(
      "הנגן לא חושף את פרופיל הדיבורית - בקרת שיחות תעבוד בערוץ הישיר, אבל הקול יישאר בטלפון. " +
        "ערוץ Shizuku לא יעזור כאן. ראו מודול Magisk ב-README.",
    )
  }
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
  appendLine("Root (su): ${if (state.rootAvailable) "זמין" else "לא זמין"}")
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
  state.rawDropInfo?.let { appendLine("ניתוקי קישור: $it") }
  state.rawConnectionDiagnostics?.let { appendLine("ניסיונות SDP/RFCOMM: $it") }
  state.headsetClientPolicy?.let { appendLine("מדיניות חיבור (פרופיל דיבורית): $it") }
  state.fullScreenAllowed?.let {
    appendLine("מסך שיחה מלא: ${if (it) "מותר" else "חסום - השיחה תופיע כהודעה מוקפצת"}")
  }
  if (state.connectionLog.isNotEmpty()) {
    appendLine("יומן חיבור:")
    state.connectionLog.forEach { appendLine(it) }
  }
  state.lastError?.let { appendLine("שגיאה אחרונה: $it") }
}
