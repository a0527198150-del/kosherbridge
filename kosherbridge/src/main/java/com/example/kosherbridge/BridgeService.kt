package com.example.kosherbridge

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.kosherbridge.bluetooth.CallDirection
import com.example.kosherbridge.bluetooth.CallInfo
import com.example.kosherbridge.bluetooth.CallState
import com.example.kosherbridge.bluetooth.HfpClientManager
import com.example.kosherbridge.bluetooth.PairedDeviceInfo
import com.example.kosherbridge.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the bridge to the kosher phone:
 * keeps the HFP client connection alive, surfaces incoming calls with a
 * full-screen UI, logs calls, and exposes dial/answer/reject/hang-up commands.
 */
class BridgeService : Service() {

  companion object {
    private const val NOTIF_BRIDGE = 1

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
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var manager: HfpClientManager

  private var activeCallLogId: Long? = null
  private var lastCallInfo: CallInfo? = null
  private var fullScreenEnabled = true
  private var reconnecting = false
  private var lastManualDisconnectAt = 0L

  override fun onCreate() {
    super.onCreate()
    instance = this
    Notifications.createChannels(this)
    manager = HfpClientManager(this, scope)
    observeSettings()
    observeManager()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CONNECT -> intent.getStringExtra(EXTRA_DEVICE)?.let { connectTo(it) }
      ACTION_DISCONNECT -> disconnect()
      ACTION_DIAL -> intent.getStringExtra(EXTRA_NUMBER)?.let { dial(it) }
      ACTION_ANSWER -> manager.answer()
      ACTION_REJECT -> manager.reject()
      ACTION_HANGUP -> manager.hangup()
      ACTION_TOGGLE_AUDIO -> manager.toggleAudio()
    }
    ensureForeground()
    if (intent?.action == ACTION_START || intent?.action == null) {
      manager.register()
      maybeAutoConnect()
    }
    return START_STICKY
  }

  override fun onDestroy() {
    instance = null
    manager.shutdown()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  // ------------------------------------------------------------------ commands

  fun bondedDevices(): List<PairedDeviceInfo> = manager.bondedDevices()

  fun connectTo(address: String) {
    val adapter = adapter()
    if (adapter == null) return
    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    if (device != null) {
      manager.register()
      manager.connect(device)
      scope.launch { ServiceLocator.settings.rememberDevice(device.name ?: address, address) }
    }
  }

  fun dial(number: String) {
    manager.dial(number)
  }

  fun disconnect() {
    lastManualDisconnectAt = System.currentTimeMillis()
    manager.disconnect()
  }

  fun answer() = manager.answer()
  fun reject() = manager.reject()
  fun hangup() = manager.hangup()
  fun toggleAudio() = manager.toggleAudio()

  // ------------------------------------------------------------------ internals

  private fun adapter(): BluetoothAdapter? =
    (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private fun ensureForeground() {
    val notification = Notifications.bridgeNotification(this, stateText())
    val type = if (Build.VERSION.SDK_INT >= 34) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIF_BRIDGE, notification, type)
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

  private fun observeSettings() {
    scope.launch { ServiceLocator.settings.fullScreen.collect { fullScreenEnabled = it } }
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
      activeCallLogId?.let { id -> ServiceLocator.contacts.updateCallState(id, CallState.IDLE) }
      activeCallLogId = null
      lastCallInfo = null
      return
    }

    lastCallInfo = info
    when (info.state) {
      CallState.INCOMING, CallState.WAITING -> {
        if (prev == null || (prev.state != CallState.INCOMING && prev.state != CallState.WAITING)) {
          val name = ServiceLocator.contacts.nameFor(info.number)
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
          activeCallLogId = ServiceLocator.contacts.logCall(
            info.number ?: "",
            name,
            CallDirection.OUTGOING,
            CallState.DIALING,
          )
        }
      }
      CallState.ACTIVE -> {
        manager.connectAudio()
        Notifications.showInCall(this, info)
      }
      else -> Unit
    }
  }

  private fun showIncomingCall(title: String, number: String?) {
    Notifications.showIncomingCall(this, title, number, fullScreenEnabled)
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
    if (System.currentTimeMillis() - lastManualDisconnectAt < 60_000) return
    val settings = ServiceLocator.settings
    val dev = settings.lastDevice.first() ?: return
    if (!settings.autoConnect.first()) return
    reconnecting = true
    delay(8000)
    reconnecting = false
    if (manager.connectionState.value == BluetoothProfile.STATE_DISCONNECTED && manager.adapterOn) {
      runCatching { adapter()?.getRemoteDevice(dev.address) }?.getOrNull()?.let {
        manager.connect(it)
      }
    }
  }
}
