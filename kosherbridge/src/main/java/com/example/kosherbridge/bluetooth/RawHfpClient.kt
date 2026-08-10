package com.example.kosherbridge.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

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
class RawHfpClient(
  private val context: Context,
  private val scope: CoroutineScope,
  private val onLog: (String, Boolean) -> Unit = { _, _ -> },
) {

  /** Optional preparation run before every socket attempt (for example,
   * disabling the platform's competing Bluetooth profile). */
  var beforeSocketOpen: (suspend (BluetoothDevice) -> Unit)? = null

  private val tag = "RawHfp"

  // HFP Audio Gateway UUID (HandsfreeAudioGateway = 0x111F). The kosher phone
  // plays the Audio Gateway (AG) role and publishes THIS service record, so
  // createRfcommSocketToServiceRecord() finds it in the phone's SDP.
  private val hfpAgUuid: UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb")

  // Handsfree UUID (0x111E) - some stacks also publish the AG service under
  // this (nominally HF-side) record for compatibility, so it is worth probing.
  private val hfUuid: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

  // HSP (legacy "headset") Audio Gateway UUID (HeadsetAudioGateway = 0x1112).
  // 0x1108 is the Headset-side UUID; using it here asks the phone for the
  // wrong role and can open a non-AG service before the phone closes it.
  private val hspAgUuid: UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

  // Gateways tried in order. The order adapts at runtime: when a link dies a
  // few seconds after connecting, that gateway is moved to the back so the
  // next attempt opens a different one (some phones accept only one of them).
  private val gatewayOrder = mutableListOf<Pair<UUID, String>>(
    hfpAgUuid to "HFP-AG",
    hfUuid to "HFP-HF",
    hspAgUuid to "HSP",
  )

  @Volatile private var lastGateway: String? = null
  @Volatile private var hspMode = false
  @Volatile private var connectedAt = 0L

  // Drop statistics for the diagnostics report: how often and how fast the
  // link keeps dying. Lets testers tell us exactly what the player does.
  val dropInfo = MutableStateFlow<String?>(null)
  private var dropCount = 0
  private var quickDropCount = 0
  @Volatile private var lastDropMs = 0L

  // HF features advertised in AT+BRSF: CLI and enhanced call status.
  // Do not advertise enhanced call control unless the implementation also
  // supports the CHLD procedures; falsely advertising it makes some basic AGs
  // send an incompatible call-hold query during SLC.
  private val hfFeatures = 0x0004 or 0x0020

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
  @Volatile private var attemptInFlight = false
  private var reconnectAttempts = 0

  val isConnected = MutableStateFlow(false)
  val call = MutableStateFlow<CallInfo?>(null)
  val lastError = MutableStateFlow<String?>(null)
  /** Exact SDP/RFCOMM attempts, shown in diagnostics when no link is made. */
  val connectionDiagnostics = MutableStateFlow<String?>(null)
  private var discoveredUuids: Set<UUID> = emptySet()
  private var channelCursor = 1

  // ------------------------------------------------------------- indicator state

  private val indicatorNames = mutableMapOf<Int, String>() // index -> name (from AT+CIND=?)
  private val indicatorValues = mutableMapOf<Int, Int>()   // index -> value (from +CIEV / AT+CIND)
  private val clccLock = Any()
  private val clccCalls = mutableListOf<CallInfo>()
  @Volatile private var clipNumber: String? = null
  @Volatile private var lastDirection = CallDirection.INCOMING

  // ----------------------------------------------------------------- connect

  fun connect(target: BluetoothDevice) {
    val sameTarget = targetDevice?.address == target.address
    // Selecting the already-connected device is a no-op. Selecting a
    // different device must first tear down the old socket; otherwise the
    // target address changes while the old RFCOMM link remains alive and the
    // phone can reject both connections.
    if (sameTarget && reconnectEnabled && (isConnected.value || attemptInFlight)) return
    targetDevice = target
    onLog("נבחר מכשיר ${target.name ?: target.address}", false)
    reconnectEnabled = true
    attemptInFlight = true
    reconnectAttempts = 0
    dropCount = 0
    quickDropCount = 0
    lastDropMs = 0L
    dropInfo.value = null
    connectionDiagnostics.value = null
    // Tear down the previous connection attempt before starting a new one.
    // readJob?.cancel() only cancels the coroutine, leaving the socket open;
    // if connect() is called twice in quick succession (e.g. auto-connect +
    // manual selection), two sockets end up alive at once — the phone sees
    // a second incoming RFCOMM and drops both.
    readJob?.cancel()
    teardown()
    readJob = scope.launch(Dispatchers.IO) { runConnection() }
  }

  private suspend fun runConnection() {
    val target = targetDevice ?: return
    while (reconnectEnabled) {
      attemptInFlight = true
      lastError.value = null
      try {
        beforeSocketOpen?.invoke(target)
      } catch (t: Throwable) {
        if (t is java.util.concurrent.CancellationException) throw t
        onLog("הכנת חיבור RFCOMM נכשלה: ${t.message ?: "שגיאה לא ידועה"}", true)
      }
      val sock = openSocket(target)
      if (sock == null) {
        val detail = connectionDiagnostics.value ?: "לא נמצא שער RFCOMM"
        Log.w(tag, "connect failed on all gateways: $detail")
        lastError.value = "החיבור הישיר נכשל: $detail - מנסה שוב..."
        attemptInFlight = false
        if (!waitBeforeRetry()) return
        continue
      }
      val streams = try {
        BufferedReader(InputStreamReader(sock.inputStream, Charsets.ISO_8859_1)) to sock.outputStream
      } catch (t: Throwable) {
        if (t is java.util.concurrent.CancellationException) throw t
        runCatching { sock.close() }
        onLog("פתיחת זרמי RFCOMM נכשלה: ${t.message ?: "שגיאה לא ידועה"}", true)
        attemptInFlight = false
        if (!waitBeforeRetry()) return
        continue
      }
      socket = sock
      input = streams.first
      output = streams.second

      // Some embedded AG stacks (feature phones) need a moment after the
      // RFCOMM channel opens before they're ready for AT commands.
      // Blasting BRSF immediately can cause "connects then drops" on slow
      // stacks - a short pause lets the AG finish its internal setup.
      delay(300)

      // Some AGs accept the socket but never answer AT commands. Close the
      // socket after 12s so a silent handshake can't hang the reconnect loop.
      // The atomic claim is important: without it, a watchdog waking at the
      // same moment as a successful handshake could close a live socket after
      // the handshake had already been accepted.
      val handshakeClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
      val watchdog = scope.launch(Dispatchers.IO) {
        delay(12_000)
        if (handshakeClaimed.compareAndSet(false, true)) {
          onLog("משא ומתן הדיבורית עבר את הזמן המותר", true)
          runCatching { sock.close() }
        }
      }
      val handshakeResult = try {
        handshake()
      } catch (t: Throwable) {
        if (t is java.util.concurrent.CancellationException) {
          // The watchdog belongs to the service scope rather than the
          // connection job, so cancel it explicitly on user/service shutdown.
          watchdog.cancel()
          throw t
        }
        false
      }
      val handshakeWon = handshakeClaimed.compareAndSet(false, true)
      watchdog.cancel()
      val handshakeOk = handshakeWon && handshakeResult && runCatching { sock.isConnected }.getOrDefault(false)
      if (!handshakeOk) {
        onLog("משא ומתן הדיבורית נכשל דרך ${lastGateway ?: "שער לא ידוע"}", true)
        // The AG rejected our AT negotiation on this gateway - try another one.
        rotateGateway()
        lastError.value = "הטלפון לא השלים את הפרוטוקול של הדיבורית - מנסה שוב..."
        teardown()
        attemptInFlight = false
        if (!waitBeforeRetry()) return
        continue
      }
      reconnectAttempts = 0
      connectedAt = System.currentTimeMillis()
      // Publish CONNECTED before allowing ACL recovery callbacks to intervene.
      // Otherwise there is a small window where a valid socket looks both
      // disconnected and no longer in-flight, so a second socket can be opened.
      isConnected.value = true
      attemptInFlight = false
      startPolling()
      readLoop() // blocks until the link dies (teardown() happens inside)
      val lastedMs = System.currentTimeMillis() - connectedAt
      if (reconnectEnabled) {
        // The user did not disconnect - the phone dropped the link on its own.
        dropCount++
        lastDropMs = lastedMs
        if (lastedMs < 4000) quickDropCount++ else quickDropCount = 0
        onLog("הקישור נותק אחרי ${lastedMs}ms דרך ${lastGateway ?: "שער לא ידוע"}", true)
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
      attemptInFlight = false
      if (!waitBeforeRetry()) return
    }
  }

  /** Backoff between reconnect attempts: 3s, 6s, 9s, then capped at 10s. */
  private suspend fun waitBeforeRetry(): Boolean {
    reconnectAttempts++
    delay(minOf(3000L * reconnectAttempts, 10_000L))
    return reconnectEnabled
  }

  /**
   * Tries SDP-advertised HFP/HSP records first. If the phone's SDP database
   * is broken or incomplete, falls back to the classic RFCOMM channel range
   * used by feature phones. Every attempt is recorded for diagnostics.
   */
  private suspend fun openSocket(target: BluetoothDevice): BluetoothSocket? {
    // Android explicitly recommends cancelling discovery before RFCOMM connect:
    // discovery consumes the radio and can make an otherwise valid socket fail
    // or drop immediately. This matters most after pairing from the app's scan.
    cancelDiscovery()
    discoveredUuids = discoverUuids(target)
    val known = gatewayOrder.toList()
    val advertised = discoveredUuids
      .mapNotNull { uuid -> known.firstOrNull { it.first == uuid } }
      .distinct()
    val order = (advertised + known).distinctBy { it.first }
    val attempts = mutableListOf<String>()

    for ((uuid, label) in order) {
      val sock = openGateway(target, uuid)
      attempts += "$label=$uuid:${if (sock != null) "OK" else "נכשל"}"
      if (sock != null) {
        lastGateway = label
        connectionDiagnostics.value = "SDP=[${discoveredUuids.joinToString()}], ניסיון=${attempts.joinToString("; ")}"
        onLog("הסוקט נפתח דרך $label", false)
        Log.i(tag, "connected via $label gateway")
        return sock
      }
    }

    // Some feature phones expose the HFP service but fail to answer SDP. On
    // Android the hidden createRfcommSocket(channel) is the only way to try
    // the channel directly. It is best-effort and harmless when blocked.
    val channels = (channelCursor..8).toList() + (1 until channelCursor).toList()
    for (channel in channels.distinct()) {
      val sock = openChannelSocket(target, channel)
      attempts += "RFCOMM:$channel:${if (sock != null) "OK" else "נכשל"}"
      channelCursor = if (channel >= 8) 1 else channel + 1
      if (sock != null) {
        lastGateway = "RFCOMM:$channel"
        connectionDiagnostics.value = "SDP=[${discoveredUuids.joinToString()}], ניסיון=${attempts.joinToString("; ")}"
        onLog("הסוקט נפתח דרך ערוץ RFCOMM $channel", false)
        Log.i(tag, "connected via direct RFCOMM channel $channel")
        return sock
      }
    }
    connectionDiagnostics.value = "SDP=[${discoveredUuids.joinToString()}], ניסיון=${attempts.joinToString("; ")}"
    onLog("כל ניסיונות החיבור נכשלו: ${attempts.joinToString("; ")}", true)
    return null
  }

  private fun cancelDiscovery() {
    runCatching {
      val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
      if (adapter?.isDiscovering == true) {
        adapter.cancelDiscovery()
        onLog("סריקת בלוטוס בוטלה לפני פתיחת החיבור", false)
      }
    }
  }

  private fun discoverUuids(target: BluetoothDevice): Set<UUID> {
    val latch = CountDownLatch(1)
    var found: Array<ParcelUuid>? = null
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        if (intent?.action != BluetoothDevice.ACTION_UUID) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device?.address != target.address) return
        found = if (Build.VERSION.SDK_INT >= 33) {
          intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.mapNotNull { it as? ParcelUuid }?.toTypedArray()
        }
        latch.countDown()
      }
    }
    return try {
      val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
      if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
      else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
      }
      val requested = runCatching { target.fetchUuidsWithSdp() }.getOrDefault(false)
      if (requested) latch.await(2500, TimeUnit.MILLISECONDS)
      val result = found.orEmpty().map { it.uuid }.toSet()
      onLog("גילוי SDP: ${if (result.isEmpty()) "לא נמצאו שירותים" else result.joinToString()}", result.isEmpty())
      result
    } catch (t: Throwable) {
      onLog("גילוי SDP נכשל: ${t.message ?: "שגיאה לא ידועה"}", true)
      Log.w(tag, "SDP discovery failed: ${t.message}")
      emptySet()
    } finally {
      runCatching { context.unregisterReceiver(receiver) }
    }
  }

  /**
   * Opens one gateway. Tries the secure socket first; some AG stacks (feature
   * phones) fail the encryption re-negotiation and only accept an insecure
   * link, so fall back to the insecure variant. Every attempt is bounded by
   * a watchdog so an unreachable phone can't stall the reconnect loop.
   */
  private suspend fun openGateway(target: BluetoothDevice, uuid: UUID): BluetoothSocket? {
    val secure = connectBounded(target, uuid, secure = true)
    if (secure != null) return secure
    Log.w(tag, "secure connect failed for $uuid")
    val insecure = connectBounded(target, uuid, secure = false)
    if (insecure != null) {
      Log.i(tag, "insecure socket accepted for $uuid")
    } else {
      Log.w(tag, "insecure connect failed for $uuid")
    }
    return insecure
  }

  /**
   * BluetoothSocket.connect() can block for a long time when the remote is
   * unreachable (the stack keeps retrying). Close the socket after 6s to
   * abort a stuck connect, so one dead gateway can't hang the whole loop.
   */
  private suspend fun connectBounded(target: BluetoothDevice, uuid: UUID, secure: Boolean): BluetoothSocket? =
    connectSocketBounded {
      if (secure) target.createRfcommSocketToServiceRecord(uuid)
      else target.createInsecureRfcommSocketToServiceRecord(uuid)
    }

  /** Best-effort direct-channel fallback for phones with broken SDP. */
  private suspend fun openChannelSocket(target: BluetoothDevice, channel: Int): BluetoothSocket? =
    connectSocketBounded {
      runCatching {
        val method = target.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        method.invoke(target, channel) as? BluetoothSocket
      }.getOrNull()
    }

  private suspend fun connectSocketBounded(create: () -> BluetoothSocket?): BluetoothSocket? {
    val sock = runCatching { create() }.getOrNull() ?: return null
    // withTimeoutOrNull + runInterruptible: if connect() blocks longer than
    // 8s, cancellation interrupts the same worker instead of racing a second
    // watchdog thread that could close a newly connected socket.
    val connected = try {
      withTimeoutOrNull(8_000) {
        runInterruptible(Dispatchers.IO) { sock.connect() }
      }
    } catch (t: Throwable) {
      // A refused RFCOMM channel is a normal connection outcome, not a fatal
      // coroutine error. Previously IOException escaped here and killed the
      // entire reconnect loop after the first failed gateway.
      if (t is java.util.concurrent.CancellationException) throw t
      onLog("פתיחת ערוץ RFCOMM נכשלה: ${t.message ?: "שגיאה לא ידועה"}", true)
      null
    }
    return if (connected != null && runCatching { sock.isConnected }.getOrDefault(false)) sock else {
      runCatching { sock.close() }
      null
    }
  }

  /** Moves the gateway that just failed to the back of the order. */
  private fun rotateGateway() {
    val g = lastGateway ?: return
    val idx = gatewayOrder.indexOfFirst { it.second == g }
    if (idx >= 0 && gatewayOrder.size > 1) {
      gatewayOrder.add(gatewayOrder.removeAt(idx))
    }
    if (g.startsWith("RFCOMM:")) {
      g.substringAfter(':').toIntOrNull()?.let { channelCursor = if (it >= 8) 1 else it + 1 }
    }
  }

  /** HFP negotiation: BRSF, CIND, CMER, CLIP, CCWA - the handshake every AG accepts. */
  private suspend fun handshake(): Boolean {
    // The legacy headset gateway speaks HSP - there is no BRSF/CIND/CMER
    // negotiation. Accept the link as-is and let the read loop watch it.
    // HSP does not understand HFP polling commands; sending AT+CLCC after an
    // HSP connection can make simple feature phones close the RFCOMM channel.
    if (lastGateway == "HSP") {
      hspMode = true
      return true
    }
    hspMode = false
    agBrsfFeatures = 0
    agFeaturesKnown = false
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
    if (!sendAndWait("AT+CMER=3,0,0,1") && !sendAndWait("AT+CMER=3,0,0,0")) {
      // CMER mode 3 is needed for unsolicited +CIEV events. Some basic
      // AGs (feature phones) reject it entirely - skip CMER; CLCC polling
      // still works, and some AGs send CIEV unsolicited regardless.
      Log.w(tag, "CMER rejected - continuing without call progress events")
    }
    // CHLD is intentionally not queried: this client does not advertise or
    // implement the three-way/call-hold procedures, and basic AGs sometimes
    // disconnect when an unsupported CHLD query is sent.
    sendCommand("AT+CLIP=1")
    readUntil { it.startsWith("OK") || it.startsWith("ERROR") }
    // Call-waiting notification is optional and is not needed for ordinary
    // incoming calls. Leave it disabled during SLC so basic AG firmware does
    // not reject an otherwise valid connection.
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
    // HSP has no HFP call-status channel. Keep the RFCOMM link alive, but do
    // not send AT+CLCC/AT polling commands that the HSP AG may reject by
    // disconnecting the link.
    if (hspMode) return
    pollJob = scope.launch(Dispatchers.IO) {
      // Let the link settle for a few seconds before polling call state -
      // some basic AG stacks (feature phones) drop the connection when the
      // HF starts sending AT+CLCC right after the SLC completes.
      delay(1500)
      if (!isConnected.value) return@launch
      sendCommand("AT")
      delay(1500)
      if (!isConnected.value) return@launch
      sendCommand("AT")
      // HFP AG feature bit 6 advertises Enhanced Call Status, which is the
      // capability behind AT+CLCC. Do not repeatedly send that optional
      // command to a basic feature phone that explicitly lacks it; some such
      // phones answer once and then terminate the RFCOMM channel.
      val supportsCurrentCalls = !agFeaturesKnown || (agBrsfFeatures and 0x40) != 0
      if (!supportsCurrentCalls) return@launch
      var writeFailures = 0
      while (isActive && isConnected.value) {
        synchronized(clccLock) { clccCalls.clear() }
        if (sendCommand("AT+CLCC")) {
          writeFailures = 0
        } else {
          writeFailures++
          // The output stream is dead (half-open socket): the phone will
          // drop the link soon anyway. Tear it down now so the reconnect
          // loop picks it up immediately instead of waiting for readLoop
          // to detect the closure.
          if (writeFailures >= 3) {
            onLog("זרם הכתיבה מת אחרי $writeFailures כשלונות — מתחבר מחדש", true)
            Log.w(tag, "output stream dead after $writeFailures write failures - forcing reconnect")
            teardown()
            return@launch
          }
        }
        // Keep the polling gentle for low-end feature phones; unsolicited
        // +CIEV notifications remain the primary call-state mechanism.
        delay(1500)
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
    attemptInFlight = false
    readJob?.cancel()
    teardown()
  }

  /** Bluetooth was turned off by the system. Keep the desired target armed,
   * but discard the stale socket so the next STATE_ON event can start cleanly. */
  fun onAdapterOff() {
    if (!reconnectEnabled) return
    attemptInFlight = false
    readJob?.cancel()
    teardown()
  }

  /** True while this raw client owns the connection lifecycle, whether it is
   * connecting, connected, or waiting for its next retry. */
  val ownsConnectionLoop: Boolean
    get() = reconnectEnabled && targetDevice != null

  /** True while the client wants the link up but is currently down. */
  val reconnectArmed: Boolean
    get() = ownsConnectionLoop && !isConnected.value && !attemptInFlight

  /**
   * Relaunches the connection loop right away (e.g. the ACL link just came
   * back) without resetting the drop statistics. Rate-limited by the caller.
   */
  fun nudge() {
    if (!reconnectArmed) return
    attemptInFlight = true
    readJob?.cancel()
    teardown()
    readJob = scope.launch(Dispatchers.IO) { runConnection() }
  }

  // ------------------------------------------------------------------ parsing

  private fun handleLine(line: String) {
    when {
      line.startsWith("+CIEV:") -> handleCiev(line)
      line.startsWith("+CLIP:") -> handleClip(line)
      line.startsWith("+CLCC:") -> handleClcc(line)
      line.startsWith("+CIND:") -> handleCind(line)
      line.startsWith("+BRSF:") -> handleBrsf(line)
      line == "OK" || line == "ERROR" -> finishClccBatch()
      // everything else is either an ack or an unsolicited event we don't need
      else -> Unit
    }
  }

  /** Parses the AG's BRSF feature bitmap (advertised in response to AT+BRSF). */
  @Volatile private var agBrsfFeatures = 0
  @Volatile private var agFeaturesKnown = false

  private fun handleBrsf(line: String) {
    agBrsfFeatures = line.substringAfter("+BRSF:").trim().toIntOrNull() ?: return
    agFeaturesKnown = true
    Log.i(tag, "AG features: $agBrsfFeatures")
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
    synchronized(clccLock) { clccCalls += CallInfo(state, number, direction) }
  }

  private fun finishClccBatch() {
    var info: CallInfo? = null
    synchronized(clccLock) {
      if (clccCalls.isEmpty()) return
      info = clccCalls.minByOrNull { rank(it.state) }
      clccCalls.clear()
    }
    info ?: return
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

  /** Returns true if the write was delivered to the output stream. */
  private fun sendCommand(cmd: String): Boolean =
    synchronized(writeLock) {
      val stream = output ?: return@synchronized false
      runCatching {
        stream.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        stream.flush()
      }.isSuccess
    }

  private suspend fun sendAndWait(cmd: String): Boolean {
    if (!sendCommand(cmd)) return false
    return readUntil { it.startsWith("OK") || it.startsWith("ERROR") }
  }

  private suspend fun readUntil(pred: (String) -> Boolean): Boolean {
    val r = input ?: return false
    while (true) {
      val line = try {
        r.readLine()
      } catch (t: Throwable) {
        if (t is java.util.concurrent.CancellationException) throw t
        return false
      } ?: return false
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
