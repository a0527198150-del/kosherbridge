package com.example.kosherbridge.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ImportContacts
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.kosherbridge.BridgeHub
import com.example.kosherbridge.data.ServiceLocator
import com.example.kosherbridge.data.local.ContactWithDetails
import com.example.kosherbridge.data.local.ContactsRepository
import kotlinx.coroutines.launch

private val HEBREW_LETTERS = "אבגדהוזחטיכלמנסעפצקרשת"
private val PHONE_LABELS = listOf("נייד", "בית", "עבודה", "אחר")
private val EMAIL_LABELS = listOf("אימייל", "עבודה", "אחר")

@Composable
fun ContactsScreen(onSnackbar: (String) -> Unit, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val repo = ServiceLocator.contacts
  var query by rememberSaveable { mutableStateOf("") }
  val list by repo.searchContacts(query).collectAsStateWithLifecycle(emptyList())
  var showAdd by remember { mutableStateOf(false) }
  var detailFor by remember { mutableStateOf<ContactWithDetails?>(null) }
  var editFor by remember { mutableStateOf<ContactWithDetails?>(null) }
  var deleteFor by remember { mutableStateOf<ContactWithDetails?>(null) }

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
        IconButton(onClick = { importLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
          Icon(Icons.Filled.ImportContacts, contentDescription = "ייבוא מאנשי הקשר")
        }
      }
      if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            if (query.isBlank()) "אין אנשי קשר.\nלחץ על + להוספה, או ייבא מהטלפון."
            else "לא נמצאו תוצאות ל\"$query\"",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      } else if (query.isBlank()) {
        GroupedContactList(list = list, onOpen = { detailFor = it }, onCall = { number -> BridgeHub.service?.dial(number) })
      } else {
        FlatContactList(list = list, onOpen = { detailFor = it }, onCall = { number -> BridgeHub.service?.dial(number) })
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
    ContactEditorDialog(
      title = "איש קשר חדש",
      initial = null,
      onSave = { name, phones, emails, notes, photo ->
        scope.launch {
          val ok = repo.addContact(name, phones, photo, emails, notes)
          if (!ok) onSnackbar("איש קשר עם מספר זה כבר קיים")
        }
        showAdd = false
      },
      onDismiss = { showAdd = false },
    )
  }
  editFor?.let { c ->
    ContactEditorDialog(
      title = "עריכת איש קשר",
      initial = c,
      onSave = { name, phones, emails, notes, photo ->
        scope.launch {
          if (photo != c.contact.photoUri) repo.deleteContactPhoto(c.contact.photoUri)
          repo.updateContact(c.contact.copy(name = name, photoUri = photo, notes = notes), phones, emails)
        }
        editFor = null
      },
      onDismiss = { editFor = null },
    )
  }
  detailFor?.let { c ->
    ContactDetailDialog(
      contact = c,
      onDismiss = { detailFor = null },
      onCall = { number ->
        BridgeHub.service?.dial(number)
        detailFor = null
      },
      onToggleFavorite = { scope.launch { repo.toggleFavorite(c.contact) } },
      onEdit = {
        editFor = c
        detailFor = null
      },
      onDelete = { deleteFor = c },
    )
  }
  deleteFor?.let { c ->
    AlertDialog(
      onDismissRequest = { deleteFor = null },
      title = { Text("למחוק את איש הקשר?") },
      text = { Text("${c.contact.name} וכל המספרים שלו יימחקו לצמיתות.") },
      confirmButton = {
        TextButton(
          onClick = {
            scope.launch { repo.deleteContact(c.contact) }
            deleteFor = null
            onSnackbar("איש הקשר נמחק")
          },
        ) { Text("מחק", fontWeight = FontWeight.Bold) }
      },
      dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("ביטול") } },
    )
  }
}

// ---------------------------------------------------------------------------
// Grouped list with alphabet index (Google Contacts style)
// ---------------------------------------------------------------------------

private sealed interface ListItem {
  data class Header(val letter: String) : ListItem
  data class Contact(val contact: ContactWithDetails) : ListItem
}

private fun buildItems(list: List<ContactWithDetails>): Pair<List<ListItem>, Map<String, Int>> {
  val items = mutableListOf<ListItem>()
  val index = mutableMapOf<String, Int>()
  val favorites = list.filter { it.contact.favorite }
  if (favorites.isNotEmpty()) {
    index["★"] = items.size
    items.add(ListItem.Header("★ מועדפים"))
    favorites.sortedBy { it.contact.name.lowercase() }.forEach { items.add(ListItem.Contact(it)) }
  }
  val rest = list.filter { !it.contact.favorite }.sortedBy { it.contact.name.lowercase() }
  rest.groupBy { indexLetter(it.contact.name) }.forEach { (letter, group) ->
    index[letter] = items.size
    items.add(ListItem.Header(letter))
    group.forEach { items.add(ListItem.Contact(it)) }
  }
  return items to index
}

