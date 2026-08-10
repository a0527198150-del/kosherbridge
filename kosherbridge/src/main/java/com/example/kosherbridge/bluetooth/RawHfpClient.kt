package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Minimal HFP Hands-Free (HF) client implemented directly over RFCOMM with AT
 * commands - no hidden API, no privileged permission. This is the same protocol
 * a car kit speaks to a phone (the Audio Gateway): it lets us see incoming
 * calls, dial, answer, reject and hang up through the kosher phone's SIM.
 *
 * Why this exists: on many players the system's HFP client profile is disabled
 * or blocked, so BluetoothHeadsetClient is unusable. Speaking HFP ourselves over
 * a plain RFCOMM socket needs only BLUETOOTH_CONNECT and works on every player
 * whose Bluetooth stack supports RFCOMM (practically all of them).
 *
 * Limitations: call CONTROL is reliable everywhere. Call AUDIO (SCO) is
 * negotiated by the Bluetooth stack, which does not know about a raw RFCOMM
 * link, so on stock Android the call voice usually stays on the phone itself.
 * Some cooperative stacks may route it - hence this is a best effort.
 */
class RawHfpClient(private val scope: CoroutineScope) {

  private val tag = "RawHfp"

  // HFP Audio Gateway UUID (HandsfreeAudioGateway = 0x111F). The kosher phone
  // plays the Audio Gateway (AG) role and publishes THIS service record, so
  // createRfcommSocketToServiceRecord() finds it in the phone's SDP.
  private val hfpAgUuid: UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb")

  // Handsfree UUID (0x111E) - some stacks also publish the AG service under
  // this (nominally HF-side) record for compatibility, so it is worth probing.
  private val hfUuid: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

  // HSP (legacy "headset") Audio Gateway UUID (HeadsetAudioGateway = 0x1108).
  // Some phones only recognize the player as a headset ("אוזנייה") through
  // this older profile - it still carries call audio (SCO).
  private val hspAgUuid: UUID = UUID.fromString("00001108-0000-1000-8000-00805f9b34fb")

  // Gateways tried in order. The order adapts at runtime: when a link dies a
  // few seconds after connecting, that gateway is moved to the back so the
  // next attempt opens a different one (some phones accept only one of them).
  private val gatewayOrder = mutableListOf<Pair<UUID, String>>(
    hfpAgUuid to "HFP-AG",
    hfUuid to "HFP-HF",
    hspAgUuid to "HSP",
  )

  @Volatile private var lastGateway: String? = null
  @Volatile private var connectedAt = 0L

  // Drop statistics for the diagnostics report: how often and how fast the
  // link keeps dying. Lets testers tell us exactly what the player does.
  val dropInfo = MutableStateFlow<String?>(null)
  private var dropCount = 0
  private var quickDropCount = 0
  @Volatile private var lastDropMs = 0L

  // HF features advertised in AT+BRSF: CLI, enhanced call status, enhanced call control
  private val hfFeatures = 0x0004 or 0x0020 or 0x0040

  @Volatile private var socket: BluetoothSocket? = null
  @Volatile private var input: BufferedReader? = null
  @Volatile private var output: OutputStream? = null
  private var readJob: Job? = null
  private var pollJob: Job? = null
  private val writeLock = Any()

  // Auto-reconnect: kosher phones (and cheap stacks) frequently drop a fresh
  // link a few seconds after it comes up. Instead of giving up, keep
  // re-opening the gateway with a short backoff until the user disconnects on
  // purpose. connect() arms it, disconnect() disarms it.
  @Volatile private var targetDevice: BluetoothDevice? = null
  @Volatile private var reconnectEnabled = false
  private var reconnectAttempts = 0

  val isConnected = MutableStateFlow(false)
  val call = MutableStateFlow<CallInfo?>(null)
  val lastError = MutableStateFlow<String?>(null)

  // ------------------------------------------------------------- indicator state

  private val indicatorNames = mutableMapOf<Int, String>() // index -> name (from AT+CIND=?)
  private val indicatorValues = mutableMapOf<Int, Int>()   // index -> value (from +CIEV / AT+CIND)
  private val clccCalls = mutableListOf<CallInfo>()
  @Volatile private var clipNumber: String? = null
  @Volatile private var lastDirection = CallDirection.INCOMING

  // ----------------------------------------------------------------- connect

  fun connect(target: BluetoothDevice) {
    targetDevice = target
    reconnectEnabled = true
    reconnectAttempts = 0
    dropCount = 0
    quickDropCount = 0
    lastDropMs = 0L
    dropInfo.value = null
    if (isConnected.value) return
    readJob?.cancel()
    readJob = scope.launch(Dispatchers.IO) { runConnection() }
  }

