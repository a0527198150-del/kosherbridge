package com.example.kosherbridge

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.kosherbridge.bluetooth.CallAudioOutcome
import com.example.kosherbridge.bluetooth.CallDirection
import com.example.kosherbridge.bluetooth.CallInfo
import com.example.kosherbridge.bluetooth.CallState
import com.example.kosherbridge.bluetooth.HfpClientManager
import com.example.kosherbridge.bluetooth.HiddenHfp
import com.example.kosherbridge.bluetooth.PairedDeviceInfo
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the bridge to the kosher phone:
 * keeps the HFP client connection alive, surfaces incoming calls with a
 * full-screen UI, logs calls, and exposes dial/answer/reject/hang-up commands.
 */
class BridgeService : Service() {

  companion object {
    private const val TAG = "BridgeService"
    private const val NOTIF_BRIDGE = 1

    /** How long a ringing/dialing state may stay unchanged before it is
     * treated as stale. Longer than any real ring cycle, short enough that a
     * stuck notification is not the user's problem for the rest of the day. */
    private const val STALE_RINGING_MS = 120_000L

    const val ACTION_START = "com.example.kosherbridge.action.START"
    const val ACTION_CONNECT = "com.example.kosherbridge.action.CONNECT"
    const val EXTRA_DEVICE = "com.example.kosherbridge.extra.DEVICE"
    const val ACTION_DISCONNECT = "com.example.kosherbridge.action.DISCONNECT"
    const val ACTION_DIAL = "com.example.kosherbridge.action.DIAL"
    const val EXTRA_NUMBER = "com.example.kosherbridge.extra.NUMBER"
    const val ACTION_ANSWER = "com.example.kosherbridge.action.ANSWER"
    const val ACTION_REJECT = "com.example.kosherbridge.action.REJECT"
    const val ACTION_HANGUP = "com.example.kosherbridge.action.HANGUP"
    const val ACTION_TOGGLE_AUDIO = "com.example.kosherbridge.action.TOGGLE_AUDIO"

    @Volatile
    var instance: BridgeService? = null
      private set

    fun start(context: Context) {
      val intent = Intent(context, BridgeService::class.java).setAction(ACTION_START)
      ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Runs [block] with the live bridge manager when the service is running.
     * When the service is not running the bridge does not exist and there is
     * nothing to act on — [onMissing] reports that instead of a silent no-op
     * (the earlier BridgeHub.service?.x pattern silently did nothing).
     */
    fun withManager(
      context: Context,
      onMissing: () -> Unit = {},
      block: (HfpClientManager) -> Unit,
    ) {
      val svc = instance
      if (svc == null) {
        onMissing()
        return
      }
      block(svc.manager)
    }

    /**
     * Fires a call command whether or not the service is currently running.
     *
     * Every call button used to go through `BridgeHub.service?.answer()` and
     * friends, which is null the moment the service is not alive - so the tap
     * did nothing at all: the ringing notification stayed on screen, the call
     * kept ringing, and there was no error anywhere. Routing through a
     * foreground-service intent means the tap always reaches a live service,
     * starting it first if the system had killed it.
     */
    fun requestCallAction(context: Context, action: String, number: String? = null) {
      val intent = Intent(context, BridgeService::class.java).setAction(action)
      if (number != null) intent.putExtra(EXTRA_NUMBER, number)
      runCatching { ContextCompat.startForegroundService(context, intent) }
        .onFailure { error ->
          // Starting a foreground service from the background is refused in
          // some states (Android 12+). The in-process path still works when
          // the service happens to be alive, so fall back to it rather than
          // dropping the user's tap.
          Log.w(TAG, "startForegroundService($action) refused", error)
          val svc = instance ?: return
          when (action) {
            ACTION_ANSWER -> svc.answer()
            ACTION_REJECT -> svc.reject()
            ACTION_HANGUP -> svc.hangup()
            ACTION_TOGGLE_AUDIO -> svc.toggleAudio()
            ACTION_DISCONNECT -> svc.disconnect()
            ACTION_DIAL -> number?.let { svc.dial(it) }
          }
        }
    }

    fun requestAnswer(context: Context) = requestCallAction(context, ACTION_ANSWER)
    fun requestReject(context: Context) = requestCallAction(context, ACTION_REJECT)
    fun requestHangup(context: Context) = requestCallAction(context, ACTION_HANGUP)
    fun requestToggleAudio(context: Context) = requestCallAction(context, ACTION_TOGGLE_AUDIO)
    fun requestDisconnect(context: Context) = requestCallAction(context, ACTION_DISCONNECT)
    fun requestDial(context: Context, number: String) =
      requestCallAction(context, ACTION_DIAL, number)

    /**
     * Connects to a device whether or not the service is already running.
     * Tapping "בחר מכשיר" previously went through BridgeHub.service?.connectTo,
     * which silently did nothing when the service was dead - no connection,
     * no log, no feedback. This starts the service with the connect intent
     * when needed so the tap always does something observable.
     */
    fun requestConnect(context: Context, address: String) {
      val svc = instance
      if (svc != null) {
        svc.connectTo(address)
      } else {
        val intent = Intent(context, BridgeService::class.java)
          .setAction(ACTION_CONNECT)
          .putExtra(EXTRA_DEVICE, address)
        ContextCompat.startForegroundService(context, intent)
      }
    }

  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var manager: HfpClientManager

  /** Keeps one call-log lifecycle per simultaneous HFP call. */
  private data class CallLogSession(
    val id: Long,
    var number: String?,
    val direction: CallDirection,
    var sawActive: Boolean = false,
    var startedAt: Long = 0L,
  )

  private val activeCallLogs = mutableListOf<CallLogSession>()
  private var fullScreenEnabled = true
  private var vibrateEnabled = true
  private var reconnecting = false
  private var lastManualDisconnectAt = 0L
  private var wakeLock: PowerManager.WakeLock? = null
  private var staleCallWatchdog: Job? = null
  private var ringtone: Ringtone? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    BridgeHub.service = this
    // Keep the CPU awake while the bridge is running. Without a partial
    // wake lock, the CPU enters deep sleep on battery-powered devices (like
    // the Jelly2 phone) — the RFCOMM socket stops processing data, the
    // kosher phone sees an idle link and drops it. The foreground-service
    // notification keeps the process alive, but only a wake lock keeps the
    // CPU from sleeping.
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
    wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kosherbridge:hfp")
    wakeLock?.setReferenceCounted(false)
    wakeLock?.acquire()
    Notifications.createChannels(this)
    manager = HfpClientManager(this, scope)
    // Service lifecycle is part of the connection journal so a diagnostic
    // report can always prove whether the bridge was even alive when the
    // user tapped connect. (An empty journal used to mean either "no attempt"
    // or "the service was silently dead" - now the two are distinguishable.)
    manager.logConnection("שירות הגשר עלה (process=${android.os.Process.myPid()})", false)
    // Log which channel this player actually resolves to, so the journal
    // answers "which channel is the device running on?" without asking the
    // user - the rest of the diagnostics assume AUTO.
    scope.launch {
      val cs = ServiceLocator.settings.channelState(Build.FINGERPRINT).first()
      manager.logConnection(
        "ערוץ חיבור: פעיל=${cs.effective}, בחירה ידנית=${cs.manual}, נלמד=${cs.learned.ifBlank { "אין" }} · נגן=${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
        false,
      )
    }
    // Remember which connection channel actually worked on this exact player
    // (keyed by Build.FINGERPRINT) so the next launch can jump straight to it.
    manager.onBackendWorked = { backend ->
      scope.launch { ServiceLocator.settings.learnChannel(Build.FINGERPRINT, backend) }
    }
    observeSettings()
    observeManager()
    publishCapabilityReport()
    // Safety net for players whose vendor power manager kills the service and
    // ignores START_STICKY - without it the bridge simply ceases to exist and
    // nothing on the device says so.
    BridgeWatchdog.schedule(this)
    // Undo any audio-HAL state a previous run left behind when it was killed
    // mid-call; otherwise Bluetooth media stays suspended on the device.
    manager.audio.resetHalAudioState()
    // Same reasoning, for the thing the user actually sees. A call notification
    // is posted `ongoing`, so it cannot be swiped away, and it outlives the
    // process that posted it. onDestroy() cancels it - but a vendor power
    // manager killing the process never calls onDestroy, and BridgeWatchdog
    // then restarts a service that has no call and no reason to cancel
    // anything. The result is the notification that will not go away, with
    // buttons for a call that ended hours ago. At this exact moment there is
    // provably no call in progress, so any call notification on screen is a
    // leftover.
    Notifications.cancelCall(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // FIRST, before anything that could throw. Every caller reaches this
    // service through startForegroundService(), and the system then gives the
    // process about five seconds to call startForeground() or it kills it with
    // ForegroundServiceDidNotStartInTimeException - a crash no catch block can
    // absorb, because it is raised by the system, not by this code. Running the
    // command handling first meant one unexpected throw in an action handler
    // was logged politely here and then killed the whole app a moment later.
    ensureForeground()
    try {
      when (intent?.action) {
        ACTION_CONNECT -> {
          val addr = intent.getStringExtra(EXTRA_DEVICE)
          if (addr != null) {
            connectTo(addr)
          }
        }
        ACTION_DISCONNECT -> disconnect()
        ACTION_DIAL -> intent.getStringExtra(EXTRA_NUMBER)?.let { number ->
          retryCommand("חיוג ל-$number") { manager.dial(number) }
        }
        // These three arrive from a notification button or the call screen,
        // and the intent may be what STARTED the service - in which case the
        // link is not up yet and a single attempt is guaranteed to fail. They
        // are also the taps a user repeats in frustration when nothing
        // happens, so each one retries briefly and says so in the journal.
        ACTION_ANSWER -> retryCommand("מענה לשיחה") { manager.answer() }
        ACTION_REJECT -> retryCommand("דחיית שיחה") { manager.reject() }
        ACTION_HANGUP -> retryCommand("ניתוק שיחה") { manager.hangup() }
        ACTION_TOGGLE_AUDIO -> manager.toggleAudio()
      }
      if (intent?.action == ACTION_START || intent?.action == null) {
      scope.launch {
        // Apply the channel for THIS player first (the user's manual choice,
        // or the channel that worked on this exact device before), then
        // register and auto-connect with it.
        val mode = ServiceLocator.settings.effectiveChannel(Build.FINGERPRINT).first()
        manager.setChannelMode(mode)
        manager.register()
        maybeAutoConnect()
        // Fall back to Shizuku only in DIRECT mode where the system profile
        // is actually in play (RAW/AUTO bypass it entirely, SHIZUKU uses its
        // own privileged binding). Only auto-trigger when the system blocked
        // the direct path with a SecurityException.
        if (mode == "DIRECT") {
          for (i in 0 until 30) {
            if (manager.profileReady.value || manager.privilegedBlocked) break
            delay(250)
          }
          if (manager.privilegedBlocked) {
            manager.bindShizuku()
          }
        }
      }
    }
    } catch (t: Throwable) {
      // A single bad command must never take the whole bridge down (on
      // Android 14+ even startForeground can throw SecurityException when
      // BLUETOOTH_CONNECT isn't granted yet) - log it and keep the service
      // alive instead of crashing the process.
      Log.e(TAG, "onStartCommand failed", t)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    runCatching { manager.logConnection("שירות הגשר נסגר", true) }
    // An ongoing call notification outlives the process that posted it. When
    // the system killed the service mid-call the ringing notification stayed
    // on screen forever, with buttons wired to a service that no longer
    // existed - the "notification that will not go away".
    runCatching { Notifications.cancelCall(this) }
    stopRingtone()
    // BridgeHub is a process-wide object: it outlives this service, and every
    // screen reads it. Leaving the last values behind meant that a service
    // stopped while the UI was still alive left the home screen showing a live
    // link, a ringing call and an audio route for a bridge that no longer
    // exists - the same "the screen says one thing and the device does
    // another" the connection card was fixed for. Only the live facts are
    // cleared; the chosen device and the capability report are still true.
    BridgeHub.update {
      it.copy(
        connectionState = BluetoothProfile.STATE_DISCONNECTED,
        rawLinkActive = false,
        reconnecting = false,
        profileReady = false,
        call = null,
        audioState = 0,
        audioRoute = null,
        audioOutcome = CallAudioOutcome.IDLE,
      )
    }
    instance = null
    BridgeHub.service = null
    manager.shutdown()
    scope.cancel()
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  // ------------------------------------------------------------------ commands

  fun bondedDevices(): List<PairedDeviceInfo> = manager.bondedDevices()

  fun connectTo(address: String) {
    val adapter = adapter()
    if (adapter == null) {
      manager.logConnection("לא נמצא מתאם בלוטוס - לא ניתן להתחבר", true)
      return
    }
    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    if (device == null) {
      manager.logConnection("לא ניתן לפתור את המכשיר $address", true)
      return
    }
    // Apply the saved channel for THIS player before connecting. When the
    // service is started via requestConnect() (ACTION_CONNECT) it never runs
    // the ACTION_START block above, so without this a user who chose DIRECT or
    // SHIZUKU would silently fall back to AUTO after the service restarts.
    scope.launch {
      val mode = ServiceLocator.settings.effectiveChannel(Build.FINGERPRINT).first()
      manager.setChannelMode(mode)
      manager.register()
      if (mode == "DIRECT") {
        // The DIRECT profile proxy binds asynchronously. Give it a bounded
        // moment to arrive so a manual connect never sees client == null and
        // reports "פרופיל הדיבורית לא זמין" prematurely.
        for (i in 0 until 20) {
          if (manager.profileReady.value) break
          delay(250)
        }
      }
      manager.connect(device)
      ServiceLocator.settings.rememberDevice(device.name ?: address, address)
    }
  }

  fun dial(number: String): Boolean = manager.dial(number)

  fun disconnect() {
    lastManualDisconnectAt = System.currentTimeMillis()
    manager.logConnection("ניתוק יזום על ידי המשתמש")
    manager.disconnect()
  }

  fun clearConnectionLog() = manager.clearConnectionLog()

  fun answer() = manager.answer()
  fun reject() = manager.reject()
  fun hangup() = manager.hangup()
  fun toggleAudio() = manager.toggleAudio()

  /** Tries to bind the privileged HFP bridge through Shizuku (shell/root UID). */
  fun bindShizuku() {
    scope.launch { manager.bindShizuku() }
  }

  /** Records briefly from the call microphone and reports the result. */
  fun checkMicrophone(onResult: (String) -> Unit) {
    scope.launch { onResult(manager.audio.checkMicrophone()) }
  }

  // ------------------------------------------------------------------ internals

  /**
   * Runs a bridge command, retrying briefly while it keeps failing.
   *
   * A command can legitimately fail for a second or two: the intent may have
   * just started the service, or the raw socket may be mid-reconnect. Failing
   * once and silently was the behaviour behind "I press answer and nothing
   * happens" - so retry for a bounded window and, if it still fails, put the
   * reason in the journal instead of nowhere.
   */
  private fun retryCommand(label: String, action: () -> Boolean) {
    scope.launch {
      val deadline = System.currentTimeMillis() + 4_000
      var attempts = 0
      while (System.currentTimeMillis() < deadline) {
        attempts++
        if (runCatching { action() }.getOrDefault(false)) {
          if (attempts > 1) manager.logConnection("$label הצליח בניסיון $attempts", false)
          return@launch
        }
        delay(300)
      }
      manager.logConnection(
        "$label נכשל אחרי $attempts ניסיונות - אין קישור פעיל אל הטלפון",
        true,
      )
    }
  }

  private fun adapter(): BluetoothAdapter? =
    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private fun ensureForeground() {
    val notification = Notifications.bridgeNotification(this, stateText())
    // Android 14+ enforces that an FGS of type connectedDevice can only be
    // started while BLUETOOTH_CONNECT is actually granted - otherwise the
    // system throws SecurityException and kills the whole process ("opens and
    // just doesn't work"). When the permission is missing, start a typeless
    // foreground service instead; the bridge can't run without Bluetooth
    // anyway, but it must never crash over a notification.
    val canUseConnectedDeviceType = Build.VERSION.SDK_INT < 34 ||
      checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val type = if (canUseConnectedDeviceType && Build.VERSION.SDK_INT >= 34) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    } else {
      0
    }
    try {
      ServiceCompat.startForeground(this, NOTIF_BRIDGE, notification, type)
    } catch (e: Throwable) {
      // Safety net: last resort is a typeless foreground service; if even
      // that fails, stop the service instead of crashing the process.
      Log.w(TAG, "startForeground(type=$type) failed", e)
      runCatching { ServiceCompat.startForeground(this, NOTIF_BRIDGE, notification, 0) }
        .onFailure { runCatching { stopSelf() } }
    }
  }

  private fun stateText(): String {
    val s = BridgeHub.state.value
    // Same wording as the home screen (ui/MainScreen.connectionText): a
    // notification that says "מנותק" while the direct channel is carrying a
    // live link is the same lie, just in a different place.
    val name = s.deviceName ?: "טלפון כשר"
    val conn = when {
      s.connectionState == BluetoothProfile.STATE_CONNECTED -> "מחובר ל-$name"
      s.rawLinkActive -> "מחובר ל-$name (ערוץ ישיר)"
      s.connectionState == BluetoothProfile.STATE_CONNECTING -> "מתחבר..."
      s.connectionState == BluetoothProfile.STATE_DISCONNECTING -> "מתנתק..."
      s.reconnecting -> "מנסה להתחבר מחדש..."
      s.deviceName != null -> "מנותק"
      else -> "לא מחובר למכשיר"
    }
    val audio = when {
      s.audioState == 2 || s.audioOutcome == CallAudioOutcome.ON_PLAYER -> " · שמע בנגן"
      s.audioOutcome == CallAudioOutcome.ON_PHONE -> " · שמע בטלפון"
      else -> ""
    }
    return conn + audio
  }

  private fun maybeAutoConnect() {
    scope.launch {
      val settings = ServiceLocator.settings
      val dev = settings.lastDevice.first() ?: return@launch
      if (!settings.autoConnect.first()) return@launch
      delay(400)
      runCatching { adapter()?.getRemoteDevice(dev.address) }?.getOrNull()?.let {
        manager.connect(it)
      }
    }
  }

  private fun observeSettings() {
    scope.launch { ServiceLocator.settings.fullScreen.collect { fullScreenEnabled = it } }
    scope.launch { ServiceLocator.settings.vibrate.collect { vibrateEnabled = it } }
    scope.launch {
      val settings = ServiceLocator.settings
      combine(settings.autoAudio, settings.volumeBoost) { auto, boost -> auto to boost }
        .collect { (auto, boost) -> manager.setAudioPrefs(auto, boost) }
    }
    scope.launch {
      val settings = ServiceLocator.settings
      val fp = Build.FINGERPRINT
      combine(settings.audioMode(fp), settings.audioImpossible(fp)) { mode, impossible ->
        mode to impossible
      }.collect { (mode, impossible) -> manager.setAudioMode(mode, impossible) }
    }
    // Remember, per player, that call audio proved impossible here. Re-deriving
    // it on every call costs a six-second window in which the app holds the
    // player's audio pipeline for a route that will never appear.
    manager.onAudioRetryRequested = {
      scope.launch { ServiceLocator.settings.setAudioImpossible(Build.FINGERPRINT, false) }
    }
    manager.onAudioProvenImpossible = {
      scope.launch {
        ServiceLocator.settings.setAudioImpossible(Build.FINGERPRINT, true)
        manager.logConnection(
          "נרשם: הנגן הזה אינו יכול לקלוט קול שיחה. משיחות הבאות השמע יישאר בטלפון " +
            "מיד, בלי השהיה. ניתן לשנות ב'הגדרות ← מצב שמע בשיחה'",
          false,
        )
      }
    }
    scope.launch {
      val settings = ServiceLocator.settings
      var appliedManual: String? = null
      // React to the user's *manual* choice, not the effective channel. The
      // effective channel also changes when onBackendWorked() learns the
      // channel that worked on this player (AUTO -> RAW right after a
      // successful connect); treating that as a user switch disconnected the
      // link the app had just established, causing a spurious drop on the
      // first connection after every fresh install.
      settings.channelState(Build.FINGERPRINT).collect { cs ->
        manager.setChannelMode(cs.effective)
        val manualChanged = appliedManual != null && appliedManual != cs.manual
        // Record the applied choice BEFORE any early return below. Returning
        // without it left appliedManual stale, so a player with no remembered
        // device re-entered this branch on every single emission.
        appliedManual = cs.manual
        if (manualChanged) {
          // The user switched the channel in settings - re-apply it to the
          // live connection so the change takes effect immediately.
          val dev = settings.lastDevice.first() ?: return@collect
          manager.disconnect()
          delay(300)
          val target = runCatching { adapter()?.getRemoteDevice(dev.address) }.getOrNull()
          if (target != null) {
            // The DIRECT path needs the in-process profile proxy, which is
            // registered on service start (and by connectTo()). A service that
            // started in AUTO/RAW skipped register() entirely, so switching to
            // DIRECT on a live service would otherwise fail with "פרופיל
            // הדיבורית לא זמין" until the app restarted. Register it now and
            // give the proxy a bounded moment to arrive before connecting.
            manager.register()
            if (cs.effective == "DIRECT") {
              for (i in 0 until 20) {
                if (manager.profileReady.value || manager.privilegedBlocked) break
                delay(250)
              }
            }
            manager.connect(target)
          }
        }
      }
    }
  }

  /**
   * One-time capability report for the diagnostics tab: what this player is
   * (manufacturer/model/SDK), whether the hidden HFP class is exposed, and
   * whether Shizuku is available and authorized. Dynamic fields (privileged
   * block, SCO) are updated by the collectors below.
   */
  private fun publishCapabilityReport() {
    scope.launch {
      val (sAvail, sGranted) = manager.shizukuState()
      val rootAvail = manager.rootState()
      BridgeHub.update {
        it.copy(
          deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
          hiddenApiAvailable = HiddenHfp.isAvailable,
          shizukuAvailable = sAvail,
          shizukuGranted = sGranted,
          rootAvailable = rootAvail,
          fullScreenAllowed = Notifications.canUseFullScreen(this@BridgeService),
        )
      }
    }
  }

  /** Human-readable SCO support line for the diagnostics tab. */
  private fun scoSupportText(): String {
    val a = manager.audio
    return when {
      a.scoDeviceEverSeen && a.scoConnected.value -> "מחובר (${a.scoTechniqueUsed})"
      a.scoDeviceEverSeen -> "נתמך - לא מחובר עכשיו"
      else -> "לא זוהה התקן SCO - הנגן כנראה תומך רק בבקרה, לא בקול"
    }
  }

  /**
   * Re-reads the HFP connection policy and republishes it to BridgeHub, for
   * when the diagnostics screen opens. Runs the blocking read off the main
   * thread; the existing cached value in BridgeUiState renders immediately
   * meanwhile.
   */
  fun refreshHeadsetClientPolicy() {
    scope.launch {
      val text = headsetClientPolicyText()
      BridgeHub.update { it.copy(headsetClientPolicy = text) }
    }
  }

  /**
   * Human-readable HFP-client connection-policy line for the diagnostics tab.
   *
   * NOTE: this read is a binding/await/unbinding binder round trip with a
   * multi-second timeout (HiddenHfp.profilePolicy). It is neither cheap nor
   * side-effect-free, and it MUST never run on the main thread - always call it
   * from a suspending context that dispatches to IO.
   */
  private suspend fun headsetClientPolicyText(): String = withContext(Dispatchers.IO) {
    val policy = manager.headsetClientPolicy(
      BridgeHub.state.value.deviceAddress?.let { addr ->
        runCatching { adapter()?.getRemoteDevice(addr) }.getOrNull()
      },
    )
    when (policy) {
      HiddenHfp.POLICY_ALLOWED -> "מאושר"
      HiddenHfp.POLICY_FORBIDDEN -> "חסום"
      HiddenHfp.POLICY_UNREADABLE -> "לא ניתן לקריאה"
      else -> "לא ידוע ($policy)"
    }
  }

  private fun observeManager() {
    scope.launch {
      manager.profileReady.collect { ready ->
        BridgeHub.update { it.copy(profileReady = ready) }
        updateBridgeNotification()
      }
    }
    scope.launch {
      manager.connectionState.collect { s ->
        BridgeHub.update {
          it.copy(
            connectionState = s,
            adapterOn = manager.adapterOn,
            // "Disconnected" and "retrying right now" look identical to a
            // user staring at the home screen; they are not the same thing.
            reconnecting = s != BluetoothProfile.STATE_CONNECTED &&
              (manager.rawOwnsConnectionLoop || reconnecting),
          )
        }
        // With no link there is no way to learn that a call ended, so a call
        // left over from the dead link would keep its notification and its
        // full-screen UI alive indefinitely. Drop it with the link.
        if (s == BluetoothProfile.STATE_DISCONNECTED && !manager.rawLinkActive.value) {
          manager.clearCall()
          Notifications.cancelCall(this@BridgeService)
        }
        updateBridgeNotification()
        maybeReconnect(s)
      }
    }
    scope.launch {
      manager.device.collect { d ->
        BridgeHub.update { it.copy(deviceName = d?.name, deviceAddress = d?.address) }
        updateBridgeNotification()
      }
    }
    scope.launch {
      manager.audioState.collect { a -> BridgeHub.update { it.copy(audioState = a) } }
    }
    scope.launch {
      manager.audio.routeLabel.collect { r ->
        BridgeHub.update {
          it.copy(
            audioRoute = r,
            scoSupport = scoSupportText(),
            scoTechnique = manager.audio.scoTechniqueUsed.ifBlank { null },
          )
        }
      }
    }
    scope.launch {
      manager.audio.outcome.collect { o -> BridgeHub.update { it.copy(audioOutcome = o) } }
    }
    scope.launch {
      manager.audioRouteAllowed.collect { a ->
        BridgeHub.update { it.copy(audioRouteAllowed = a) }
      }
    }
    scope.launch {
      manager.privilegedBlockedFlow.collect { blocked ->
        BridgeHub.update { it.copy(privilegedBlocked = blocked) }
      }
    }
    scope.launch {
      manager.backendLabel.collect { r -> BridgeHub.update { it.copy(backendLabel = r) } }
    }
    scope.launch {
      manager.rawLinkActive.collect { up ->
        BridgeHub.update { it.copy(rawLinkActive = up) }
      }
    }
    scope.launch {
      manager.rawDropInfo.collect { d -> BridgeHub.update { it.copy(rawDropInfo = d) } }
    }
    scope.launch {
      manager.rawConnectionDiagnostics.collect { d ->
        BridgeHub.update { it.copy(rawConnectionDiagnostics = d) }
      }
    }
    scope.launch {
      manager.connectionLog.collect { lines ->
        BridgeHub.update { it.copy(connectionLog = lines) }
      }
    }
    scope.launch {
      // The HFP policy row is not a flow and must NOT refresh per connection-log
      // line: the read is a blocking binder round trip (up to several seconds)
      // and a connection attempt writes dozens of lines in a burst, so updating
      // it here was a multi-second IPC on the main thread for every line. Refresh
      // it only when the connection state actually changes, debounced so a burst
      // of state events triggers at most one read. The read itself runs off the
      // main thread inside headsetClientPolicyText(); the diagnostics screen also
      // triggers a refresh when it opens (rendering is served from cache).
      manager.connectionState
        .debounce(300)
        .collect {
          val text = headsetClientPolicyText()
          BridgeHub.update { it.copy(headsetClientPolicy = text) }
        }
    }
    scope.launch {
      manager.lastError.collect { e -> BridgeHub.update { it.copy(lastError = e) } }
    }
    scope.launch {
      manager.call.collect { info -> onCallChanged(info) }
    }
  }

  private fun updateBridgeNotification() {
    Notifications.updateBridge(this, stateText())
  }

  private suspend fun onCallChanged(info: CallInfo?) {
    BridgeHub.update { it.copy(call = info) }
    armStaleCallWatchdog(info)
    // Anything that is not a ringing call must silence the ringtone -
    // answered, rejected, ended, or the link dropped underneath it.
    if (info?.state != CallState.INCOMING && info?.state != CallState.WAITING) stopRingtone()

    if (info == null || info.state == CallState.IDLE || info.state == CallState.TERMINATED) {
      Notifications.cancelCall(this)
      // A null/idle snapshot means the AG has no remaining calls. Finalize
      // every session, not only the most recently observed one: HFP call
      // waiting can keep the first call held while a second call is ringing.
      activeCallLogs.toList().forEach { session ->
        val missed = session.direction == CallDirection.INCOMING && !session.sawActive
        val duration = if (session.sawActive && session.startedAt != 0L) {
          ((System.currentTimeMillis() - session.startedAt) / 1000L).toInt()
        } else {
          0
        }
        ServiceLocator.contacts.finishCall(session.id, missed, duration)
      }
      activeCallLogs.clear()
      return
    }

    val normalizedNumber = ContactsRepository.normalizePhone(info.number.orEmpty())
    val sameDirection = activeCallLogs.filter { it.direction == info.direction }
    val existingSession = sameDirection.firstOrNull { candidate ->
      val candidateNumber = ContactsRepository.normalizePhone(candidate.number.orEmpty())
      when {
        // Both sides know the number: it decides, and only it.
        normalizedNumber.isNotEmpty() && candidateNumber.isNotEmpty() ->
          normalizedNumber == candidateNumber
        // A caller ID can arrive shortly after the first CIEV/RING event.
        // Reuse the direction's number-less session instead of creating a
        // duplicate row when the number becomes known.
        candidateNumber.isEmpty() -> true
        // This update has no number but the open session does. A gateway that
        // stops repeating the number mid-call is describing the call already
        // in progress, not a new one - so long as there is exactly one to be
        // describing. With two open calls in this direction (call waiting)
        // there is no way to tell which, and guessing would merge two calls
        // into one log entry, so a new session is the safer answer.
        else -> sameDirection.size == 1
      }
    }
    val isNewSession = existingSession == null
    val session = existingSession ?: run {
      val name = ServiceLocator.contacts.nameFor(info.number)
      val id = ServiceLocator.contacts.logCall(
        info.number ?: "",
        name,
        info.direction,
        info.state,
      )
      CallLogSession(id, info.number, info.direction).also { activeCallLogs += it }
    }
    if (!isNewSession && session.number.isNullOrBlank() && !info.number.isNullOrBlank()) {
      // Keep the in-memory identity stable while caller ID is filled in.
      session.number = info.number
    }

    when (info.state) {
      CallState.INCOMING, CallState.WAITING -> {
        // A new WAITING event alongside an existing ACTIVE/HELD session is a
        // second call, so it gets its own log row and its own missed flag.
        if (isNewSession) {
          showIncomingCall(ServiceLocator.contacts.nameFor(info.number), info.number)
        }
      }
      CallState.ACTIVE -> {
        session.sawActive = true
        // Only start the clock on the first ACTIVE of this call, so a
        // HELD -> ACTIVE transition mid-call doesn't shrink its duration.
        if (session.startedAt == 0L) session.startedAt = System.currentTimeMillis()
        manager.connectAudio()
        // The caller's name, not the bare number: the notification is the one
        // place a running call is visible once the full-screen UI is dismissed,
        // and it was the only screen in the app still showing digits.
        Notifications.showInCall(
          this,
          info,
          ServiceLocator.contacts.nameFor(info.number) ?: info.number ?: "שיחה",
        )
      }
      else -> Unit
    }
  }

  /**
   * Guards against a call state that never ends.
   *
   * A basic gateway can stop reporting a call - no +CIEV, no CLCC row - and
   * the bridge then holds a ringing call forever: an ongoing notification that
   * cannot be dismissed, and a full-screen call UI over an ended call. No real
   * phone rings for two minutes, so a ringing state that old is stale by
   * definition. An ACTIVE call is left alone: real conversations do run long.
   */
  private fun armStaleCallWatchdog(info: CallInfo?) {
    staleCallWatchdog?.cancel()
    staleCallWatchdog = null
    val ringing = info?.state == CallState.INCOMING || info?.state == CallState.WAITING ||
      info?.state == CallState.DIALING || info?.state == CallState.ALERTING
    if (!ringing) return
    staleCallWatchdog = scope.launch {
      delay(STALE_RINGING_MS)
      // Only act when nothing changed in the meantime - a state change would
      // have cancelled this job and armed a new one.
      if (manager.call.value != info) return@launch
      manager.logConnection(
        "מצב השיחה לא התעדכן ${STALE_RINGING_MS / 1000} שניות - מנקה אותה כדי שההתראה לא תיתקע",
        true,
      )
      manager.clearCall()
      Notifications.cancelCall(this@BridgeService)
    }
  }

  private fun showIncomingCall(name: String?, number: String?) {
    Notifications.showIncomingCall(
      this,
      name ?: number ?: "מספר חסום",
      fullScreenEnabled,
      vibrateEnabled,
    )
    // The ringtone belongs to the service, not to the call activity.
    // Android 10+ blocks background activity starts, so startActivity() below
    // is frequently dropped without an exception - and when the ringtone lived
    // in the activity, that meant a completely SILENT incoming call, with only
    // a notification to notice. Ringing from here always happens; the
    // full-screen intent on the notification is what actually brings the
    // screen up when the direct start is refused.
    startRingtone()
    if (fullScreenEnabled) {
      val intent = IncomingCallActivity.createIntent(this)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
      runCatching { startActivity(intent) }
    }
  }

  private fun startRingtone() {
    if (ringtone?.isPlaying == true) return
    runCatching {
      val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: return
      val r = RingtoneManager.getRingtone(this, uri) ?: return
      r.audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
      // Ringtone.play() is one-shot below API 28, which made a call ring once
      // and then wait in silence.
      if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
      ringtone = r
      r.play()
    }.onFailure { Log.w(TAG, "ringtone failed", it) }
  }

  private fun stopRingtone() {
    runCatching { ringtone?.stop() }
    ringtone = null
  }

  private suspend fun maybeReconnect(state: Int) {
    if (state != BluetoothProfile.STATE_DISCONNECTED || reconnecting) return
    // RawHfpClient already owns a bounded reconnect loop. Starting a second
    // loop here resets its socket/statistics and can create two competing
    // RFCOMM attempts, which makes the phone drop both links.
    if (manager.rawOwnsConnectionLoop) return
    if (System.currentTimeMillis() - lastManualDisconnectAt < 60_000) return
    val settings = ServiceLocator.settings
    val dev = settings.lastDevice.first() ?: return
    if (!settings.autoConnect.first()) return
    reconnecting = true
    delay(8000)
    reconnecting = false
    // Re-check after the delay: RAW may have started between the initial
    // state event and this delayed callback. Never reset an active RAW loop.
    if (manager.rawOwnsConnectionLoop) return
    if (manager.connectionState.value == BluetoothProfile.STATE_DISCONNECTED && manager.adapterOn) {
      runCatching { adapter()?.getRemoteDevice(dev.address) }?.getOrNull()?.let {
        manager.connect(it)
      }
    }
  }
}
