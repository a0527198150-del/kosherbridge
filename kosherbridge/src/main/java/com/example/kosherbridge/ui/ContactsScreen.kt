package com.example.kosherbridge.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ImportContacts
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
  var detailFor by remember { mutableStateOf<ContactEntity?>(null) }

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
          ContactRow(c) { detailFor = c }
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
      initialEmail = "",
      initialNotes = "",
      initialPhoto = null,
      onSave = { name, phone, email, notes, photo ->
        scope.launch { repo.addContact(name, phone, photo, email, notes) }
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
      initialEmail = c.email ?: "",
      initialNotes = c.notes ?: "",
      initialPhoto = c.photoUri,
      onSave = { name, phone, email, notes, photo ->
        scope.launch {
          if (photo != c.photoUri) repo.deleteContactPhoto(c.photoUri)
          repo.updateContact(
            c.copy(
              name = name,
              phone = phone,
              normalizedPhone = ContactsRepository.normalizePhone(phone),
              photoUri = photo,
              email = email,
              notes = notes,
            ),
          )
        }
        editing = null
      },
      onDismiss = { editing = null },
    )
  }
  detailFor?.let { c ->
    ContactDetailDialog(
      contact = c,
      onDismiss = { detailFor = null },
      onCall = {
        BridgeHub.service?.dial(c.phone)
        detailFor = null
      },
      onToggleFavorite = {
        scope.launch { repo.toggleFavorite(c) }
        detailFor = null
      },
      onEdit = {
        editing = c
        detailFor = null
      },
      onDelete = {
        scope.launch { repo.deleteContact(c) }
        detailFor = null
      },
    )
  }
}

// --- Rows & dialogs ---

@Composable
internal fun ContactAvatar(name: String, photoUri: String?, size: Dp, modifier: Modifier = Modifier) {
  if (!photoUri.isNullOrBlank()) {
    AsyncImage(
      model = photoUri,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = modifier.size(size).clip(CircleShape),
    )
  } else {
    val fontSize = (size.value * 0.34f).sp
    Box(
      modifier = modifier
        .size(size)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        initials(name),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
      )
    }
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
    ContactAvatar(c.name, c.photoUri, 44.dp)
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

/** Google-Contacts-style detail view: big avatar, contact info and quick actions. */
@Composable
private fun ContactDetailDialog(
  contact: ContactEntity,
  onDismiss: () -> Unit,
  onCall: () -> Unit,
  onToggleFavorite: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        ContactAvatar(contact.name, contact.photoUri, 104.dp)
        Spacer(Modifier.height(12.dp))
        Text(
          contact.name,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
        )
        if (contact.favorite) {
          Text(
            "מועדף",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFFB300),
          )
        }
        Spacer(Modifier.height(12.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onCall)
            .padding(vertical = 10.dp, horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.width(12.dp))
          Text(
            contact.phone,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
          )
        }
        contact.email?.takeIf { it.isNotBlank() }?.let { email ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(email, style = MaterialTheme.typography.bodyLarge)
          }
        }
        contact.notes?.takeIf { it.isNotBlank() }?.let { notes ->
          Text(
            notes,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(12.dp))
        ActionButton(Icons.Filled.Call, "חייג") { onCall() }
        ActionButton(
          if (contact.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
          if (contact.favorite) "הסר מהמועדפים" else "הוסף למועדפים",
        ) { onToggleFavorite() }
        ActionButton(Icons.Filled.Edit, "ערוך") { onEdit() }
        ActionButton(Icons.Filled.Delete, "מחק", error = true) { onDelete() }
      }
    },
    confirmButton = {},
  )
}

@Composable
private fun ContactEditDialog(
  title: String,
  initialName: String,
  initialPhone: String,
  initialEmail: String,
  initialNotes: String,
  initialPhoto: String?,
  onSave: (name: String, phone: String, email: String?, notes: String?, photo: String?) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember(initialName) { mutableStateOf(initialName) }
  var phone by remember(initialPhone) { mutableStateOf(initialPhone) }
  var email by remember(initialEmail) { mutableStateOf(initialEmail) }
  var notes by remember(initialNotes) { mutableStateOf(initialNotes) }
  var photo by remember(initialPhoto) { mutableStateOf(initialPhoto) }
  val scope = rememberCoroutineScope()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
      scope.launch {
        ServiceLocator.contacts.saveContactPhoto(uri)?.let { photo = it }
      }
    }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ContactAvatar(name.ifEmpty { "?" }, photo, 88.dp)
        Row {
          TextButton(
            onClick = {
              picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
              )
            },
          ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("בחר תמונה")
          }
          if (photo != null) {
            TextButton(onClick = { photo = null }) { Text("הסר תמונה") }
          }
        }
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
        OutlinedTextField(
          value = email,
          onValueChange = { email = it },
          label = { Text("אימייל (אופציונלי)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        OutlinedTextField(
          value = notes,
          onValueChange = { notes = it },
          label = { Text("הערות (אופציונלי)") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onSave(name.trim(), phone.trim(), email.trim().ifEmpty { null }, notes.trim().ifEmpty { null }, photo) },
        enabled = name.isNotBlank() && phone.isNotBlank(),
      ) { Text("שמור") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
  )
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, error: Boolean = false, onClick: () -> Unit) {
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
