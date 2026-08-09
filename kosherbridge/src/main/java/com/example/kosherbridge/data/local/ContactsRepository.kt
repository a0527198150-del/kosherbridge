package com.example.kosherbridge.data.local

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.example.kosherbridge.bluetooth.CallDirection
import com.example.kosherbridge.bluetooth.CallState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ContactsRepository(
  private val db: AppDatabase,
  private val context: Context,
) {
  fun allContacts(): Flow<List<ContactEntity>> = db.contactDao().all()

  /** Contacts with all phones/emails, for the contacts UI. */
  fun contactsWithDetails(): Flow<List<ContactWithDetails>> = db.contactDao().allWithDetails()

  fun searchContacts(query: String): Flow<List<ContactWithDetails>> =
    if (query.isBlank()) db.contactDao().allWithDetails() else db.contactDao().searchWithDetails(query.trim())

  fun recentCalls(): Flow<List<CallLogEntity>> = db.callDao().all()

  fun searchCalls(query: String): Flow<List<CallLogEntity>> =
    if (query.isBlank()) db.callDao().all() else db.callDao().search(query.trim())

  /** Calls the user flagged for follow-up ("handle later"), newest first. */
  fun followUps(): Flow<List<CallLogEntity>> = db.callDao().followUps()

  suspend fun addContact(
    name: String,
    phones: List<Pair<String, String>>, // label to number
    photoUri: String? = null,
    emails: List<Pair<String, String>> = emptyList(),
    notes: String? = null,
  ): Boolean {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return false
    val cleanPhones = phones.map { it.first to it.second.trim() }.filter { it.second.isNotEmpty() }
    if (cleanPhones.isEmpty()) return false
    val primary = cleanPhones.first().second
    val normalized = normalizePhone(primary)
    if (normalized.isNotEmpty() && db.contactDao().byPhone(normalized) != null) return false
    if (normalized.isNotEmpty() && db.contactDao().phoneByNormalized(normalized) != null) return false

    val contactId = db.contactDao().insert(
      ContactEntity(
        name = cleanName,
        phone = primary,
        normalizedPhone = normalized,
        photoUri = photoUri,
        email = emails.firstOrNull()?.second?.trim()?.takeIf { it.isNotEmpty() },
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
      ),
    )
    syncPhonesAndEmails(contactId, cleanPhones, emails)
    return true
  }

  suspend fun updateContact(
    contact: ContactEntity,
    phones: List<Pair<String, String>>,
    emails: List<Pair<String, String>> = emptyList(),
  ) {
    val cleanPhones = phones.map { it.first to it.second.trim() }.filter { it.second.isNotEmpty() }
    val primary = cleanPhones.firstOrNull()?.second ?: contact.phone
    db.contactDao().update(
      contact.copy(
        phone = primary,
        normalizedPhone = normalizePhone(primary),
        email = emails.firstOrNull()?.second?.trim()?.takeIf { it.isNotEmpty() } ?: contact.email,
      ),
    )
    syncPhonesAndEmails(contact.id, cleanPhones, emails)
  }

  /** Rewrites the phone/email rows of a contact so they always match the editor state. */
  private suspend fun syncPhonesAndEmails(
    contactId: Long,
    phones: List<Pair<String, String>>,
    emails: List<Pair<String, String>>,
  ) {
    db.contactDao().deletePhonesFor(contactId)
    phones.forEach { (label, number) ->
      val n = normalizePhone(number)
      db.contactDao().insertPhone(
        ContactPhoneEntity(contactId = contactId, label = label, number = number, normalizedPhone = n),
      )
    }
    db.contactDao().deleteEmailsFor(contactId)
    emails.forEach { (label, email) ->
      db.contactDao().insertEmail(ContactEmailEntity(contactId = contactId, label = label, email = email.trim()))
    }
  }

  suspend fun deleteContact(contact: ContactEntity) {
    deleteContactPhoto(contact.photoUri)
    db.contactDao().delete(contact) // phones/emails cascade
  }

  suspend fun clearAllContacts() = db.contactDao().clear()

  suspend fun toggleFavorite(contact: ContactEntity) =
    db.contactDao().update(contact.copy(favorite = !contact.favorite))

  /** Name of the contact holding a given number (used for caller-id / call log). */
  suspend fun nameFor(number: String?): String? = contactFor(number)?.name

  /** Full contact for a call number, used e.g. to show the photo on the incoming-call screen. */
  suspend fun contactFor(number: String?): ContactEntity? {
    if (number.isNullOrBlank()) return null
    val normalized = normalizePhone(number)
    if (normalized.isEmpty()) return null
    return db.contactDao().byPhone(normalized) ?: db.contactDao().contactByPhoneNormalized(normalized)
  }

  suspend fun logCall(
    number: String,
    name: String?,
    direction: CallDirection,
    state: CallState,
  ): Long = db.callDao().insert(
    CallLogEntity(
      number = number,
      name = name,
      direction = direction.name,
      state = state.name,
      timestamp = System.currentTimeMillis(),
    ),
  )

  /** Marks a call log entry as finished: records whether it was missed and its duration. */
  suspend fun finishCall(id: Long, missed: Boolean, durationSec: Int) =
    db.callDao().finishCall(id, CallState.IDLE.name, missed, durationSec)

  /** Toggles the user's "call me back / handle later" flag on a call log entry. */
  suspend fun markFollowUp(id: Long, value: Boolean) = db.callDao().updateFollowUp(id, value)

  suspend fun deleteCall(id: Long) = db.callDao().deleteById(id)

  suspend fun clearCallLog() = db.callDao().clear()

  /** Copies a picked image (content Uri) into private app storage and returns its file path. */
  suspend fun saveContactPhoto(source: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
      val dir = File(context.filesDir, "contact_photos").apply { mkdirs() }
      val dest = File(dir, "photo_${System.currentTimeMillis()}.jpg")
      context.contentResolver.openInputStream(source)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
      } ?: return@withContext null
      dest.absolutePath
    }.getOrNull()
  }

  /** Deletes a stored contact photo file (best-effort, path may come from the DB). */
  fun deleteContactPhoto(path: String?) {
    if (path.isNullOrBlank()) return
    runCatching { File(path).delete() }
  }

  /**
   * Imports contacts from the device address book.
   * Caller must hold READ_CONTACTS. Returns how many new contacts were added.
   */
  suspend fun importFromDevice(): Int = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    var added = 0
    resolver.query(uri, projection, null, null, null)?.use { cursor ->
      val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
      val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
      while (cursor.moveToNext()) {
        if (nameIdx < 0 || numIdx < 0) break
        val name = cursor.getString(nameIdx) ?: continue
        val number = cursor.getString(numIdx) ?: continue
        val normalized = normalizePhone(number)
        if (normalized.isEmpty()) continue
        val existing = db.contactDao().byPhone(normalized) ?: db.contactDao().phoneByNormalized(normalized)
        if (existing == null) {
          val id = db.contactDao().insert(
            ContactEntity(name = name, phone = number.trim(), normalizedPhone = normalized),
          )
          db.contactDao().insertPhone(
            ContactPhoneEntity(contactId = id, label = "נייד", number = number.trim(), normalizedPhone = normalized),
          )
          added++
        }
      }
    }
    added
  }

  // --- JSON backup / restore (SAF documents) ---

  /** Exports all contacts to a JSON file (no photos). Returns the number of contacts written. */
  suspend fun exportContactsTo(uri: Uri): Int = withContext(Dispatchers.IO) {
    val contacts = db.contactDao().allWithDetails().first()
    val array = JSONArray()
    contacts.forEach { c ->
      val obj = JSONObject()
      obj.put("name", c.contact.name)
      obj.put("favorite", c.contact.favorite)
      obj.put("notes", c.contact.notes ?: "")
      val phones = JSONArray()
      (c.phoneLabels()).forEach { (label, number) ->
        phones.put(JSONObject().put("label", label).put("number", number))
      }
      obj.put("phones", phones)
      val emails = JSONArray()
      val emailRows = c.emails.map { it.label to it.email } +
        listOfNotNull(c.contact.email?.takeIf { it.isNotBlank() }?.let { "אימייל" to it })
      emailRows.distinctBy { it.second }.forEach { (label, email) ->
        emails.put(JSONObject().put("label", label).put("email", email))
      }
      obj.put("emails", emails)
      array.put(obj)
    }
    context.contentResolver.openOutputStream(uri)?.use { out ->
      out.write(array.toString(2).toByteArray())
    } ?: throw IllegalStateException("לא ניתן היה לכתוב את הקובץ")
    contacts.size
  }

  /** Imports contacts from a JSON backup file. Returns how many new contacts were added. */
  suspend fun importContactsFrom(uri: Uri): Int = withContext(Dispatchers.IO) {
    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
      ?: throw IllegalStateException("לא ניתן היה לקרוא את הקובץ")
    val array = JSONArray(text)
    var added = 0
    for (i in 0 until array.length()) {
      val obj = array.getJSONObject(i)
      val name = obj.optString("name", "").trim()
      if (name.isEmpty()) continue
      val phones = mutableListOf<Pair<String, String>>()
      val phonesArr = obj.optJSONArray("phones") ?: JSONArray()
      for (j in 0 until phonesArr.length()) {
        val p = phonesArr.getJSONObject(j)
        val number = p.optString("number", "").trim()
        if (number.isNotEmpty()) phones.add(p.optString("label", "נייד") to number)
      }
      if (phones.isEmpty()) continue
      val emails = mutableListOf<Pair<String, String>>()
      val emailsArr = obj.optJSONArray("emails") ?: JSONArray()
      for (j in 0 until emailsArr.length()) {
        val e = emailsArr.getJSONObject(j)
        val email = e.optString("email", "").trim()
        if (email.isNotEmpty()) emails.add(e.optString("label", "אימייל") to email)
      }
      val existing = db.contactDao().byPhone(normalizePhone(phones.first().second))
      if (existing == null) {
        val id = db.contactDao().insert(
          ContactEntity(
            name = name,
            phone = phones.first().second,
            normalizedPhone = normalizePhone(phones.first().second),
            favorite = obj.optBoolean("favorite", false),
            notes = obj.optString("notes", "").takeIf { it.isNotEmpty() },
            email = emails.firstOrNull()?.second,
          ),
        )
        db.contactDao().deletePhonesFor(id)
        phones.forEach { (label, number) ->
          db.contactDao().insertPhone(
            ContactPhoneEntity(contactId = id, label = label, number = number, normalizedPhone = normalizePhone(number)),
          )
        }
        db.contactDao().deleteEmailsFor(id)
        emails.forEach { (label, email) ->
          db.contactDao().insertEmail(ContactEmailEntity(contactId = id, label = label, email = email))
        }
        added++
      }
    }
    added
  }

  companion object {
    /** Normalizes an Israeli phone number for deduplication. */
    fun normalizePhone(raw: String): String {
      val digits = raw.filter { it.isDigit() }
      return when {
        digits.isEmpty() -> ""
        digits.startsWith("0") && digits.length == 10 -> "972" + digits.drop(1)
        else -> digits
      }
    }
  }
}
