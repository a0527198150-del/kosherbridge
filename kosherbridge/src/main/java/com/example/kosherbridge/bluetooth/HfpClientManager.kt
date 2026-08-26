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

  val profileReady = MutableStateFlow(false)
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
      // competes for the phone's single hands-free slot. Disable it
      // best-effort next to HEADSET (1), without removing the existing guard.
      withContext(Dispatchers.IO) {
        runCatching { HiddenHfp.setProfilePriority(context, device, 16 /* HEADSET_CLIENT */, 0) }
        runCatching { HiddenHfp.forceDisconnectProfile(context, device, 16 /* HEADSET_CLIENT */) }
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
            // Un-paired: clear the learned channel so a future re-pair starts
            // fresh.
            scope.launch {
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
  private var audioInUse = false
  private var audioRetry = 0

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
            profileReady.value = true
            onBackendWorked?.invoke("RAW")
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
    if (rawActive) {
      // Raw RFCOMM has no profile-level SCO, so also force the stack to open
      // the SCO voice channel directly - harmless if the stack refuses.
      audio.ensureCallAudio(device.value, volumeBoost, forceVirtualSco = true)
      return
    }
    if (useShizuku) shizuku?.connectAudio()
    else if (useRoot) root?.connectAudio()
    else HiddenHfp.connectAudio(client)
    if (autoAudio) audio.ensureCallAudio(device.value, volumeBoost)
  }

  fun toggleAudio(): Boolean {
    if (rawActive) {
      audio.ensureCallAudio(device.value, volumeBoost)
      return true
    }
    val connected = audioState.value == 2
    val ok = if (useShizuku) {
      if (connected) shizuku?.disconnectAudio() ?: false
      else shizuku?.connectAudio() ?: false
    } else if (useRoot) {
      if (connected) root?.disconnectAudio() ?: false
      else root?.connectAudio() ?: false
    } else {
      if (connected) HiddenHfp.disconnectAudio(client)
      else HiddenHfp.connectAudio(client)
    }
    if (ok && !connected && autoAudio) audio.ensureCallAudio(device.value, volumeBoost)
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
