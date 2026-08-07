package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.util.Log
import java.lang.reflect.Method

/**
 * Safe reflection wrapper over the hidden `android.bluetooth.BluetoothHeadsetClient`
 * API. That profile is the HFP **client** (hands-free) role - the same role a car
 * kit plays: this device connects to the kosher phone (the audio gateway), sees its
 * calls and can answer / reject / dial / hang-up through it.
 *
 * Everything is guarded with runCatching-style fallbacks because on some devices the
 * API is not exposed at all (then [isAvailable] is false) and on Android 12+ hidden
 * API enforcement may block reflection (see the in-app diagnostics / README).
 */
object HiddenHfp {
  const val PROFILE_ID = 16 // BluetoothProfile.HEADSET_CLIENT (hidden constant)

  private const val TAG = "HiddenHfp"

  private var clientClass: Class<*>? = null
  var callClass: Class<*>? = null
    private set
  var callbackClass: Class<*>? = null
    private set

  private var mConnect: Method? = null
  private var mDisconnect: Method? = null
  private var mGetConnected: Method? = null
  private var mGetConnectionState: Method? = null
  private var mGetAudioState: Method? = null
  private var mConnectAudio: Method? = null
  private var mDisconnectAudio: Method? = null
  private var mDial: Method? = null
  private var mRedial: Method? = null
  private var mAccept: Method? = null
  private var mReject: Method? = null
  private var mHangup: Method? = null
  private var mCurrentCalls: Method? = null
  private var mRegisterCallback: Method? = null
  private var mUnregisterCallback: Method? = null

  private var mCallState: Method? = null
  private var mCallNumber: Method? = null
  private var mCallRemote: Method? = null
  private var mCallDirection: Method? = null

  // BluetoothHeadsetClientCall constants (AOSP fallbacks if reflection fails)
  var callStateActive = 0; private set
  var callStateHeld = 1; private set
  var callStateDialing = 2; private set
  var callStateAlerting = 3; private set
  var callStateIncoming = 4; private set
  var callStateWaiting = 5; private set
  var callStateIdle = 6; private set
  var callStateTerminated = 7; private set
  var callDirectionOutgoing = 0; private set
  var callDirectionIncoming = 1; private set

  fun init() {
    if (clientClass != null) return
    clientClass = try { Class.forName("android.bluetooth.BluetoothHeadsetClient") }
    catch (e: Throwable) { Log.w(TAG, "BluetoothHeadsetClient not available", e); null }
    callClass = try { Class.forName("android.bluetooth.BluetoothHeadsetClientCall") }
    catch (e: Throwable) { null }
    callbackClass = try { Class.forName("android.bluetooth.BluetoothHeadsetClient\$Callback") }
    catch (e: Throwable) { null }

    val c = clientClass
    mConnect = method(c, "connect", BluetoothDevice::class.java)
    mDisconnect = method(c, "disconnect", BluetoothDevice::class.java)
    mGetConnected = method(c, "getConnectedDevices")
    mGetConnectionState = method(c, "getConnectionState", BluetoothDevice::class.java)
    mGetAudioState = method(c, "getAudioState", BluetoothDevice::class.java)
    mConnectAudio = method(c, "connectAudio")
    mDisconnectAudio = method(c, "disconnectAudio")
    mDial = method(c, "dial", String::class.java)
    mRedial = method(c, "redial")
    mAccept = method(c, "acceptCall")
    mReject = method(c, "rejectCall")
    mHangup = method(c, "hangupCall")
    mCurrentCalls = method(c, "getCurrentCalls")
    callbackClass?.let { cb ->
      mRegisterCallback = method(c, "registerCallback", cb)
      mUnregisterCallback = method(c, "unregisterCallback", cb)
    }

    val cc = callClass
    mCallState = method(cc, "getState")
    mCallNumber = method(cc, "getNumber")
    mCallRemote = method(cc, "getRemoteParty")
    mCallDirection = method(cc, "getDirection")

    callStateActive = intConstant(cc, "CALL_STATE_ACTIVE", 0)
    callStateHeld = intConstant(cc, "CALL_STATE_HELD", 1)
    callStateDialing = intConstant(cc, "CALL_STATE_DIALING", 2)
    callStateAlerting = intConstant(cc, "CALL_STATE_ALERTING", 3)
    callStateIncoming = intConstant(cc, "CALL_STATE_INCOMING", 4)
    callStateWaiting = intConstant(cc, "CALL_STATE_WAITING", 5)
    callStateIdle = intConstant(cc, "CALL_STATE_IDLE", 6)
    callStateTerminated = intConstant(cc, "CALL_STATE_TERMINATED", 7)
    callDirectionOutgoing = intConstant(cc, "CALL_DIRECTION_OUTGOING", 0)
    callDirectionIncoming = intConstant(cc, "CALL_DIRECTION_INCOMING", 1)
  }

