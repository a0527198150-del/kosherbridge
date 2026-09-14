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
  /** Value of the HFP-client audio-gate property ("" when unset). */
  val audioRouteFlag: String,
  /** "Enforcing" / "Permissive" / "" when it could not be read. */
  val selinuxMode: String,
  /** True when the hidden BluetoothHeadsetClient class is reachable. */
  val hiddenApiReachable: Boolean,
  /**
   * Every profile id the stack actually started, or null when unreadable.
   *
   * The single "is 16 there" answer was not enough to reason about a player
   * from a screenshot: whether A2DP-sink is on says what kind of build this is,
   * and the presence of the other client-role profiles (PBAP_CLIENT,
   * MAP_CLIENT, AVRCP_CONTROLLER) is the signature of a build that was
   * configured for car/media use - exactly the builds where HFP-client is
   * plausible.
   */
  val enabledProfiles: List<Int>? = null,
  /**
   * The connection channel in force when this probe was taken.
   *
   * Part of the capability picture, not decoration: "the profile is on" and
   * "the profile is being used" are different facts, and on this app they can
   * disagree. AUTO and RAW carry calls over a raw RFCOMM socket and
   * deliberately switch the system hands-free profile OFF for the bridged
   * phone, so that the app's socket does not compete with it for the phone's
   * single hands-free slot. On a player where the profile is enabled, that
   * trade is backwards - the profile is the only path that carries VOICE -
   * and the verdict has to say so instead of promising audio.
   */
  val activeChannel: String? = null,
  /** Developer options on. Wireless debugging is inside them. */
  val adbEnabled: Boolean? = null,
  /** Wireless debugging on - the gate on every no-root shell route. */
  val wirelessDebugging: Boolean? = null,
  /** Android API level of the player - this decides how the profile is gated. */
  val sdkInt: Int = Build.VERSION.SDK_INT,
  /**
   * Every Bluetooth-related system property this player actually has, with its
   * value.
   *
   * The app used to carry a hand-written list of five property names collected
   * from build.prop recipes, and try each one. That is guessing: a vendor fork
   * uses whatever name its own engineers chose, and no list written elsewhere
   * can contain it. Reading what this specific player HAS turns the guess into
   * a measurement - and `getprop` output is world-readable, so it needs no
   * Shizuku, no ADB and no root. It works in the standard build, on a stock
   * device, with nothing set up.
   */
  val bluetoothProperties: Map<String, String> = emptyMap(),
) {

  /**
   * The verdict, in the terms the user actually needs: can this player carry
   * call audio, and if not, what is the next thing to try.
   */
  val verdict: String
    get() = when {
      profileEnabled == true && channelBypassesProfile ->
        "פרופיל הדיבורית פעיל בנגן - אבל ערוץ החיבור הנוכחי אינו משתמש בו. הערוץ " +
          "האוטומטי (וגם 'RFCOMM ישיר') פותח שקע ישיר אל הטלפון ומכבה בכוונה את " +
          "פרופיל הדיבורית של הנגן, כדי ששניהם לא יתחרו על חיבור הדיבורית היחיד " +
          "שיש לטלפון. זה נכון בנגן שאין לו פרופיל - אבל כאן יש, והפרופיל הוא " +
          "היחיד שמעביר קול. עבור להגדרות ← כל הגדרות החיבור ← 'ערוץ חיבור' " +
          "ובחר Shizuku, ADB מקומי, רוט או 'ישיר' - ואז התחבר מחדש."
      profileEnabled == true ->
        "פרופיל הדיבורית פעיל בנגן - הקול אמור לעבור. אם עדיין אין קול, בדוק את " +
          "שורת 'ניתוב שמע השיחה' והרץ 'פתח ניתוב שמע לשיחה'."
      profilePresent == false ->
        "היצרן הוציא את פרופיל הדיבורית מהבנייה של הנגן. אין שום דרך תוכנתית " +
          "להחזיר אותו - גם לא עם רוט. הנגן יכול לשמש כשלט בלבד, והקול יישאר בטלפון."
      profileEnabled == false && selinuxMode.equals("Permissive", ignoreCase = true) ->
        "הפרופיל קיים בנגן אבל כבוי, ו-SELinux במצב Permissive - יש סיכוי טוב " +
          "שאפשר להדליק אותו בלי רוט. הרץ 'הפעל פרופיל דיבורית' (דרוש Shizuku)."
      profileEnabled == false && !profileGateIsProperty ->
        "הפרופיל קיים בנגן אבל כבוי, והנגן מריץ אנדרואיד ${sdkVersionName()}. עד " +
          "אנדרואיד 12 הפרופיל נשלט על ידי משאב שמהודר לתוך אפליקציית הבלוטוס " +
          "(profile_supported_hfpclient), לא על ידי מאפיין מערכת - ולכן שום מאפיין " +
          "ושום הגדרה לא ידליקו אותו, גם לא עם רוט. מה שכן עוזר שם זה ROM שנבנה עם " +
          "הפרופיל דלוק. אפשר בכל זאת לנסות 'הפעל פרופיל דיבורית': יש יצרנים " +
          "שהוסיפו מאפיין משלהם לגרסה הישנה, אבל זו הזדמנות קלושה ולא המסלול הצפוי."
      profileEnabled == false ->
        "הפרופיל קיים בנגן אבל כבוי (bluetooth.profile.hfp.hf.enabled לא מוגדר). " +
          "SELinux במצב Enforcing, ולכן סביר שרק רוט/מודול Magisk יוכלו להדליק אותו - " +
          "אבל שווה לנסות 'הפעל פרופיל דיבורית' דרך Shizuku לפני שמוותרים: הפעולה " +
          "מנסה כמה שמות מאפיינים, וחלקם יושבים בהקשר אבטחה מתירני יותר."
      else ->
        "לא ניתן לקבוע את מצב הפרופיל בנגן הזה. הרץ 'בדוק יכולות הנגן' שוב אחרי " +
          "שהבלוטוס דלוק."
    }

  /**
   * True when the profile is gated by a SYSTEM PROPERTY on this Android
   * version, rather than by a resource compiled into the Bluetooth app.
   *
   * This is the most important thing to know before trying to switch the
   * profile on, and it is decided entirely by the Android version:
   *
   *  - Android 13+ (where Bluetooth became a Mainline module) gates each
   *    profile on a sysprop - `bluetooth.profile.hfp.hf.enabled` for this one -
   *    which is what every property route in this app targets.
   *  - Android 12 and below gate it on a boolean RESOURCE compiled into the
   *    Bluetooth APK (`profile_supported_hfpclient`, in its config.xml). No
   *    property and no setting can change a compiled resource, so on those
   *    players the property ladder cannot work - not even with root. What
   *    helps there is a ROM built with the profile on, or a resource overlay,
   *    neither of which an app can ship generically.
   *
   * Vendor forks of the older stack sometimes added a property of their own,
   * which is why the ladder still tries on Android 12 - but as a long shot
   * described honestly, not as the expected path.
   */
  val profileGateIsProperty: Boolean
    get() = sdkInt >= 33

  /**
   * Properties on THIS player that look like they gate the HFP hands-free
   * (client) role and are currently off.
   *
   * Deliberately conservative about the role: `hfp` alone is not enough,
   * because `bluetooth.profile.hfp.ag.enabled` is the AUDIO GATEWAY side -
   * the profile every Android already runs and the one carrying the phone's
   * own hands-free support. Writing to that would be meddling with something
   * that works, to no purpose.
   */
  val discoveredProfileCandidates: List<String>
    get() = profileCandidatesIn(bluetoothProperties)

  /**
   * True when the channel in force carries calls over the raw RFCOMM socket,
   * which bypasses - and actively disables - the player's hands-free profile.
   *
   * Null (unknown) counts as "no": a missing channel is not evidence of a
   * mismatch, and claiming one would send the user to change a setting that
   * may already be right.
   */
  val channelBypassesProfile: Boolean
    get() = activeChannel == "AUTO" || activeChannel == "RAW"

  /** True when trying the privileged enable is worth the user's time. */
  val worthTryingEnable: Boolean
    get() = profileEnabled == false && profilePresent != false

  fun report(): String = buildString {
    activeChannel?.let {
      appendLine("ערוץ חיבור בזמן הבדיקה: $it" + if (channelBypassesProfile) " (עוקף את הפרופיל)" else "")
    }
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
    appendLine("$AUDIO_ROUTE_PROPERTY: ${audioRouteFlag.ifBlank { "לא מוגדר" }}")
    appendLine("SELinux: ${selinuxMode.ifBlank { "לא ניתן לקריאה" }}")
    appendLine("API נסתר (HFP) נגיש: ${if (hiddenApiReachable) "כן" else "לא"}")
    appendLine("אפשרויות מפתח: " + yesNo(adbEnabled))
    appendLine("ניפוי באגים אלחוטי: " + yesNo(wirelessDebugging))
    appendLine("פרופילים פעילים: $profileSummary")
    appendLine(
      "שליטה בפרופיל: " + if (profileGateIsProperty) {
        "מאפיין מערכת (אנדרואיד 13+)"
      } else {
        "משאב מהודר באפליקציית הבלוטוס (אנדרואיד 12 ומטה) - מאפיינים לא רלוונטיים"
      },
    )
    appendLine("מסקנה: $verdict")
    if (bluetoothProperties.isNotEmpty()) {
      appendLine()
      appendLine("מאפייני בלוטוס בנגן הזה (${bluetoothProperties.size}):")
      bluetoothProperties.toSortedMap().forEach { (key, value) ->
        appendLine("  $key = ${value.ifBlank { "(ריק)" }}")
      }
      val candidates = discoveredProfileCandidates
      if (candidates.isNotEmpty()) {
        appendLine("מועמדים שזוהו להדלקת פרופיל הדיבורית: ${candidates.joinToString(", ")}")
      }
    }
  }

  /** The marketing-ish Android version, for a line a human has to read. */
  private fun sdkVersionName(): String = when {
    sdkInt >= 36 -> "16"
    sdkInt >= 35 -> "15"
    sdkInt >= 34 -> "14"
    sdkInt >= 33 -> "13"
    sdkInt >= 31 -> "12"
    sdkInt >= 30 -> "11"
    sdkInt >= 29 -> "10"
    sdkInt >= 28 -> "9"
    sdkInt >= 26 -> "8"
    else -> "ישנה מאוד (SDK $sdkInt)"
  }

  private fun yesNo(value: Boolean?): String = when (value) {
    true -> "דלוק"
    false -> "כבוי"
    null -> "לא ניתן לקריאה"
  }

  /** The started profiles, named where the name means something to a reader. */
  val profileSummary: String
    get() {
      val profiles = enabledProfiles ?: return "לא ניתן לקריאה"
      if (profiles.isEmpty()) return "אין"
      return profiles.sorted().joinToString(", ") { id ->
        PROFILE_NAMES[id]?.let { "$it ($id)" } ?: id.toString()
      }
    }

  companion object {
    /** BluetoothProfile.HEADSET_CLIENT - hidden constant. */
    private const val PROFILE_HEADSET_CLIENT = 16

    /**
     * Profile ids worth naming in a report someone has to read. Deliberately
     * partial: an unnamed id is printed as a number rather than guessed at.
     */
    private val PROFILE_NAMES = mapOf(
      1 to "דיבורית (AG)",
      2 to "A2DP",
      5 to "HID",
      6 to "PAN",
      9 to "PBAP",
      10 to "GATT",
      11 to "A2DP-sink",
      12 to "AVRCP-controller",
      16 to "דיבורית-לקוח",
      17 to "PBAP-client",
      18 to "MAP-client",
      19 to "HID-device",
      22 to "מכשיר שמיעה",
      23 to "LE Audio",
    )

    /** Developer options master switch. World-readable. */
    private const val ADB_ENABLED = "adb_enabled"

    /** Wireless debugging (Android 11+). World-readable. */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    const val HFP_HF_PROPERTY = "bluetooth.profile.hfp.hf.enabled"

    /**
     * Every property name known to switch the HFP-client profile on, tried in
     * order. There is no single answer: Android 13+ reads the first one, while
     * older and vendor-forked stacks read one of the `persist.*` names that
     * circulate in build.prop recipes for these players.
     *
     * Trying all of them is not guesswork for its own sake - the `persist.*`
     * names sit in a DIFFERENT SELinux context from `bluetooth_config_prop`,
     * and that context is frequently writable by the shell identity Shizuku
     * provides. A build that refuses the official name can still accept one of
     * these, which is the difference between needing root and not.
     */
    val HFP_HF_PROPERTY_CANDIDATES = listOf(
      HFP_HF_PROPERTY,
      "persist.bluetooth.hfpclient",
      "persist.service.bt.hfp.client",
      "persist.vendor.bluetooth.hfpclient",
      "persist.bluetooth.enablehfpclient",
    )

    /**
     * The property that decides the HFP-client audio gate at birth.
     *
     * Verified against AOSP: HeadsetClientStateMachine initialises its
     * `mAudioRouteAllowed` field from the service and then lets this property
     * override it -
     *
     *     mAudioRouteAllowed = SystemProperties.getBoolean(
     *         "bluetooth.headset_client.initial_audio_route.enabled",
     *         mAudioRouteAllowed);
     *
     * and when that field is false the state machine answers the phone's
     * incoming SCO with "Audio is not allowed! Disconnect SCO."
     *
     * This is a DIFFERENT problem from the profile flags: those decide whether
     * the profile runs at all, this decides whether a running profile accepts
     * the voice. Setting it is the declarative equivalent of calling
     * setAudioRouteAllowed(true), and unlike that call it survives into every
     * future connection instead of needing to be re-applied per device.
     */
    const val AUDIO_ROUTE_PROPERTY = "bluetooth.headset_client.initial_audio_route.enabled"

    /**
     * A harmless property used only to find out whether this player lets the
     * privileged identity write ANY property at all. `debug.*` is the most
     * permissive context there is, so a refusal here means SELinux is shut
     * tight and no property route can work - which turns "try the next name"
     * into "stop, this needs Magisk" without the user testing five times.
     */
    const val WRITE_PROBE_PROPERTY = "debug.kosherbridge.writeprobe"

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
      activeChannel: String? = null,
    ): PlayerCapabilities = withContext(Dispatchers.IO) {
      HiddenHfp.init()
      val profiles = privilegedProfiles?.takeIf { it.isNotEmpty() } ?: supportedProfiles(context)
      PlayerCapabilities(
        device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})",
        profileEnabled = profiles?.contains(PROFILE_HEADSET_CLIENT),
        profilePresent = headsetClientServicePresent(context),
        profileFlag = systemProperty(HFP_HF_PROPERTY),
        audioRouteFlag = systemProperty(AUDIO_ROUTE_PROPERTY),
        selinuxMode = privilegedSelinux?.takeIf { it.isNotBlank() } ?: selinuxMode(),
        hiddenApiReachable = HiddenHfp.isAvailable,
        enabledProfiles = profiles,
        activeChannel = activeChannel,
        bluetoothProperties = bluetoothProperties(),
        adbEnabled = globalFlag(context, ADB_ENABLED),
        wirelessDebugging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          globalFlag(context, ADB_WIFI_ENABLED)
        } else {
          // Wireless debugging does not exist before Android 11, so "off"
          // would be a wrong answer rather than a missing one.
          null
        },
      )
    }

    /**
     * Whether the HFP hands-free (client) profile is running RIGHT NOW, read
     * without any privileged channel. Null when the stack will not say.
     *
     * This is the question the boot-time restore turns on: re-applying a
     * property and restarting Bluetooth is disruptive, and doing it when the
     * profile is already up would be both pointless and rude. A null answer
     * means "unknown", and the caller treats that as "do not act".
     */
    fun headsetClientRunning(context: Context): Boolean? =
      supportedProfiles(context)?.contains(PROFILE_HEADSET_CLIENT)

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

    /**
     * Reads a Settings.Global flag. These are world-readable, so this needs no
     * permission and no privileged channel - which is the point: the answer to
     * "is wireless debugging even on?" should not itself require the shell
     * access that wireless debugging is the gate on.
     */
    private fun globalFlag(context: Context, key: String): Boolean? = runCatching {
      android.provider.Settings.Global.getInt(context.contentResolver, key) != 0
    }.getOrNull()

    /**
     * Picks, out of a property map, the names that look like they gate the HFP
     * hands-free (client) role and are currently off.
     *
     * Deliberately conservative about the role: `hfp` alone is not enough,
     * because `bluetooth.profile.hfp.ag.enabled` is the AUDIO GATEWAY side -
     * the profile every Android already runs, and the one carrying the phone's
     * own hands-free support. Writing to that would be meddling with something
     * that works, to no purpose.
     */
    fun profileCandidatesIn(properties: Map<String, String>): List<String> =
      properties.entries
        .filter { (key, value) ->
          val lower = key.lowercase()
          val mentionsHfp = "hfp" in lower || "handsfree" in lower
          val clientRole = ".hf." in lower || "hf.enabled" in lower ||
            "client" in lower || "hfpclient" in lower
          val gatewayRole = ".ag." in lower || "ag.enabled" in lower
          val looksOff = value.isBlank() || value.equals("false", true) || value == "0"
          mentionsHfp && clientRole && !gatewayRole && looksOff
        }
        .map { it.key }
        .distinct()

    /**
     * Whether wireless debugging is on, without a whole probe.
     *
     * Public because the setup screens need it BEFORE any channel exists -
     * which is the point: the answer to "is the thing every shell route
     * depends on even switched on?" must not itself require a shell route. It
     * is a world-readable global setting, so it does not.
     */
    fun wirelessDebuggingEnabled(context: Context): Boolean? {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
      return globalFlag(context, ADB_WIFI_ENABLED)
    }

    /** Candidate names on THIS player, without running a whole probe. */
    fun discoverProfileCandidates(): List<String> =
      profileCandidatesIn(bluetoothProperties())

    /**
     * Every Bluetooth-related system property on this device.
     *
     * `getprop` with no arguments dumps the whole property store in
     * `[key]: [value]` form, and the store is world-readable - no permission,
     * no privileged channel. The output is filtered here rather than in the
     * caller so the report never carries the hundreds of unrelated properties
     * a device has.
     */
    private fun bluetoothProperties(): Map<String, String> = runCatching {
      val out = exec("getprop")
      if (out.isBlank()) return@runCatching emptyMap()
      val line = Regex("^\\[([^\\]]+)\\]: \\[(.*)\\]$")
      out.lineSequence()
        .mapNotNull { line.find(it.trim())?.destructured }
        .map { (key, value) -> key to value }
        .filter { (key, _) ->
          val lower = key.lowercase()
          "bluetooth" in lower || "hfp" in lower || "handsfree" in lower ||
            lower.startsWith("bt.") || ".bt." in lower
        }
        .toMap()
    }.getOrDefault(emptyMap())

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
