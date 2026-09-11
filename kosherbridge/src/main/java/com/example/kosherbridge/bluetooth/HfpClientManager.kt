package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.kosherbridge.data.ServiceLocator
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the HFP client (hands-free) profile. Exposes flows for UI/service and
 * commands to connect, dial, answer, reject, hang up and control call audio.
 *
 * Call state is delivered two ways:
 *  1. the hidden [android.bluetooth.BluetoothHeadsetClient.Callback] (API 30+), when
 *     reflection is permitted;
 *  2. a lightweight poll of getCurrentCalls() that works everywhere the profile does.
 */
class HfpClientManager(private val context: Context, private val scope: CoroutineScope) {

  private val adapter: BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  /**
   * Does the SYSTEM expose the HFP client (hands-free) profile — i.e. can a
   * profile proxy actually bind? Set only by the real profile paths (the
   * in-process proxy, or isProfileReady() from the Shizuku/root user service).
   * The raw RFCOMM path deliberately never sets this: a raw socket talks AT
   * commands to the phone without any profile, so a successful raw link says
   * nothing about profile support. That distinction is what diagnostics
   * reports to the user; the live-link fact has its own flow below.
   */
  val profileReady = MutableStateFlow(false)

  /**
   * A live raw RFCOMM link exists right now — independent of [profileReady].
   * On a player whose stack has no HFP-Client profile, call control still
   * works through this link, and diagnostics must show BOTH facts: profile
   * unsupported AND link active. Call control paths may read either; nothing
   * may collapse them into one.
   */
  val rawLinkActive = MutableStateFlow(false)

  val connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
  val audioState = MutableStateFlow(0)
  val device = MutableStateFlow<BluetoothDevice?>(null)
  val call = MutableStateFlow<CallInfo?>(null)
  val lastError = MutableStateFlow<String?>(null)

  /** Human-readable name of the currently active bridge path, for diagnostics. */
  val backendLabel = MutableStateFlow<String?>(null)

  private var client: Any? = null
  private var callbackProxy: Any? = null
  private var pollJob: Job? = null
  private var stateReceiver: BroadcastReceiver? = null
  private var shizuku: ShizukuBridge? = null
  private var root: RootBridge? = null
  private var raw: RawHfpClient? = null
  private var shizukuFallbackLaunched = false
  /** The sticky binder-received listener was registered exactly once, so a
   * capability report taken before the Shizuku binder arrived can be
   * refreshed instead of staying stale. */
  @Volatile private var binderListenerRegistered = false

  /** Raw-link drop stats (count + last duration) for the diagnostics report. */
  val rawDropInfo = MutableStateFlow<String?>(null)
  /** SDP and direct-channel attempts, including the exact failure reason. */
  val rawConnectionDiagnostics = MutableStateFlow<String?>(null)

