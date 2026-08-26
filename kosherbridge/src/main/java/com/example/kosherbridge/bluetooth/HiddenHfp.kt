package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

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
  private const val PROFILE_PROXY_TIMEOUT_SECONDS = 2L
  /** BluetoothHeadsetClient.CALL_ACCEPT_NONE - answer without holding/terminating others. */
  private const val CALL_ACCEPT_NONE = 0

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
  private var mTerminate: Method? = null
  private var mCurrentCalls: Method? = null
  private var mRegisterCallback: Method? = null
  private var mUnregisterCallback: Method? = null

  private var mCallState: Method? = null
  private var mCallNumber: Method? = null
  private var mCallId: Method? = null
  private var mCallOutgoing: Method? = null

  /**
   * Becomes true the moment any privileged call is rejected with a
   * SecurityException - i.e. the profile proxy was obtained (so [isAvailable]
   * and profileReady are true) but the app actually lacks BLUETOOTH_PRIVILEGED
   * ("wall 2"). Sticky: the permission cannot change at runtime. The manager
   * uses this to fall back to Shizuku instead of failing silently.
   */
  @Volatile
  var privilegedBlocked: Boolean = false
    private set

  /**
   * Flow mirror of [privilegedBlocked], so the UI/service can react the moment
   * the wall is proven instead of only reading the getter.
   */
  val privilegedBlockedFlow = MutableStateFlow(false)

  /**
   * Records that the system blocked a privileged Bluetooth call with a
   * SecurityException - proof this device needs the Shizuku path. Called from
   * the manager when getProfileProxy itself is rejected, in addition to the
   * per-method invokers below.
   */
  fun markPrivilegedBlocked() {
    privilegedBlocked = true
    privilegedBlockedFlow.value = true
  }

  // BluetoothHeadsetClientCall constants (AOSP fallbacks if reflection fails)
  var callStateActive = 0; private set
  var callStateHeld = 1; private set
  var callStateDialing = 2; private set
  var callStateAlerting = 3; private set
  var callStateIncoming = 4; private set
  var callStateWaiting = 5; private set
  // There is no CALL_STATE_IDLE in the hidden API. Use -1 as the app's own
  // "no call / unknown" sentinel so it cannot collide with a real call state
  // (6 is CALL_STATE_HELD_BY_RESPONSE_AND_HOLD in AOSP).
  var callStateIdle = -1; private set
  var callStateHeldByResponseAndHold = 6; private set
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
    mDial = method(c, "dial", BluetoothDevice::class.java, String::class.java)
    mRedial = method(c, "redial", BluetoothDevice::class.java)
    mAccept = method(c, "acceptCall", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
    mReject = method(c, "rejectCall", BluetoothDevice::class.java)
    mTerminate = method(c, "terminateCall", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
    mCurrentCalls = method(c, "getCurrentCalls", BluetoothDevice::class.java)
    callbackClass?.let { cb ->
      mRegisterCallback = method(c, "registerCallback", cb)
      mUnregisterCallback = method(c, "unregisterCallback", cb)
    }

    val cc = callClass
    mCallState = method(cc, "getState")
    mCallNumber = method(cc, "getNumber")
    mCallId = method(cc, "getId")
    mCallOutgoing = method(cc, "isOutgoing")

    callStateActive = intConstant(cc, "CALL_STATE_ACTIVE", 0)
    callStateHeld = intConstant(cc, "CALL_STATE_HELD", 1)
    callStateDialing = intConstant(cc, "CALL_STATE_DIALING", 2)
    callStateAlerting = intConstant(cc, "CALL_STATE_ALERTING", 3)
    callStateIncoming = intConstant(cc, "CALL_STATE_INCOMING", 4)
    callStateWaiting = intConstant(cc, "CALL_STATE_WAITING", 5)
    callStateHeldByResponseAndHold = intConstant(cc, "CALL_STATE_HELD_BY_RESPONSE_AND_HOLD", 6)
    callStateTerminated = intConstant(cc, "CALL_STATE_TERMINATED", 7)
    callStateIdle = -1
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
  fun dial(client: Any?, device: BluetoothDevice?, number: String): Boolean =
    if (device == null) false else bool(mDial, client, device, number)

  fun redial(client: Any?, device: BluetoothDevice?): Boolean =
    if (device == null) false else bool(mRedial, client, device)

  fun accept(client: Any?, device: BluetoothDevice?): Boolean =
    if (device == null) false else bool(mAccept, client, device, CALL_ACCEPT_NONE)

  fun reject(client: Any?, device: BluetoothDevice?): Boolean =
    if (device == null) false else bool(mReject, client, device)

  fun hangup(client: Any?, device: BluetoothDevice?): Boolean {
    if (device == null) return false
    for (call in currentCalls(client, device)) {
      if (call == null) continue
      val index = int(mCallId, call, -1)
      if (index >= 0) return bool(mTerminate, client, device, index)
    }
    return false
  }

  fun currentCalls(client: Any?, device: BluetoothDevice?): List<*> =
    if (device == null) emptyList<Any>() else list(mCurrentCalls, client, device)

  fun registerCallback(client: Any?, callback: Any?): Boolean = bool(mRegisterCallback, client, callback)
  fun unregisterCallback(client: Any?, callback: Any?): Boolean = bool(mUnregisterCallback, client, callback)

  fun callState(call: Any): Int = int(mCallState, call, callStateIdle)
  fun callNumber(call: Any): String? = string(mCallNumber, call)?.takeIf { it.isNotBlank() }
  fun callDirection(call: Any): Int =
    if (bool(mCallOutgoing, call, false)) callDirectionOutgoing else callDirectionIncoming

  private fun method(c: Class<*>?, name: String, vararg p: Class<*>): Method? =
    if (c == null) null
    else try { c.getMethod(name, *p) } catch (e: Throwable) { Log.w(TAG, "missing method $name"); null }

  private fun intConstant(c: Class<*>?, name: String, fallback: Int): Int =
    try { c?.getField(name)?.getInt(null) ?: fallback } catch (e: Throwable) { fallback }

  private fun bool(m: Method?, recv: Any?, vararg args: Any?): Boolean =
    try { (m?.invoke(recv, *args) as? Boolean) ?: false }
    catch (e: SecurityException) { markPrivilegedBlocked(); false }
    catch (e: Throwable) { false }

  private fun int(m: Method?, recv: Any?, def: Int, vararg args: Any?): Int =
    try { (m?.invoke(recv, *args) as? Int) ?: def }
    catch (e: SecurityException) { markPrivilegedBlocked(); def }
    catch (e: Throwable) { def }

  private fun string(m: Method?, recv: Any?, vararg args: Any?): String? =
    try { m?.invoke(recv, *args) as? String }
    catch (e: SecurityException) { markPrivilegedBlocked(); null }
    catch (e: Throwable) { null }

  private fun list(m: Method?, recv: Any?, vararg args: Any?): List<*> =
    try { m?.invoke(recv, *args) as? List<*> ?: emptyList<Any>() }
    catch (e: SecurityException) { markPrivilegedBlocked(); emptyList<Any>() }
    catch (e: Throwable) { emptyList<Any>() }

  // ------------------------------------------------------ system profile priorities

  /**
   * Sets the hidden BluetoothProfile.setPriority for one profile/device via
   * reflection. Used to stop the OS from auto-connecting its own hands-free /
   * audio links to the kosher phone: the phone (AG) accepts a single HFP link,
   * and when the system's profile connects at the same time as our raw RFCOMM
   * link, the AG drops one of them ("connects then immediately disconnects").
   * PRIORITY_OFF (0) makes the stack neither initiate nor accept the profile
   * for that device, while a direct RFCOMM socket is unaffected.
   *
   * Best-effort: newer Android versions guard setPriority behind
   * BLUETOOTH_PRIVILEGED; failures are silent and harmless.
   */
  fun setProfilePriority(context: Context, device: BluetoothDevice, profileId: Int, priority: Int): Boolean {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
      ?: return false
    val latch = CountDownLatch(1)
    val callbackLock = Any()
    var timedOut = false
    var proxy: BluetoothProfile? = null
    val listener = object : BluetoothProfile.ServiceListener {
      override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
        if (profile != profileId) return
        synchronized(callbackLock) {
          if (timedOut) {
            runCatching { adapter.closeProfileProxy(profileId, p) }
          } else {
            proxy = p
            latch.countDown()
          }
        }
      }
      override fun onServiceDisconnected(profile: Int) = Unit
    }
    val started = runCatching { adapter.getProfileProxy(context, listener, profileId) }.getOrDefault(false)
    if (!started) return false
    if (!latch.await(PROFILE_PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      val lateProxy = synchronized(callbackLock) {
        timedOut = true
        proxy
      }
      if (lateProxy != null) runCatching { adapter.closeProfileProxy(profileId, lateProxy) }
      Log.w(TAG, "setProfilePriority: timeout waiting for profile $profileId")
      return false
    }
    val p = proxy ?: return false
    // The proxy must remain open while setPriority is invoked. The previous
    // order closed it first, so many Android Bluetooth stacks silently ignored
    // the priority change and immediately reconnected their own profile,
    // competing with the raw RFCOMM socket.
    return try {
      // On Android 12+ setPriority(BluetoothDevice, int) was replaced by
      // setConnectionPolicy(BluetoothDevice, int) (CONNECTION_POLICY_FORBIDDEN
      // = 0), and the old method is blocked/absent at targetSdk 36. Try the
      // new method first and fall back to setPriority, logging which one
      // actually worked.
      val policy = runCatching {
        val m = p.javaClass.getMethod(
          "setConnectionPolicy", BluetoothDevice::class.java,
          Int::class.javaPrimitiveType,
        )
        (m.invoke(p, device, priority) as? Boolean) ?: false
      }.getOrElse { e ->
        Log.w(TAG, "setConnectionPolicy($profileId) unavailable: ${e.message}")
        false
      }
      if (policy) return@try true
      val m = p.javaClass.getMethod(
        "setPriority", BluetoothDevice::class.java,
        Int::class.javaPrimitiveType,
      )
      (m.invoke(p, device, priority) as? Boolean) ?: false
    } catch (e: Throwable) {
      Log.w(TAG, "setProfilePriority($profileId, $priority) failed: ${e.message}")
      false
    } finally {
      runCatching { adapter.closeProfileProxy(profileId, p) }
    }
  }

  /**
   * Explicitly disconnects a system profile from one device via reflection.
   * Setting priority OFF prevents future reconnections, but an in-flight
   * auto-connect (which fires at bond time) may race past the priority change.
   * Calling disconnect kills any existing or pending connection immediately.
   */
  fun forceDisconnectProfile(context: Context, device: BluetoothDevice, profileId: Int): Boolean {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
      ?: return false
    val latch = CountDownLatch(1)
    val callbackLock = Any()
    var timedOut = false
    var proxy: BluetoothProfile? = null
    val listener = object : BluetoothProfile.ServiceListener {
      override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
        if (profile != profileId) return
        synchronized(callbackLock) {
          if (timedOut) {
            runCatching { adapter.closeProfileProxy(profileId, p) }
          } else {
            proxy = p
            latch.countDown()
          }
        }
      }
      override fun onServiceDisconnected(profile: Int) = Unit
    }
    val started = runCatching { adapter.getProfileProxy(context, listener, profileId) }.getOrDefault(false)
    if (!started) {
      Log.w(TAG, "forceDisconnectProfile: could not request profile $profileId")
      return false
    }
    if (!latch.await(PROFILE_PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      val lateProxy = synchronized(callbackLock) {
        timedOut = true
        proxy
      }
      if (lateProxy != null) runCatching { adapter.closeProfileProxy(profileId, lateProxy) }
      Log.w(TAG, "forceDisconnectProfile: timeout waiting for profile $profileId")
      return false
    }
    val p = proxy ?: return false
    try {
      val m = p.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
      m.invoke(p, device)

      // The reflection call only means that the request was accepted. Verify
      // the profile proxy reports DISCONNECTED before allowing RFCOMM to open;
      // otherwise the system profile can still be tearing down its ACL link.
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (System.nanoTime() < deadline) {
        val state = runCatching { p.getConnectionState(device) }.getOrNull()
        if (state == BluetoothProfile.STATE_DISCONNECTED) return true
        if (state == null) return false
        Thread.sleep(100)
      }
      return runCatching {
        p.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED
      }.getOrDefault(false)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return false
    } catch (e: Throwable) {
      Log.w(TAG, "forceDisconnectProfile($profileId) failed: ${e.message}")
      return false
    } finally {
      runCatching { adapter.closeProfileProxy(profileId, p) }
    }
  }
}
