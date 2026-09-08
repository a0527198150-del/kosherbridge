package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers, on the player itself, the one question that decides whether call
 * audio can ever work here - without adb, without a second person holding the
 * device, and without root.
 *
 * The distinction it draws is the one every confusing report has turned on:
 *
 *  - The HFP-client **service component** can be present in the Bluetooth APK
 *    while the **profile** was never started. `getProfileProxy` binds happily
 *    to that dormant component, every call returns empty, and the bridge used
 *    to conclude "the profile is supported". [profileEnabled] reads what the
 *    stack actually started, so a dormant profile is reported as dormant.
 *  - When the profile is dormant, the reason is almost always the build flag
 *    `bluetooth.profile.hfp.hf.enabled`, which is unset on players that are
 *    not automotive builds. [profileFlag] reads it directly.
 *  - Whether that flag can be flipped without root is decided by the player's
 *    SELinux policy, not by its model name: stock policy puts the Bluetooth
 *    profile flags in a context only `init` may write, while the lax policies
 *    common to cheap players often allow it. [selinuxMode] is the honest
 *    predictor, and the privileged write is attempted rather than guessed.
 */
data class PlayerCapabilities(
  /** Manufacturer, model and Android version, for the report. */
  val device: String,
  /** True when the stack has the HFP-client profile RUNNING (profile id 16). */
  val profileEnabled: Boolean?,
  /** True when HeadsetClientService exists in the Bluetooth APK at all. */
  val profilePresent: Boolean?,
  /** Value of bluetooth.profile.hfp.hf.enabled ("" when unset). */
  val profileFlag: String,
  /** "Enforcing" / "Permissive" / "" when it could not be read. */
  val selinuxMode: String,
  /** True when the hidden BluetoothHeadsetClient class is reachable. */
  val hiddenApiReachable: Boolean,
) {

  /**
   * The verdict, in the terms the user actually needs: can this player carry
   * call audio, and if not, what is the next thing to try.
   */
  val verdict: String
    get() = when {
      profileEnabled == true ->
        "פרופיל הדיבורית פעיל בנגן - הקול אמור לעבור. אם עדיין אין קול, בדוק את " +
          "שורת 'ניתוב שמע השיחה' והרץ 'פתח ניתוב שמע לשיחה'."
      profilePresent == false ->
        "היצרן הוציא את פרופיל הדיבורית מהבנייה של הנגן. אין שום דרך תוכנתית " +
          "להחזיר אותו - גם לא עם רוט. הנגן יכול לשמש כשלט בלבד, והקול יישאר בטלפון."
      profileEnabled == false && selinuxMode.equals("Permissive", ignoreCase = true) ->
        "הפרופיל קיים בנגן אבל כבוי, ו-SELinux במצב Permissive - יש סיכוי טוב " +
          "שאפשר להדליק אותו בלי רוט. הרץ 'הפעל פרופיל דיבורית' (דרוש Shizuku)."
      profileEnabled == false ->
        "הפרופיל קיים בנגן אבל כבוי (bluetooth.profile.hfp.hf.enabled לא מוגדר). " +
          "SELinux במצב Enforcing, ולכן סביר שרק רוט/מודול Magisk יוכלו להדליק אותו - " +
          "אבל שווה לנסות 'הפעל פרופיל דיבורית' דרך Shizuku לפני שמוותרים."
      else ->
        "לא ניתן לקבוע את מצב הפרופיל בנגן הזה. הרץ 'בדוק יכולות הנגן' שוב אחרי " +
          "שהבלוטוס דלוק."
    }

  /** True when trying the privileged enable is worth the user's time. */
  val worthTryingEnable: Boolean
    get() = profileEnabled == false && profilePresent != false

  fun report(): String = buildString {
    appendLine("מכשיר: $device")
    appendLine(
      "פרופיל דיבורית פעיל במחסנית: " + when (profileEnabled) {
        true -> "כן"
        false -> "לא"
        null -> "לא ניתן לקריאה"
      },
    )
    appendLine(
      "קוד הפרופיל קיים בנגן: " + when (profilePresent) {
        true -> "כן"
        false -> "לא - הוצא מהבנייה"
        null -> "לא ניתן לקריאה"
      },
    )
    appendLine("bluetooth.profile.hfp.hf.enabled: ${profileFlag.ifBlank { "לא מוגדר" }}")
    appendLine("SELinux: ${selinuxMode.ifBlank { "לא ניתן לקריאה" }}")
    appendLine("API נסתר (HFP) נגיש: ${if (hiddenApiReachable) "כן" else "לא"}")
    appendLine("מסקנה: $verdict")
  }

  companion object {
    /** BluetoothProfile.HEADSET_CLIENT - hidden constant. */
    private const val PROFILE_HEADSET_CLIENT = 16

    const val HFP_HF_PROPERTY = "bluetooth.profile.hfp.hf.enabled"

    private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
    private const val HEADSET_CLIENT_SERVICE =
      "com.android.bluetooth.hfpclient.HeadsetClientService"

    /**
     * Probes the player. Runs off the main thread: reading a system property
     * and asking the package manager about another package are both binder
     * round trips.
     *
     * @param privilegedProfiles profile ids read through a bound Shizuku/root
     *   bridge, when one exists. `getSupportedProfiles` is a system API, so on
     *   a strict build the app process cannot read it and only the privileged
     *   process can answer.
     */
    suspend fun probe(
      context: Context,
      privilegedProfiles: List<Int>? = null,
      privilegedSelinux: String? = null,
    ): PlayerCapabilities = withContext(Dispatchers.IO) {
      HiddenHfp.init()
      val profiles = privilegedProfiles?.takeIf { it.isNotEmpty() } ?: supportedProfiles(context)
      PlayerCapabilities(
        device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})",
        profileEnabled = profiles?.contains(PROFILE_HEADSET_CLIENT),
        profilePresent = headsetClientServicePresent(context),
        profileFlag = systemProperty(HFP_HF_PROPERTY),
        selinuxMode = privilegedSelinux?.takeIf { it.isNotBlank() } ?: selinuxMode(),
        hiddenApiReachable = HiddenHfp.isAvailable,
      )
    }

    /**
     * Profile ids the stack has actually started, or null when the read is
     * refused. Deliberately NOT confused with "the profile proxy binds": a
     * dormant HeadsetClientService binds and then answers nothing.
     */
    private fun supportedProfiles(context: Context): List<Int>? {
      val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
          ?: return null
      return runCatching {
        @Suppress("UNCHECKED_CAST")
        (adapter.javaClass.getMethod("getSupportedProfiles").invoke(adapter) as? List<Int>)
          ?.filterNotNull()
      }.getOrNull()
    }

    /**
     * Whether the Bluetooth APK declares HeadsetClientService at all. This is
     * the difference between "disabled, and therefore possibly fixable" and
     * "removed from the build, and therefore hopeless".
     */
    private fun headsetClientServicePresent(context: Context): Boolean? = runCatching {
      context.packageManager.getServiceInfo(
        ComponentName(BLUETOOTH_PACKAGE, HEADSET_CLIENT_SERVICE),
        0,
      )
      true
    }.getOrElse { error ->
      // NameNotFoundException is a real answer ("not in this build"); anything
      // else means the query itself failed and we must not claim to know.
      if (error is PackageManager.NameNotFoundException) false else null
    }

    /** Reads a system property. Readable by any app; no permission needed. */
    fun systemProperty(key: String): String = runCatching {
      Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
        .invoke(null, key, "") as? String ?: ""
    }.getOrElse { runCatching { exec("getprop", key) }.getOrDefault("") }

    /**
     * SELinux mode. This is the honest predictor of whether the profile flag
     * can be written without root, and it costs nothing to read.
     */
    private fun selinuxMode(): String {
      val fromBinary = runCatching { exec("getenforce") }.getOrDefault("")
      if (fromBinary.isNotBlank()) return fromBinary
      return runCatching {
        when (java.io.File("/sys/fs/selinux/enforce").readText().trim()) {
          "1" -> "Enforcing"
          "0" -> "Permissive"
          else -> ""
        }
      }.getOrDefault("")
    }

    private fun exec(vararg cmd: String): String {
      val p = Runtime.getRuntime().exec(cmd)
      val out = p.inputStream.bufferedReader().use { it.readText() }
      p.waitFor()
      return out.trim()
    }
  }
}
