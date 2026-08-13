package com.example.kosherbridge.bluetooth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Simulated kosher phone playing the HFP Audio Gateway role. It speaks the
 * exact dialect captured from a real kosher phone (the AT probe recorded
 * `AT+BRSF=20 -> +BRSF: 355`, the CIND indicator order, `+CIEV` and `+CLIP`
 * events), so the app's AT-command logic can be exercised on an emulator that
 * has no Bluetooth radio at all.
 *
 * The production [RawHfpClient] talks to this object over an in-memory pipe
 * ([LoopbackHfpLink]) — the same transport-agnostic [HfpLink] interface the
 * real RFCOMM socket uses, so what passes here is what runs on real hardware.
 */
class MockAg(
  private val link: HfpLink,
  private val scope: CoroutineScope,
) {
  private val writeLock = Any()
  @Volatile private var running = false

  fun start() {
    running = true
    scope.launch(Dispatchers.IO) { run() }
  }

  fun close() {
    running = false
    runCatching { link.close() }
  }

  /** Fires an unsolicited incoming-call sequence, mirroring a real phone. */
  fun incomingCall(number: String) {
    // CLIP first so clipNumber is populated before the CIEV/RING that emit
    // the call state (matches the real phone's ordering closely enough).
    send("+CLIP: \"$number\",145")
    send("+CIEV: 3,1") // callsetup = 1 (incoming)
    send("RING")
  }

  private fun send(s: String) {
    synchronized(writeLock) {
      runCatching {
        link.output.write((s + "\r\n").toByteArray(Charsets.US_ASCII))
        link.output.flush()
      }
    }
  }

  private fun handleAnswer() {
    send("OK")
    send("+CIEV: 2,1") // call = 1
    send("+CIEV: 3,0") // callsetup = 0 -> ACTIVE
  }

  private fun handleHangup() {
    send("OK")
    send("+CIEV: 2,0") // call = 0 -> IDLE
  }

  private fun handleDial(cmd: String) {
    send("OK")
    // A real phone walks callsetup 2 (dialing) -> 3 (alerting) then call = 1.
    send("+CIEV: 3,2")
    send("+CIEV: 3,3")
    send("+CIEV: 2,1")
    send("+CIEV: 3,0")
  }

  private fun run() {
    val r = link.input
    try {
      while (running) {
        val line = try { r.readLine() } catch (t: Throwable) { null } ?: break
        val cmd = line.trim()
        when {
          cmd.startsWith("AT+BRSF=") -> {
            // 355 = CLI + Enhanced Call Status + ... (matches the real probe).
            send("+BRSF: 355")
            send("OK")
          }
          cmd.startsWith("AT+CIND=?") -> {
            // Exact indicator order captured from the real kosher phone.
            // 1=service, 2=call, 3=callsetup, 4=callheld, 5=battchg,
            // 6=signal, 7=roam - matches the +CIEV indices below.
            send("+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0,3)),(\"callheld\",(0,2)),(\"battchg\",(0,5)),(\"signal\",(0,5)),(\"roam\",(0,1))")
            send("OK")
          }
          cmd.startsWith("AT+CIND") -> {
            send("+CIND: 1,0,0,0,5,5,0")
            send("OK")
          }
          cmd.startsWith("AT+CMER") -> send("OK")
          cmd.startsWith("AT+CLIP=") -> send("OK")
          cmd.startsWith("AT+CCWA=") -> send("OK")
          cmd == "AT" -> send("OK")
          cmd.startsWith("AT+CLCC") -> send("OK") // empty call list
          cmd.startsWith("ATD") -> handleDial(cmd)
          cmd == "ATA" -> handleAnswer()
          cmd == "AT+CHUP" -> handleHangup()
          cmd.startsWith("AT+CKPD") -> handleAnswer() // HSP button semantics
          else -> send("ERROR")
        }
      }
    } finally {
      running = false
    }
  }
}
