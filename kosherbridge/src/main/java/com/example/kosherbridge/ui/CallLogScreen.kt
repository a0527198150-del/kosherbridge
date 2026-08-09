package com.example.kosherbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.CallLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private enum class CallFilter(val label: String) {
  ALL("הכול"),
  INCOMING("נכנסות"),
  OUTGOING("יוצאות"),
  MISSED("לא נענו"),
  FOLLOW_UP("לטיפול"),
}

/** Full call-log screen: filters, missed calls, follow-up marking, delete & clear. */
@Composable
fun CallLogScreen(onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val repo = ServiceLocator.contacts
  val calls by repo.recentCalls().collectAsStateWithLifecycle(emptyList())
  var filterName by rememberSaveable { mutableStateOf(CallFilter.ALL.name) }
  var showClearConfirm by remember { mutableStateOf(false) }
  val filter = CallFilter.valueOf(filterName)

  Column(modifier.fillMaxSize()) {
    Row(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      CallFilter.entries.forEach { f ->
        FilterChip(
          selected = filter == f,
          onClick = { filterName = f.name },
          label = { Text(f.label) },
        )
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.End,
    ) {
      TextButton(onClick = { showClearConfirm = true }) { Text("נקה יומן") }
    }

    val filtered = calls.filter { c ->
      when (filter) {
        CallFilter.ALL -> true
        CallFilter.INCOMING -> c.direction == "INCOMING"
        CallFilter.OUTGOING -> c.direction == "OUTGOING"
        CallFilter.MISSED -> c.missed
        CallFilter.FOLLOW_UP -> c.followUp
      }
    }

    if (filtered.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          "אין שיחות בסינון זה",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(Modifier.fillMaxSize()) {
        items(filtered, key = { it.id }) { c ->
          CallLogRow(
            call = c,
            onDial = { BridgeHub.service?.dial(c.number) },
            onToggleFollowUp = { scope.launch { repo.markFollowUp(c.id, !c.followUp) } },
            onDelete = { scope.launch { repo.deleteCall(c.id) } },
          )
        }
      }
    }
  }

  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = { showClearConfirm = false },
      title = { Text("לנקות את יומן השיחות?") },
      text = { Text("כל השיחות ביומן יימחקו לצמיתות.") },
      confirmButton = {
        TextButton(
          onClick = {
            scope.launch { repo.clearCallLog() }
            showClearConfirm = false
            onSnackbar("יומן השיחות נוקה")
          },
        ) { Text("מחק הכול", fontWeight = FontWeight.Bold) }
      },
      dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("ביטול") } },
    )
  }
}

@Composable
private fun CallLogRow(
  call: CallLogEntity,
  onDial: () -> Unit,
  onToggleFollowUp: () -> Unit,
  onDelete: () -> Unit,
) {
  val missed = call.missed && call.direction == "INCOMING"
  val time = SimpleDateFormat("d/M HH:mm", Locale.getDefault()).format(Date(call.timestamp))
  val subtitle = buildString {
    if (missed) append("לא נענתה · ")
    append(time)
    if (call.durationSec > 0) append(" · ${formatDuration(call.durationSec)}")
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        if (call.followUp) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surface,
      )
      .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier
        .weight(1f)
        .clickable(onClick = onDial)
        .padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (call.direction == "INCOMING") Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
        contentDescription = null,
        tint = when {
          missed -> MaterialTheme.colorScheme.error
          call.direction == "INCOMING" -> MaterialTheme.colorScheme.tertiary
          else -> MaterialTheme.colorScheme.primary
        },
      )
      Spacer(Modifier.width(12.dp))
      Column {
        Text(
          call.name ?: call.number,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = if (missed) FontWeight.Bold else FontWeight.Medium,
          color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (call.name != null) {
          Text(
            call.number,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
          )
        }
      }
    }
    if (call.followUp) {
      Text(
        "לטיפול",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFB26A00),
        modifier = Modifier
          .background(Color(0xFFFFE0B2), RoundedCornerShape(6.dp))
          .padding(horizontal = 6.dp, vertical = 2.dp),
      )
      Spacer(Modifier.width(6.dp))
    }
    IconButton(onClick = onToggleFollowUp, modifier = Modifier.size(36.dp)) {
      Icon(
        imageVector = if (call.followUp) Icons.Filled.Flag else Icons.Outlined.Flag,
        contentDescription = "סימון לטיפול בהמשך",
        tint = if (call.followUp) Color(0xFFB26A00) else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
      Icon(
        Icons.Filled.Delete,
        contentDescription = "מחק שיחה מהיומן",
        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
      )
    }
  }
}

private fun formatDuration(sec: Int): String {
  val s = sec % 60
  val m = (sec / 60) % 60
  val h = sec / 3600
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
