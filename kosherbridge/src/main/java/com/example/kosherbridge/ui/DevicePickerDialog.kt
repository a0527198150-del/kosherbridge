package com.example.kosherbridge.ui

import android.bluetooth.BluetoothManager
import android.content.Context
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
    // The service is the preferred source (it may be reading through a
    // privileged bridge), but it is null whenever the service is not running -
    // and this dialog then told the user "no paired devices found", with three
    // pieces of troubleshooting advice, about a phone that is in fact paired.
    // Fall back to the adapter, which needs nothing but BLUETOOTH_CONNECT.
    devices = BridgeHub.service?.bondedDevices()?.takeIf { it.isNotEmpty() }
      ?: bondedFromAdapter(context)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
    title = { Text("בחירת טלפון כשר") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (devices.isEmpty()) {
          Text(
            "לא נמצאו מכשירים מזווגים.\n\n" +
            "אם זווגת בעבר:\n" +
            "• ודא שהרשאת הבלוטוס מאושרת (הגדרות ← אפליקציות ← גשר כשר ← הרשאות)\n" +
            "• נסה לבטל ולהפעיל בלוטוס בנגן\n" +
            "• או: השתמש ב'צימוד מכשיר חדש (סריקה)' כדי למצוא אותו מחדש",
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
      }
    },
    // A TV box or player is driven with a remote, where dismissing a dialog by
    // tapping outside it is awkward or impossible - it needs a real button.
    confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } },
  )
}

/**
 * Paired devices straight from the Bluetooth adapter. Returns an empty list
 * when BLUETOOTH_CONNECT is missing - some implementations throw there rather
 * than returning nothing.
 */
private fun bondedFromAdapter(context: Context): List<PairedDeviceInfo> = runCatching {
  val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
  adapter?.bondedDevices?.map { PairedDeviceInfo(it.name ?: it.address, it.address) } ?: emptyList()
}.getOrDefault(emptyList())
