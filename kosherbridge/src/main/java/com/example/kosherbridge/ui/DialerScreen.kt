package com.example.kosherbridge.ui

import android.bluetooth.BluetoothProfile
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.ContactsRepository
import com.example.kosherbridge.data.local.ContactWithDetails

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val state by BridgeHub.state.collectAsStateWithLifecycle()
  var number by rememberSaveable { mutableStateOf("") }
  val connected = state.connectionState == BluetoothProfile.STATE_CONNECTED
  val contacts by ServiceLocator.contacts.contactsWithDetails().collectAsStateWithLifecycle(emptyList())
  val recentCalls by ServiceLocator.contacts.recentCalls().collectAsStateWithLifecycle(emptyList())
  val keyTone by ServiceLocator.settings.keyTone.collectAsStateWithLifecycle(true)

  val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 60) }.getOrNull() }
  DisposableEffect(Unit) { onDispose { runCatching { tone?.release() } } }

  val suggestions = remember(number, contacts) {
    if (number.isBlank()) emptyList()
    else contacts.filter { c ->
      c.phoneLabels().any { (_, n) ->
        val digits = ContactsRepository.normalizePhone(n)
        digits.startsWith(number.filter { it.isDigit() })
      }
    }.take(4)
  }
  val recents = recentCalls.take(3)

  // No vertical scroll: the keypad is sized from the height that is left after
  // the display/actions, so every row (including *, 0, # and the call button)
  // stays on screen even on short landscape screens.
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // ---------------- number display (LTR) ----------------
    val display = if (number.isEmpty()) "הזן מספר" else formatDialNumber(number)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
      Text(
        text = display,
        style = MaterialTheme.typography.headlineMedium.copy(
          textDirection = if (number.isEmpty()) TextDirection.Content else TextDirection.Ltr,
        ),
        fontSize = when {
          display.length > 15 -> 22.sp
          display.length > 10 -> 26.sp
          else -> 32.sp
        },
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Spacer(Modifier.height(4.dp))
    Text(
      text = if (connected) "מחובר · החיוג דרך הטלפון הכשר" else "לא מחובר — לא ניתן לחייג",
      style = MaterialTheme.typography.bodySmall,
      color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )

    // ---------------- suggestions & recents ----------------
    if (number.isNotEmpty() && suggestions.isNotEmpty()) {
      Column(
        Modifier
          .fillMaxWidth()
          .padding(vertical = 6.dp)
          .clip(MaterialTheme.shapes.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
      ) {
        suggestions.forEach { c ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { number = c.primaryPhone }
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            ContactAvatar(c.contact.name, c.contact.photoUri, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
              Text(c.contact.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
              Text(c.primaryPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
          }
        }
      }
    } else if (number.isEmpty() && recents.isNotEmpty()) {
      Column(
        Modifier
          .fillMaxWidth()
          .padding(vertical = 6.dp)
          .clip(MaterialTheme.shapes.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
      ) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
          Spacer(Modifier.width(6.dp))
          Text("שיחות אחרונות", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recents.forEach { c ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { number = c.number }
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              c.name ?: c.number,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.weight(1f),
            )
            Text(
              c.number,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    // ---------------- keypad (LTR) ----------------
    Spacer(Modifier.height(8.dp))
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
      // weight(1f) hands the keypad every dp that the rest of the screen does
      // not use, so on a short landscape screen the keys shrink and all four
      // rows still fit; on a tall phone they grow up to 88dp.
      BoxWithConstraints(
        Modifier
          .fillMaxWidth()
          .weight(1f),
      ) {
        val keySize =
          ((maxWidth - 56.dp) / 3)
            .coerceAtMost(((maxHeight - 21.dp) / 4))
            .coerceIn(36.dp, 88.dp)
        Column(
          Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
        ) {
          keys.chunked(3).forEach { row ->
            Row(
              horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
              modifier = Modifier.fillMaxWidth(),
            ) {
              row.forEach { key ->
                DialKey(
                  label = key,
                  sub = t9Letters(key),
                  size = keySize,
                  onLongClick = if (key == "0") ({ number += "+" }) else null,
                  onClick = {
                    number += key
                    if (keyTone) tone?.startTone(toneFor(key), 120)
                  },
                )
              }
            }
          }
        }
      }
    }

    // ---------------- actions ----------------
    Spacer(Modifier.height(6.dp))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .combinedClickable(
              onClick = { if (number.isNotEmpty()) number = number.dropLast(1) },
              onLongClick = { number = "" },
              enabled = number.isNotEmpty(),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "מחיקה (החזקה לנקות הכל)",
            tint = if (number.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
          )
        }
        FilledIconButton(
          onClick = {
            if (number.isBlank()) return@FilledIconButton
            val ok = BridgeHub.service?.dial(number) == true
            if (!ok) onSnackbar(BridgeHub.state.value.lastError ?: "החיוג נכשל")
          },
          enabled = number.isNotEmpty() && connected,
          modifier = Modifier.size(72.dp),
          colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color(0xFF00875A),
            contentColor = Color.White,
          ),
        ) {
          Icon(Icons.Filled.Call, contentDescription = "חייג", modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(64.dp))
      }
    }
    Spacer(Modifier.height(8.dp))
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
  label: String,
  sub: String?,
  size: Dp,
  onLongClick: (() -> Unit)? = null,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(size)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        label,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        fontSize = (size.value * 0.42f).sp,
      )
      if (!sub.isNullOrEmpty()) {
        Text(
          sub,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Classic T9 letters shown under the digits. */
private fun t9Letters(key: String): String? = when (key) {
  "2" -> "ABC"
  "3" -> "DEF"
  "4" -> "GHI"
  "5" -> "JKL"
  "6" -> "MNO"
  "7" -> "PQRS"
  "8" -> "TUV"
  "9" -> "WXYZ"
  else -> null
}

/** DTMF tone for a dialer key (played when the "key sounds" setting is on). */
private fun toneFor(key: String): Int = when (key) {
  "1" -> ToneGenerator.TONE_DTMF_1
  "2" -> ToneGenerator.TONE_DTMF_2
  "3" -> ToneGenerator.TONE_DTMF_3
  "4" -> ToneGenerator.TONE_DTMF_4
  "5" -> ToneGenerator.TONE_DTMF_5
  "6" -> ToneGenerator.TONE_DTMF_6
  "7" -> ToneGenerator.TONE_DTMF_7
  "8" -> ToneGenerator.TONE_DTMF_8
  "9" -> ToneGenerator.TONE_DTMF_9
  "0" -> ToneGenerator.TONE_DTMF_0
  "*" -> ToneGenerator.TONE_DTMF_S
  else -> ToneGenerator.TONE_DTMF_P
}

/** Readable grouping for dialing, e.g. 050-123-4567 or +972-50-123-4567. */
internal fun formatDialNumber(raw: String): String = when {
  raw.length == 10 && raw.startsWith("0") -> "${raw.substring(0, 3)}-${raw.substring(3, 6)}-${raw.substring(6)}"
  raw.length == 12 && raw.startsWith("972") -> "+${raw.substring(0, 3)}-${raw.substring(3, 5)}-${raw.substring(5, 8)}-${raw.substring(8)}"
  else -> raw
}