  private suspend fun runConnection() {
    val target = targetDevice ?: return
    while (reconnectEnabled) {
      lastError.value = null
      val sock = openSocket(target)
      if (sock == null) {
        Log.w(tag, "connect failed on all gateways - retrying")
        lastError.value = "החיבור הישיר נכשל - מנסה שוב..."
        if (!waitBeforeRetry()) return
        continue
      }
      socket = sock
      input = BufferedReader(InputStreamReader(sock.inputStream, Charsets.ISO_8859_1))
      output = sock.outputStream

      // Some AGs accept the socket but never answer AT commands. Close the
      // socket after 12s so a silent handshake can't hang the reconnect loop.
      val watchdog = scope.launch(Dispatchers.IO) {
        delay(12_000)
        runCatching { sock.close() }
      }
      val handshakeOk = handshake()
      watchdog.cancel()
      if (!handshakeOk) {
        // The AG rejected our AT negotiation on this gateway - try another one.
        rotateGateway()
        lastError.value = "הטלפון לא השלים את הפרוטוקול של הדיבורית - מנסה שוב..."
        teardown()
        if (!waitBeforeRetry()) return
        continue
      }
      reconnectAttempts = 0
      connectedAt = System.currentTimeMillis()
      isConnected.value = true
      startPolling()
      readLoop() // blocks until the link dies (teardown() happens inside)
      val lastedMs = System.currentTimeMillis() - connectedAt
      if (reconnectEnabled) {
        // The user did not disconnect - the phone dropped the link on its own.
        dropCount++
        lastDropMs = lastedMs
        if (lastedMs < 4000) quickDropCount++ else quickDropCount = 0
        Log.w(tag, "link closed after ${lastedMs}ms (gateway: $lastGateway) [drop #$dropCount]")
        dropInfo.value = buildDropInfo()
        // A link that dies within a few seconds usually means this gateway is
        // being rejected - move it to the back and try a different one.
        if (lastedMs < 4000) rotateGateway()
        // Repeated fast drops: a stale bond or a competing system link is the
        // usual culprit - tell the user to re-pair (the app now disables the
        // system profiles the moment the bond completes).
        if (quickDropCount >= 2 && reconnectAttempts <= 3) {
          lastError.value =
            "הקישור נופל שוב ושוב. מחק את זיווג הטלפון וזווג אותו מחדש - האפליקציה תכבה עכשיו את החיבורים המערכתיים שמתחרים על הקישור."
        }
      } else {
        Log.i(tag, "link closed by user")
      }
      // The phone dropped the link - reconnect unless the user disconnected
      // on purpose. This is the fix for "connects for a few seconds then
      // immediately disconnects" on cheap stacks.
      if (!waitBeforeRetry()) return
    }
  }

  /** Backoff between reconnect attempts: 3s, 6s, 9s, then capped at 10s. */
  private suspend fun waitBeforeRetry(): Boolean {
    reconnectAttempts++
    delay(minOf(3000L * reconnectAttempts, 10_000L))
    return reconnectEnabled
  }

  /** Tries each gateway in order; the order adapts when one keeps dropping. */
  private fun openSocket(target: BluetoothDevice): BluetoothSocket? {
    val order = gatewayOrder.toList()
    for ((uuid, label) in order) {
      val sock = openGateway(target, uuid)
      if (sock != null) {
        lastGateway = label
        Log.i(tag, "connected via $label gateway")
        return sock
      }
    }
    return null
  }

  /**
   * Opens one gateway. Tries the secure socket first; some AG stacks (feature
   * phones) fail the encryption re-negotiation and only accept an insecure
   * link, so fall back to the insecure variant.
   */
  private fun openGateway(target: BluetoothDevice, uuid: UUID): BluetoothSocket? {
    val secure = runCatching {
      target.createRfcommSocketToServiceRecord(uuid).also { it.connect() }
    }.getOrNull()
    if (secure != null) return secure
    Log.w(tag, "secure connect failed for $uuid")
    val insecure = runCatching {
      target.createInsecureRfcommSocketToServiceRecord(uuid).also { it.connect() }
    }.getOrNull()
    if (insecure != null) {
      Log.i(tag, "insecure socket accepted for $uuid")
    } else {
      Log.w(tag, "insecure connect failed for $uuid")
    }
    return insecure
  }

