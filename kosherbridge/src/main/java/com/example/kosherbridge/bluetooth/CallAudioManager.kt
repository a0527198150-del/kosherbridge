package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.media.AudioDeviceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The audio half of the bridge: techniques that force the conversation - both
 * the caller's voice AND this device's microphone - through the Bluetooth SCO
 * link, even on players that were never built for calls and have no
 * hands-free/call UI of their own.
 *
 * The HFP profile connection alone (RFCOMM/AT) only carries call *control*
 * (dial, answer, rings). The actual voice travels over a separate SCO link,
 * and on a stock player the system will not route any audio there unless the
 * app actively claims it. That is exactly what this class does:
 *
 *  1. MODE_IN_COMMUNICATION - the system only opens the voice pipeline
 *     (mic included) and routes it to a Bluetooth SCO device in this mode.
 *  2. Audio focus on STREAM_VOICE_CALL - without it the system can duck or
 *     block our streams while another app plays audio.
 *  3. Routing: API 31+ setCommunicationDevice(device) - routes BOTH playback
 *     and capture (the player's microphone) to the HFP device and asks the
 *     stack to bring the SCO link up. On older APIs: startBluetoothSco() +
 *     setBluetoothScoOn(true), the classic car-kit technique.
 *  4. Volume: STREAM_VOICE_CALL / STREAM_BLUETOOTH_SCO pushed to maximum so
 *     the caller is clearly audible on the player's speaker.
 *  5. Watchdog hooks: when SCO drops mid-call (or another app steals the
 *     audio stream), HfpClientManager is notified and re-asserts the route.
 *
 * All of these run in the normal app process - no root, no Shizuku, no hidden
 * API. The SCO *link* itself is negotiated by the Bluetooth stack, so the
 * final say belongs to the stack; these techniques are what make the stack
 * actually do it on players that would otherwise keep the voice on the phone.
 */
class CallAudioManager(private val context: Context) {

  private val tag = "CallAudio"
  private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  @Volatile private var inCall = false
  @Volatile private var focusGranted = false
  @Volatile private var receiversRegistered = false
  @Volatile private var micWasMuted = false // mic muted at OS level when the call started

  /** True while the Bluetooth stack reports a live SCO (voice) link. */
  val scoConnected = MutableStateFlow(false)

  /** Short human-readable description of the current routing attempt. */
  val routeLabel = MutableStateFlow<String?>(null)

  /** Invoked by HfpClientManager when the SCO link dropped mid-call. */
  var onScoDropped: (() -> Unit)? = null

  /** Invoked when another app stole the audio stream (ACTION_AUDIO_BECOMING_NOISY). */
  var onAudioStolen: (() -> Unit)? = null

  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    if (change == AudioManager.AUDIOFOCUS_LOSS ||
      change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    ) {
      focusGranted = false
    }
  }

  private val audioReceiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
      when (intent?.action) {
        AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
          val state = intent.getIntExtra(
            AudioManager.EXTRA_SCO_AUDIO_STATE,
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
          )
          scoConnected.value = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
          // If the forced (virtual) SCO dropped, forget it so the next
          // connectAudio() re-forces it instead of no-op'ing.
          if (state != AudioManager.SCO_AUDIO_STATE_CONNECTED) virtualScoOn = false
          Log.i(tag, "SCO state -> $state (inCall=$inCall)")
          if (inCall && state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
            onScoDropped?.invoke()
          }
        }
        AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
          Log.i(tag, "audio becoming noisy (inCall=$inCall) - reclaiming route")
          if (inCall) onAudioStolen?.invoke()
        }
      }
    }
  }

  /**
   * Called when a call becomes active (or the user enables audio). Sets up
   * everything needed to hear the caller and send the microphone through the
   * HFP device.
   *
   * @param forceVirtualSco when true (raw RFCOMM path), also ask the Bluetooth
   *   stack to open the SCO voice channel to the device even though there is no
   *   profile-level call - the stack doesn't know about the raw link, so this
   *   is the only way to get call audio flowing.
   */
  fun ensureCallAudio(device: BluetoothDevice?, boostVolume: Boolean, forceVirtualSco: Boolean = false) {
    inCall = true
    registerReceivers()

    runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
      .onFailure { Log.w(tag, "setMode failed: ${it.message}") }

    // If the microphone was muted at the OS level (by the user, another app,
    // or a system state), un-mute it for the call so the far side can hear
    // us - and remember to restore it when the call ends.
    runCatching {
      micWasMuted = am.isMicrophoneMute
      if (micWasMuted) {
        am.isMicrophoneMute = false
        Log.i(tag, "microphone was muted - unmuted for the call")
      }
    }

    requestFocus()

    routeToDevice(device)

    if (forceVirtualSco) startVirtualSco(device)

    if (boostVolume) boostVolume()
  }

  /**
   * Re-asserts the route. Called by the watchdog when a call is active but the
   * SCO link is not up, and after SCO drops / audio gets stolen.
   */
  fun retryAudio(device: BluetoothDevice?, boostVolume: Boolean, forceVirtualSco: Boolean = false) {
    if (!inCall) return
    requestFocus()
    routeToDevice(device)
    if (forceVirtualSco) startVirtualSco(device)
    if (boostVolume) boostVolume()
  }

  private fun routeToDevice(device: BluetoothDevice?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Modern path: routes playback + mic to the HFP device and triggers the
      // SCO setup at the stack level. The single most effective technique on
      // API 31+ players.
      val target = findScoDevice(device)
      if (target == null) {
        routeLabel.value = "אין התקן דיבורית לניתוב"
        startLegacySco()
        return
      }
      val ok = runCatching { am.setCommunicationDevice(target) }.getOrDefault(false)
      routeLabel.value = if (ok) "מנותב להתקן דיבורית" else "הניתוב נכשל - מנסה SCO ישן"
      if (!ok) startLegacySco()
    } else {
      // Legacy path (API 24-30): the classic SCO technique used by car-kit
      // and VoIP apps. Deprecated on 31+ in favor of setCommunicationDevice.
      startLegacySco()
    }
  }

  /**
   * Finds the hands-free SCO device among the available communication devices,
   * preferring the exact paired HFP device when its address is known.
   */
  private fun findScoDevice(device: BluetoothDevice?): AudioDeviceInfo? {
    val available = runCatching { am.getAvailableCommunicationDevices() }.getOrDefault(emptyList())
    if (device != null) {
      available.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
          runCatching { it.address == device.address }.getOrDefault(false)
      }?.let { return it }
    }
    return available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
  }

  private fun startLegacySco() {
    runCatching { am.startBluetoothSco() }
    runCatching { am.isBluetoothScoOn = true }
  }

  private fun requestFocus() {
    if (focusGranted) return
    val result = runCatching {
      am.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
    }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
    focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  private fun boostVolume() {
    // STREAM_VOICE_CALL maps to the SCO/communication path once the
    // communication device is set and the mode is MODE_IN_COMMUNICATION.
    runCatching {
      val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
      am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
    }
  }

  /** Call ended: undo everything so the player behaves normally again. */
  fun releaseCallAudio() {
    if (!inCall && !receiversRegistered) return
    inCall = false
    stopVirtualSco()
    runCatching { am.stopBluetoothSco() }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      runCatching { am.clearCommunicationDevice() }
    }
    runCatching { am.isBluetoothScoOn = false }
    if (micWasMuted) {
      runCatching { am.isMicrophoneMute = true }
      micWasMuted = false
    }
    abandonFocus()
    runCatching { am.mode = AudioManager.MODE_NORMAL }
    scoConnected.value = false
    routeLabel.value = null
    unregisterReceivers()
  }

  // ------------------------------------------------------------------ virtual SCO
  // startScoUsingVirtualVoiceCall asks the Bluetooth stack to open the SCO
  // (voice) channel to the phone even without a profile-level call. The raw
  // RFCOMM path has call control but the stack doesn't know about the link, so
  // this is the bridge that gets call audio flowing on players with no HFP
  // client profile. Only used on the raw path; the profile paths negotiate SCO
  // themselves.
  //
  // These methods are hidden/system APIs (not in the public SDK), so they are
  // invoked through reflection - the same approach as HiddenHfp. If a given
  // stack doesn't expose them (or blocks them), the attempt just fails
  // silently and everything else keeps working.

  @Volatile private var headset: BluetoothHeadset? = null
  @Volatile private var virtualScoOn = false
  @Volatile private var virtualScoDevice: BluetoothDevice? = null
  @Volatile private var mStartVirtualCall: Method? = null
  @Volatile private var mStopVirtualCall: Method? = null

  private fun startVirtualSco(device: BluetoothDevice?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (virtualScoOn) return
    val d = device ?: return
    val h = headset
    if (h != null) {
      virtualScoOn = invokeVirtualSco(h, d, start = true)
      if (virtualScoOn) {
        virtualScoDevice = d
        Log.i(tag, "virtual voice call started - SCO forced to the phone")
      } else {
        Log.w(tag, "startScoUsingVirtualVoiceCall failed - stack refuses SCO")
      }
      return
    }
    // Headset proxy not ready yet - fetch it on a background thread (the
    // profile callback can be slow) and try again.
    Thread {
      val hs = acquireHeadsetProxy() ?: return@Thread
      val ok = invokeVirtualSco(hs, d, start = true)
      if (ok) {
        virtualScoOn = true
        virtualScoDevice = d
        Log.i(tag, "virtual voice call started (after proxy)")
      } else {
        Log.w(tag, "startScoUsingVirtualVoiceCall failed - stack refuses SCO")
      }
    }.start()
  }

  /** Reflectively calls start/stopScoUsingVirtualVoiceCall on the headset proxy. */
  private fun invokeVirtualSco(h: BluetoothHeadset, d: BluetoothDevice, start: Boolean): Boolean {
    val cached = if (start) mStartVirtualCall else mStopVirtualCall
    val m = cached ?: runCatching {
      val name = if (start) "startScoUsingVirtualVoiceCall" else "stopScoUsingVirtualVoiceCall"
      h.javaClass.getMethod(name, BluetoothDevice::class.java)
    }.getOrNull() ?: return false
    if (cached == null) {
      if (start) mStartVirtualCall = m else mStopVirtualCall = m
    }
    return runCatching { m.invoke(h, d) as? Boolean ?: false }.getOrDefault(false)
  }

  private fun acquireHeadsetProxy(): BluetoothHeadset? {
    if (headset != null) return headset
    val adapter =
      (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
    val latch = CountDownLatch(1)
    var hs: BluetoothHeadset? = null
    val listener = object : BluetoothProfile.ServiceListener {
      override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        if (profile != BluetoothProfile.HEADSET) return
        hs = proxy as? BluetoothHeadset
        latch.countDown()
      }
      override fun onServiceDisconnected(profile: Int) {
        if (profile == BluetoothProfile.HEADSET) headset = null
      }
    }
    runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }
    runCatching { latch.await(3, TimeUnit.SECONDS) }
    headset = hs
    return hs
  }

  private fun stopVirtualSco() {
    if (!virtualScoOn) return
    virtualScoOn = false
    val d = virtualScoDevice
    virtualScoDevice = null
    if (d != null) {
      val h = headset
      if (h != null) runCatching { invokeVirtualSco(h, d, start = false) }
    }
    runCatching {
      val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
      headset?.let { h -> adapter?.closeProfileProxy(BluetoothProfile.HEADSET, h) }
    }
    headset = null
  }

  private fun abandonFocus() {
    if (!focusGranted) return
    runCatching { am.abandonAudioFocus(focusListener) }
    focusGranted = false
  }

  private fun registerReceivers() {
    if (receiversRegistered) return
    val filter = IntentFilter().apply {
      addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
      addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    }
    runCatching {
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(audioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(audioReceiver, filter)
      }
      receiversRegistered = true
    }
  }

  private fun unregisterReceivers() {
    if (!receiversRegistered) return
    runCatching { context.unregisterReceiver(audioReceiver) }
    receiversRegistered = false
  }

  /**
   * Records ~0.4s from the call microphone source (VOICE_COMMUNICATION - the
   * exact source the conversation uses) and reports the peak level, so the
   * user can verify the player's microphone actually feeds the bridge.
   * Returns a short Hebrew diagnostic string.
   */
  suspend fun checkMicrophone(): String = withContext(Dispatchers.IO) {
    val sampleRate = 16000
    val frames = sampleRate / 4
    val buffer = ShortArray(frames)
    val record = runCatching {
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        buffer.size * 2,
      )
    }.getOrNull()
    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
      record?.release()
      return@withContext "מיקרופון לא זמין במכשיר"
    }
    var result: String
    try {
      record.startRecording()
      Thread.sleep(300)
      val read = record.read(buffer, 0, frames, AudioRecord.READ_BLOCKING)
      val peak = (0 until read).maxOfOrNull { abs(buffer[it].toInt()) } ?: 0
      result = if (peak == 0) {
        "המיקרופון שקט - לא נקלט קול (בדוק שהנגן מזווג והבלוטוס דלוק)"
      } else {
        "המיקרופון תקין - קולט קול (עוצמה $peak)"
      }
    } catch (e: Exception) {
      result = "שגיאה בבדיקה: ${e.message ?: "לא ידוע"}"
    } finally {
      runCatching { record.stop() }
      record.release()
    }
    result
  }
}