  val isAvailable: Boolean get() = clientClass != null

  fun castClient(proxy: BluetoothProfile): Any? =
    try { clientClass?.cast(proxy) } catch (e: Throwable) { null }

  fun connect(client: Any?, device: BluetoothDevice): Boolean = bool(mConnect, client, device)
  fun disconnect(client: Any?, device: BluetoothDevice): Boolean = bool(mDisconnect, client, device)
  fun connectedDevices(client: Any?): List<*> = list(mGetConnected, client)
  fun connectionState(client: Any?, device: BluetoothDevice?): Int =
    if (device == null) BluetoothProfile.STATE_DISCONNECTED
    else int(mGetConnectionState, client, BluetoothProfile.STATE_DISCONNECTED, device)
  fun audioState(client: Any?, device: BluetoothDevice?): Int =
    if (device == null) 0 else int(mGetAudioState, client, 0, device)
  fun connectAudio(client: Any?): Boolean = bool(mConnectAudio, client)
  fun disconnectAudio(client: Any?): Boolean = bool(mDisconnectAudio, client)
  fun dial(client: Any?, number: String): Boolean = bool(mDial, client, number)
  fun redial(client: Any?): Boolean = bool(mRedial, client)
  fun accept(client: Any?): Boolean = bool(mAccept, client)
  fun reject(client: Any?): Boolean = bool(mReject, client)
  fun hangup(client: Any?): Boolean = bool(mHangup, client)
  fun currentCalls(client: Any?): List<*> = list(mCurrentCalls, client)
  fun registerCallback(client: Any?, callback: Any?): Boolean = bool(mRegisterCallback, client, callback)
  fun unregisterCallback(client: Any?, callback: Any?): Boolean = bool(mUnregisterCallback, client, callback)

  fun callState(call: Any): Int = int(mCallState, call, callStateIdle)
  fun callNumber(call: Any): String? =
    (string(mCallNumber, call) ?: string(mCallRemote, call))?.takeIf { it.isNotBlank() }
  fun callDirection(call: Any): Int = int(mCallDirection, call, callDirectionIncoming)

  private fun method(c: Class<*>?, name: String, vararg p: Class<*>): Method? =
    if (c == null) null
    else try { c.getMethod(name, *p) } catch (e: Throwable) { Log.w(TAG, "missing method $name"); null }

  private fun intConstant(c: Class<*>?, name: String, fallback: Int): Int =
    try { c?.getField(name)?.getInt(null) ?: fallback } catch (e: Throwable) { fallback }

  private fun bool(m: Method?, recv: Any?, vararg args: Any?): Boolean =
    try { (m?.invoke(recv, *args) as? Boolean) ?: false } catch (e: Throwable) { false }

  private fun int(m: Method?, recv: Any?, def: Int, vararg args: Any?): Int =
    try { (m?.invoke(recv, *args) as? Int) ?: def } catch (e: Throwable) { def }

  private fun string(m: Method?, recv: Any?, vararg args: Any?): String? =
    try { m?.invoke(recv, *args) as? String } catch (e: Throwable) { null }

  private fun list(m: Method?, recv: Any?): List<*> =
    try { m?.invoke(recv) as? List<*> ?: emptyList<Any>() } catch (e: Throwable) { emptyList<Any>() }
}