  private val connectionLogPrefs =
    context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE)
  val connectionLog = MutableStateFlow(
    connectionLogPrefs.getString("lines", "")
      ?.lineSequence()
      ?.filter { it.isNotBlank() }
      ?.toList()
      ?: emptyList(),
  )
  private val connectionLogLock = Any()

  /** Adds a timestamped local entry and keeps the last 200 entries. */
  fun logConnection(message: String, error: Boolean = false) {
    synchronized(connectionLogLock) {
      val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date())
      val prefix = if (error) "🔴 שגיאה" else "מידע"
      val line = "$stamp · $prefix: $message"
      val next = (connectionLog.value + line).takeLast(200)
      connectionLog.value = next
      connectionLogPrefs.edit().putString("lines", next.joinToString("\n")).apply()
    }
  }

  fun clearConnectionLog() {
    synchronized(connectionLogLock) {
      connectionLog.value = emptyList()
      connectionLogPrefs.edit().remove("lines").apply()
    }
  }

  // ------------------------------------------------------------------ system profiles

  /**
   * The kosher phone (AG) accepts a single hands-free link. Android, however,
   * auto-connects its own profiles (BluetoothHeadset + A2DP) to every bonded
   * device - so right after pairing the system's own HFP link competes with
   * the app's link, and the phone drops one of them ("connects for a few
   * seconds then immediately disconnects"). Setting the profiles to
   * PRIORITY_OFF for the kosher phone makes the stack neither initiate nor
   * accept them, leaving the field to the app's raw RFCOMM link. Applied
   * before every raw socket attempt so a vendor stack cannot restore the
   * competing profile between reconnects.
   */
  private val systemProfilesMutex = Mutex()
  private var bondWatchStarted = false
  private var bondReceiver: BroadcastReceiver? = null
  private var aclWatchStarted = false
  private var aclReceiver: BroadcastReceiver? = null
  @Volatile private var lastAclNudge = 0L

  /**
   * Pure policy bookkeeping (record original → restore / repair) behind the
   * profile guard, extracted to [ConnectionPolicyGuard] so the actual fix — the
   * `channelMode == "RAW"` gate for profile 16 and the record/restore/repair
   * mechanism — is unit-tested with a fake read/write. The originals are also
   * persisted in SettingsRepository (`policy_<address>_<profileId>` keys) so
   * they survive a process restart; this class supplies the real policy
   * reads/writes and the logging, and reconciles the guard's snapshot with
   * that persistence.
   */
  private val policyGuard = ConnectionPolicyGuard()

  /**
   * Writes the guard's records through to DataStore and seeds it back on
   * startup, through the [com.example.kosherbridge.data.local.PolicyStore]
   * seam — the record/clear/load round trip is unit-tested in
   * PolicyPersistenceTest with a fake store.
   */
  private val policyPersistence = PolicyPersistence(ServiceLocator.settings)

  private fun readPolicy(device: BluetoothDevice, profileId: Int): Int =
    HiddenHfp.profilePolicy(context, device, profileId)

  private fun writePolicy(device: BluetoothDevice, profileId: Int, policy: Int): Boolean {
    val address = device.address
    // Prefer the privileged channel when one is bound: the write then goes
    // through the privileged process, whose BLUETOOTH_PRIVILEGED write is not
    // refused by the locked-down stack and covers every guarded profile.
    // Otherwise fall back to the unprivileged write, whose refusal the caller
    // reports with a concrete instruction.
    return when {
      useShizuku -> shizuku?.setProfilePolicy(address, profileId, policy) ?: false
      useRoot -> root?.setProfilePolicy(address, profileId, policy) ?: false
      else -> HiddenHfp.setProfilePriority(context, device, profileId, policy)
    }
  }

  /**
   * Records the pre-write original for every guarded profile of one device into
   * the guard (once each — a later read may already observe the app's own
   * FORBIDDEN), then persists what the guard recorded so the originals survive
   * a process restart. Non-readable policies are skipped by the guard.
   *
   * The profiles come from [ConnectionPolicyGuard.recordedFor], keyed by
   * profile id — the guard owns the "address:profileId" key format, and a MAC
   * address itself contains ':', so the id is never parsed back out of a key
   * here.
   */
  private suspend fun recordPolicyOriginals(device: BluetoothDevice) {
    policyGuard.recordOriginals(device.address) { profileId -> readPolicy(device, profileId) }
    policyPersistence.persistRecorded(policyGuard, device.address)
  }

  /**
   * Restores every connection policy this app changed for one device back to
   * the values recorded before the first FORBIDDEN write, then forgets the
   * record. Called on deliberate disconnect, on unpair, and from the manual
   * repair action. Idempotent and safe when nothing was ever recorded.
   *
   * Privileged channels (SHIZUKU/ROOT/DIRECT) never disable system profiles,
   * so they never restore either — restoring would write policies the guard
   * never touched.
   */
  fun restoreSystemProfiles(device: BluetoothDevice) {
    if (channelMode in listOf("SHIZUKU", "ROOT", "DIRECT")) return
    val address = device.address
    if (!policyGuard.hasRecorded(address)) {
      logConnection("שחזור מדיניות חיבור: אין מה לשחזר עבור ${device.name ?: address}", false)
      return
    }
    scope.launch {
      systemProfilesMutex.withLock {
        val results = withContext(Dispatchers.IO) {
          policyGuard.restore(address) { profileId, original ->
            writePolicy(device, profileId, original)
          }
        }
        for (r in results) {
          if (r.applied) {
            logConnection(
              "מדיניות החיבור של פרופיל ${r.profileId} שוחזרה לערך המקורי (${r.original})",
              false,
            )
          } else {
            // The write failed (unprivileged process on a locked-down stack).
            // Keep the record so a later repair attempt can try again.
            logConnection(
              "לא ניתן לשחזר את מדיניות החיבור של פרופיל ${r.profileId} - ינוסה שוב בתיקון הבא",
              true,
            )
          }
        }
        // Clear only the records whose write actually succeeded.
        policyPersistence.clearApplied(address, results)
      }
    }
  }

  /**
   * The escape hatch for every player already stuck in the poisoned state:
   * sets all guarded profiles back to ALLOWED for the device, whether or not
   * this app recorded an original. When a privileged channel (Shizuku/root) is
   * bound, the repair runs through the privileged process, whose write is not
   * refused by the locked-down stack and covers every guarded profile;
   * otherwise it falls back to the unprivileged write, whose refusal is
   * reported with a concrete instruction.
   */
  fun repairConnectionPolicies(device: BluetoothDevice) {
    val address = device.address
    logConnection("מתקן מדיניות חיבור עבור ${device.name ?: address}", false)
    scope.launch {
      val results = withContext(Dispatchers.IO) {
        policyGuard.repair(address) { profileId, policy ->
          writePolicy(device, profileId, policy)
        }
      }
      for (r in results) {
        if (r.applied) {
          logConnection("פרופיל ${r.profileId}: מדיניות הוחזרה למאושר", false)
        } else if (useShizuku || useRoot) {
          // A privileged write that fails is unusual - it means the remote
          // process or the Bluetooth stack refused it for another reason.
          logConnection("פרופיל ${r.profileId}: הכתיבה נדחתה גם דרך הערוץ המיוחס", true)
        } else {
          // No privileged channel: the unprivileged write was refused because
          // the app lacks BLUETOOTH_PRIVILEGED. Tell the user how to proceed
          // instead of just that it failed.
          logConnection(
            "לא ניתן לתקן ללא הרשאת מערכת - הפעל את ערוץ Shizuku או רוט ונסה שוב",
            true,
          )
        }
      }
      // Clear only the records whose write actually succeeded.
      policyPersistence.clearApplied(address, results)
    }
  }

  /**
   * The current HFP-client (profile 16) connection policy for one device, for
   * the diagnostics row and report. POLICY_UNREADABLE when it cannot be read.
   *
   * Prefers the privileged read when a privileged channel is bound: the
   * unprivileged path reflects into getConnectionPolicy, which needs
   * BLUETOOTH_PRIVILEGED exactly like the write does, so on a stock player it
   * throws, is swallowed and returns POLICY_UNREADABLE - "לא ניתן לקריאה" on
   * precisely the devices where the poisoned policy matters. The privileged
   * process can read it.
   */
  fun headsetClientPolicy(device: BluetoothDevice?): Int {
    val d = device ?: return HiddenHfp.POLICY_UNREADABLE
    return when {
      useShizuku -> shizuku?.connectionPolicy(d.address) ?: HiddenHfp.POLICY_UNREADABLE
      useRoot -> root?.connectionPolicy(d.address) ?: HiddenHfp.POLICY_UNREADABLE
      else -> HiddenHfp.profilePolicy(context, d, 16)
    }
  }

  private suspend fun disableSystemProfiles(device: BluetoothDevice) {
    // Privileged channels route the link THROUGH the system HFP profile (just
    // from a privileged process). Forcing that profile off would kill their
    // link a moment after it is established. connect() already guards the
    // SHIZUKU/ROOT/DIRECT branches; the bond-time path in startBondWatch()
    // does not, so a re-pair silently broke those channels - and it now
    // disables HEADSET_CLIENT (16), the exact profile they rely on.
    if (channelMode == "SHIZUKU" || channelMode == "ROOT" || channelMode == "DIRECT") {
      logConnection("ערוץ $channelMode משתמש בפרופיל המערכת - מדלג על ניטרול", false)
      return
    }
    // Optional A/B switch: skip the profile guard entirely so a tester can
    // compare "with protection" vs "without" on the same player.
    if (!ServiceLocator.settings.profileGuard.first()) {
      logConnection("ניטרול פרופילי המערכת כבוי בהגדרות - מדלג", false)
      return
    }
    // Record the pre-existing policies BEFORE the first FORBIDDEN write, so a
    // later restore can put back what the stack originally held. Connection
    // policy is persistent per device — without this record the change is
    // irreversible (it survives restart, reboot, and uninstall).
    recordPolicyOriginals(device)
    // Pairing broadcasts and a manual connect can arrive together. Serialize
    // this operation so the raw socket never starts while the system profile
    // is still being disabled, and do not cache a failed priority change as if
    // it succeeded.
    systemProfilesMutex.withLock {
      val priorityApplied = withContext(Dispatchers.IO) {
        HiddenHfp.setProfilePriority(context, device, BluetoothProfile.HEADSET, 0)
      }
      val disconnected = withContext(Dispatchers.IO) {
        HiddenHfp.forceDisconnectProfile(context, device, BluetoothProfile.HEADSET)
      }
      if (priorityApplied) {
        if (disconnected) {
          logConnection("חיבור הדיבורית המערכתי נוטרל ואומת לפני RFCOMM", false)
        } else {
          logConnection("נוטרלה עדיפות הדיבורית אך ניתוקה לא אומת - יבוצע ניסיון חוזר", true)
        }
      } else {
        logConnection(
          "לא ניתן לנטרל את פרופיל הדיבורית המערכתי (נותק בפועל: $disconnected) - יבוצע ניסיון חוזר",
          true,
        )
      }

      // HEADSET_CLIENT (16) is the HFP client role - the exact same role this
      // app plays - so on players that expose it, it is the profile that truly
      // competes for the phone's single hands-free slot. In the user's
      // explicit, sticky RAW choice it is disabled next to HEADSET (1).
      // In AUTO it is left UNTOUCHED: AUTO resolves to the raw path today, but
      // profile 16 is also the profile the Shizuku/root channels drive, and a
      // FORBIDDEN policy persisted here silently breaks every one of those
      // channels afterwards — the stack refuses (or tears down seconds later)
      // every hands-free connection for this device, with no visible cause and
      // no recovery short of unpairing. Only the explicit RAW choice may
      // sacrifice the privileged channels.
      if (policyGuard.shouldForbidHeadsetClient(channelMode)) {
        withContext(Dispatchers.IO) {
          runCatching { HiddenHfp.setProfilePriority(context, device, 16 /* HEADSET_CLIENT */, 0) }
          runCatching { HiddenHfp.forceDisconnectProfile(context, device, 16 /* HEADSET_CLIENT */) }
        }
      }

      // A2DP does not own the HFP slot, but on some low-end stacks its ACL
      // activity can still overlap the raw opening. Await the best-effort
      // cleanup instead of launching it after this function returns.
      val a2dpReady = withContext(Dispatchers.IO) {
        val priority = HiddenHfp.setProfilePriority(context, device, BluetoothProfile.A2DP, 0)
        val disconnectedA2dp = HiddenHfp.forceDisconnectProfile(context, device, BluetoothProfile.A2DP)
        runCatching { HiddenHfp.setProfilePriority(context, device, 11 /* A2DP_SINK */, 0) }
        priority && disconnectedA2dp
      }
      if (!a2dpReady) {
        logConnection("ניקוי A2DP לא אושר; ממשיך לאחר סיום הניסיון", true)
      }
    }
    // MediaTek stacks tear down the ACL asynchronously after the last system
    // profile disconnects. Opening the raw RFCOMM socket immediately races
    // that teardown: the fresh socket rides a dying ACL and drops within
    // seconds ("connects then immediately disconnects"). Let the radio settle
    // before the raw socket opens, without removing the intentional guard.
    delay(1_000L)
  }

  /**
   * Watches for new pairings and disables the fighting profiles the moment a
   * device becomes bonded - before the system's auto-connect can start the
   * connection dance that drops the app's link.
   */
  private fun startBondWatch() {
    if (bondWatchStarted) return
    bondWatchStarted = true
    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        val dev = if (Build.VERSION.SDK_INT >= 33)
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val addr = dev?.address ?: return
        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
          BluetoothDevice.BOND_BONDED -> {
            // Pairing can trigger the system profiles immediately, so run the
            // same cleanup now before the raw connection attempt begins.
            if (dev != null) scope.launch { disableSystemProfiles(dev) }
          }
          BluetoothDevice.BOND_NONE -> {
            // Un-paired: restore the connection policies this app changed and
            // clear the learned channel so a future re-pair starts fresh.
            scope.launch {
              if (dev != null) restoreSystemProfiles(dev)
              ServiceLocator.settings.learnChannel(Build.FINGERPRINT, "")
            }
          }
        }
      }
    }
    bondReceiver = receiver
    runCatching {
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
      }
    }
  }

  /**
   * Watches the ACL link of the selected phone. When the link comes back up
   * (the phone re-establishes it, or Bluetooth toggles), jump the raw
   * reconnect queue immediately instead of waiting out the backoff - so an
   * incoming call that arrives right after a drop is not missed.
   */
  private fun startAclWatch() {
    if (aclWatchStarted) return
    aclWatchStarted = true
    val filter = IntentFilter().apply {
      addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
      addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
      addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
    }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        val r = raw ?: return

        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
          when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_OFF -> r.onAdapterOff()
            BluetoothAdapter.STATE_ON -> {
              // Vendor Bluetooth services may reset profile priorities when the
              // adapter restarts. Do not trust the in-memory guard from before
              // the restart; the next raw attempt must apply the protection
              // again before opening RFCOMM.
              if (!r.reconnectArmed) return
              val now = System.currentTimeMillis()
              if (now - lastAclNudge < 5000) return
              lastAclNudge = now
              r.nudge()
            }
          }
          return
        }

        if (!r.reconnectArmed) return
        val dev = if (Build.VERSION.SDK_INT >= 33)
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        if (dev == null || dev.address != device.value?.address) return

        val now = System.currentTimeMillis()
        if (now - lastAclNudge < 5000) return // rate-limit: don't hammer the phone
        lastAclNudge = now
        r.nudge()
      }
    }
    aclReceiver = receiver
    runCatching {
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
      }
    }
  }

  /**
   * Active call-audio techniques (SCO routing, communication mode, focus,
   * volume). Drives the voice through the player even when it has no call
   * support of its own.
   */
  val audio = CallAudioManager(context)

  @Volatile private var autoAudio = true
  @Volatile private var volumeBoost = true

  /** "AUTO", "PLAYER" or "PHONE" - see SettingsRepository.audioMode. */
  @Volatile private var audioMode = "AUTO"

  /** True once this player proved it cannot carry call audio (persisted). */
  @Volatile private var audioKnownImpossible = false

  /**
   * Fired the first time this player proves it cannot carry call audio, so the
   * verdict can be persisted and every later call can skip the attempt.
   */
  var onAudioProvenImpossible: (() -> Unit)? = null

  /**
   * Fired when the user explicitly asks for the voice on the player after the
   * bridge had given up, so the stored verdict can be cleared too. Clearing it
   * only in memory left the persisted value to come back on the next settings
   * emission or service restart, silently undoing the override.
   */
  var onAudioRetryRequested: (() -> Unit)? = null

  fun setAudioMode(mode: String, knownImpossible: Boolean) {
    audioMode = mode
    audioKnownImpossible = knownImpossible
  }

  /**
   * True when the bridge should not even try to pull the voice onto the player:
   * the user asked for phone audio, or this player already proved it cannot.
   */
  private val keepVoiceOnPhone: Boolean
    get() = audioMode == "PHONE" || (audioMode == "AUTO" && audioKnownImpossible)
  private var audioInUse = false
  private var audioRetry = 0

  /**
   * State of the HFP-client audio-route gate, in Hebrew, for the diagnostics
   * report: whether this player will let the kosher phone's voice link land at
   * all. See [HiddenHfp.setAudioRouteAllowed].
   */
  val audioRouteAllowed = MutableStateFlow<String?>(null)

  /** Address the gate was already opened for, so it is not re-run every poll. */
  @Volatile private var audioRouteOpenedFor: String? = null

  /**
   * Opens the stack's call-audio gate for this device.
   *
   * On a player that is not an automotive build, AOSP's HFP-client state
   * machine starts with the audio route DENIED and answers the phone's
   * incoming voice link with an immediate disconnect - the call connects, and
   * nobody hears anything, on either device. This is the call that fixes that,
   * and on Android 8-12 it needs no privilege at all. On Android 13+ the same
   * method moved behind BLUETOOTH_PRIVILEGED, so the privileged bridges get
   * the first try and the in-process attempt is the fallback.
   */
  fun allowAudioRoute(target: BluetoothDevice?, forceRetry: Boolean = false) {
    val d = target ?: device.value ?: return
    if (!forceRetry && audioRouteOpenedFor == d.address) return
    // Privileged bridges first: they can make the call on every Android
    // version, the app process only on 8-12.
    if (useShizuku || useRoot) {
      val ok = if (useShizuku) shizuku?.setAudioRouteAllowed(d.address, true) ?: false
      else root?.setAudioRouteAllowed(d.address, true) ?: false
      if (ok) {
        audioRouteOpenedFor = d.address
        audioRouteAllowed.value = "מאושר (ערוץ מורשה)"
        logConnection("ניתוב שמע השיחה אושר בערוץ המורשה", false)
        return
      }
    }
    val privileged = useShizuku || useRoot
    val c = client
    if (c == null) {
      audioRouteAllowed.value = if (privileged) {
        // No profile object to call through, but the declarative route needs
        // none: the property is read when the stack builds the state machine.
        if (writeAudioRouteProperty()) "מאושר דרך מאפיין מערכת (נדרש חיבור מחדש)"
        else "הערוץ המורשה לא הצליח לפתוח את הניתוב"
      } else {
        // Raw RFCOMM: there is no profile object to open a gate on. The gate
        // is not what blocks that path, so this is not an error.
        "לא רלוונטי (ללא פרופיל)"
      }
      return
    }
    when (HiddenHfp.setAudioRouteAllowed(c, d, true)) {
      HiddenHfp.AudioRoutePermission.ALLOWED -> {
        audioRouteOpenedFor = d.address
        audioRouteAllowed.value = "מאושר"
        logConnection("ניתוב שמע השיחה נפתח - הקול יוכל לעבור לנגן", false)
      }
      HiddenHfp.AudioRoutePermission.ALREADY_ALLOWED -> {
        audioRouteOpenedFor = d.address
        audioRouteAllowed.value = "מאושר"
      }
      HiddenHfp.AudioRoutePermission.BLOCKED -> {
        // The imperative call was refused. AOSP also reads the gate's initial
        // value from a system property, and that is a second, independent
        // route to the same result - and a better one when it works, because
        // it applies to every future connection instead of needing to be
        // re-asserted per device.
        if (privileged && writeAudioRouteProperty()) {
          audioRouteAllowed.value = "מאושר דרך מאפיין מערכת (נדרש חיבור מחדש)"
          logConnection(
            "הקריאה הישירה נדחתה, אבל המאפיין ${PlayerCapabilities.AUDIO_ROUTE_PROPERTY} " +
              "נכתב בהצלחה. התנתק והתחבר מחדש לטלפון כדי שהשער ייפתח",
            false,
          )
        } else {
          audioRouteAllowed.value =
            if (HiddenHfp.audioRouteGateNeedsPrivilege) "חסום - נדרש Shizuku או רוט" else "חסום"
          logConnection(
            "המערכת חסמה את פתיחת ניתוב השמע - ללא זה הטלפון מוסר את השיחה והקול אובד. " +
              "נדרש ערוץ Shizuku/רוט, או ערוץ RFCOMM שמשאיר את השמע בטלפון",
            true,
          )
        }
      }
      HiddenHfp.AudioRoutePermission.UNSUPPORTED -> {
        // Nothing to open: this build has no such gate, so an absent voice
        // link has some other cause.
        audioRouteAllowed.value = "אין שער כזה בגרסה זו"
      }
    }
  }

  // ------------------------------------------------- player capability probe

  /**
   * Asks the player what it can actually do, preferring a bound privileged
   * bridge for the two reads the app process may be refused
   * (`getSupportedProfiles` and `getenforce`).
   */
  suspend fun probeCapabilities(): PlayerCapabilities = withContext(Dispatchers.IO) {
    // Every read below is a binder round trip - the privileged ones can block
    // for seconds - so none of it may run on the caller's main thread.
    val profiles = when {
      useShizuku -> shizuku?.enabledProfiles()
      useRoot -> root?.enabledProfiles()
      else -> null
    }
    val selinux = when {
      useShizuku -> shizuku?.selinuxMode()
      useRoot -> root?.selinuxMode()
      else -> null
    }
    val result = PlayerCapabilities.probe(context, profiles, selinux)
    logConnection("בדיקת יכולות הנגן: ${result.verdict}", result.profileEnabled != true)
    result
  }

  /**
   * Tries to switch the HFP-client profile on WITHOUT root, through whichever
   * privileged bridge is available (Shizuku first - it is reachable over
   * wireless adb and needs no root at all).
   *
   * The write targets `bluetooth.profile.hfp.hf.enabled`, which the Bluetooth
   * stack reads when it starts. Stock Android puts that property in an SELinux
   * context only `init` may write, so on a strict build this fails cleanly and
   * says so; on the lax policies common to cheap players it can succeed, and
   * then a Bluetooth restart brings HeadsetClientService up for real. Trying
   * is the only way to find out - the answer is per-ROM, not per-model.
   *
   * Returns a Hebrew description of what happened.
   */
  suspend fun tryEnableHeadsetClientProfile(): String = withContext(Dispatchers.IO) {
    if (!useShizuku && !useRoot) {
      return@withContext "נדרש ערוץ מורשה: התקן והפעל Shizuku (adb אלחוטי, בלי רוט) " +
        "או בחר את ערוץ הרוט, ונסה שוב."
    }
    val selinux = privilegedSelinux()

    // Already on but dormant: the stack simply has not re-read the flag.
    if (privilegedProperty(PlayerCapabilities.HFP_HF_PROPERTY) == "true") {
      logConnection("מאפיין הפרופיל כבר מוגדר - מפעיל מחדש את הבלוטוס", false)
      return@withContext if (restartBluetoothPrivileged()) {
        "המאפיין כבר היה דלוק, והבלוטוס הופעל מחדש. הרץ 'בדוק יכולות הנגן' כדי לראות אם הפרופיל עלה."
      } else {
        "המאפיין כבר דלוק אבל לא הצלחתי להפעיל מחדש את הבלוטוס. כבה והדלק בלוטוס ידנית ובדוק שוב."
      }
    }

    // Can this player be written to AT ALL? debug.* is the most permissive
    // SELinux context there is; a refusal there means the property route is
    // closed entirely, and trying five Bluetooth names would only waste the
    // user's time before the same conclusion.
    val probe = writePrivilegedProperty(PlayerCapabilities.WRITE_PROBE_PROPERTY, "1")
    val canWriteAnything = probe == "1"
    if (!canWriteAnything) {
      logConnection("בדיקת כתיבת מאפיינים נכשלה: ${probe ?: "ללא תשובה"} (SELinux=$selinux)", true)
      return@withContext buildString {
        append("הנגן הזה לא מאפשר לערוץ המורשה לכתוב שום מאפיין מערכת")
        if (selinux.isNotBlank()) append(" (SELinux: $selinux)")
        append(". ")
        append(
          "זו מדיניות נעולה של המחסנית, לא תקלה באפליקציה - ולכן גם שמות מאפיינים " +
            "אחרים לא יעזרו. הדרך היחידה שנשארה היא מודול ה-Magisk (דורש רוט), " +
            "שמחיל את המאפיין לפני שתהליך הבלוטוס עולה.",
        )
        propertyErrorDetail(probe)?.let { append(" פירוט: ").append(it) }
      }
    }

    // Properties ARE writable here. Walk every name known to switch the
    // profile on - they live in different SELinux contexts, so the official
    // one being refused says nothing about the rest.
    val refusals = mutableListOf<String>()
    for (key in PlayerCapabilities.HFP_HF_PROPERTY_CANDIDATES) {
      val result = writePrivilegedProperty(key, "true")
      if (result == "true") {
        logConnection("מאפיין הפרופיל נכתב בהצלחה: $key", false)
        val restarted = restartBluetoothPrivileged()
        return@withContext buildString {
          append("המאפיין $key נכתב בהצלחה! ")
          append(
            if (restarted) "הבלוטוס הופעל מחדש - הרץ 'בדוק יכולות הנגן' כדי לראות אם הפרופיל עלה."
            else "לא הצלחתי להפעיל מחדש את הבלוטוס - כבה והדלק אותו ידנית ובדוק שוב.",
          )
          append(
            " שים לב: אם השורה 'פרופיל דיבורית פעיל במחסנית' עדיין מראה 'לא', " +
              "המחסנית של הנגן פשוט לא קוראת את השם הזה.",
          )
          if (!key.startsWith("persist.")) {
            append(" בנוסף, המאפיין נמחק בכל אתחול - הרץ את הפעולה שוב אחרי כל הפעלה מחדש.")
          }
        }
      }
      refusals += "$key: ${propertyErrorDetail(result) ?: "נדחה"}"
    }

    logConnection("כל שמות המאפיינים נדחו: ${refusals.joinToString(" · ")}", true)

    // Properties are not the only way a manufacturer switches a profile off,
    // and the remaining routes do not touch properties at all.

    // 1. The profile's service COMPONENT can be disabled at the package-manager
    // level instead. `pm enable` needs CHANGE_COMPONENT_ENABLED_STATE, which
    // the shell identity holds - so where that is the cause, this fixes it
    // outright with no root.
    val componentResult = enableProfileComponentPrivileged()
    if (componentResult == "OK") {
      logConnection("רכיב שירות הפרופיל הופעל מחדש (pm enable)", false)
      restartBluetoothPrivileged()
      return@withContext "שמות המאפיינים נדחו, אבל רכיב שירות הפרופיל הופעל מחדש והבלוטוס אותחל. " +
        "הרץ 'בדוק יכולות הנגן' - אם 'פרופיל דיבורית פעיל במחסנית' הפך ל'כן', זה היה הגורם."
    }

    // 2. Some builds keep a bitmask in Settings.Global that switches profiles
    // off independently of the build flags. Reading it is free; clearing it is
    // unambiguous ("nothing disabled") and the previous value is reported so
    // the change can be undone.
    val disabledMask = disabledProfilesPrivileged()
    if (disabledMask.isNotBlank() && disabledMask != "0") {
      val cleared = clearDisabledProfilesPrivileged()
      if (cleared == "OK") {
        logConnection("נוקתה הגדרת bluetooth_disabled_profiles (הערך הקודם: $disabledMask)", false)
        restartBluetoothPrivileged()
        return@withContext "נמצאה הגדרת מערכת שמכבה פרופילי בלוטוס (ערך $disabledMask) והיא נוקתה, " +
          "והבלוטוס אותחל. הרץ 'בדוק יכולות הנגן'. לשחזור: settings put global " +
          "bluetooth_disabled_profiles $disabledMask"
      }
      logConnection("ניקוי bluetooth_disabled_profiles נדחה: ${propertyErrorDetail(cleared) ?: cleared}", true)
    }

    // 3. Finally: on Android 12/13 a profile is started by sending its service
    // the STATE_CHANGED intent, so a profile the stack merely never asked for
    // can be asked for directly. It usually fails - the component is not
    // exported to the shell identity - but it costs one call, it is reversible
    // with a Bluetooth restart, and when it works it is the whole answer.
    val started = startProfileServicePrivileged()
    if (started == "OK") {
      logConnection("נשלחה בקשת הפעלה ישירה לשירות פרופיל הדיבורית", false)
      return@withContext "שמות המאפיינים נדחו, אבל נשלחה בקשת הפעלה ישירה לשירות הפרופיל. " +
        "הרץ 'בדוק יכולות הנגן' - אם השורה 'פרופיל דיבורית פעיל במחסנית' הפכה ל'כן', זה הצליח. " +
        "אם לא, המערכת התעלמה מהבקשה, ונשארה רק הדרך של מודול ה-Magisk."
    }
    logConnection("הפעלה ישירה של שירות הפרופיל נדחתה: ${propertyErrorDetail(started) ?: started}", true)

    buildString {
      append("הנגן מאפשר כתיבת מאפיינים, אבל דחה את כל השמות שמדליקים את פרופיל הדיבורית")
      if (selinux.isNotBlank()) append(" (SELinux: $selinux)")
      append(", וגם הפעלה ישירה של שירות הפרופיל נדחתה. ")
      append(
        "המאפיינים האלה שמורים ל-init במדיניות של הנגן. הדרך שנשארה היא מודול " +
          "ה-Magisk (דורש רוט), שמחיל אותם לפני שתהליך הבלוטוס עולה.",
      )
    }
  }

  /**
   * Opens the audio gate declaratively, by setting the property AOSP reads when
   * it builds a HFP-client state machine:
   *
   *     mAudioRouteAllowed = SystemProperties.getBoolean(
   *         "bluetooth.headset_client.initial_audio_route.enabled", mAudioRouteAllowed);
   *
   * Worth trying when the imperative setAudioRouteAllowed() call is refused,
   * and better than it when it succeeds: the value is read for every state
   * machine the stack builds from then on, so the gate stays open for future
   * connections rather than having to be re-asserted for each device. It takes
   * effect on the NEXT connection, since the field is read at construction.
   */
  private fun writeAudioRouteProperty(): Boolean {
    val key = PlayerCapabilities.AUDIO_ROUTE_PROPERTY
    if (privilegedProperty(key) == "true") return true
    return writePrivilegedProperty(key, "true") == "true"
  }

  private fun startProfileServicePrivileged(): String =
    if (useShizuku) shizuku?.startHeadsetClientService() ?: "ERR:אין Shizuku"
    else root?.startHeadsetClientService() ?: "ERR:אין ערוץ רוט"

  private fun enableProfileComponentPrivileged(): String =
    if (useShizuku) shizuku?.enableProfileComponent() ?: "ERR:אין Shizuku"
    else root?.enableProfileComponent() ?: "ERR:אין ערוץ רוט"

  private fun disabledProfilesPrivileged(): String =
    (if (useShizuku) shizuku?.disabledProfilesSetting() else root?.disabledProfilesSetting())
      .orEmpty()

  private fun clearDisabledProfilesPrivileged(): String =
    if (useShizuku) shizuku?.clearDisabledProfilesSetting() ?: "ERR:אין Shizuku"
    else root?.clearDisabledProfilesSetting() ?: "ERR:אין ערוץ רוט"

  private fun privilegedSelinux(): String =
    (if (useShizuku) shizuku?.selinuxMode() else root?.selinuxMode()).orEmpty()

  private fun privilegedProperty(key: String): String =
    (if (useShizuku) shizuku?.systemProperty(key) else root?.systemProperty(key)).orEmpty()

  /** Writes through the privileged bridge. Returns the value read back, or an
   * "ERR:..." string carrying why the write was refused. */
  private fun writePrivilegedProperty(key: String, value: String): String? =
    if (useShizuku) shizuku?.writeSystemProperty(key, value)
    else root?.writeSystemProperty(key, value)

  /** The reason text out of an "ERR:..." result, or null when it was a value. */
  private fun propertyErrorDetail(result: String?): String? =
    result?.removePrefix("ERR:")?.takeIf { it != result && it.isNotBlank() }

  private fun restartBluetoothPrivileged(): Boolean =
    if (useShizuku) shizuku?.restartBluetooth() ?: false
    else root?.restartBluetooth() ?: false

  /**
   * Lifts Android's non-SDK interface restriction through the privileged
   * bridge. DEVICE-GLOBAL: it relaxes the restriction for every app on the
   * player, so it is never applied automatically - only when the user asks.
   */
  suspend fun liftHiddenApiRestriction(): String = withContext(Dispatchers.IO) {
    val ok = when {
      useShizuku -> shizuku?.setHiddenApiPolicy(1) ?: false
      useRoot -> root?.setHiddenApiPolicy(1) ?: false
      else -> return@withContext "נדרש ערוץ מורשה (Shizuku או רוט) כדי לשנות את ההגדרה הזו."
    }
    logConnection(if (ok) "חסימת ה-API הנסתר הוסרה (הגדרה גלובלית)" else "לא ניתן היה לשנות את מדיניות ה-API הנסתר", !ok)
    if (ok) "בוצע. הפעל מחדש את האפליקציה כדי שהשינוי ייכנס לתוקף."
    else "המערכת דחתה את השינוי."
  }

  /**
   * Which connection channel to use: "AUTO" (probe everything in order),
   * "DIRECT" (in-process hidden API, no fallbacks), "SHIZUKU" (privileged
   * process), "RAW" (direct RFCOMM). Set by BridgeService from
   * SettingsRepository - either the user's manual choice or the channel that
   * worked on this exact player (Build.FINGERPRINT).
   */
  @Volatile var channelMode: String = "AUTO"
    private set

  fun setChannelMode(mode: String) {
    channelMode = mode
  }

  /** The currently applied channel mode - read by BridgeService. */
  val activeChannel: String get() = channelMode

  /**
   * Invoked when a backend proves itself working on this player: "DIRECT",
   * "SHIZUKU" or "RAW". BridgeService persists it per Build.FINGERPRINT so
   * the next launch can jump straight to the known-good channel.
   */
  var onBackendWorked: ((String) -> Unit)? = null

  init {
    audio.onScoDropped = { if (autoAudio) connectAudio() }
    audio.onAudioStolen = { if (autoAudio) connectAudio() }
    // The SCO broadcast only covers the gateway role; on the profile paths the
    // HFP-client's own audio state is the authoritative signal.
    audio.profileAudioConnected = { audioState.value == 2 }
    audio.onAudioStayedOnPhone = { certain ->
      // Proven, not guessed: only the certain verdict is worth remembering,
      // and only it should stop future calls from trying.
      if (certain && !audioKnownImpossible) {
        audioKnownImpossible = true
        onAudioProvenImpossible?.invoke()
      }
      val gate = when {
        rawActive && raw?.audioRequestSupported != true ->
          ", הטלפון אינו תומך בבקשת שמע (AT+BCC)"
        else -> audioRouteAllowed.value?.let { ", ניתוב שמע: $it" } ?: ""
      }
      logConnection(
        if (certain) {
          "לא נפתח ערוץ קול אל הנגן - השמע נשאר בטלפון הכשר. " +
            "הנגן ממשיך לשמש כשלט (מענה, ניתוק, חיוג)$gate"
        } else {
          "ערוץ הקול טרם נפתח - ממשיך לנסות לנתב את השיחה אל הנגן$gate"
        },
        true,
      )
    }
    // Reload any originals persisted by a previous process run, so restore
    // still works after a restart: the in-memory record dies with the process
    // while the policy it guards survives in the Bluetooth stack. The hot path
    // stays synchronous - only this one-time load touches DataStore.
    scope.launch {
      policyPersistence.seedGuard(policyGuard)
    }
  }

  /**
   * Forgets the current call. Used when the link to the phone is gone: with no
   * link there is no way to learn that the call ended, and a call state left
   * behind keeps a ringing notification and a full-screen call UI alive with
   * nothing behind them.
   */
  fun clearCall() {
    if (call.value != null) call.value = null
  }

  /** Applies the "שמע אוטומטי" and "הגברת עוצמה בשיחה" settings. */
  fun setAudioPrefs(auto: Boolean, boost: Boolean) {
    autoAudio = auto
    volumeBoost = boost
  }

  /** Short window after an explicit disconnect where the poll ignores re-detection. */
  private var ignorePollUntil = 0L

  /** When the privileged Shizuku bridge is bound, every operation goes through it. */
  private val useShizuku: Boolean get() = shizuku?.isBound == true

  /** When the privileged root bridge is bound, every operation goes through it. */
  private val useRoot: Boolean get() = root?.isBound == true

  /** Raw RFCOMM mode, opted-in from Settings ("חיבור ישיר"). */
  private val rawActive: Boolean get() = raw?.isConnected?.value == true

  /** The raw client owns its own reconnect loop; the service must not launch a
   * second reconnect loop while that client is still active. */
  val rawOwnsConnectionLoop: Boolean get() = raw?.ownsConnectionLoop == true
  val rawReconnectArmed: Boolean get() = raw?.reconnectArmed == true

  /**
   * True once a real privileged call was rejected with SecurityException -
   * the profile proxy exists (profileReady == true) but BLUETOOTH_PRIVILEGED
   * is actually missing. BridgeService checks this when deciding whether the
   * delayed Shizuku fallback is still needed.
   */
  val privilegedBlocked: Boolean get() = HiddenHfp.privilegedBlocked

  /** Flow mirror of [privilegedBlocked] - collected by BridgeService for the UI. */
  val privilegedBlockedFlow = HiddenHfp.privilegedBlockedFlow

  /**
   * Whether the Shizuku server is reachable and this app is authorized there -
   * for the diagnostics report. Safe to call anytime; creates the (cheap)
   * bridge object if it hasn't been created yet.
   */
  fun shizukuState(): Pair<Boolean, Boolean> {
    val b = shizuku ?: ShizukuBridge(context).also { shizuku = it }
    // The bridge may be asked about Shizuku before the server ever delivered
    // its binder (the report then reads "Shizuku unavailable" permanently).
    // Register the sticky binder-received listener once so the report can be
    // refreshed the moment the server actually becomes available.
    if (!binderListenerRegistered) {
      binderListenerRegistered = true
      b.onBinderReceived {
        logConnection("שרת Shizuku זמין כעת", false)
      }
    }
    return b.isAvailable to b.permissionGranted
  }

  /**
   * Whether this device exposes a `su` binary (root available), for the
   * diagnostics report. Does NOT trigger the root grant prompt.
   */
  suspend fun rootState(): Boolean = withContext(Dispatchers.IO) {
    val b = root ?: RootBridge(context).also { root = it }
    b.hasRootBinary()
  }

  val adapterOn: Boolean get() = adapter?.isEnabled == true

  fun bondedDevices(): List<PairedDeviceInfo> =
    if (useShizuku) shizuku?.bondedDevices() ?: emptyList()
    else if (useRoot) root?.bondedDevices() ?: emptyList()
    else {
      val a = adapter ?: return@bondedDevices emptyList()
      try {
        a.bondedDevices?.map { PairedDeviceInfo(it.name ?: it.address, it.address) }
          ?: emptyList()
      } catch (e: SecurityException) {
        // Android 12+ — BLUETOOTH_CONNECT not granted: bondedDevices returns
        // an empty set silently on many implementations, but some throw.
        // Expose the cause through the diagnostic state so the user sees
        // "no permission" instead of just "no devices".
        Log.w("HfpClientManager", "bondedDevices: BLUETOOTH_CONNECT not granted")
        emptyList()
      }
    }

  /** Binds the HFP client profile proxy. */
  fun register() {
    // Always watch for new pairings - the system profiles must be disabled at
    // bond time regardless of the connection channel, or the phone drops the
    // app's link the moment the system's own hands-free link shows up.
    startBondWatch()
    startAclWatch()
    when (channelMode) {
      // Raw RFCOMM has no profile to register - the link is opened in connect().
      "RAW" -> {
        lastError.value = null
        return
      }
      // AUTO also uses raw RFCOMM as the primary path; registering the
      // system HFP profile here would tell the Bluetooth service "there is
      // an HFP Client running" — and the service would try to auto-connect
      // to the bonded phone through that profile, competing for the phone's
      // sole hands-free slot. Skip registration; only DIRECT needs it.
      "AUTO" -> {
        lastError.value = null
        return
      }
      // Shizuku binding is started lazily from connect() so it is never
      // launched twice during startup.
      "SHIZUKU" -> {
        lastError.value = null
        return
      }
      // The root bridge is also bound lazily from connect(), never here.
      "ROOT" -> {
        lastError.value = null
        return
      }
      // Only the DIRECT (in-process hidden-API) path needs the system
      // profile proxy registered here.
      else -> Unit
    }
    HiddenHfp.init()
    if (!HiddenHfp.isAvailable) {
      profileReady.value = false
      lastError.value =
        "המכשיר אינו חושף את פרופיל הדיבורית (HFP Client). נדרש נגן/קופסה עם תמיכת דיבורית (מכשירי רכב, נגני אנדרואיד)."
      return
    }
    if (client != null) {
      profileReady.value = true
      return
    }
    val a = adapter
    if (a == null) {
      lastError.value = "בלוטוס לא זמין במכשיר"
      return
    }
    if (!a.isEnabled) {
      lastError.value = "הבלוטוס כבוי - הדלק אותו והתחבר שוב"
      return
    }
    val listener = object : BluetoothProfile.ServiceListener {
      override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        if (profile != HiddenHfp.PROFILE_ID) return
        client = HiddenHfp.castClient(proxy)
        profileReady.value = client != null
        if (client == null) {
          lastError.value = "פרופיל הדיבורית אינו זמין במכשיר זה"
        } else {
          // Early capability probe: fire a couple of cheap, non-destructive
          // profile calls right away. On devices that actually lack
          // BLUETOOTH_PRIVILEGED they throw SecurityException, which sets
          // HiddenHfp.privilegedBlocked (and its flow) immediately - so the
          // app knows at boot that this player needs the Shizuku path,
          // instead of discovering it mid-call on the first real dial.
          runCatching { HiddenHfp.currentCalls(client, device.value) }
          runCatching { HiddenHfp.connectedDevices(client) }

          // The proxy exists, but the probe above may have proven the app
          // actually lacks BLUETOOTH_PRIVILEGED. In that case do NOT record
          // "DIRECT" as the learned working channel: BridgeService persists
          // onBackendWorked() per Build.FINGERPRINT, so a blocked device would
          // otherwise jump straight into the blocked path on every next launch
          // instead of falling back to Shizuku/RFCOMM.
          if (HiddenHfp.privilegedBlocked) {
            lastError.value =
              "גישת פרופיל הדיבורית נחסמה על ידי המערכת - נדרש Shizuku או חיבור RFCOMM ישיר"
          } else {
            // The direct (in-process) path works on this device - Shizuku is
            // not needed. Clear any stale error (e.g. a premature Shizuku
            // message) so the home screen doesn't keep telling the user to
            // install it.
            lastError.value = null
            backendLabel.value = "ישיר"
            onBackendWorked?.invoke("DIRECT")
          }
          // Open the call-audio gate as early as the profile allows. Doing it
          // at connect time rather than when the phone rings means the stack
          // is already willing to accept the voice link when the first call
          // arrives, instead of rejecting it while the app catches up.
          allowAudioRoute(device.value)
          registerCallback()
          registerStateReceiver()
          startPolling()
        }
      }

      override fun onServiceDisconnected(profile: Int) {
        if (profile != HiddenHfp.PROFILE_ID) return
        client = null
        profileReady.value = false
        connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        device.value = null
        call.value = null
      }
    }
    val ok = try {
      a.getProfileProxy(context, listener, HiddenHfp.PROFILE_ID)
    } catch (e: SecurityException) {
      // The system rejected the profile request itself - the strongest proof
      // this device needs the privileged Shizuku path.
      HiddenHfp.markPrivilegedBlocked()
      lastError.value = "גישה לפרופיל הדיבורית נחסמה על ידי המערכת"
      false
    } catch (e: Throwable) {
      false
    }
    if (!ok && !HiddenHfp.privilegedBlocked) {
      lastError.value = "לא ניתן לגשת לפרופיל הדיבורית במכשיר זה"
    }
  }

  /**
   * Binds the privileged HFP bridge through Shizuku. The user service runs in a
   * separate process under the shell/root UID - exempt from hidden-API enforcement
   * and granted BLUETOOTH_PRIVILEGED - so the same reflection calls that are
   * blocked in this process succeed there. Once bound, every operation is routed
   * through the remote service.
   */
  suspend fun bindShizuku(): Boolean {
    logConnection("מתחיל חיבור דרך שיזוקו")
    val b = shizuku ?: ShizukuBridge(context).also { shizuku = it }
    // Mirror of bindRoot(): when the privileged user-service process dies
    // mid-session, reset the UI state so the user sees the failure instead of
    // a stale "Shizuku" label, and so maybeReconnect() can re-establish the
    // channel instead of leaving a dead bridge looking active.
    b.onRemoteDied {
      profileReady.value = false
      backendLabel.value = null
      lastError.value = "תהליך Shizuku נפל - נסה לחבר שוב"
    }
    if (b.isBound && b.isProfileReady()) {
      profileReady.value = true
      startPolling()
      return true
    }
    if (!b.isAvailable) {
      lastError.value =
        "Shizuku אינו פעיל - התקן את Shizuku והפעל אותו (adb או root), ואז נסה שוב"
      logConnection("שיזוקו אינו פעיל", true)
      return false
    }
    if (!b.permissionGranted) {
      // Ask through the official flow instead of failing right away - the
      // dialog result arrives via onPermissionResult, and when granted the
      // binding is re-attempted. Only when no request can be made (pre-V11,
      // or the user chose "deny and don't ask again") does the flow continue
      // to the existing failure branch below.
      b.onPermissionResult { granted ->
        if (granted) {
          logConnection("הוענקה הרשאת Shizuku - מתחבר מחדש", false)
          scope.launch { bindShizuku() }
        }
      }
      if (!b.requestPermission()) {
        lastError.value =
          "לא הוענקה הרשאה ל-Shizuku - פתח את אפליקציית Shizuku והענק הרשאה לאפליקציה זו"
        logConnection("לשיזוקו אין הרשאה לאפליקציה", true)
        return false
      }
      // requestPermission() returned true - the grant landed between the
      // checks; fall through and bind normally.
    }
    if (!b.bind()) {
      lastError.value = "החיבור ל-Shizuku נכשל"
      logConnection("בקשת חיבור לשיזוקו נכשלה", true)
      return false
    }
    // The Shizuku server itself allows up to 30 seconds to spawn the
    // user-service process (it runs app_process and loads the whole
    // Application). On slow players this can take many seconds - wait like the
    // server does instead of giving up after a few seconds. Giving up early
    // left the remote never registered, so every connect afterwards failed.
    var waited = 0
    while (!b.isBound && waited < 30_000) {
      delay(200)
      waited += 200
    }
    if (!b.isBound) {
      logConnection("תהליך שיזוקו עדיין לא חזר עם חיבור", true)
      // Still spawning. Keep watching in the background: when the binder
      // finally arrives, finish the wiring so the app starts working instead
      // of staying broken. (Bounded so a dead server doesn't leak a loop.)
      lastError.value =
        "תהליך Shizuku עדיין עולה (זה יכול לקחת כמה שניות בנגן איטי) - אם זה נמשך, ודא ש-Shizuku פעיל ונסה שוב"
      scope.launch {
        var waited = 0
        while (!b.isBound && waited < 60_000) {
          if (!b.isAvailable) return@launch
          delay(500)
          waited += 500
        }
        if (b.isBound) finishShizukuBind(b)
      }
      return false
    }
    return finishShizukuBind(b)
  }

  /**
   * Wires up the remote user service once its binder arrived: registers the
   * HFP profile there, re-connects a previously selected device, clears the
   * error and starts polling call state.
   */
  private suspend fun finishShizukuBind(b: ShizukuBridge): Boolean {
    if (!b.registerProfile()) {
      // The most common "Shizuku doesn't work at all" failure: the privileged
      // process is up, but the player's Bluetooth stack does not expose the
      // HFP client profile, so the profile proxy never connects. Shizuku only
      // lifts the permission walls - it cannot add HFP-client support to the
      // vendor stack. Point the user to the channel that bypasses the profile.
      lastError.value =
        "המכשיר לא מאפשר את פרופיל הדיבורית (HFP Client) גם דרך Shizuku - " +
          "שנה את 'ערוץ חיבור' ל'חיבור ישיר RFCOMM' שמדבר עם הטלפון הכשר ישירות"
      logConnection("שיזוקו מחובר אך רישום פרופיל הדיבורית נכשל - אין תמיכת HFP Client בנגן", true)
      return false
    }
    logConnection("שיזוקו רשם את פרופיל הדיבורית", false)
    // If a device was already selected, make sure the remote profile connects to it.
    device.value?.address?.let { addr ->
      if (shizuku?.connectionState(addr) != BluetoothProfile.STATE_CONNECTED) {
        shizuku?.connect(addr)
      }
    }
    lastError.value = null
    backendLabel.value = "Shizuku"
    profileReady.value = true
    startPolling()
    onBackendWorked?.invoke("SHIZUKU")
    return true
  }

  /**
   * Binds the privileged HFP bridge through root (su). The app spawns its own
   * app_process child under uid 0 - exempt from hidden-API enforcement and
   * granted BLUETOOTH_PRIVILEGED - which runs HfpUserService and hands the
   * binder back through RootBridgeProvider. No extra app is needed, only a
   * rooted device that grants this app `su`.
   */
  suspend fun bindRoot(): Boolean {
    logConnection("מתחיל חיבור דרך הרשאת רוט")
    val b = root ?: RootBridge(context).also { root = it }
    b.onRemoteDied {
      profileReady.value = false
      backendLabel.value = null
      lastError.value = "תהליך הרוט נפל - נסה לחבר שוב"
    }
    if (b.isBound && b.isProfileReady()) {
      profileReady.value = true
      startPolling()
      return true
    }
    if (!withContext(Dispatchers.IO) { b.isRootAvailable() }) {
      lastError.value =
        "לא הוענקה הרשאת רוט לאפליקציה - אשרו את בקשת ההרשאה (Magisk) ובחרו שוב 'דרך הרשאת רוט'"
      logConnection("הרשאת רוט לא זמינה", true)
      return false
    }
    if (!withContext(Dispatchers.IO) { b.start() }) {
      lastError.value = "הפעלת תהליך הרוט נכשלה"
      logConnection("הפעלת תהליך הרוט נכשלה", true)
      return false
    }
    logConnection("תהליך הרוט הופעל - ממתין לחיבור", false)
    // Booting the app in the root process (ActivityThread + Application) can
    // take several seconds on slow players - wait like Shizuku does instead
    // of giving up early.
    var waited = 0
    while (!b.isBound && waited < 30_000) {
      delay(200)
      waited += 200
    }
    if (!b.isBound) {
      logConnection("תהליך הרוט עדיין לא החזיר חיבור", true)
      lastError.value =
        "תהליך הרוט עדיין עולה (יכול לקחת כמה שניות בנגן איטי) - אם זה נמשך, ודא שהרשאת הרוט הוענקה ונסה שוב"
      // Keep watching in the background: when the binder finally arrives,
      // finish the wiring instead of staying broken.
      scope.launch {
        var waited = 0
        while (!b.isBound && waited < 60_000) {
          delay(500)
          waited += 500
        }
        if (b.isBound) finishRootBind(b)
      }
      return false
    }
    return finishRootBind(b)
  }

  /**
   * Wires up the root user service once its binder arrived: registers the HFP
   * profile there, re-connects a previously selected device, clears the error
   * and starts polling call state.
   */
  private suspend fun finishRootBind(b: RootBridge): Boolean {
    if (!b.registerProfile()) {
      lastError.value = "פרופיל הדיבורית לא זמין דרך הרוט במכשיר זה"
      logConnection("הרוט מחובר אך רישום פרופיל הדיבורית נכשל", true)
      return false
    }
    logConnection("הרוט רשם את פרופיל הדיבורית", false)
    // If a device was already selected, make sure the remote profile connects to it.
    device.value?.address?.let { addr ->
      if (root?.connectionState(addr) != BluetoothProfile.STATE_CONNECTED) {
        root?.connect(addr)
      }
    }
    lastError.value = null
    backendLabel.value = "Root"
    profileReady.value = true
    startPolling()
    // Deliberately NOT learned as the "channel that worked" on this player:
    // the user requires AUTO to never touch root - it may only run when the
    // user picks it explicitly in the channel selector. Learning "ROOT" here
    // would make a later AUTO choice resolve to the learned ROOT channel and
    // spawn su without an explicit selection. The manual choice persists on
    // its own, so the ROOT channel keeps working for this player either way.
    return true
  }

  private var rawCollectorsLaunched = false

  /**
   * Direct HFP over RFCOMM - no hidden API and no privileged permission.
   * Opens the kosher phone's headset port directly, so the phone sees this
   * player as a hands-free/headset even when the player's Bluetooth stack has
   * no HFP client profile at all. Call control and caller ID work on every
   * player; call audio (SCO) only on stacks that cooperate.
   */
  fun connectRaw(target: BluetoothDevice) {
    // The Settings shortcut can call RAW directly without register(). Ensure
    // its bond/ACL recovery listeners are still installed on that path.
    startBondWatch()
    startAclWatch()
    device.value = target
    logConnection("מתחיל חיבור RFCOMM ישיר אל ${target.name ?: target.address}")
    backendLabel.value = "RFCOMM ישיר"
    val r = raw ?: RawHfpClient(context, scope) { message, error ->
      logConnection(message, error)
    }.also { raw = it }
    // Re-apply the profile guard before every retry, not only before the first
    // connection. If a vendor stack refuses setPriority once, its automatic
    // profile may come back during a later retry and steal the phone's slot.
    r.beforeSocketOpen = { target -> disableSystemProfiles(target) }
    if (!rawCollectorsLaunched) {
      rawCollectorsLaunched = true
      scope.launch {
        r.call.collect { info ->
          call.value = info
          // The raw path has no profile-level audio: force the SCO voice
          // channel while a call is actually active, and tear it down when
          // the call ends or the link drops.
          when {
            info == null || info.state == CallState.IDLE || info.state == CallState.TERMINATED ->
              audio.releaseCallAudio()
            info.state == CallState.ACTIVE -> connectAudio()
            else -> Unit
          }
        }
      }
      scope.launch {
        r.isConnected.collect { connected ->
          connectionState.value =
            if (connected) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED
          if (connected) {
            // A raw socket is NOT the system HFP-Client profile — it must not
            // set profileReady, which means "the system exposes the profile".
            // Marking profile support here reported "נתמך" on players whose
            // stack provably lacks the profile and kept the Shizuku dead-end
            // alive. The live-link fact has its own flow.
            rawLinkActive.value = true
            onBackendWorked?.invoke("RAW")
          } else {
            rawLinkActive.value = false
          }
          if (!connected) call.value = null
        }
      }
      scope.launch {
        r.lastError.collect { e -> if (e != null) lastError.value = e }
      }
      scope.launch {
        r.dropInfo.collect { rawDropInfo.value = it }
      }
      scope.launch {
        r.connectionDiagnostics.collect { rawConnectionDiagnostics.value = it }
      }
    }
    // RawHfpClient runs the same guard before every socket attempt, including
    // reconnects after a drop. This avoids a second uncoordinated connection
    // loop and prevents the system profile from reclaiming the slot between
    // retries.
    r.connect(target)
  }

  /**
   * Connects the bridge to the kosher phone. Priority: active raw RFCOMM link,
   * then the privileged Shizuku path, then the in-process hidden API. When the
   * hands-free profile is unavailable on this player - or the stack-level
   * connect fails - fall back to opening the phone's headset port directly
   * over RFCOMM, so the phone still sees this device as a hands-free/headset.
   */
  fun connect(target: BluetoothDevice) {
    device.value = target
    logConnection("מתחיל חיבור בערוץ $channelMode אל ${target.name ?: target.address}")
    when (channelMode) {
      // User forced the raw RFCOMM path - open the phone's headset port
      // directly, no profile involvement, no fallbacks.
      // (connectRaw disables the system profiles internally before opening
      // the socket, so there is no need to call disableSystemProfiles here.)
      "RAW" -> {
        connectRaw(target)
        return
      }
      // User forced the Shizuku path - bind on demand and connect through the
      // remote privileged process.
      "SHIZUKU" -> {
        // CRITICAL: do NOT call disableSystemProfiles here. The Shizuku
        // connection itself goes through the system HFP profile (just in
        // the privileged shell process). disableSystemProfiles forces a
        // disconnect on that same profile - which kills the Shizuku link
        // a fraction of a second after it was established, producing the
        // "connects then immediately disconnects" symptom.
        if (useShizuku) {
          if (profileReady.value) {
            // Restore the HFP-client connection policy to ALLOWED in the
            // privileged process BEFORE connecting. A policy left FORBIDDEN by
            // an earlier raw/AUTO session is persisted per device by the stack:
            // the connect below would be refused — or accepted and torn down
            // seconds later — with no visible cause. Only the privileged
            // identity can perform this write.
            val allowed = shizuku!!.setConnectionAllowed(target.address)
            if (allowed) {
              logConnection("מדיניות החיבור לפרופיל הדיבורית הוחזרה לפעילה", false)
            } else {
              logConnection("לא ניתן להחזיר את מדיניות החיבור - החיבור עלול ליפול", true)
            }
            if (!shizuku!!.connect(target.address)) {
              lastError.value = "חיבור הדיבורית נכשל דרך Shizuku - נסה 'חיבור ישיר RFCOMM' בערוץ החיבור"
            }
          } else {
            lastError.value =
              "פרופיל הדיבורית לא זמין דרך Shizuku בנגן זה - נסה 'חיבור ישיר RFCOMM' בערוץ החיבור"
          }
        } else {
          lastError.value = "מתחבר דרך Shizuku..."
          scope.launch {
            if (bindShizuku()) {
              connect(target)
            } else {
              // bindShizuku() already set a specific error + log entry - do
              // not overwrite it with a generic message that hides the real
              // reason (server down, permission missing, process still booting).
              logConnection("החיבור בערוץ Shizuku נכשל", true)
            }
          }
        }
        return
      }
      // User forced the root path - the app spawns its own privileged
      // process (su, uid 0) and connects through it.
      "ROOT" -> {
        // Same rule as SHIZUKU: the root connection itself goes through the
        // system HFP profile (just in the privileged process), so do NOT call
        // disableSystemProfiles here - it would force a disconnect on that
        // same profile and kill the root link a moment after it was made.
        if (useRoot) {
          if (profileReady.value) {
            // Same rule as SHIZUKU: restore ALLOWED in the privileged process
            // before connect, undoing any FORBIDDEN policy a raw/AUTO session
            // persisted for this device.
            val allowed = root!!.setConnectionAllowed(target.address)
            if (allowed) {
              logConnection("מדיניות החיבור לפרופיל הדיבורית הוחזרה לפעילה", false)
            } else {
              logConnection("לא ניתן להחזיר את מדיניות החיבור - החיבור עלול ליפול", true)
            }
            if (!root!!.connect(target.address)) {
              lastError.value = "חיבור הדיבורית נכשל דרך הרוט"
            }
          } else {
            lastError.value = "פרופיל הדיבורית לא זמין דרך הרוט בנגן זה"
          }
        } else {
          lastError.value = "מתחבר דרך הרשאת רוט..."
          scope.launch {
            if (bindRoot()) {
              connect(target)
            } else {
              // bindRoot() already set a specific error + log entry - keep it.
              logConnection("החיבור בערוץ הרוט נכשל", true)
            }
          }
        }
        return
      }
      // User forced the in-process hidden-API path - no automatic fallbacks.
      "DIRECT" -> {
        // For the in-process path, the system profile is in this process.
        // Wait for the OS profiles to be disabled first, then connect -
        // otherwise the auto-connect may race ahead and claim the slot.
        scope.launch {
          disableSystemProfiles(target)
          // The DIRECT channel drives the same profile whose policy the raw
          // path may have set to FORBIDDEN. Try to restore ALLOWED first; in
          // an unprivileged process the write may be refused — log it, do not
          // treat it as fatal (the Shizuku fallback below still applies).
          val allowed = withContext(Dispatchers.IO) {
            HiddenHfp.setProfilePriority(context, target, 16, HiddenHfp.POLICY_ALLOWED)
          }
          if (allowed) {
            logConnection("מדיניות החיבור לפרופיל הדיבורית הוחזרה לפעילה", false)
          } else {
            logConnection("לא ניתן להחזיר את מדיניות החיבור - החיבור עלול ליפול", true)
          }
          val c = client
          if (c != null) {
            if (!HiddenHfp.connect(c, target)) {
              lastError.value = if (HiddenHfp.privilegedBlocked) {
                "הגישה הישירה נחסמה על ידי המערכת - שנה ערוץ חיבור לאוטומטי או Shizuku"
              } else {
                "חיבור הדיבורית נכשל"
              }
            }
          } else {
            lastError.value = "פרופיל הדיבורית לא זמין - שנה ערוץ חיבור בהגדרות"
          }
        }
        return
      }
      // AUTO — raw RFCOMM is the primary (and most stable) path: it opens the
      // phone's headset gateway directly over a socket, bypassing the system
      // HFP profile entirely. No competition for the phone's single hands-free
      // slot, no privileged permissions needed. If raw fails (unbonded device,
      // unsupported controller), the reconnect loop inside RawHfpClient keeps
      // retrying. The system HFP profile is intentionally NOT registered in
      // AUTO mode (see register()), so it cannot compete for the phone's
      // single hands-free slot.
      // (connectRaw disables the system profiles internally before opening
      // the socket, so there is no need to call disableSystemProfiles here.)
      "AUTO" -> {
        connectRaw(target)
        return
      }
    }
  }

  fun disconnect() {
    // Disconnect an armed raw client even when it is currently between retry
    // attempts. Checking only rawActive left its reconnect loop running after a
    // user disconnect, which could reopen the socket moments later.
    raw?.disconnect()
    // A deliberate disconnect hands the phone's hands-free slot back: restore
    // the connection policies this app changed so the phone can connect to the
    // player as an ordinary hands-free device again (outside this app).
    device.value?.let { d ->
      scope.launch { restoreSystemProfiles(d) }
    }
    if (useShizuku) {
      device.value?.address?.let { shizuku?.disconnect(it) }
    } else if (useRoot) {
      device.value?.address?.let { root?.disconnect(it) }
    } else {
      val c = client
      val d = device.value
      if (c != null && d != null) HiddenHfp.disconnect(c, d)
    }
    ignorePollUntil = System.currentTimeMillis() + 3000
    device.value = null
    connectionState.value = BluetoothProfile.STATE_DISCONNECTED
    call.value = null
  }

  /** Resolves the device for the in-process hidden-API path, preferring the
   * tracked device and falling back to the connected-devices list so a quick
   * dial right after connect never passes a null device to the hidden API. */
  private fun directDevice(): BluetoothDevice? {
    val c = client ?: return null
    return device.value ?: (HiddenHfp.connectedDevices(c).firstOrNull() as? BluetoothDevice)
  }

  fun dial(number: String): Boolean {
    if (number.isBlank()) return false
    if (connectionState.value != BluetoothProfile.STATE_CONNECTED) {
      lastError.value = "לא מחובר לטלפון הכשר"
      return false
    }
    if (rawActive) return raw?.dial(number) ?: false
    if (useShizuku) return shizuku?.dial(number) ?: false
    if (useRoot) return root?.dial(number) ?: false
    val c = client ?: return false
    val d = directDevice() ?: return false
    val ok = HiddenHfp.dial(c, d, number)
    if (!ok && HiddenHfp.privilegedBlocked) {
      fallbackToShizuku("החיוג הישיר נחסם על ידי המערכת - עוברים אוטומטית ל-Shizuku")
    }
    return ok
  }

  fun redial(): Boolean =
    if (rawActive) raw?.redial() ?: false
    else if (useShizuku) shizuku?.redial() ?: false
    else if (useRoot) root?.redial() ?: false
    else {
      val c = client
      val d = if (c != null) directDevice() else null
      c != null && d != null && HiddenHfp.redial(c, d)
    }

  fun answer(): Boolean {
    val ok = if (rawActive) raw?.answer() ?: false
    else if (useShizuku) shizuku?.accept() ?: false
    else if (useRoot) root?.accept() ?: false
    else {
      val c = client
      val d = if (c != null) directDevice() else null
      c != null && d != null && HiddenHfp.accept(c, d)
    }
    if (ok) {
      // Give the AG a moment to move the call to ACTIVE before requesting SCO.
      scope.launch {
        delay(350)
        connectAudio()
      }
    }
    return ok
  }

  fun reject(): Boolean =
    if (rawActive) raw?.reject() ?: false
    else if (useShizuku) shizuku?.reject() ?: false
    else if (useRoot) root?.reject() ?: false
    else {
      val c = client
      val d = if (c != null) directDevice() else null
      c != null && d != null && HiddenHfp.reject(c, d)
    }

  fun hangup(): Boolean =
    if (rawActive) raw?.hangup() ?: false
    else if (useShizuku) shizuku?.hangup() ?: false
    else if (useRoot) root?.hangup() ?: false
    else {
      val c = client
      val d = if (c != null) directDevice() else null
      c != null && d != null && HiddenHfp.hangup(c, d)
    }

  fun connectAudio() {
    if (keepVoiceOnPhone) {
      // Nothing is claimed, so the phone keeps the call on its own earpiece
      // from the first second and the player stays a working remote control.
      audio.keepAudioOnPhone()
      return
    }
    if (rawActive) {
      // Raw RFCOMM has no profile-level SCO, so also force the stack to open
      // the SCO voice channel directly - harmless if the stack refuses.
      //
      // AT+BCC is only sent when this player actually exposes somewhere for
      // call audio to land. Asking the phone to hand over a conversation that
      // provably cannot be received would take the voice off the phone's own
      // earpiece as well, which is precisely the failure this bridge had.
      if (audio.scoDeviceAvailable(device.value) && !audio.audioGivenUp) {
        raw?.requestAudio()
      }
      // Codec 2 is mSBC (wideband); the HAL needs the right sampling rate or
      // the stream is decoded as noise.
      audio.wideBandSpeech = raw?.negotiatedCodec == 2
      audio.ensureCallAudio(device.value, volumeBoost, forceVirtualSco = true)
      return
    }
    // Open the stack's audio gate before asking for the link: with the gate
    // shut the stack answers the phone's voice link with a disconnect, and the
    // call is silent on both devices.
    allowAudioRoute(device.value)
    if (useShizuku) shizuku?.connectAudio()
    else if (useRoot) root?.connectAudio()
    else HiddenHfp.connectAudio(client, device.value)
    if (autoAudio) audio.ensureCallAudio(device.value, volumeBoost)
  }

  /**
   * The user asked for the voice on the player. Unlike the automatic path this
   * always retries, even after the bridge measured that the audio stayed on
   * the phone - an explicit request overrides that latch.
   */
  fun toggleAudio(): Boolean {
    // An explicit request beats the remembered verdict: the user may have
    // changed something (a different phone, a newly enabled profile) that the
    // stored answer predates. Cleared in memory AND on disk - otherwise the
    // persisted value returns at the next settings emission and the override
    // quietly expires.
    audioKnownImpossible = false
    onAudioRetryRequested?.invoke()
    if (rawActive) {
      if (audio.scoDeviceAvailable(device.value)) raw?.requestAudio()
      audio.forceRetry(device.value, volumeBoost, forceVirtualSco = true)
      return true
    }
    val connected = audioState.value == 2
    if (!connected) allowAudioRoute(device.value, forceRetry = true)
    val ok = if (useShizuku) {
      if (connected) shizuku?.disconnectAudio() ?: false
      else shizuku?.connectAudio() ?: false
    } else if (useRoot) {
      if (connected) root?.disconnectAudio() ?: false
      else root?.connectAudio() ?: false
    } else {
      if (connected) HiddenHfp.disconnectAudio(client, device.value)
      else HiddenHfp.connectAudio(client, device.value)
    }
    if (!connected) audio.forceRetry(device.value, volumeBoost)
    return ok
  }

  fun shutdown() {
    pollJob?.cancel()
    audio.releaseCallAudio()
    stateReceiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
    stateReceiver = null
    callbackProxy?.let { HiddenHfp.unregisterCallback(client, it) }
    callbackProxy = null
    client?.let { c ->
      runCatching { adapter?.closeProfileProxy(HiddenHfp.PROFILE_ID, c as BluetoothProfile) }
    }
    client = null
    shizuku?.unbind()
    shizuku = null
    root?.stop()
    root = null
    raw?.disconnect()
    raw = null
    rawCollectorsLaunched = false
    rawDropInfo.value = null
    rawConnectionDiagnostics.value = null
    bondReceiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
    bondReceiver = null
    bondWatchStarted = false
    aclReceiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
    aclReceiver = null
    aclWatchStarted = false
    backendLabel.value = null
  }

  // ------------------------------------------------------------------ callbacks

  private fun registerCallback() {
    val cbClass = HiddenHfp.callbackClass ?: return
    val c = client ?: return
    val handler = InvocationHandler { _, method, args ->
      when (method.name) {
        "onCallChanged" -> args?.getOrNull(0)?.let { parseAndEmit(it) }
        "onConnectionStateChanged" ->
          args?.getOrNull(1)?.let { connectionState.value = it as? Int ?: BluetoothProfile.STATE_DISCONNECTED }
        "onAudioStateChanged" -> args?.getOrNull(1)?.let { audioState.value = it as? Int ?: 0 }
      }
      null // all Callback methods are void
    }
    callbackProxy = runCatching {
      Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass), handler)
    }.getOrNull()
    if (callbackProxy != null) HiddenHfp.registerCallback(c, callbackProxy)
  }

  private fun registerStateReceiver() {
    val filter = IntentFilter().apply {
      addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
      addAction("android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED")
    }
    stateReceiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
        val dev = if (Build.VERSION.SDK_INT >= 33)
          intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java)
        else
          @Suppress("DEPRECATION")
          intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE")
        when (intent.action) {
          "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" -> {
            connectionState.value = state
            if (state == BluetoothProfile.STATE_CONNECTED) {
              device.value = dev ?: device.value
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
              device.value = null
              call.value = null
            }
          }
          "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED" -> audioState.value = state
        }
      }
    }
    runCatching {
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(stateReceiver, filter)
      }
    }
  }

  private fun startPolling() {
    pollJob?.cancel()
    pollJob = scope.launch {
      var lastKey: String? = null
      while (isActive) {
        if (System.currentTimeMillis() < ignorePollUntil) {
          delay(700)
          continue
        }
        if (!rawActive) {
          if (useShizuku) {
            pollShizuku()
          } else if (useRoot) {
            pollRoot()
          } else {
            val c = client
            if (c != null) {
              val d = device.value ?: (HiddenHfp.connectedDevices(c).firstOrNull() as? BluetoothDevice)
              if (d != null) {
                device.value = d
                connectionState.value = HiddenHfp.connectionState(c, d)
                audioState.value = HiddenHfp.audioState(c, d)
                if (connectionState.value == BluetoothProfile.STATE_CONNECTED) {
                  // Cheap after the first success: allowAudioRoute() memoizes
                  // per address and only re-runs when the device changes.
                  allowAudioRoute(d)
                  val calls = HiddenHfp.currentCalls(c, d)
                  val key = callKey(calls)
                  if (key != lastKey) {
                    lastKey = key
                    call.value = primaryCall(calls)
                  }
                } else {
                  call.value = null
                }
              }
            }
          }
        }

        // ---- call-audio watchdog: keep the voice link alive during a call ----
        // On players not built for calls the SCO link can fail silently or
        // drop mid-call. While a call is ACTIVE and the profile reports the
        // audio link down, re-request it (with the routing techniques) about
        // every 2 seconds. When the call ends, tear the audio down.
        if (autoAudio && !rawActive) {
          val active = call.value?.state == CallState.ACTIVE
          if (active) {
            audioInUse = true
            if (audioState.value != 2) {
              audioRetry++
              if (audioRetry >= 3) {
                audioRetry = 0
                connectAudio()
              }
            } else {
              audioRetry = 0
            }
          } else if (audioInUse) {
            audioInUse = false
            audioRetry = 0
            audio.releaseCallAudio()
          }
        }
        delay(700)
      }
    }
  }

  /**
   * Automatically switches to the privileged Shizuku path when the direct
   * hidden-API path is blocked by the system (SecurityException). Root is
   * deliberately NOT used here - the root channel is only ever used when the
   * user selects it explicitly in the channel selector, never automatically.
   * Called from connect()/dial() so the user is never left with a silently
   * failing "direct" connection; BridgeService's delayed check is a backstop.
   */
  private fun fallbackToShizuku(reason: String) {
    if (useShizuku || shizukuFallbackLaunched) return
    shizukuFallbackLaunched = true
    lastError.value = reason
    scope.launch {
      bindShizuku()
      shizukuFallbackLaunched = false
    }
  }

  /** Polls call state from the remote Shizuku user service. */
  private fun pollShizuku() {
    val s = shizuku ?: return
    var d = device.value
    if (d == null) {
      // Rediscover the connected device, mirroring the direct path's
      // connectedDevices() lookup, so a manual Shizuku connect (via the
      // "ערוץ חיבור" selector) works even when auto-connect never filled
      // device.value.
      val addr = s.bondedDevices()
        .firstOrNull { s.connectionState(it.address) == BluetoothProfile.STATE_CONNECTED }
        ?.address
      if (addr != null) {
        val dev = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull()
        if (dev != null) {
          device.value = dev
          d = dev
        }
      }
    }
    if (d == null) return
    connectionState.value = s.connectionState(d.address)
    audioState.value = s.audioState(d.address)
    if (connectionState.value == BluetoothProfile.STATE_CONNECTED) {
      allowAudioRoute(d)
      call.value = snapshotToCall(s.currentCallSnapshot())
    } else {
      call.value = null
    }
  }

  /** Polls call state from the remote root user service. */
  private fun pollRoot() {
    val b = root ?: return
    var d = device.value
    if (d == null) {
      // Rediscover the connected device, mirroring pollShizuku().
      val addr = b.bondedDevices()
        .firstOrNull { b.connectionState(it.address) == BluetoothProfile.STATE_CONNECTED }
        ?.address
      if (addr != null) {
        val dev = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull()
        if (dev != null) {
          device.value = dev
          d = dev
        }
      }
    }
    if (d == null) return
    connectionState.value = b.connectionState(d.address)
    audioState.value = b.audioState(d.address)
    if (connectionState.value == BluetoothProfile.STATE_CONNECTED) {
      allowAudioRoute(d)
      call.value = snapshotToCall(b.currentCallSnapshot())
    } else {
      call.value = null
    }
  }

  /** Maps the remote "state|number|direction" snapshot to a CallInfo. */
  private fun snapshotToCall(snap: String): CallInfo? {
    if (snap.isBlank()) return null
    val parts = snap.split('|')
    if (parts.size < 3) return null
    val rawState = parts[0].toIntOrNull() ?: HiddenHfp.callStateIdle
    val number = parts[1].takeIf { it.isNotBlank() }
    val rawDir = parts[2].toIntOrNull() ?: HiddenHfp.callDirectionIncoming
    return CallInfo(mapState(rawState), number, mapDirection(rawDir))
  }

  private fun parseAndEmit(callObj: Any) {
    val info = runCatching { parse(callObj) }.getOrNull() ?: return
    if (call.value != info) call.value = info
  }

  private fun parse(callObj: Any): CallInfo = CallInfo(
    state = mapState(HiddenHfp.callState(callObj)),
    number = HiddenHfp.callNumber(callObj),
    direction = mapDirection(HiddenHfp.callDirection(callObj)),
  )

  private fun primaryCall(calls: List<*>): CallInfo? {
    if (calls.isEmpty()) return null
    val parsed = calls.mapNotNull { c -> if (c == null) null else runCatching { parse(c) }.getOrNull() }
    if (parsed.isEmpty()) return null
    // Prefer a newly arriving/ringing call over an already-active one so call
    // waiting surfaces on the screen instead of being hidden behind the call
    // the user is already on (mirrors emitFromIndicators() in RawHfpClient).
    val rank = listOf(CallState.INCOMING, CallState.WAITING, CallState.ACTIVE, CallState.ALERTING, CallState.DIALING, CallState.HELD)
    return parsed.minByOrNull { rank.indexOf(it.state).let { i -> if (i < 0) Int.MAX_VALUE else i } }
  }

  private fun callKey(calls: List<*>): String {
    val parts = calls.mapNotNull { c ->
      if (c == null) null
      else runCatching { "${HiddenHfp.callState(c)}:${HiddenHfp.callNumber(c)}" }.getOrNull()
    }
    return parts.sorted().joinToString("|")
  }

  private fun mapState(s: Int): CallState = when (s) {
    HiddenHfp.callStateActive -> CallState.ACTIVE
    HiddenHfp.callStateHeld -> CallState.HELD
    HiddenHfp.callStateHeldByResponseAndHold -> CallState.HELD
    HiddenHfp.callStateDialing -> CallState.DIALING
    HiddenHfp.callStateAlerting -> CallState.ALERTING
    HiddenHfp.callStateIncoming -> CallState.INCOMING
    HiddenHfp.callStateWaiting -> CallState.WAITING
    HiddenHfp.callStateTerminated -> CallState.TERMINATED
    else -> CallState.IDLE
  }

  private fun mapDirection(d: Int): CallDirection =
    if (d == HiddenHfp.callDirectionOutgoing) CallDirection.OUTGOING else CallDirection.INCOMING
}
