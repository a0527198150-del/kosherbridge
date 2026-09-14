package com.example.kosherbridge.bluetooth

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The TELECOM channel: full call control **and call audio** with no root, no
 * Shizuku, no hidden API and no privileged permission — only ordinary runtime
 * permissions the user grants in a dialog.
 *
 * See [TelecomSupport] for why this works. In short: the platform's own
 * HFP-Client profile connects to the kosher phone, AOSP's
 * `HfpClientConnectionService` publishes those calls into Telecom, and this
 * class reads and drives them through public `TelecomManager` API. The system
 * owns the SCO voice link, so the conversation comes out of the player's
 * speaker without this app touching audio routing at all.
 *
 * What this channel needs from the player, and cannot provide itself: the
 * HFP-Client profile must exist and be enabled in the player's Bluetooth stack.
 * [isUsable] reports exactly that, with no reflection — if the platform
 * published no HFP PhoneAccount, this channel is unavailable on this device and
 * the app must fall back to the raw RFCOMM channel (control only, voice stays
 * on the phone).
 */
class TelecomBridge(
  private val context: Context,
  private val onLog: (String, Boolean) -> Unit = { _, _ -> },
) {

  private val tag = "TelecomBridge"

  private val telecom: TelecomManager? =
    context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

  /** The call currently visible through Telecom, or null when the line is idle. */
  val call = MutableStateFlow<CallInfo?>(null)

  /** True while a usable HFP PhoneAccount is published by the platform. */
  val available = MutableStateFlow(false)

  private var receiver: BroadcastReceiver? = null

  /** Set when this app placed the in-flight call, so direction can be reported. */
  @Volatile private var outgoingPending = false

  /**
   * Whether any `ACTION_PHONE_STATE_CHANGED` broadcast has ever arrived.
   *
   * Telecom emits that broadcast through `TelephonyRegistryManager`, and on a
   * player with no telephony at all that registry may be absent — in which case
   * the calls exist in Telecom but this app is never told about them. There is
   * no way to detect that up front, so it is detected after the fact and
   * reported: "the account is there but no call event ever arrived" is a
   * completely different problem from "this player has no hands-free profile",
   * and a user with no way to read logs has to be able to tell them apart.
   */
  @Volatile private var sawBroadcast = false

  /** True once Telecom has reported at least one call-state change. */
  val hasSeenCallEvents: Boolean get() = sawBroadcast

  /**
   * Whether this Android version has the whole API this channel needs.
   *
   * Below [TelecomSupport.MIN_SDK] the channel must report itself unusable
   * rather than half-work: answering would succeed and hanging up would throw,
   * which on a player the user cannot debug is the worst possible outcome.
   */
  val isSupportedHere: Boolean get() = Build.VERSION.SDK_INT >= TelecomSupport.MIN_SDK

  /**
   * True while this bridge is actually observing Telecom.
   *
   * Goes false on [stop], which is what a deliberate user disconnect calls.
   * The availability poll must respect it: otherwise it keeps re-reporting the
   * platform's still-connected hands-free account and the channel springs back
   * to "connected" seconds after the user asked it to stop.
   */
  val isWatching: Boolean get() = receiver != null

  /** Address of the kosher phone, so the right PhoneAccount is picked. */
  @Volatile private var deviceAddress: String? = null

  // ------------------------------------------------------------------ permissions

  private fun has(permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  /** Reading call state at all. Without it the channel is blind. */
  val canReadState: Boolean get() = has(Manifest.permission.READ_PHONE_STATE)

  /** Caller ID. Without it calls still show, just without a number (Android 9+). */
  val canReadNumber: Boolean get() = has(Manifest.permission.READ_CALL_LOG)

  /** Answering and hanging up. */
  val canAnswer: Boolean get() = has(Manifest.permission.ANSWER_PHONE_CALLS)

  /** Placing calls through the phone. */
  val canDial: Boolean get() = has(Manifest.permission.CALL_PHONE)

  /** The runtime permissions this channel wants, for the permission request. */
  fun missingPermissions(): List<String> = missingPermissions(context)

  companion object {
    /**
     * Every runtime permission the TELECOM channel uses. All four are ordinary
     * dangerous permissions granted from a dialog — the channel needs nothing
     * privileged, which is the entire point of it.
     */
    val PERMISSIONS: List<String> = listOf(
      Manifest.permission.READ_PHONE_STATE,
      Manifest.permission.READ_CALL_LOG,
      Manifest.permission.ANSWER_PHONE_CALLS,
      Manifest.permission.CALL_PHONE,
    )

    /**
     * Which of [PERMISSIONS] are still missing.
     *
     * Deliberately needs only a [Context]: the settings UI asks this while the
     * user is choosing a channel, which can happen while the bridge service is
     * not running, so it must not depend on the service or the manager.
     */
    fun missingPermissions(context: Context): List<String> = PERMISSIONS.filter {
      ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
  }

  // ------------------------------------------------------------------ accounts

  /**
   * The HFP PhoneAccounts Telecom currently publishes, as plain data.
   *
   * Requires READ_PHONE_STATE; without it Telecom throws and this reports an
   * empty list, which the caller must not confuse with "the player has no
   * HFP-Client profile" — [canReadState] separates the two.
   */
  private fun accountRefs(): List<TelecomSupport.AccountRef> {
    val tm = telecom ?: return emptyList()
    if (!canReadState) return emptyList()
    val handles: List<PhoneAccountHandle> = try {
      tm.callCapablePhoneAccounts
    } catch (e: SecurityException) {
      Log.w(tag, "callCapablePhoneAccounts denied: ${e.message}")
      return emptyList()
    } catch (e: Throwable) {
      Log.w(tag, "callCapablePhoneAccounts failed: ${e.message}")
      return emptyList()
    }
    return handles.mapNotNull { handle ->
      runCatching {
        TelecomSupport.AccountRef(handle.componentName.className, handle.id)
      }.getOrNull()
    }
  }

  private fun handleFor(ref: TelecomSupport.AccountRef): PhoneAccountHandle? {
    val tm = telecom ?: return null
    return try {
      tm.callCapablePhoneAccounts.firstOrNull {
        it.componentName.className == ref.componentClassName && it.id == ref.id
      }
    } catch (e: Throwable) {
      null
    }
  }

  /**
   * Whether this player can run the TELECOM channel right now: the platform
   * published an HFP PhoneAccount, which only happens when the HFP-Client
   * profile exists, is enabled, and is connected to a phone.
   */
  fun isUsable(): Boolean {
    if (!isSupportedHere) {
      available.value = false
      return false
    }
    val usable = TelecomSupport.isChannelUsable(accountRefs())
    available.value = usable
    return usable
  }

  /**
   * Human-readable status for the diagnostics screen.
   *
   * The three failure modes are deliberately distinct, because the fix for each
   * is completely different: a missing permission the user grants, a player
   * whose stack has no HFP-client profile (nothing helps but a Magisk module),
   * and a player that bridges the calls but never delivers the call-state
   * broadcast (the channel cannot be used, but for a different reason).
   */
  fun statusText(): String = when {
    !isSupportedHere -> "דורש אנדרואיד 9 ומעלה (בגרסה זו אין ניתוק שיחה דרך Telecom)"
    telecom == null -> "שירות Telecom לא זמין בנגן"
    !canReadState -> "אין הרשאת מצב שיחות - אשר אותה כדי להפעיל את הערוץ"
    !isUsable() -> "אין חשבון HFP - הנגן לא חיבר את הטלפון כדיבורית מערכת"
    receiver != null && !sawBroadcast ->
      "פעיל - ממתין לאירוע השיחה הראשון (אם שיחה מצלצלת ולא מופיעה כאן, הנגן לא משדר אירועי שיחות)"
    else -> "פעיל - המערכת מגשרת את שיחות הטלפון"
  }

  // ------------------------------------------------------------------ lifecycle

  /**
   * Starts watching Telecom for calls on the kosher phone.
   *
   * [address] is the phone's MAC, used to pick its PhoneAccount when the player
   * is paired to more than one device.
   */
  fun start(address: String?) {
    deviceAddress = address
    isUsable()
    if (!isSupportedHere) {
      onLog("ערוץ המערכת דורש אנדרואיד 9 ומעלה - בחר ערוץ אחר", true)
      return
    }
    if (receiver != null) return
    if (!canReadState) {
      onLog("ערוץ המערכת דורש הרשאת 'מצב שיחות' - אשר אותה בהגדרות ההרשאות", true)
      return
    }
    val r = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        onPhoneState(state, number)
      }
    }
    receiver = r
    val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
    val registered = runCatching {
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(r, filter)
      }
    }.isSuccess
    if (!registered) {
      receiver = null
      onLog("לא ניתן להאזין למצב השיחות בנגן זה", true)
      return
    }
    onLog(
      "ערוץ המערכת פעיל" + if (canReadNumber) "" else " (בלי הרשאת יומן שיחות - לא יוצג מספר מתקשר)",
      false,
    )
  }

  fun stop() {
    receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
    receiver = null
    outgoingPending = false
    call.value = null
  }

  /** Visible for tests and for the receiver: turns one broadcast into state. */
  internal fun onPhoneState(state: String?, number: String?) {
    sawBroadcast = true
    if (TelecomSupport.isIdle(state)) outgoingPending = false
    val info = TelecomSupport.callFor(state, number, outgoingPending)
    if (call.value != info) call.value = info
  }

  // ------------------------------------------------------------------ commands

  /**
   * Answers the ringing call on the phone. The system brings the SCO voice link
   * up on its own, so there is nothing to do for audio afterwards.
   */
  fun answer(): Boolean {
    val tm = telecom ?: return false
    if (!canAnswer) {
      onLog("אין הרשאת מענה לשיחות - אשר אותה כדי לענות מהנגן", true)
      return false
    }
    return runCatching {
      @Suppress("DEPRECATION")
      tm.acceptRingingCall()
      true
    }.getOrElse {
      onLog("המענה לשיחה נכשל: ${it.message ?: "שגיאה"}", true)
      false
    }
  }

  /**
   * Rejects a ringing call, or hangs up the active one — `endCall()` does both,
   * depending on what is in the foreground.
   */
  fun endCall(): Boolean {
    val tm = telecom ?: return false
    if (!canAnswer) {
      onLog("אין הרשאת מענה לשיחות - אשר אותה כדי לנתק מהנגן", true)
      return false
    }
    return runCatching {
      @Suppress("DEPRECATION")
      tm.endCall()
    }.getOrElse {
      onLog("הניתוק נכשל: ${it.message ?: "שגיאה"}", true)
      false
    }
  }

  /**
   * Dials through the kosher phone's SIM by aiming the call at that device's
   * HFP PhoneAccount, so it is never placed on some other account.
   */
  fun dial(number: String): Boolean {
    val tm = telecom ?: return false
    if (number.isBlank()) return false
    if (!canDial) {
      onLog("אין הרשאת חיוג - אשר אותה כדי לחייג מהנגן", true)
      return false
    }
    val ref = TelecomSupport.selectAccount(accountRefs(), deviceAddress)
    if (ref == null) {
      onLog("לא נמצא חשבון HFP - הנגן לא מחובר לטלפון כדיבורית מערכת", true)
      return false
    }
    val handle = handleFor(ref)
    if (handle == null) {
      onLog("חשבון ה-HFP נעלם באמצע החיוג - נסה שוב", true)
      return false
    }
    val extras = Bundle().apply {
      putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
    }
    return runCatching {
      outgoingPending = true
      tm.placeCall(Uri.fromParts("tel", number, null), extras)
      true
    }.getOrElse {
      outgoingPending = false
      onLog("החיוג נכשל: ${it.message ?: "שגיאה"}", true)
      false
    }
  }
}
