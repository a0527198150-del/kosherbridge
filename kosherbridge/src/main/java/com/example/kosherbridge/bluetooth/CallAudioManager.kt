package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
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
   */
  fun ensureCallAudio(device: BluetoothDevice?, boostVolume: Boolean) {
    inCall = true
    registerReceivers()

    runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
      .onFailure { Log.w(tag, "setMode failed: ${it.message}") }

    requestFocus()

    routeToDevice(device)

    if (boostVolume) boostVolume()
  }

  /**
   * Re-asserts the route. Called by the watchdog when a call is active but the
   * SCO link is not up, and after SCO drops / audio gets stolen.
   */
  fun retryAudio(device: BluetoothDevice?, boostVolume: Boolean) {
    if (!inCall) return
    requestFocus()
    routeToDevice(device)
    if (boostVolume) boostVolume()
  }

  private fun routeToDevice(device: BluetoothDevice?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Modern path: routes playback + mic to the HFP device and triggers the
      // SCO setup at the stack level. The single most effective technique on
      // API 31+ players.
      val target = device ?: am.communicationDevice
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
    runCatching {
      val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
      am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
    }
    runCatching {
      val sMax = am.getStreamMaxVolume(AudioManager.STREAM_BLUETOOTH_SCO)
      am.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO, sMax, 0)
    }
  }

  /** Call ended: undo everything so the player behaves normally again. */
  fun releaseCallAudio() {
    if (!inCall && !receiversRegistered) return
    inCall = false
    runCatching { am.stopBluetoothSco() }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      runCatching { am.clearCommunicationDevice() }
    }
    runCatching { am.isBluetoothScoOn = false }
    abandonFocus()
    runCatching { am.mode = AudioManager.MODE_NORMAL }
    scoConnected.value = false
    routeLabel.value = null
    unregisterReceivers()
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