  /** Moves the gateway that just failed to the back of the order. */
  private fun rotateGateway() {
    val g = lastGateway ?: return
    val idx = gatewayOrder.indexOfFirst { it.second == g }
    if (idx >= 0 && gatewayOrder.size > 1) {
      gatewayOrder.add(gatewayOrder.removeAt(idx))
    }
  }

  /** HFP negotiation: BRSF, CIND, CMER, CLIP, CCWA - the handshake every AG accepts. */
  private suspend fun handshake(): Boolean {
    // The legacy headset gateway speaks HSP - there is no BRSF/CIND/CMER
    // negotiation. Accept the link as-is and let the read loop watch it
    // (HSP carries call audio but has no call indicators; AT+CLCC polling
    // will simply get ERROR on a pure-HSP phone). Without this, an HSP-only
    // phone drops us the moment we send AT+BRSF - "connects then disconnects".
    if (lastGateway == "HSP") return true
    // Claim the common feature set; some AGs reject feature bits they don't
    // understand, so retry with the minimal set before giving up.
    if (!sendAndWait("AT+BRSF=$hfFeatures") && !sendAndWait("AT+BRSF=0")) return false
    sendCommand("AT+CIND=?")
    if (!readUntil { it.startsWith("OK") || it.startsWith("ERROR") }) return false
    if (!sendAndWait("AT+CIND")) {
      // Some AGs only support AT+CIND=? (not the value query) - continue
      // without current indicator values; +CIEV events still arrive.
      Log.w(tag, "AT+CIND rejected - continuing without indicator values")
    }
    if (!sendAndWait("AT+CMER=3,0,0,1")) return false
    sendCommand("AT+CLIP=1")
    readUntil { it.startsWith("OK") || it.startsWith("ERROR") }
    sendCommand("AT+CCWA=1")
    readUntil { it.startsWith("OK") || it.startsWith("ERROR") }
    return true
  }

  private fun readLoop() {
    val r = input
    if (r == null) {
      teardown()
      return
    }
    while (true) {
      val line = try { r.readLine() } catch (e: Throwable) { null } ?: break
      handleLine(line.trim())
    }
    Log.w(tag, "link closed")
    teardown()
  }

  private fun startPolling() {
    pollJob?.cancel()
    pollJob = scope.launch(Dispatchers.IO) {
      while (isActive && isConnected.value) {
        sendCommand("AT+CLCC")
        delay(800)
      }
    }
  }

  // ----------------------------------------------------------------- actions

  fun dial(number: String): Boolean {
    if (number.isBlank()) return false
    sendCommand("ATD$number;")
    lastDirection = CallDirection.OUTGOING
    return true
  }

  fun redial(): Boolean {
    sendCommand("AT+BLDN")
    lastDirection = CallDirection.OUTGOING
    return true
  }

  fun answer(): Boolean {
    sendCommand("ATA")
    return true
  }

  fun reject(): Boolean {
    sendCommand("AT+CHUP")
    return true
  }

  fun hangup(): Boolean {
    sendCommand("AT+CHUP")
    return true
  }

  fun disconnect() {
    reconnectEnabled = false
    readJob?.cancel()
    teardown()
  }

  // ------------------------------------------------------------------ parsing

  private fun handleLine(line: String) {
    when {
      line.startsWith("+CIEV:") -> handleCiev(line)
      line.startsWith("+CLIP:") -> handleClip(line)
      line.startsWith("+CLCC:") -> handleClcc(line)
      line.startsWith("+CIND:") -> handleCind(line)
      line == "OK" || line == "ERROR" -> finishClccBatch()
      // everything else is either an ack or an unsolicited event we don't need
      else -> Unit
    }
  }

  private fun handleCiev(line: String) {
    val parts = line.substringAfter("+CIEV:").trim().split(",")
    val idx = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return
    val value = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return
    indicatorValues[idx] = value
    emitFromIndicators()
  }

  private fun emitFromIndicators() {
    val callIdx = nameIndex("call")
    val setupIdx = nameIndex("callsetup")
    val heldIdx = nameIndex("callheld")
    val callInd = callIdx?.let { indicatorValues[it] }
    val setup = setupIdx?.let { indicatorValues[it] }
    val held = heldIdx?.let { indicatorValues[it] }

    val state: CallState = when {
      callInd == 1 && held == 1 -> CallState.HELD
      callInd == 1 -> CallState.ACTIVE
      setup == 1 -> CallState.INCOMING
      setup == 2 -> CallState.DIALING
      setup == 3 -> CallState.ALERTING
      setup == 4 -> CallState.WAITING
      callInd == 0 && setup == 0 -> CallState.IDLE
      else -> return
    }
    if (state == CallState.INCOMING || state == CallState.WAITING) {
      lastDirection = CallDirection.INCOMING
    } else if (state == CallState.DIALING || state == CallState.ALERTING) {
      lastDirection = CallDirection.OUTGOING
    }
    val number = when (state) {
      CallState.INCOMING, CallState.WAITING -> clipNumber
      else -> null
    }
    val info = CallInfo(state, number, lastDirection)
    if (call.value != info) call.value = info
    if (state == CallState.IDLE) clipNumber = null
  }

