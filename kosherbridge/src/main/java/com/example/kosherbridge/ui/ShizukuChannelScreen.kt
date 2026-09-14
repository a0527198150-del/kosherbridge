package com.example.kosherbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.BridgeService
import com.example.kosherbridge.bluetooth.BridgeUiState
import com.example.kosherbridge.bluetooth.PlayerCapabilities
import kotlinx.coroutines.launch

/** Where the Shizuku route currently stands. Each state has ONE next step. */
private enum class ShizukuStage { NOT_INSTALLED, NOT_RUNNING, NOT_GRANTED, READY }

/**
 * Sets up the Shizuku route to a shell identity.
 *
 * In the standard build this is the ONLY way to get one - the in-app ADB
 * channel lives in the "plus" build, because it needs a network permission this
 * app's users have a specific reason to refuse. So this screen carries the
 * whole weight for most installs, and it used to be a single row on the
 * readiness list saying "not running. Open Shizuku."
 *
 * That is not enough. The route has four distinct states, each with exactly one
 * next step and each failing in its own way, and a user who is told "open
 * Shizuku" when the real problem is that wireless debugging is off will open
 * Shizuku, find nothing, and conclude the app is broken. The stage is detected
 * and only its own step is offered.
 */
@Composable
fun ShizukuChannelScreen(
  state: BridgeUiState,
  onSnackbar: (String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var result by remember { mutableStateOf<String?>(null) }
  // Bumped after returning from Shizuku or Settings, so the stage is re-read
  // instead of showing what it was when the screen opened.
  var refresh by remember { mutableIntStateOf(0) }
  var wirelessDebugging by remember { mutableStateOf<Boolean?>(null) }

  LaunchedEffect(refresh) {
    // World-readable, so this needs no channel - which matters here, since the
    // whole screen exists because there is no channel yet.
    wirelessDebugging = PlayerCapabilities.wirelessDebuggingEnabled(context)
  }

  val installed = remember(refresh) { isInstalled(context, SHIZUKU_PACKAGE) }
  val stage = when {
    !installed -> ShizukuStage.NOT_INSTALLED
    !state.shizukuAvailable -> ShizukuStage.NOT_RUNNING
    !state.shizukuGranted -> ShizukuStage.NOT_GRANTED
    else -> ShizukuStage.READY
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageHeader("ערוץ Shizuku", onBack)

    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(
        containerColor = if (stage == ShizukuStage.READY) {
          MaterialTheme.colorScheme.secondaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceVariant
        },
      ),
    ) {
      Column(Modifier.padding(16.dp)) {
        Text(
          when (stage) {
            ShizukuStage.NOT_INSTALLED -> "Shizuku אינו מותקן"
            ShizukuStage.NOT_RUNNING -> "Shizuku מותקן אך אינו פועל"
            ShizukuStage.NOT_GRANTED -> "Shizuku פועל אך טרם אישר לאפליקציה"
            ShizukuStage.READY -> "מוכן - ערוץ ההרשאות זמין"
          },
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          "Shizuku נותן לאפליקציה זהות shell בלי רוט. זה מה שמאפשר לפתוח את שער " +
            "השמע, לתקן מדיניות חיבור, ובנגן עם SELinux מתירני גם להדליק את פרופיל " +
            "הדיבורית.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    result?.let { text ->
      Card(shape = RoundedCornerShape(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
      }
    }

    // Only the step this stage is actually waiting on. Showing all four at once
    // is how a setup screen becomes a wall of text nobody reads.
    when (stage) {
      ShizukuStage.NOT_INSTALLED -> SettingsCard("שלב 1 - התקן את Shizuku") {
        Text(
          "Shizuku היא אפליקציה חינמית וקוד פתוח. בנגן רבים אין חנות אפליקציות, " +
            "ולכן אם הכפתור לא פותח חנות הוא יפתח את אתר הפרויקט להורדה ישירה.",
          style = MaterialTheme.typography.bodySmall,
        )
        SettingRow("פתח את דף ההתקנה", SHIZUKU_SITE) {
          if (!openStoreOrSite(context)) {
            onSnackbar("לא ניתן לפתוח דפדפן - הורד את Shizuku ידנית")
          }
        }
      }

      ShizukuStage.NOT_RUNNING -> SettingsCard("שלב 2 - הפעל את Shizuku") {
        Text(
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "באנדרואיד 11 ומעלה Shizuku מופעלת מתוך עצמה, דרך ניפוי באגים אלחוטי - " +
              "בלי מחשב. סדר הפעולות: הדלק ניפוי באגים אלחוטי, פתח את Shizuku, " +
              "ובחר שם 'Start via wireless debugging'. Shizuku תבקש התאמה עם קוד " +
              "בן שש ספרות ותטפל בה בעצמה."
          } else {
            "בנגן הזה (אנדרואיד לפני 11) אין ניפוי באגים אלחוטי, ולכן Shizuku " +
              "חייבת להיות מופעלת ממחשב פעם אחת אחרי כל הדלקה, עם הפקודה שמופיעה " +
              "בתוך Shizuku. זו מגבלה של גרסת האנדרואיד, לא של האפליקציה."
          },
          style = MaterialTheme.typography.bodySmall,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          SettingRow(
            "ניפוי באגים אלחוטי",
            when (wirelessDebugging) {
              true -> "דלוק - אפשר להמשיך ל-Shizuku"
              false -> "כבוי. זה השלב שחוסם עכשיו - לחץ כדי לפתוח את המסך"
              null -> "לא ניתן לקריאה - פתח את אפשרויות המפתח ובדוק"
            },
            error = wirelessDebugging == false,
          ) {
            if (!openDeveloperOptionsScreen(context)) {
              onSnackbar("לא ניתן לפתוח את המסך אוטומטית - הגדרות ← אפשרויות מפתח")
            }
          }
        }
        SettingRow("פתח את Shizuku", "ובחר שם את אפשרות ההפעלה") {
          if (!openApp(context, SHIZUKU_PACKAGE)) {
            onSnackbar("לא ניתן לפתוח את Shizuku")
          }
        }
      }

      ShizukuStage.NOT_GRANTED -> SettingsCard("שלב 3 - אשר לאפליקציה") {
        Text(
          "Shizuku פועלת. נשאר רק לאשר לגשר הכשר להשתמש בה - האישור מוצג " +
            "כחלון קופץ של Shizuku.",
          style = MaterialTheme.typography.bodySmall,
        )
        SettingRow("בקש אישור עכשיו", "ואשר בחלון שייפתח") {
          result = "מבקש אישור..."
          BridgeService.withManager(
            context,
            onMissing = { result = "שירות הגשר לא פעיל - פתח את המסך הראשי" },
          ) { bridge ->
            // bindShizuku() raises Shizuku's own grant dialog when the
            // permission is missing, which is exactly this step.
            scope.launch {
              result = if (bridge.bindShizuku()) {
                "האישור התקבל - הערוץ פעיל"
              } else {
                BridgeHub.state.value.lastError ?: "האישור לא התקבל - נסה שוב"
              }
              refresh++
            }
          }
        }
      }

      ShizukuStage.READY -> SettingsCard("מוכן") {
        Text(
          "אפשר לבחור הגדרות ← כל הגדרות החיבור ← 'ערוץ חיבור' ← 'דרך Shizuku'. " +
            "שים לב ש-Shizuku נעצרת בכיבוי הנגן ויש להפעיל אותה מחדש אחרי כל " +
            "הדלקה - זו מגבלה של Shizuku עצמה.",
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          "אם הדלקת כאן את פרופיל הדיבורית: המאפיין שמדליק אותו נמחק בכל אתחול. " +
            "האפליקציה זוכרת איזה מאפיין עבד ומחזירה אותו לבד ברגע ש-Shizuku " +
            "חוזרת לפעול - היא ממתינה לה עד חצי שעה אחרי ההדלקה. אין צורך " +
            "לחזור על ההדלקה ידנית, רק להפעיל את Shizuku.",
          style = MaterialTheme.typography.bodySmall,
        )
        SettingRow("בדוק את הערוץ", "מתחבר דרך Shizuku ומדווח מה קרה") {
          scope.launch {
            result = "בודק..."
            BridgeService.withManager(
              context,
              onMissing = { result = "שירות הגשר לא פעיל - פתח את המסך הראשי" },
            ) { bridge ->
              scope.launch {
                result = if (bridge.bindShizuku()) {
                  "הערוץ עובד - פרופיל הדיבורית נרשם דרך Shizuku"
                } else {
                  BridgeHub.state.value.lastError ?: "החיבור דרך Shizuku נכשל"
                }
                refresh++
              }
            }
          }
        }
      }
    }
  }
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_SITE = "https://shizuku.rikka.app/"

private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
  context.packageManager.getLaunchIntentForPackage(pkg) != null
}.getOrDefault(false)

private fun openApp(context: Context, pkg: String): Boolean = runCatching {
  val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
  context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  true
}.getOrDefault(false)

private fun openStoreOrSite(context: Context): Boolean {
  val candidates = listOf(
    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")),
    Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SITE)),
  )
  for (intent in candidates) {
    val ok = runCatching {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    }.getOrDefault(false)
    if (ok) return true
  }
  return false
}
