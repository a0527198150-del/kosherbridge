package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothProfile

enum class CallState { IDLE, INCOMING, WAITING, DIALING, ALERTING, ACTIVE, HELD, TERMINATED }

enum class CallDirection { INCOMING, OUTGOING }

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
  val backendLabel: String? = null, // active bridge path, for diagnostics
  val call: CallInfo? = null,
  val lastError: String? = null,
  // ---- capability report (diagnostics tab) ----
  val deviceInfo: String? = null, // manufacturer + model + SDK
  val hiddenApiAvailable: Boolean = false, // BluetoothHeadsetClient class exposed?
  val privilegedBlocked: Boolean = false, // system rejected privileged calls?
  val shizukuAvailable: Boolean = false,
  val shizukuGranted: Boolean = false,
  val scoSupport: String? = null, // does this player expose SCO at all?
  val scoTechnique: String? = null, // last SCO technique the stack was asked for
  val rawDropInfo: String? = null, // raw RFCOMM link drop stats (count + last duration)
  val permissionHint: String? = null, // shown when a required runtime permission is missing
)
