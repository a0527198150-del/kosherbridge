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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.kosherbridge.bluetooth.CallDirection
import com.example.kosherbridge.bluetooth.CallInfo
import com.example.kosherbridge.bluetooth.CallState
import com.example.kosherbridge.bluetooth.HfpClientManager
import com.example.kosherbridge.bluetooth.HiddenHfp
import com.example.kosherbridge.bluetooth.PairedDeviceInfo
import com.example.kosherbridge.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the bridge to the kosher phone:
 * keeps the HFP client connection alive, surfaces incoming calls with a
 * full-screen UI, logs calls, and exposes dial/answer/reject/hang-up commands.
 */
class BridgeService : Service() {

  companion object {
    private const val TAG = "BridgeService"
    private const val NOTIF_BRIDGE = 1

    const val ACTION_START = "com.example.kosherbridge.action.START"
    const val ACTION_CONNECT = "com.example.kosherbridge.action.CONNECT"
    const val EXTRA_DEVICE = "com.example.kosherbridge.extra.DEVICE"
    const val EXTRA_RAW = "com.example.kosherbridge.extra.RAW"
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

    /** Force the raw RFCOMM path even when the service is not running yet. */
    fun requestConnectRaw(context: Context, address: String) {
      val svc = instance
      if (svc != null) {
        svc.connectRaw(address)
      } else {
        val intent = Intent(context, BridgeService::class.java)
          .setAction(ACTION_CONNECT)
          .putExtra(EXTRA_DEVICE, address)
          .putExtra(EXTRA_RAW, true)
        ContextCompat.startForegroundService(context, intent)
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var manager: HfpClientManager

  private var activeCallLogId: Long? = null
  private var lastCallInfo: CallInfo? = null
  private var fullScreenEnabled = true
  private var vibrateEnabled = true
  private var sawActive = false       // did the current call ever reach ACTIVE?
  private var callStartedAt = 0L      // when ACTIVE began, for duration
  private var activeCallDirection: CallDirection? = null
  private var reconnecting = false
  private var lastManualDisconnectAt = 0L
  private var wakeLock: PowerManager.WakeLock? = null

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
    // Remember which connection channel actually worked on this exact player
    // (keyed by Build.FINGERPRINT) so the next launch can jump straight to it.
    manager.onBackendWorked = { backend ->
      scope.launch { ServiceLocator.settings.learnChannel(Build.FINGERPRINT, backend) }
    }
    observeSettings()
    observeManager()
    publishCapabilityReport()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      when (intent?.action) {
        ACTION_CONNECT -> {
          val addr = intent.getStringExtra(EXTRA_DEVICE)
          if (addr != null) {
            if (intent.getBooleanExtra(EXTRA_RAW, false)) connectRaw(addr) else connectTo(addr)
          }
        }
        ACTION_DISCONNECT -> disconnect()
        ACTION_DIAL -> intent.getStringExtra(EXTRA_NUMBER)?.let { dial(it) }
        ACTION_ANSWER -> manager.answer()
        ACTION_REJECT -> manager.reject()
        ACTION_HANGUP -> manager.hangup()
        ACTION_TOGGLE_AUDIO -> manager.toggleAudio()
      }
      ensureForeground()
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
    manager.register()
    manager.connect(device)
    scope.launch { ServiceLocator.settings.rememberDevice(device.name ?: address, address) }
  }

  fun dial(number: String): Boolean = manager.dial(number)

  /** Direct HFP over RFCOMM - no permissions needed; audio depends on the player. */
  fun connectRaw(address: String) {
    val adapter = adapter()
    if (adapter == null) {
      manager.logConnection("לא נמצא מתאם בלוטוס - לא ניתן להתחבר (RAW)", true)
      return
    }
    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    if (device == null) {
      manager.logConnection("לא ניתן לפתור את המכשיר $address (RAW)", true)
      return
    }
    manager.connectRaw(device)
    scope.launch { ServiceLocator.settings.rememberDevice(device.name ?: address, address) }
  }

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
    val conn = when (s.connectionState) {
      BluetoothProfile.STATE_CONNECTED -> "מחובר ל-${s.deviceName ?: "טלפון כשר"}"
      BluetoothProfile.STATE_CONNECTING -> "מתחבר..."
      BluetoothProfile.STATE_DISCONNECTING -> "מתנתק..."
      else -> if (s.deviceName != null) "מנותק" else "לא מחובר למכשיר"
    }
    return conn + if (s.audioState == 2) " · שמע פעיל" else ""
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
      var applied: String? = null
      settings.effectiveChannel(Build.FINGERPRINT).collect { mode ->
        manager.setChannelMode(mode)
        if (applied != null && applied != mode) {
          // The user switched the channel in settings - re-apply it to the
          // live connection so the change takes effect immediately.
          val dev = settings.lastDevice.first() ?: return@collect
          manager.disconnect()
          delay(300)
          runCatching { adapter()?.getRemoteDevice(dev.address) }?.getOrNull()?.let {
            manager.connect(it)
          }
        }
        applied = mode
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
      BridgeHub.update {
        it.copy(
          deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
          hiddenApiAvailable = HiddenHfp.isAvailable,
          shizukuAvailable = sAvail,
          shizukuGranted = sGranted,
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

  private fun observeManager() {
    scope.launch {
      manager.profileReady.collect { ready ->
        BridgeHub.update { it.copy(profileReady = ready) }
        updateBridgeNotification()
      }
    }
    scope.launch {
      manager.connectionState.collect { s ->
        BridgeHub.update { it.copy(connectionState = s, adapterOn = manager.adapterOn) }
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
      manager.privilegedBlockedFlow.collect { blocked ->
        BridgeHub.update { it.copy(privilegedBlocked = blocked) }
      }
    }
    scope.launch {
      manager.backendLabel.collect { r -> BridgeHub.update { it.copy(backendLabel = r) } }
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
    val prev = lastCallInfo
    BridgeHub.update { it.copy(call = info) }

    if (info == null || info.state == CallState.IDLE || info.state == CallState.TERMINATED) {
      Notifications.cancelCall(this)
      // Finalize the call log entry: an incoming call that never reached ACTIVE
      // is a missed call; otherwise record the conversation length.
      activeCallLogId?.let { id ->
        val missed = activeCallDirection == CallDirection.INCOMING && !sawActive
        val duration = if (sawActive && callStartedAt != 0L) {
          ((System.currentTimeMillis() - callStartedAt) / 1000L).toInt()
        } else {
          0
        }
        ServiceLocator.contacts.finishCall(id, missed, duration)
      }
      activeCallLogId = null
      sawActive = false
      callStartedAt = 0L
      activeCallDirection = null
      lastCallInfo = null
      return
    }

    lastCallInfo = info
    when (info.state) {
      CallState.INCOMING, CallState.WAITING -> {
        if (prev == null || (prev.state != CallState.INCOMING && prev.state != CallState.WAITING)) {
          val name = ServiceLocator.contacts.nameFor(info.number)
          sawActive = false
          callStartedAt = 0L
          activeCallDirection = CallDirection.INCOMING
          activeCallLogId = ServiceLocator.contacts.logCall(
            info.number ?: "",
            name,
            CallDirection.INCOMING,
            info.state,
          )
          showIncomingCall(name ?: info.number ?: "שיחה נכנסת", info.number)
        }
      }
      CallState.DIALING -> {
        if (info.direction == CallDirection.OUTGOING && activeCallLogId == null) {
          val name = ServiceLocator.contacts.nameFor(info.number)
          activeCallDirection = CallDirection.OUTGOING
          activeCallLogId = ServiceLocator.contacts.logCall(
            info.number ?: "",
            name,
            CallDirection.OUTGOING,
            CallState.DIALING,
          )
        }
      }
      CallState.ACTIVE -> {
        sawActive = true
        // Only start the clock on the first ACTIVE of this call, so a
        // HELD -> ACTIVE transition mid-call doesn't shrink the duration
        // that gets written to the call log.
        if (callStartedAt == 0L) callStartedAt = System.currentTimeMillis()
        manager.connectAudio()
        Notifications.showInCall(this, info)
      }
      else -> Unit
    }
  }

  private fun showIncomingCall(title: String, number: String?) {
    Notifications.showIncomingCall(this, title, number, fullScreenEnabled, vibrateEnabled)
    if (fullScreenEnabled) {
      val intent = IncomingCallActivity.createIntent(this, number, title)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
      runCatching { startActivity(intent) }
    }
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
