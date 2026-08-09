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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.CallLogEntity
import com.example.kosherbridge.data.local.ContactEntity
import com.example.kosherbridge.data.local.ContactsRepository
import java.text.SimpleDateFormat
import java.util.Calendar
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

private sealed interface LogItem {
  data class DayHeader(val label: String) : LogItem
  data class Call(val call: CallLogEntity) : LogItem
}

/** Full call-log screen: search, filters, day grouping, follow-up and details. */
@Composable
fun CallLogScreen(onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val repo = ServiceLocator.contacts
  var query by rememberSaveable { mutableStateOf("") }
  val calls by repo.searchCalls(query).collectAsStateWithLifecycle(emptyList())
  var filterName by rememberSaveable { mutableStateOf(CallFilter.ALL.name) }
  var showClearConfirm by remember { mutableStateOf(false) }
  var detailFor by remember { mutableStateOf<CallLogEntity?>(null) }
  val filter = CallFilter.valueOf(filterName)

  // Contact photos for known numbers, resolved once per list update. The map
  // is keyed by EVERY phone of each contact (primary + secondary) so calls
  // that arrived from a non-primary number still get the right photo.
  val contacts by ServiceLocator.contacts.contactsWithDetails().collectAsStateWithLifecycle(emptyList())
  val photoByNumber = remember(contacts) {
    buildMap {
      contacts.forEach { c ->
        val primary = ContactsRepository.normalizePhone(c.contact.phone)
        if (primary.isNotEmpty()) put(primary, c.contact)
        c.phones.forEach { p ->
          val n = ContactsRepository.normalizePhone(p.number)
          if (n.isNotEmpty()) put(n, c.contact)
        }
      }
    }
  }

  Column(modifier.fillMaxSize()) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("חיפוש שם או מספר") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
          if (query.isNotEmpty()) {
            IconButton(onClick = { query = "" }) {
              Icon(Icons.Filled.Close, contentDescription = "ניקוי חיפוש")
            }
          }
        },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      TextButton(onClick = { showClearConfirm = true }) { Text("נקה יומן") }
    }
    Row(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp),
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
          if (query.isBlank()) "אין שיחות בסינון זה" else "לא נמצאו תוצאות",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(Modifier.fillMaxSize()) {
        val grouped = buildLogItems(filtered)
        items(grouped, key = {
          when (it) {
            is LogItem.DayHeader -> "d_${it.label}"
            is LogItem.Call -> "c_${it.call.id}"
          }
        }) { item ->
          when (item) {
            is LogItem.DayHeader -> DayHeaderRow(item.label)
            is LogItem.Call -> CallLogRow(
              call = item.call,
              contact = photoByNumber[ContactsRepository.normalizePhone(item.call.number)],
              onOpen = { detailFor = item.call },
              onToggleFollowUp = { scope.launch { repo.markFollowUp(item.call.id, !item.call.followUp) } },
              onDelete = { scope.launch { repo.deleteCall(item.call.id) } },
            )
          }
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

  detailFor?.let { c ->
    CallDetailDialog(
      call = c,
      contact = photoByNumber[ContactsRepository.normalizePhone(c.number)],
      onDismiss = { detailFor = null },
      onCall = {
        BridgeHub.service?.dial(c.number)
        detailFor = null
      },
      onToggleFollowUp = {
        scope.launch { repo.markFollowUp(c.id, !c.followUp) }
        detailFor = null
      },
      onDelete = {
        scope.launch { repo.deleteCall(c.id) }
        detailFor = null
      },
    )
  }
}

private fun buildLogItems(calls: List<CallLogEntity>): List<LogItem> {
  val items = mutableListOf<LogItem>()
  val now = Calendar.getInstance()
  val todayStart = now.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
  val yesterdayStart = todayStart - 24 * 60 * 60 * 1000
  val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
  var currentHeader: String? = null
  calls.forEach { c ->
    val header = when {
      c.timestamp >= todayStart -> "היום"
      c.timestamp >= yesterdayStart -> "אתמול"
      else -> dayFormat.format(Date(c.timestamp))
    }
    if (header != currentHeader) {
      items.add(LogItem.DayHeader(header))
      currentHeader = header
    }
    items.add(LogItem.Call(c))
  }
  return items
}

@Composable
private fun DayHeaderRow(label: String) {
  Text(
    label,
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
      .padding(horizontal = 16.dp, vertical = 6.dp),
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary,
  )
}

@Composable
private fun CallLogRow(
  call: CallLogEntity,
  contact: ContactEntity?,
  onOpen: () -> Unit,
  onToggleFollowUp: () -> Unit,
  onDelete: () -> Unit,
) {
  val missed = call.missed && call.direction == "INCOMING"
  val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(call.timestamp))
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
      .clickable(onClick = onOpen)
      .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ContactAvatar(call.name ?: call.number, contact?.photoUri, 40.dp)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          call.name ?: call.number,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = if (missed) FontWeight.Bold else FontWeight.Medium,
          color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
          imageVector = if (call.direction == "INCOMING") Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
          contentDescription = null,
          tint = when {
            missed -> MaterialTheme.colorScheme.error
            call.direction == "INCOMING" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
          },
          modifier = Modifier.size(16.dp),
        )
      }
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
    if (call.followUp) {
      Icon(Icons.Filled.Flag, contentDescription = "לטיפול", tint = Color(0xFFB26A00), modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(4.dp))
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

/** Full call details with quick actions: call back, follow-up, delete. */
@Composable
private fun CallDetailDialog(
  call: CallLogEntity,
  contact: ContactEntity?,
  onDismiss: () -> Unit,
  onCall: () -> Unit,
  onToggleFollowUp: () -> Unit,
  onDelete: () -> Unit,
) {
  val missed = call.missed && call.direction == "INCOMING"
  val fullTime = SimpleDateFormat("EEEE, d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(call.timestamp))
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 480.dp)
        .clip(RoundedCornerShape(28.dp))
        .background(MaterialTheme.colorScheme.surface)
        .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      ContactAvatar(call.name ?: call.number, contact?.photoUri, 88.dp)
      Spacer(Modifier.padding(top = 12.dp))
      Text(
        call.name ?: call.number,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      if (call.name != null) {
        Text(call.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Spacer(Modifier.padding(top = 8.dp))
      Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        InfoCell(if (missed) "לא נענתה" else if (call.direction == "INCOMING") "נכנסת" else "יוצאת")
        InfoCell(fullTime)
        InfoCell(if (call.durationSec > 0) formatDuration(call.durationSec) else "—")
      }
      Spacer(Modifier.padding(top = 16.dp))
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        TextButton(onClick = onCall) {
          Icon(Icons.Filled.Call, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("התקשר שוב")
        }
        TextButton(onClick = onToggleFollowUp) {
          Icon(
            if (call.followUp) Icons.Filled.Flag else Icons.Outlined.Flag,
            contentDescription = null,
            tint = Color(0xFFB26A00),
          )
          Spacer(Modifier.width(6.dp))
          Text(if (call.followUp) "ביטול טיפול" else "לטיפול")
        }
        TextButton(onClick = onDelete) {
          Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
          Spacer(Modifier.width(6.dp))
          Text("מחק", color = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}

@Composable
private fun InfoCell(text: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

private fun formatDuration(sec: Int): String {
  val s = sec % 60
  val m = (sec / 60) % 60
  val h = sec / 3600
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
