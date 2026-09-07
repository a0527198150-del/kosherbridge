package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothProfile

enum class CallState { IDLE, INCOMING, WAITING, DIALING, ALERTING, ACTIVE, HELD, TERMINATED }

enum class CallDirection { INCOMING, OUTGOING }

/**
 * Where the conversation actually is, as measured by CallAudioManager rather
 * than assumed from the routing attempt.
 *
 * The distinction matters because a bridge that *asks* for the voice and does
 * not get it is worse than one that never asked: the kosher phone has already
 * handed the call to the "hands-free", so the caller is audible on neither
 * device. ON_PHONE is the honest answer - the player is a remote control for
 * this call, and the UI says so.
 */
enum class CallAudioOutcome {
  /** No call, or the call ended. */
  IDLE,

  /** The claim is in flight; the voice link has not been measured yet. */
  ROUTING,

  /** A live SCO link: the caller is heard on the player, the player's
   * microphone is what the caller hears. */
  ON_PLAYER,

  /** No voice link on this player. The kosher phone carries the conversation
   * on its own earpiece and the bridge holds nothing. */
  ON_PHONE,
}

data class CallInfo(
  val state: CallState,
  val number: String?,
  val direction: CallDirection,
)

data class PairedDeviceInfo(val name: String, val address: String)

/** Mirrors the Bluetooth profile connection states (BluetoothProfile.STATE_*). */
data class BridgeUiState(
  val profileReady: Boolean = false,
  val adapterOn: Boolean = false,
  val connectionState: Int = BluetoothProfile.STATE_DISCONNECTED,
  val deviceName: String? = null,
  val deviceAddress: String? = null,
  val audioState: Int = 0, // 0 disconnected, 1 connecting, 2 connected
  val audioRoute: String? = null, // last routing attempt, for diagnostics
  val audioOutcome: CallAudioOutcome = CallAudioOutcome.IDLE, // where the voice actually is
  val audioRouteAllowed: String? = null, // HFP-client audio gate, for diagnostics
  val backendLabel: String? = null, // active bridge path, for diagnostics
  val call: CallInfo? = null,
  val lastError: String? = null,
  // ---- capability report (diagnostics tab) ----
  val deviceInfo: String? = null, // manufacturer + model + SDK
  val hiddenApiAvailable: Boolean = false, // BluetoothHeadsetClient class exposed?
  val privilegedBlocked: Boolean = false, // system rejected privileged calls?
  val shizukuAvailable: Boolean = false,
  val shizukuGranted: Boolean = false,
  val rootAvailable: Boolean = false, // a su binary exists on this device

  val scoSupport: String? = null, // does this player expose SCO at all?
  val scoTechnique: String? = null, // last SCO technique the stack was asked for
  val rawLinkActive: Boolean = false, // a live raw RFCOMM link exists right now (independent of the system profile)
  val rawDropInfo: String? = null, // raw RFCOMM link drop stats (count + last duration)
  val rawConnectionDiagnostics: String? = null, // SDP/channel attempts before connection
  val headsetClientPolicy: String? = null, // HFP-client connection policy, for diagnostics
  val fullScreenAllowed: Boolean? = null, // can the system honour a full-screen call intent?
  val connectionLog: List<String> = emptyList(), // recent local connection log
  val permissionHint: String? = null, // shown when a required runtime permission is missing
)