/** First Hebrew letter of the name, or "#" for anything else (Latin/digits). */
private fun indexLetter(name: String): String {
  val first = name.trim().firstOrNull()?.toString() ?: return "#"
  return if (first in HEBREW_LETTERS) first else "#"
}

@Composable
private fun GroupedContactList(
  list: List<ContactWithDetails>,
  onOpen: (ContactWithDetails) -> Unit,
  onCall: (String) -> Unit,
) {
  val (items, index) = remember(list) { buildItems(list) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  BoxWithConstraints(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
      items(items, key = {
        when (it) {
          is ListItem.Header -> "h_${it.letter}"
          is ListItem.Contact -> "c_${it.contact.contact.id}"
        }
      }) { item ->
        when (item) {
          is ListItem.Header -> SectionHeader(item.letter)
          is ListItem.Contact -> ContactRow(item.contact, onClick = { onOpen(item.contact) }, onCall = onCall)
        }
      }
    }
    // Alphabet / favorites index bar on the end side (left in RTL). Only shown
    // when there is enough vertical space - on short landscape screens the
    // letters would be squeezed together and overlap.
    if (maxHeight >= 420.dp) {
      Column(
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .fillMaxHeight()
          .padding(vertical = 24.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        val letters = (listOf("★") + HEBREW_LETTERS.map { it.toString() }).filter { index.containsKey(it) }
        letters.forEach { letter ->
          Text(
            letter,
            modifier = Modifier
              .clickable { scope.launch { index[letter]?.let { listState.animateScrollToItem(it) } } }
              .padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

@Composable
private fun FlatContactList(
  list: List<ContactWithDetails>,
  onOpen: (ContactWithDetails) -> Unit,
  onCall: (String) -> Unit,
) {
  LazyColumn(Modifier.fillMaxSize()) {
    items(list, key = { it.contact.id }) { c ->
      ContactRow(c, onClick = { onOpen(c) }, onCall = onCall)
    }
  }
}

@Composable
private fun SectionHeader(letter: String) {
  Text(
    letter,
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
      .padding(horizontal = 16.dp, vertical = 6.dp),
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary,
  )
}

// ---------------------------------------------------------------------------
// Rows & avatars
// ---------------------------------------------------------------------------

@Composable
internal fun ContactAvatar(name: String, photoUri: String?, size: Dp, modifier: Modifier = Modifier) {
  if (!photoUri.isNullOrBlank()) {
    AsyncImage(
      model = photoUri,
      contentDescription = null,
      contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
private fun ContactRow(c: ContactWithDetails, onClick: () -> Unit, onCall: (String) -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ContactAvatar(c.contact.name, c.contact.photoUri, 44.dp)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(c.contact.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
      Text(
        c.primaryPhone,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (c.contact.favorite) {
      Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(4.dp))
    }
    IconButton(onClick = { onCall(c.primaryPhone) }, modifier = Modifier.size(40.dp)) {
      Icon(
        Icons.Filled.Call,
        contentDescription = "חייג ל${c.contact.name}",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

// ---------------------------------------------------------------------------
// Detail view (full-screen, Google Contacts style)
// ---------------------------------------------------------------------------

@Composable
private fun ContactDetailDialog(
  contact: ContactWithDetails,
  onDismiss: () -> Unit,
  onCall: (String) -> Unit,
  onToggleFavorite: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 560.dp)
        .clip(RoundedCornerShape(28.dp))
        .background(MaterialTheme.colorScheme.surface)
        .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleFavorite) {
          Icon(
            if (contact.contact.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = "מועדף",
            tint = if (contact.contact.favorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onEdit) {
          Icon(Icons.Filled.Edit, contentDescription = "עריכה", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Filled.Delete, contentDescription = "מחיקה", tint = MaterialTheme.colorScheme.error)
        }
      }
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        ContactAvatar(contact.contact.name, contact.contact.photoUri, 104.dp)
        Spacer(Modifier.height(12.dp))
        Text(
          contact.contact.name,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
        )
        if (contact.contact.favorite) {
          Text("מועדף", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFB300))
        }
        Spacer(Modifier.height(16.dp))
        contact.phoneLabels().forEach { (label, number) ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.medium)
              .clickable { onCall(number) }
              .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Filled.Call, contentDescription = "חייג", tint = MaterialTheme.colorScheme.primary)
          }
        }
        val emailRows = contact.emails.map { it.label to it.email } +
          listOfNotNull(contact.contact.email?.takeIf { it.isNotBlank() }?.let { "אימייל" to it })
        emailRows.distinctBy { it.second }.forEach { (label, email) ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column {
              Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(email, style = MaterialTheme.typography.bodyLarge)
            }
          }
        }
        contact.contact.notes?.takeIf { it.isNotBlank() }?.let { notes ->
          Text(
            notes,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(16.dp))
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Add / edit editor (full-screen, multi phone + email)
// ---------------------------------------------------------------------------

private data class PhoneField(val label: String = "נייד", val number: String = "")
private data class EmailField(val label: String = "אימייל", val email: String = "")

@Composable
private fun ContactEditorDialog(
  title: String,
  initial: ContactWithDetails?,
  onSave: (name: String, phones: List<Pair<String, String>>, emails: List<Pair<String, String>>, notes: String?, photo: String?) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember(initial) { mutableStateOf(initial?.contact?.name ?: "") }
  val phones = remember(initial) {
    mutableStateListOf<PhoneField>().apply {
      if (initial != null) {
        initial.phoneLabels().forEach { (label, number) -> add(PhoneField(label, number)) }
        if (isEmpty()) add(PhoneField())
      } else {
        add(PhoneField())
      }
    }
  }
  val emails = remember(initial) {
    mutableStateListOf<EmailField>().apply {
      if (initial != null) {
        initial.emails.forEach { add(EmailField(it.label, it.email)) }
        initial.contact.email?.takeIf { it.isNotBlank() }?.let { add(EmailField("אימייל", it)) }
        if (isEmpty()) add(EmailField())
      } else {
        add(EmailField())
      }
    }
  }
  var notes by remember(initial) { mutableStateOf(initial?.contact?.notes ?: "") }
  var photo by remember(initial) { mutableStateOf(initial?.contact?.photoUri) }
  val scope = rememberCoroutineScope()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
      scope.launch {
        ServiceLocator.contacts.saveContactPhoto(uri)?.let { photo = it }
      }
    }
  }
  val hasValidPhone = phones.any { it.number.isNotBlank() }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 560.dp)
        // Cap the height (90% of the screen) so the middle list scrolls and the
        // pinned action row is always on screen.
        .fillMaxHeight(0.9f)
        // Shrink when the keyboard opens so the pinned action buttons stay reachable.
        .imePadding()
        .clip(RoundedCornerShape(28.dp))
        .background(MaterialTheme.colorScheme.surface)
        .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
          Icon(Icons.Filled.Close, contentDescription = "סגירה")
        }
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ContactAvatar(name.ifEmpty { "?" }, photo, 88.dp)
            Row {
              TextButton(
                onClick = {
                  picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
              ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("בחר תמונה")
              }
              if (photo != null) {
                TextButton(onClick = { photo = null }) { Text("הסר") }
              }
            }
          }
        }
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("שם") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Text("טלפונים", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        phones.forEachIndexed { i, p ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            LabelPicker(selected = p.label, options = PHONE_LABELS, onSelect = { phones[i] = p.copy(label = it) })
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
              value = p.number,
              onValueChange = { phones[i] = p.copy(number = it) },
              placeholder = { Text("מספר") },
              singleLine = true,
              modifier = Modifier.weight(1f),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            IconButton(onClick = { if (phones.size > 1) phones.removeAt(i) }) {
              Icon(
                Icons.Filled.Close,
                contentDescription = "הסר מספר",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        TextButton(onClick = { phones.add(PhoneField()) }) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("הוסף מספר")
        }
        Text("אימיילים", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        emails.forEachIndexed { i, e ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            LabelPicker(selected = e.label, options = EMAIL_LABELS, onSelect = { emails[i] = e.copy(label = it) })
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
              value = e.email,
              onValueChange = { emails[i] = e.copy(email = it) },
              placeholder = { Text("אימייל") },
              singleLine = true,
              modifier = Modifier.weight(1f),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            IconButton(onClick = { if (emails.size > 1) emails.removeAt(i) }) {
              Icon(
                Icons.Filled.Close,
                contentDescription = "הסר אימייל",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        TextButton(onClick = { emails.add(EmailField()) }) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("הוסף אימייל")
        }
        OutlinedTextField(
          value = notes,
          onValueChange = { notes = it },
          label = { Text("הערות (אופציונלי)") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      // Action row is pinned below the scrollable fields so it is always visible
      // (never pushed off-screen on short landscape screens).
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("ביטול") }
        Spacer(Modifier.width(8.dp))
        TextButton(
          onClick = {
            val cleanPhones = phones.map { it.label to it.number.trim() }.filter { it.second.isNotEmpty() }
            val cleanEmails = emails.map { it.label to it.email.trim() }.filter { it.second.isNotEmpty() }
            if (name.isNotBlank() && cleanPhones.isNotEmpty()) {
              onSave(name.trim(), cleanPhones, cleanEmails, notes.trim().ifEmpty { null }, photo)
            }
          },
          enabled = name.isNotBlank() && hasValidPhone,
        ) { Text("שמור", fontWeight = FontWeight.Bold) }
      }
    }
  }
}

/** Compact label dropdown (נייד/בית/עבודה/אחר) - fixed width, cannot overflow. */
@Composable
private fun LabelPicker(selected: String, options: List<String>, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    OutlinedButton(
      onClick = { expanded = true },
      modifier = Modifier.width(88.dp),
      contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
      Text(selected, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      Icon(
        Icons.Filled.ArrowDropDown,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(option) },
          onClick = {
            onSelect(option)
            expanded = false
          },
        )
      }
    }
  }
}

internal fun initials(name: String): String =
  name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").ifEmpty { "?" }
