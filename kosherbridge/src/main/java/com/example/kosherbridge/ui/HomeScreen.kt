package com.example.kosherbridge.ui

import android.bluetooth.BluetoothProfile
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.BridgeService
import com.example.kosherbridge.bluetooth.BridgeUiState
import com.example.kosherbridge.bluetooth.CallInfo
import com.example.kosherbridge.bluetooth.CallState
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.CallLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(state: BridgeUiState, onGoToDialer: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var showDevices by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    ConnectionCard(state, onShowDevices = { showDevices = true }, onGoToDialer = onGoToDialer)
    state.call?.let { CallCard(it, state.audioState) }
    state.lastError?.let { ErrorCard(it) }
    FollowUpCard()
    RecentCallsCard()
  }

  if (showDevices) {
    DevicePickerDialog(
      onDismiss = { showDevices = false },
      onPick = { address ->
        showDevices = false
        BridgeService.requestConnect(context, address)
      },
    )
  }
}

@Composable
private fun ConnectionCard(state: BridgeUiState, onShowDevices: () -> Unit, onGoToDialer: () -> Unit) {
  val connected = state.connectionState == BluetoothProfile.STATE_CONNECTED
  val connecting = state.connectionState == BluetoothProfile.STATE_CONNECTING
  Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (connected) Color(0xFF00696D) else Color(0xFF274D4F),
    ),
  ) {
    Column(
      Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = when {
            connected -> Icons.Filled.BluetoothConnected
            connecting -> Icons.Filled.Bluetooth
            else -> Icons.Filled.BluetoothDisabled
          },
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(
            "גשר כשר",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            connectionText(state),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      if (state.audioState == 2) {
        Text(
          "🔊 שמע שיחה מנותב לנגן",
          color = Color.White,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onShowDevices,
          colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF00696D),
          ),
        ) {
          Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("בחר מכשיר")
        }
        if (connected) {
          OutlinedButton(
            onClick = { BridgeHub.service?.disconnect() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
          ) {
            Text("ניתוק")
          }
        }
        Button(
          onClick = onGoToDialer,
          colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF00696D),
          ),
        ) {
          Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("חייג")
        }
      }
    }
  }
}

@Composable
private fun CallCard(call: CallInfo, audioState: Int) {
  val ringing = call.state == CallState.INCOMING || call.state == CallState.WAITING
  val active = call.state == CallState.ACTIVE
  val outgoing = call.state == CallState.DIALING || call.state == CallState.ALERTING
  val title = when {
    ringing -> "שיחה נכנסת"
    outgoing -> "מחייג..."
    active -> "בשיחה"
    call.state == CallState.HELD -> "שיחה בהמתנה"
    else -> "שיחה"
  }
  Card(shape = RoundedCornerShape(24.dp)) {
    Column(
      Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = if (ringing) Icons.Filled.Call else if (active || outgoing) Icons.Filled.VolumeUp else Icons.Filled.CallEnd,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column {
          Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            call.number ?: "לא ידוע",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
          ringing -> {
            OutlinedButton(
              onClick = { BridgeHub.service?.reject() },
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
              Icon(Icons.Filled.CallEnd, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("דחה")
            }
            Button(
              onClick = { BridgeHub.service?.answer() },
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00875A),
                contentColor = Color.White,
              ),
            ) {
              Icon(Icons.Filled.Call, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("ענה")
            }
          }
          active -> {
            Button(
              onClick = { BridgeHub.service?.hangup() },
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD32F2F),
                contentColor = Color.White,
              ),
            ) {
              Icon(Icons.Filled.CallEnd, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text("נתק")
            }
            OutlinedButton(onClick = { BridgeHub.service?.toggleAudio() }) {
              Text(if (audioState == 2) "כבה שמע" else "הפעל שמע")
            }
          }
          else -> {
            Button(
              onClick = { BridgeHub.service?.hangup() },
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD32F2F),
                contentColor = Color.White,
              ),
            ) {
              Text("בטל")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ErrorCard(message: String) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Filled.Warning,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onErrorContainer,
      )
      Spacer(Modifier.width(12.dp))
      Text(
        message,
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

/** Calls the user flagged for follow-up: the app surfaces them until handled. */
@Composable
private fun FollowUpCard() {
  val followUps by ServiceLocator.contacts.followUps().collectAsStateWithLifecycle(emptyList())
  val scope = rememberCoroutineScope()
  if (followUps.isEmpty()) return
  Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
  ) {
    Column(
      Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Flag, contentDescription = null, tint = Color(0xFFB26A00))
        Spacer(Modifier.width(8.dp))
        Text("לטיפול בהמשך", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      }
      followUps.take(5).forEach { c ->
        Row(
          Modifier.fillMaxWidth().padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(
            Modifier.weight(1f).clickable { BridgeHub.service?.dial(c.number) },
          ) {
            Text(
              c.name ?: c.number,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.Medium,
            )
            if (c.name != null) {
              Text(
                c.number,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          TextButton(onClick = { scope.launch { ServiceLocator.contacts.markFollowUp(c.id, false) } }) {
            Text("סיים טיפול")
          }
        }
      }
    }
  }
}

@Composable
private fun RecentCallsCard() {
  val calls by ServiceLocator.contacts.recentCalls().collectAsStateWithLifecycle(emptyList())
  Card(shape = RoundedCornerShape(24.dp)) {
    Column(
      Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text("שיחות אחרונות", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      if (calls.isEmpty()) {
        Text(
          "אין עדיין שיחות",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        calls.take(10).forEach { c -> CallRow(c) }
      }
    }
  }
}

@Composable
private fun CallRow(c: CallLogEntity) {
  val missed = c.missed && c.direction == "INCOMING"
  val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.timestamp))
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { BridgeHub.service?.dial(c.number) }
      .padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = if (c.direction == "INCOMING") Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
      contentDescription = null,
      tint = when {
        missed -> MaterialTheme.colorScheme.error
        c.direction == "INCOMING" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
      },
    )
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(
        c.name ?: c.number,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (missed) FontWeight.Bold else FontWeight.Medium,
        color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
      when {
        missed -> Text("לא נענתה", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        c.name != null -> Text(
          c.number,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (c.followUp) {
      Icon(
        Icons.Filled.Flag,
        contentDescription = "לטיפול",
        tint = Color(0xFFB26A00),
        modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(8.dp))
    }
    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
