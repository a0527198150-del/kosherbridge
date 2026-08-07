package com.example.kosherbridge.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ImportContacts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.ContactEntity
import com.example.kosherbridge.data.local.ContactsRepository
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val repo = ServiceLocator.contacts
  var query by rememberSaveable { mutableStateOf("") }
  val list by repo.searchContacts(query).collectAsStateWithLifecycle(emptyList())
  var showAdd by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<ContactEntity?>(null) }
  var actionsFor by remember { mutableStateOf<ContactEntity?>(null) }

  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      scope.launch {
        val n = repo.importFromDevice()
        onSnackbar(if (n > 0) "יובאו $n אנשי קשר" else "לא נמצאו אנשי קשר חדשים")
      }
    } else {
      onSnackbar("אין הרשאת גישה לאנשי הקשר")
    }
  }

  Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("חיפוש איש קשר") },
          leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
          singleLine = true,
          modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { importLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
          Icon(Icons.Filled.ImportContacts, contentDescription = "ייבוא מאנשי הקשר")
        }
      }
      LazyColumn(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
          item {
            Text(
              "אין אנשי קשר.\nלחץ על + להוספה, או ייבא מהטלפון.",
              modifier = Modifier.padding(16.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        items(list, key = { it.id }) { c ->
          ContactRow(c) { actionsFor = c }
          HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
          )
        }
      }
    }
    FloatingActionButton(
      onClick = { showAdd = true },
      modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
    ) {
      Icon(Icons.Filled.Add, contentDescription = "הוספת איש קשר")
    }
  }

  if (showAdd) {
    ContactEditDialog(
      title = "איש קשר חדש",
      initialName = "",
      initialPhone = "",
      onSave = { name, phone ->
        scope.launch { repo.addContact(name, phone) }
        showAdd = false
      },
      onDismiss = { showAdd = false },
    )
  }
  editing?.let { c ->
    ContactEditDialog(
      title = "עריכת איש קשר",
      initialName = c.name,
      initialPhone = c.phone,
      onSave = { name, phone ->
        scope.launch {
          repo.updateContact(
            c.copy(name = name, phone = phone, normalizedPhone = ContactsRepository.normalizePhone(phone)),
          )
        }
        editing = null
      },
      onDismiss = { editing = null },
    )
  }
  actionsFor?.let { c ->
    ContactActionsDialog(
      contact = c,
      onDismiss = { actionsFor = null },
      onCall = {
        BridgeHub.service?.dial(c.phone)
        actionsFor = null
      },
      onToggleFavorite = {
        scope.launch { repo.toggleFavorite(c) }
        actionsFor = null
      },
      onEdit = {
        editing = c
        actionsFor = null
      },
      onDelete = {
        scope.launch { repo.deleteContact(c) }
        actionsFor = null
      },
    )
  }
}

@Composable
private fun ContactRow(c: ContactEntity, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        initials(c.name),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Bold,
      )
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
      Text(
        c.phone,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (c.favorite) {
      Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFB300))
    }
  }
}

@Composable
private fun ContactEditDialog(
  title: String,
  initialName: String,
  initialPhone: String,
  onSave: (String, String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  var phone by remember { mutableStateOf(initialPhone) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("שם") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = phone,
          onValueChange = { phone = it },
          label = { Text("מספר טלפון") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onSave(name, phone) },
        enabled = name.isNotBlank() && phone.isNotBlank(),
      ) { Text("שמור") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
  )
}

@Composable
private fun ContactActionsDialog(
  contact: ContactEntity,
  onDismiss: () -> Unit,
  onCall: () -> Unit,
  onToggleFavorite: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(contact.name) },
    text = {
      Column {
        Text(contact.phone, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(8.dp))
        ActionButton(Icons.Filled.Call, "חייג") { onCall() }
        ActionButton(if (contact.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (contact.favorite) "הסר מהמועדפים" else "הוסף למועדפים") { onToggleFavorite() }
        ActionButton(Icons.Filled.Edit, "ערוך") { onEdit() }
        ActionButton(Icons.Filled.Delete, "מחק", error = true) { onDelete() }
      }
    },
    confirmButton = {},
  )
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, error: Boolean = false, onClick: () -> Unit) {
  TextButton(
    onClick = onClick,
    colors = ButtonDefaults.textButtonColors(
      contentColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    ),
  ) {
    Icon(icon, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text(label)
  }
}

internal fun initials(name: String): String =
  name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").ifEmpty { "?" }
