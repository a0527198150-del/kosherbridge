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

  // HFP Audio Gateway UUID (HandsfreeAudioGateway = 0x111E)
  private val hfpAgUuid: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

  // HF features advertised in AT+BRSF: CLI, enhanced call status, enhanced call control
  private val hfFeatures = 0x0004 or 0x0020 or 0x0040

  @Volatile private var socket: BluetoothSocket? = null
  @Volatile private var input: BufferedReader? = null
  @Volatile private var output: OutputStream? = null
  private var readJob: Job? = null
  private var pollJob: Job? = null
  private val writeLock = Any()

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
    if (isConnected.value) return
    readJob?.cancel()
    readJob = scope.launch(Dispatchers.IO) { runConnection(target) }
  }

  private suspend fun runConnection(target: BluetoothDevice) {
    lastError.value = null
    val sock = runCatching {
      target.createRfcommSocketToServiceRecord(hfpAgUuid).also { it.connect() }
    }.getOrElse {
      Log.w(tag, "connect failed: ${it.message}")
      lastError.value = "החיבור הישיר נכשל - בדוק שהכשר מזווג והבלוטוס דלוק"
      isConnected.value = false
      return
    }
    socket = sock
    input = BufferedReader(InputStreamReader(sock.inputStream, Charsets.ISO_8859_1))
    output = sock.outputStream

    if (!handshake()) {
      lastError.value = "הטלפון לא השלים את הפרוטוקול של הדיבורית"
      close()
      return
    }
    isConnected.value = true
    startPolling()
    readLoop()
  }

  /** HFP negotiation: BRSF, CIND, CMER, CLIP, CCWA - the handshake every AG accepts. */
  private suspend fun handshake(): Boolean {
    if (!sendAndWait("AT+BRSF=$hfFeatures")) return false
    sendCommand("AT+CIND=?")
    if (!readUntil { it.startsWith("OK") || it.startsWith("ERROR") }) return false
    if (!sendAndWait("AT+CIND")) return false
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
      close()
      return
    }
    while (true) {
      val line = try { r.readLine() } catch (e: Throwable) { null } ?: break
      handleLine(line.trim())
    }
    Log.w(tag, "link closed")
    close()
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
    readJob?.cancel()
    close()
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

  private fun close() {
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
