package com.example.kosherbridge.ui

import android.bluetooth.BluetoothProfile
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub

@Composable
fun DialerScreen(onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val state by BridgeHub.state.collectAsStateWithLifecycle()
  var number by rememberSaveable { mutableStateOf("") }
  val connected = state.connectionState == BluetoothProfile.STATE_CONNECTED

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = number.ifEmpty { "הזן מספר" },
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = if (connected) "מחובר · החיוג דרך הטלפון הכשר" else "לא מחובר — לא ניתן לחייג",
      style = MaterialTheme.typography.bodySmall,
      color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(20.dp))

    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    keys.chunked(3).forEach { row ->
      Row(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.padding(vertical = 7.dp),
      ) {
        row.forEach { key ->
          DialKey(
            label = key,
            onLongClick = if (key == "0") ({ number += "+" }) else null,
          ) { number += key }
        }
      }
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
      IconButton(
        onClick = { if (number.isNotEmpty()) number = number.dropLast(1) },
        enabled = number.isNotEmpty(),
        modifier = Modifier.size(64.dp),
      ) {
        Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "מחיקה")
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(72.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center,
    )
  }
}
