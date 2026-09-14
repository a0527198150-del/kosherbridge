package com.example.kosherbridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/**
 * What the KOSHER PHONE offers, read from its SDP records.
 *
 * Half of every "why is there no sound" question is about the player, and the
 * app answers that thoroughly. The other half is about the phone, and the app
 * said nothing at all - the SDP UUIDs were fetched, used to pick a gateway, and
 * then reported as raw 128-bit strings that nobody can read.
 *
 * They decide what is possible. A phone that publishes HandsfreeAudioGateway
 * (0x111F) is a full HFP gateway and the direct channel has something to
 * connect to. A phone that publishes only the legacy HeadsetAudioGateway
 * (0x1112) speaks HSP, which has no call-status channel at all, so caller ID
 * and call state will be thin however good the player is. And a phone that
 * publishes neither cannot be bridged by anything.
 *
 * Reading them needs only BLUETOOTH_CONNECT, which the app already holds.
 */
object PeerProfiles {

  /**
   * Bluetooth's assigned service UUIDs, named. Only the ones that mean
   * something for this app - an unknown UUID is printed as its short form
   * rather than guessed at.
   */
  private val NAMES = mapOf(
    0x1101 to "יציאה טורית",
    0x1105 to "שליחת קבצים",
    0x1106 to "העברת קבצים",
    0x1108 to "אוזניה (HSP)",
    0x110A to "מקור שמע (A2DP)",
    0x110B to "יעד שמע (A2DP)",
    0x110C to "יעד שלט (AVRCP)",
    0x110E to "שלט (AVRCP)",
    0x1112 to "שער אוזניה (HSP-AG)",
    0x111E to "דיבורית (HFP-HF)",
    0x111F to "שער דיבורית (HFP-AG)",
    0x112F to "ספר טלפונים (PBAP)",
    0x1132 to "הודעות (MAP)",
    0x1200 to "פרטי מכשיר",
  )

  /** The 16-bit short form of a Bluetooth base UUID, or null when not one. */
  private fun shortForm(uuid: java.util.UUID): Int? {
    val s = uuid.toString().lowercase()
    if (!s.endsWith("-0000-1000-8000-00805f9b34fb")) return null
    return s.substring(4, 8).toIntOrNull(16)
  }

  /**
   * One readable line listing what the phone publishes, for diagnostics and the
   * report. Returns null when nothing is known yet, so callers can say "not
   * discovered" instead of "none" - which are very different answers.
   */
  @SuppressLint("MissingPermission")
  fun describe(device: BluetoothDevice?): String? {
    val uuids = runCatching { device?.uuids }.getOrNull() ?: return null
    if (uuids.isEmpty()) return null
    return uuids.mapNotNull { it?.uuid }
      .mapNotNull { uuid ->
        val short = shortForm(uuid)
        when {
          short == null -> "ייחודי ליצרן"
          NAMES.containsKey(short) -> NAMES[short]
          else -> "0x%04X".format(short)
        }
      }
      .distinct()
      .joinToString(", ")
  }

  /**
   * What the phone's records mean for this bridge, in the terms the user
   * needs. Null when SDP has not answered yet.
   */
  @SuppressLint("MissingPermission")
  fun verdict(device: BluetoothDevice?): String? {
    val uuids = runCatching { device?.uuids }.getOrNull() ?: return null
    if (uuids.isEmpty()) return null
    val shorts = uuids.mapNotNull { it?.uuid }.mapNotNull { shortForm(it) }.toSet()
    val hfpAg = 0x111F in shorts
    val hspAg = 0x1112 in shorts
    return when {
      hfpAg ->
        "הטלפון מפרסם שער דיבורית מלא (HFP-AG). זה מה שהערוץ הישיר מתחבר אליו, " +
          "ומכאן מגיעים זיהוי מתקשר, מצב שיחה ובקשת ערוץ קול."
      hspAg ->
        "הטלפון מפרסם רק שער אוזניה ישן (HSP-AG), בלי HFP. אפשר לענות ולנתק, " +
          "אבל לפרוטוקול הזה אין ערוץ מצב שיחה כלל - ולכן זיהוי המתקשר ומצב " +
          "השיחה יישארו חלקיים, בלי קשר ליכולות הנגן."
      else ->
        "הטלפון לא מפרסם שום שער דיבורית או אוזניה. אי אפשר לגשר עליו - ודא " +
          "שהוא מזווג ושהבלוטוס שלו דלוק, ואז הרץ שוב."
    }
  }
}