  private fun handleClip(line: String) {
    val num = line.substringAfter("+CLIP:").substringAfter('"').substringBefore('"').trim()
    if (num.isNotEmpty()) clipNumber = num
  }

  private fun handleCind(line: String) {
    val body = line.substringAfter("+CIND:").trim()
    if (body.contains('"')) {
      indicatorNames.clear()
      Regex("\"([a-zA-Z]+)\"").findAll(body).map { it.groupValues[1] }.forEachIndexed { i, n ->
        indicatorNames[i + 1] = n
      }
    } else {
      body.split(",").mapNotNull { it.trim().toIntOrNull() }.forEachIndexed { i, v ->
        indicatorValues[i + 1] = v
      }
    }
  }

  private fun handleClcc(line: String) {
    val fields = splitQuoted(line.substringAfter("+CLCC:").trim())
    val dir = fields.getOrNull(1)?.trim()?.toIntOrNull()
    val status = fields.getOrNull(2)?.trim()?.toIntOrNull()
    val number = fields.firstOrNull { it.startsWith("\"") }?.trim('"')?.takeIf { it.isNotBlank() }
    val state: CallState = when (status) {
      0 -> CallState.ACTIVE
      1 -> CallState.HELD
      2 -> CallState.DIALING
      3 -> CallState.ALERTING
      4 -> CallState.INCOMING
      5 -> CallState.WAITING
      else -> null
    } ?: return
    val direction = if (dir == 0) CallDirection.OUTGOING else CallDirection.INCOMING
    clccCalls += CallInfo(state, number, direction)
  }

  private fun finishClccBatch() {
    if (clccCalls.isEmpty()) return
    val info = clccCalls.minByOrNull { rank(it.state) } ?: return
    clccCalls.clear()
    lastDirection = info.direction
    if (call.value != info) call.value = info
    if (info.state == CallState.IDLE) clipNumber = null
  }

  private fun rank(s: CallState): Int = when (s) {
    CallState.ACTIVE -> 0
    CallState.INCOMING -> 1
    CallState.WAITING -> 2
    CallState.ALERTING -> 3
    CallState.DIALING -> 4
    CallState.HELD -> 5
    else -> 9
  }

  private fun nameIndex(name: String): Int? =
    indicatorNames.entries.firstOrNull { it.value == name }?.key
      ?: when (name) { // fallback to the HFP standard order
        "call" -> 1
        "callsetup" -> 2
        "callheld" -> 3
        else -> null
      }

  private fun splitQuoted(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    for (c in s) {
      when {
        c == '"' -> {
          inQuotes = !inQuotes
          cur.append(c)
        }
        c == ',' && !inQuotes -> {
          out += cur.toString()
          cur.clear()
        }
        else -> cur.append(c)
      }
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
  }

  // ------------------------------------------------------------------- helpers

  private fun sendCommand(cmd: String) {
    synchronized(writeLock) {
      runCatching {
        output?.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        output?.flush()
      }
    }
  }

  private suspend fun sendAndWait(cmd: String): Boolean {
    sendCommand(cmd)
    return readUntil { it.startsWith("OK") || it.startsWith("ERROR") }
  }

  private suspend fun readUntil(pred: (String) -> Boolean): Boolean {
    val r = input ?: return false
    while (true) {
      val line = r.readLine() ?: return false
      val l = line.trim()
      if (l.isEmpty()) continue
      handleLine(l)
      if (pred(l)) return l.startsWith("OK")
    }
  }

  private fun buildDropInfo(): String {
    if (dropCount == 0) return "אין ניתוקים"
    val last = if (lastDropMs > 0) "האחרון אחרי ${String.format("%.1f", lastDropMs / 1000.0)} שניות" else ""
    return if (dropCount == 1) "ניתוק אחד ($last)" else "$dropCount ניתוקים ($last)"
  }

  private fun teardown() {
    pollJob?.cancel()
    pollJob = null
    runCatching { socket?.close() }
    socket = null
    input = null
    output = null
    isConnected.value = false
    call.value = null
  }
}
