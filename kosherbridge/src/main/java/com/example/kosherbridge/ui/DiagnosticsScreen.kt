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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.BridgeService
import com.example.kosherbridge.bluetooth.BridgeUiState
import com.example.kosherbridge.bluetooth.CallAudioOutcome
import com.example.kosherbridge.bluetooth.PlayerCapabilities
import kotlinx.coroutines.launch

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
  val scope = rememberCoroutineScope()
  var micResult by remember { mutableStateOf<String?>(null) }
  var capabilities by remember { mutableStateOf<PlayerCapabilities?>(null) }
  var enableResult by remember { mutableStateOf<String?>(null) }
  var probing by remember { mutableStateOf(false) }

  // The probe is the first thing this screen should be able to answer, so run
  // it on open rather than making the user find a button. It is a handful of
  // binder reads, all off the main thread.
  LaunchedEffect(Unit) {
    BridgeService.withManager(context) { bridge ->
      scope.launch { capabilities = bridge.probeCapabilities() }
    }
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
      DiagRow(
        "חיבור",
        connectionText(state),
        state.connectionState == BluetoothProfile.STATE_CONNECTED || state.rawLinkActive,
      )
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
      state.audioRouteAllowed?.let {
        DiagRow("ניתוב שמע השיחה (HFP Client)", it, it.startsWith("מאושר"))
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
      SettingRow(
        "בדוק יכולות הנגן",
        if (probing) "בודק..." else "מה הנגן הזה באמת מסוגל לעשות - פרופיל, מאפיין מערכת ו-SELinux",
      ) {
        probing = true
        BridgeService.withManager(
          context,
          onMissing = {
            probing = false
            onSnackbar("שירות הגשר לא פעיל - פתח את המסך הראשי ונסה שוב")
          },
        ) { bridge ->
          scope.launch {
            capabilities = bridge.probeCapabilities()
            probing = false
          }
        }
      }
      capabilities?.let { caps ->
        DiagRow(
          "פרופיל דיבורית פעיל במחסנית",
          when (caps.profileEnabled) {
            true -> "כן"
            false -> "לא - כבוי"
            null -> "לא ניתן לקריאה"
          },
          caps.profileEnabled == true,
        )
        DiagRow(
          "קוד הפרופיל קיים בנגן",
          when (caps.profilePresent) {
            true -> "כן"
            false -> "לא - הוצא מהבנייה"
            null -> "לא ניתן לקריאה"
          },
          caps.profilePresent == true,
        )
        DiagRow(
          "מאפיין hfp.hf.enabled",
          caps.profileFlag.ifBlank { "לא מוגדר" },
          caps.profileFlag == "true",
        )
        DiagRow(
          "מאפיין שער השמע",
          caps.audioRouteFlag.ifBlank { "לא מוגדר" },
          caps.audioRouteFlag == "true",
        )
        DiagRow(
          "SELinux",
          caps.selinuxMode.ifBlank { "לא ניתן לקריאה" },
          caps.selinuxMode.equals("Permissive", ignoreCase = true),
        )
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
          ),
        ) {
          Text(
            caps.verdict,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
          )
        }
        if (caps.worthTryingEnable) {
          SettingRow(
            "הפעל פרופיל דיבורית (בלי רוט)",
            enableResult
              ?: "מנסה להדליק את הפרופיל דרך Shizuku ולהפעיל מחדש את הבלוטוס. " +
              "מצליח רק אם מדיניות המערכת בנגן מרשה זאת",
          ) {
            BridgeService.withManager(
              context,
              onMissing = { onSnackbar("שירות הגשר לא פעיל - פתח את המסך הראשי ונסה שוב") },
            ) { bridge ->
              scope.launch {
                enableResult = "מנסה..."
                enableResult = bridge.tryEnableHeadsetClientProfile()
                capabilities = bridge.probeCapabilities()
              }
            }
          }
        }
        SettingRow(
          "הסר חסימת API נסתר",
          "דרוש Shizuku/רוט. שים לב: זו הגדרה גלובלית שמשפיעה על כל האפליקציות בנגן",
        ) {
          BridgeService.withManager(
            context,
            onMissing = { onSnackbar("שירות הגשר לא פעיל - פתח את המסך הראשי ונסה שוב") },
          ) { bridge ->
            scope.launch { onSnackbar(bridge.liftHiddenApiRestriction()) }
          }
        }
      }
      SettingRow(
        "פתח ניתוב שמע לשיחה",
        "מבקש מהמערכת לאשר קליטת קול השיחה בנגן. הרץ אם השיחה מתחברת אבל אין קול באף צד",
      ) {
        BridgeService.withManager(context) { bridge ->
          bridge.allowAudioRoute(null, forceRetry = true)
        }
        onSnackbar("הבקשה נשלחה - התוצאה מופיעה בשורה 'ניתוב שמע השיחה'")
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
        cm.setPrimaryClip(ClipData.newPlainText("kosherbridge-diagnostics", buildDiagnosticsReport(state, capabilities)))
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
  !linkUp(state) -> {
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
  // The audio-route gate is the single most common reason a connected bridge
  // is silent on BOTH devices, so it is checked before the generic advice.
  state.audioRouteAllowed?.startsWith("חסום") == true ->
    "המערכת חוסמת את ניתוב קול השיחה לנגן, ולכן הטלפון מוסר את השיחה והקול אובד בשני הצדדים. " +
      "הרץ 'פתח ניתוב שמע לשיחה'; אם הוא נשאר חסום, עבור ל'ערוץ חיבור' → Shizuku או רוט, " +
      "או ל-RFCOMM ישיר - שם השמע נשאר בטלפון והנגן משמש כשלט."
  state.audioOutcome == CallAudioOutcome.ON_PHONE ->
    "השיחה מחוברת אבל הקול נשאר בטלפון הכשר - דבר ושמע בטלפון. " +
      "לחיצה על 'העבר שמע' במסך השיחה מנסה למשוך את הקול לנגן."
  state.audioState != 2 ->
    "מחובר. אם אין קול: רוץ 'פתח ניתוב שמע לשיחה' ואז 'בדיקת מיקרופון', " +
      "ואם הקול עדיין לא עובר - נסה לשנות את 'ערוץ חיבור' ל-RFCOMM ישיר."
  else -> null
}

/** Builds the full local capability report copied by "העתק דוח אבחון". */
private fun buildDiagnosticsReport(
  state: BridgeUiState,
  capabilities: PlayerCapabilities?,
): String = buildString {
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
  state.audioRouteAllowed?.let { appendLine("ניתוב שמע השיחה (HFP Client): $it") }
  capabilities?.let {
    appendLine()
    appendLine("-- יכולות הנגן --")
    append(it.report())
  }
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
