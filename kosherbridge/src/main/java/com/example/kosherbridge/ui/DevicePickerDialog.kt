package com.example.kosherbridge.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.bluetooth.PairedDeviceInfo

@Composable
fun DevicePickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
  val context = LocalContext.current
  var devices by remember { mutableStateOf<List<PairedDeviceInfo>>(emptyList()) }

  LaunchedEffect(Unit) {
    devices = BridgeHub.service?.bondedDevices() ?: emptyList()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
    title = { Text("בחירת טלפון כשר") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (devices.isEmpty()) {
          Text(
            "אין מכשירים מזווגים.\nזווג קודם את הטלפון הכשר מהגדרות הבלוטוס שלו, ואז לחץ על \"רענון\".",
            style = MaterialTheme.typography.bodyMedium,
          )
        } else {
          devices.forEach { d ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onPick(d.address) }
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.width(12.dp))
              Text(d.name, fontWeight = FontWeight.Medium)
            }
          }
        }
        TextButton(
          onClick = {
            onDismiss()
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
          },
          modifier = Modifier.align(Alignment.End),
        ) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Spacer(Modifier.width(4.dp))
          Text("התאמת מכשיר חדש")
        }
      }
    },
    confirmButton = {},
  )
}
