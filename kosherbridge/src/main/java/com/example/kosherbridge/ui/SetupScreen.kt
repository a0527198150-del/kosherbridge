package com.example.kosherbridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.BridgeService
import com.example.kosherbridge.bluetooth.BridgeUiState
import com.example.kosherbridge.bluetooth.PlayerCapabilities
import kotlinx.coroutines.launch

/** How ready one requirement is. */
enum class SetupLevel { OK, ACTION, OPTIONAL }

/**
 * One line of the readiness checklist: what it is, where it stands, and the
 * single tap that fixes it.
 */
data class SetupItem(
  val title: String,
  val detail: String,
  val level: SetupLevel,
  val actionLabel: String? = null,
  val action: (() -> Unit)? = null,
)

/**
 * The readiness checklist.
 *
 * A bridge handed to someone else fails for reasons that have nothing to do
 * with Bluetooth: a permission denied on first launch, battery optimisation
 * killing the service overnight, a player whose stack cannot carry call audio
 * at all. Each of those produces the same useless impression - "it does not
 * work" - and none of them is discoverable from the main screen.
 *
 * This screen states every requirement, what it is currently, and the one tap
 * that fixes it, including the honest "this player can never carry call audio"
 * verdict. It is the first screen a new user should see.
 */
@Composable
fun SetupScreen(
  state: BridgeUiState,
  onSnackbar: (String) -> Unit,
  onBack: () -> Unit,
  onOpenConnectionSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var capabilities by remember { mutableStateOf<PlayerCapabilities?>(null) }
  var probeFailed by remember { mutableStateOf(false) }
  var enableResult by remember { mutableStateOf<String?>(null) }

  // Bumped whenever the user returns from a system settings screen, so every
  // permission row re-reads its real state instead of showing a stale "missing"
  // after the user just granted it.
  var refresh by remember { mutableIntStateOf(0) }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { refresh++ }
  val settingsLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { refresh++ }

  LaunchedEffect(refresh) {
    BridgeService.withManager(
      context,
      // Without this the call-audio row sat on "checking..." forever whenever
      // the service was not running - on the one screen a new user opens first.
      onMissing = { probeFailed = true },
    ) { bridge ->
      probeFailed = false
      scope.launch { capabilities = bridge.probeCapabilities() }
    }
  }

  val items = buildSetupItems(
    context = context,
    state = state,
    capabilities = capabilities,
    probeFailed = probeFailed,
    refreshKey = refresh,
    requestPermissions = { perms -> permissionLauncher.launch(perms.toTypedArray()) },
    openSettings = { intent -> runCatching { settingsLauncher.launch(intent) } },
    onOpenConnectionSettings = onOpenConnectionSettings,
  )
  val blocking = items.count { it.level == SetupLevel.ACTION }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageHeader("התקנה ומוכנות", onBack)

    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(
        containerColor = if (blocking == 0) {
          MaterialTheme.colorScheme.secondaryContainer
        } else {
          MaterialTheme.colorScheme.errorContainer
        },
      ),
    ) {
      Column(Modifier.padding(16.dp)) {
        Text(
          if (blocking == 0) "הכול מוכן" else "$blocking דברים דורשים טיפול",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          if (blocking == 0) {
            "הגשר מוגדר כראוי בנגן הזה. פריטים המסומנים כרשות ישפרו את החוויה אך אינם חובה."
          } else {
            "כל פריט אדום למטה הוא דבר שימנע מהגשר לעבוד כמו שצריך. לחיצה על הפריט פותחת את המסך שמתקן אותו."
          },
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    SettingsCard("מוכנות הנגן") {
      items.forEach { item ->
        SetupRow(item)
      }
    }

    capabilities?.let { caps ->
      if (caps.worthTryingEnable) {
        SettingsCard("ניסיון מתקדם (בלי רוט)") {
          SettingRow(
            "הפעל פרופיל דיבורית",
            enableResult
              ?: "מנסה להדליק את פרופיל הקול בנגן דרך Shizuku. מצליח רק אם מדיניות " +
              "האבטחה של הנגן מרשה זאת - הפעולה תדווח בדיוק מה קרה",
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
      }
    }
  }
}

@Composable
private fun SetupRow(item: SetupItem) {
  val tint = when (item.level) {
    SetupLevel.OK -> Color(0xFF00875A)
    SetupLevel.ACTION -> MaterialTheme.colorScheme.error
    SetupLevel.OPTIONAL -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val icon = when (item.level) {
    SetupLevel.OK -> Icons.Filled.CheckCircle
    SetupLevel.ACTION -> Icons.Filled.Warning
    SetupLevel.OPTIONAL -> Icons.Filled.Info
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
      Text(
        item.detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (item.action != null && item.actionLabel != null) {
        Spacer(Modifier.height(6.dp))
        Text(
          item.actionLabel,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Medium,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { item.action.invoke() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        )
      }
    }
  }
}

/**
 * Builds the checklist. [refreshKey] is unused inside but makes the caller
 * recompute every row after the user returns from a system settings screen -
 * permission state is read imperatively and has no flow to observe.
 */
private fun buildSetupItems(
  context: Context,
  state: BridgeUiState,
  capabilities: PlayerCapabilities?,
  probeFailed: Boolean,
  @Suppress("UNUSED_PARAMETER") refreshKey: Int,
  requestPermissions: (List<String>) -> Unit,
  openSettings: (Intent) -> Unit,
  onOpenConnectionSettings: () -> Unit,
): List<SetupItem> {
  val items = mutableListOf<SetupItem>()

  // 1. Bluetooth - the one permission the bridge genuinely cannot run without.
  val btPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
  } else {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
  }
  val missingBt = btPerms.filter {
    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
  }
  items += SetupItem(
    title = "הרשאת בלוטוס",
    detail = if (missingBt.isEmpty()) {
      "מאושרת - הגשר יכול להתחבר לטלפון הכשר"
    } else {
      "חסרה. בלעדיה הגשר לא יכול להתחבר לטלפון כלל"
    },
    level = if (missingBt.isEmpty()) SetupLevel.OK else SetupLevel.ACTION,
    actionLabel = if (missingBt.isEmpty()) null else "אשר עכשיו",
    action = if (missingBt.isEmpty()) null else ({ requestPermissions(missingBt) }),
  )

  // 2. Battery optimisation. The single biggest cause of "it worked yesterday":
  // Doze suspends the service overnight, and then the ring never arrives, the
  // buttons do nothing, and the notification sticks around.
  val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
  val batteryExempt = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
    runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() == true
  items += SetupItem(
    title = "פטור מחיסכון בסוללה",
    detail = if (batteryExempt) {
      "מאושר - המערכת לא תרדים את הגשר"
    } else {
      "לא מאושר. זו הסיבה הנפוצה ביותר לכך שהגשר מפסיק לעבוד אחרי כמה שעות: " +
        "המערכת מרדימה אותו, ואז שיחות נכנסות לא מצלצלות והכפתורים לא מגיבים"
    },
    level = if (batteryExempt) SetupLevel.OK else SetupLevel.ACTION,
    actionLabel = if (batteryExempt) null else "אשר עכשיו",
    action = if (batteryExempt) {
      null
    } else {
      {
        // The targeted dialog when it is available, otherwise the system list.
        // @SuppressLint, not @Suppress: BatteryLife is an Android LINT check
        // (severity error), and Kotlin's @Suppress does not reach it - which is
        // why the lint step was failing. The exemption is legitimate here: this
        // is a foreground bridge that must survive Doze to receive calls at
        // all, and it is requested by an explicit user tap, never silently.
        @SuppressLint("BatteryLife")
        val direct = Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:${context.packageName}"),
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (direct.resolveActivity(context.packageManager) != null) {
          openSettings(direct)
        } else {
          openSettings(fallback)
        }
      }
    },
  )

  // 2b. Vendor auto-start managers. Android has no API to read or set these,
  // so the honest thing is to say it plainly and open the vendor screen when
  // one is known. On the Chinese players this targets, an app missing from
  // that list is killed no matter what Android's own settings say - which is
  // indistinguishable, from the user's side, from the app being broken.
  autoStartIntent(context)?.let { intent ->
    items += SetupItem(
      title = "הפעלה אוטומטית (הגדרת יצרן)",
      detail = "לנגן הזה יש מנהל הפעלה־אוטומטית משלו, שאנדרואיד לא מאפשר לאפליקציה " +
        "לקרוא או לשנות. ודא ש'גשר כשר' מסומן שם, אחרת המערכת תסגור אותו בלי קשר " +
        "להרשאות שאישרת כאן",
      level = SetupLevel.OPTIONAL,
      actionLabel = "פתח את המסך של היצרן",
      action = { openSettings(intent) },
    )
  }

  // 3. Notifications - how an incoming call reaches the user when the
  // full-screen activity is refused.
  val notifOk = Build.VERSION.SDK_INT < 33 ||
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
    PackageManager.PERMISSION_GRANTED
  items += SetupItem(
    title = "הרשאת התראות",
    detail = if (notifOk) {
      "מאושרת - שיחות נכנסות יופיעו כהתראה"
    } else {
      "חסרה. הגשר יעבוד, אבל שיחה נכנסת לא תציג התראה עם כפתורי מענה/דחייה"
    },
    level = if (notifOk) SetupLevel.OK else SetupLevel.ACTION,
    actionLabel = if (notifOk) null else "אשר עכשיו",
    action = if (notifOk) {
      null
    } else {
      { requestPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS)) }
    },
  )

  // 4. Full-screen call UI (Android 14+ makes it user-revocable).
  state.fullScreenAllowed?.let { allowed ->
    items += SetupItem(
      title = "מסך שיחה מלא",
      detail = if (allowed) {
        "מותר - שיחה נכנסת תפתח מסך מלא"
      } else {
        "חסום. שיחה נכנסת תופיע רק כהתראה מוקפצת, בלי מסך שיחה"
      },
      level = if (allowed) SetupLevel.OK else SetupLevel.ACTION,
      actionLabel = if (allowed) null else "פתח הגדרות",
      action = if (allowed) {
        null
      } else {
        {
          openSettings(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
              .setData(Uri.parse("package:${context.packageName}")),
          )
        }
      },
    )
  }

  // 5. The paired phone.
  items += SetupItem(
    title = "טלפון כשר מזווג",
    detail = state.deviceName?.let { "נבחר: $it" } ?: "עדיין לא נבחר טלפון",
    level = if (state.deviceName != null) SetupLevel.OK else SetupLevel.ACTION,
    actionLabel = if (state.deviceName != null) null else "בחר טלפון",
    action = if (state.deviceName != null) null else onOpenConnectionSettings,
  )

  // 6. Call audio - the one item that can be honestly impossible, and the one
  // the user most needs a straight answer about before buying into the app.
  val caps = capabilities
  items += when {
    caps == null && probeFailed -> SetupItem(
      title = "קול בנגן",
      detail = "לא ניתן לבדוק - שירות הגשר אינו פעיל. פתח את המסך הראשי וחזור לכאן",
      level = SetupLevel.ACTION,
    )
    caps == null -> SetupItem(
      title = "קול בנגן",
      detail = "בודק את יכולות הנגן...",
      level = SetupLevel.OPTIONAL,
    )
    caps.profileEnabled == true -> SetupItem(
      title = "קול בנגן",
      detail = "פרופיל הדיבורית פעיל - הקול אמור לעבור דרך הנגן",
      level = SetupLevel.OK,
    )
    caps.profilePresent == false -> SetupItem(
      title = "קול בנגן",
      detail = "הנגן הזה לא יכול לקלוט קול שיחה - היצרן הוציא את פרופיל הדיבורית " +
        "מהבנייה. בקרת השיחות תעבוד מצוין; הקול יישאר בטלפון הכשר",
      level = SetupLevel.OPTIONAL,
    )
    else -> SetupItem(
      title = "קול בנגן",
      detail = "פרופיל הדיבורית קיים בנגן אבל כבוי. בקרת השיחות עובדת; לקול צריך " +
        "להדליק את הפרופיל - ראה 'ניסיון מתקדם' למטה",
      level = SetupLevel.OPTIONAL,
    )
  }

  // 7. Shizuku - optional, and only worth mentioning when it would change
  // something. Telling every user to install a developer tool they do not need
  // is how a setup screen becomes noise.
  if (caps != null && caps.profileEnabled != true && caps.profilePresent != false) {
    val shizukuReady = state.shizukuAvailable && state.shizukuGranted
    items += SetupItem(
      title = "Shizuku (רשות - לקול)",
      detail = when {
        shizukuReady -> "מחובר ומאושר - אפשר לנסות להדליק את פרופיל הקול"
        state.shizukuAvailable -> "מותקן ופועל, אך טרם אושר לאפליקציה. פתח את Shizuku ואשר"
        else -> "לא פועל. Shizuku מאפשר פעולות מורשות בלי רוט (מופעל פעם אחת דרך " +
          "ניפוי באגים אלחוטי). בלעדיו לא ניתן לנסות להדליק את פרופיל הקול"
      },
      level = SetupLevel.OPTIONAL,
      actionLabel = if (shizukuReady) null else "פתח את Shizuku",
      action = if (shizukuReady) {
        null
      } else {
        {
          val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
          if (launch != null) {
            openSettings(launch)
          } else {
            // Not installed. A player often has no Play Store at all, so try
            // the store page and fall back to the project's site rather than
            // leaving a dead-end instruction on screen.
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            if (store.resolveActivity(context.packageManager) != null) {
              openSettings(store)
            } else {
              openSettings(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SITE)))
            }
          }
        }
      },
    )
  }

  return items
}

/**
 * The vendor auto-start screen for this player, or null when none is known.
 *
 * These are undocumented, vendor-private activities; each entry is resolved
 * against the package manager before it is offered, so a wrong guess shows
 * nothing rather than a button that dead-ends.
 */
private fun autoStartIntent(context: Context): Intent? {
  val candidates = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
  )
  for ((pkg, cls) in candidates) {
    val intent = Intent().setClassName(pkg, cls)
    if (intent.resolveActivity(context.packageManager) != null) return intent
  }
  return null
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_SITE = "https://shizuku.rikka.app/"
