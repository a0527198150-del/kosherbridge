package com.example.kosherbridge

import com.example.kosherbridge.bluetooth.BridgeUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** App-wide single source of truth for the bridge status, updated by [BridgeService]. */
object BridgeHub {
  val state = MutableStateFlow(BridgeUiState())

  @Volatile
  var service: BridgeService? = null

  fun update(transform: (BridgeUiState) -> BridgeUiState) {
    state.update(transform)
  }
}
