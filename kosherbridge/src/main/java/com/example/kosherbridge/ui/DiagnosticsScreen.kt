package com.example.kosherbridge.ui

import android.Manifest
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
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
import com.example.kosherbridge.bluetooth.TelecomBridge

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
  // Recomputed on every recomposition (no remember): the user can grant or
  // revoke these in system settings while this screen is open, and a cached
  // value would keep offering a button that does nothing.
  val telecomMissing = TelecomBridge.missingPermissions(context)
  val ignoringBattery = runCatching {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
  }.getOrDefault(false)

  val telecomPermissions = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { grants ->
    onSnackbar(
      if (grants.values.all { it }) "ההרשאות אושרו - ערוץ המערכת מוכן"
      else "חלק מההרשאות נדחו - פתח הגדרות → אפליקציות → גשר כשר → הרשאות",
    )
  }

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
      // The decisive row for a player with no root and no Shizuku: when this
      // says the system bridges the phone's calls, the player can carry call
      // AUDIO through the "מערכת (Telecom)" channel.
      state.telecomStatus?.let {
        DiagRow("ערוץ מערכת (Telecom)", it, it.startsWith("פעיל"))
      }
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
      // The Telecom channel is the only one that carries voice without root, and
      // it is driven entirely by these four ordinary runtime permissions. If the
      // user denied them (or revoked them later), the channel goes quiet with no
      // way back from inside the app - this row is that way back.
      if (telecomMissing.isNotEmpty()) {
        SettingRow(
          "אשר הרשאות לערוץ המערכת",
          "חסרות ${telecomMissing.size} הרשאות (מצב שיחות / יומן שיחות / מענה / חיוג) - בלעדיהן הערוץ לא יעבוד",
          error = true,
        ) { telecomPermissions.launch(telecomMissing.toTypedArray()) }
      }
      // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared in the manifest to stop
      // Doze suspending Bluetooth on MediaTek players - but declaring it only
      // permits ASKING. Nothing ever asked, so the protection did not exist.
      DiagRow(
        "חיסכון בסוללה",
        if (ignoringBattery) "מבוטל - החיבור יציב" else "פעיל - עלול לנתק את החיבור",
        ignoringBattery,
      )
      if (!ignoringBattery) {
        SettingRow(
          "בטל חיסכון בסוללה לאפליקציה",
          "מונע מהמערכת להשהות את הבלוטוס כשהמסך כבוי - מומלץ בנגנים שמתנתקים",
        ) {
          val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
          val opened = runCatching { context.startActivity(intent); true }.getOrDefault(false)
          if (!opened) {
            // Some players ship without that dialog; the app-details page is the
            // fallback the user can still reach the setting from.
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
              .setData(Uri.parse("package:${context.packageName}"))
            if (runCatching { context.startActivity(fallback); true }.getOrDefault(false)) {
              onSnackbar("פתח: סוללה ← ללא הגבלה")
            } else {
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
  // Before sending anyone to Shizuku or root: if the platform already bridges
  // the phone's calls, the system channel gives full calls INCLUDING voice with
  // nothing but runtime permissions. That is strictly the best option, so it is
  // offered first whenever it is actually available.
  state.telecomStatus?.startsWith("פעיל") == true && state.backendLabel?.contains("Telecom") != true ->
    "הנגן הזה מגשר את שיחות הטלפון בעצמו. בחר 'ערוץ חיבור' ← 'דרך המערכת (Telecom)' - " +
      "ככה מקבלים שיחות מלאות כולל קול, בלי רוט ובלי Shizuku."
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
  state.telecomStatus?.let { appendLine("ערוץ מערכת (Telecom): $it") }
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
