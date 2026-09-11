package com.example.kosherbridge.data.local

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.example.kosherbridge.bluetooth.CallDirection
import com.example.kosherbridge.bluetooth.CallState
import java.io.File
import kotlinx.coroutines.CancellationException
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
  ): Boolean {
    val cleanPhones = phones.map { it.first to it.second.trim() }.filter { it.second.isNotEmpty() }
    val primary = cleanPhones.firstOrNull()?.second ?: contact.phone
    val normalized = normalizePhone(primary)
    // addContact() rejects duplicate numbers; editing must too, otherwise two
    // contacts can end up sharing a normalized number and caller-id resolves to
    // whichever row Room returns first.
    if (normalized.isNotEmpty()) {
      val byPrimary = db.contactDao().byPhone(normalized)
      val bySecondary = db.contactDao().phoneByNormalized(normalized)
      if ((byPrimary != null && byPrimary.id != contact.id) ||
        (bySecondary != null && bySecondary.contactId != contact.id)
      ) {
        return false
      }
    }
    // The photo the row USED to point at, before the edit replaced it. Photos
    // are copied into private storage by saveContactPhoto, and only
    // deleteContact ever cleaned one up - so every time a contact's picture was
    // changed, the old JPEG stayed on disk for the life of the install,
    // unreferenced and invisible.
    val previousPhoto = db.contactDao().byId(contact.id)?.photoUri
    db.contactDao().update(
      contact.copy(
        phone = primary,
        normalizedPhone = normalized,
        email = emails.firstOrNull()?.second?.trim()?.takeIf { it.isNotEmpty() } ?: contact.email,
      ),
    )
    if (previousPhoto != null && previousPhoto != contact.photoUri) deleteContactPhoto(previousPhoto)
    syncPhonesAndEmails(contact.id, cleanPhones, emails)
    return true
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

  /**
   * Deletes every contact - and the photo files that belonged to them.
   *
   * Only the rows used to go. The JPEGs copied into private storage stayed
   * behind for ever: invisible, unreferenced, and counted against the app's
   * storage on a player that has very little of it.
   */
  suspend fun clearAllContacts() {
    val photos = db.contactDao().allWithDetails().first().mapNotNull { it.contact.photoUri }
    db.contactDao().clear()
    withContext(Dispatchers.IO) { photos.forEach { deleteContactPhoto(it) } }
  }

  suspend fun toggleFavorite(contact: ContactEntity) =
    db.contactDao().update(contact.copy(favorite = !contact.favorite))

  /** Name of the contact holding a given number (used for caller-id / call log). */
  suspend fun nameFor(number: String?): String? = contactFor(number)?.name

  /** Full contact for a call number, used e.g. to show the photo on the incoming-call screen. */
  suspend fun contactFor(number: String?): ContactEntity? {
    if (number.isNullOrBlank()) return null
    // Try every form the number could be stored as, so a contact saved under
    // an older normalisation is still recognised without a data migration.
    for (candidate in phoneVariants(number)) {
      db.contactDao().byPhone(candidate)?.let { return it }
      db.contactDao().contactByPhoneNormalized(candidate)?.let { return it }
    }
    return null
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

  /** One address-book entry being assembled during [importFromDevice]. */
  private class DeviceContact(val name: String) {
    /** label to number, in the order the address book returned them. */
    val phones = mutableListOf<Pair<String, String>>()
  }

  /**
   * Imports contacts from the device address book.
   * Caller must hold READ_CONTACTS. Returns how many new contacts were added.
   *
   * The Phone table has one row per NUMBER, so a contact with a mobile and a
   * landline arrives as two rows - and importing row by row turned them into
   * two separate contacts with the same name. Rows are grouped by contact id
   * instead, into the one contact with two numbers that the contacts screen
   * has always been able to display and that caller ID matches on either
   * number.
   */
  suspend fun importFromDevice(): Int = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER,
      ContactsContract.CommonDataKinds.Phone.TYPE,
    )
    val byContact = LinkedHashMap<Long, DeviceContact>()
    resolver.query(uri, projection, null, null, null)?.use { cursor ->
      val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
      val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
      val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
      val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
      if (idIdx < 0 || nameIdx < 0 || numIdx < 0) return@use
      while (cursor.moveToNext()) {
        val contactId = cursor.getLong(idIdx)
        val name = cursor.getString(nameIdx)?.trim().orEmpty()
        val number = cursor.getString(numIdx)?.trim().orEmpty()
        if (name.isEmpty() || number.isEmpty()) continue
        if (normalizePhone(number).isEmpty()) continue
        val entry = byContact.getOrPut(contactId) { DeviceContact(name) }
        val label = if (typeIdx >= 0) phoneTypeLabel(cursor.getInt(typeIdx)) else "נייד"
        // The address book repeats the same number across linked accounts.
        if (entry.phones.none { normalizePhone(it.second) == normalizePhone(number) }) {
          entry.phones += label to number
        }
      }
    }

    var added = 0
    for (entry in byContact.values) {
      if (entry.phones.isEmpty()) continue
      // Skip when ANY of this entry's numbers is already known, so re-running
      // the import does not create a second copy of a contact whose secondary
      // number was the one already stored.
      val alreadyExists = entry.phones.any { (_, number) ->
        val n = normalizePhone(number)
        n.isNotEmpty() &&
          (db.contactDao().byPhone(n) != null || db.contactDao().phoneByNormalized(n) != null)
      }
      if (alreadyExists) continue
      val primary = entry.phones.first().second
      val id = db.contactDao().insert(
        ContactEntity(
          name = entry.name,
          phone = primary,
          normalizedPhone = normalizePhone(primary),
        ),
      )
      entry.phones.forEach { (label, number) ->
        db.contactDao().insertPhone(
          ContactPhoneEntity(
            contactId = id,
            label = label,
            number = number,
            normalizedPhone = normalizePhone(number),
          ),
        )
      }
      added++
    }
    added
  }

  /** The address book's phone type as the label this app shows. */
  private fun phoneTypeLabel(type: Int): String = when (type) {
    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "נייד"
    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "בית"
    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "עבודה"
    else -> "אחר"
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
      // One malformed entry must not cost the user the other four hundred.
      // Every getJSONObject below throws on a backup whose shape is slightly
      // off - an entry that is a bare string, a "phones" array of strings
      // instead of objects - and the exception propagated out of the loop, so
      // the whole import failed with a raw JSONException and nothing imported.
      added += try {
        importEntry(array.getJSONObject(i))
      } catch (cancelled: CancellationException) {
        throw cancelled // never swallow cancellation: the user closed the screen
      } catch (_: Throwable) {
        0
      }
    }
    added
  }

  /** Imports one backup entry. Returns 1 when a new contact was added. */
  private suspend fun importEntry(obj: JSONObject): Int {
    val name = obj.optString("name", "").trim()
    if (name.isEmpty()) return 0
    val phones = mutableListOf<Pair<String, String>>()
    val phonesArr = obj.optJSONArray("phones") ?: JSONArray()
    for (j in 0 until phonesArr.length()) {
      val p = phonesArr.getJSONObject(j)
      val number = p.optString("number", "").trim()
      if (number.isNotEmpty()) phones.add(p.optString("label", "נייד") to number)
    }
    if (phones.isEmpty()) return 0
    val emails = mutableListOf<Pair<String, String>>()
    val emailsArr = obj.optJSONArray("emails") ?: JSONArray()
    for (j in 0 until emailsArr.length()) {
      val e = emailsArr.getJSONObject(j)
      val email = e.optString("email", "").trim()
      if (email.isNotEmpty()) emails.add(e.optString("label", "אימייל") to email)
    }
    // Skip if ANY of this entry's numbers already exists - either as a
    // contact's primary number (contacts.normalizedPhone) or as any
    // secondary number (contact_phones.normalizedPhone).
    val alreadyExists = phones.asSequence()
      .map { normalizePhone(it.second) }
      .filter { it.isNotEmpty() }
      .any { n -> db.contactDao().byPhone(n) != null || db.contactDao().phoneByNormalized(n) != null }
    if (!alreadyExists) {
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
      return 1
    }
    return 0
  }

  companion object {
    /** Normalizes an Israeli phone number for deduplication. */
    fun normalizePhone(raw: String): String {
      var digits = raw.filter { it.isDigit() }
      if (digits.isEmpty()) return ""
      // "00" is the international access prefix - 00972... is the same number
      // as +972..., and leaving it in made the two forms different contacts.
      if (digits.startsWith("00")) digits = digits.drop(2)
      return when {
        // Local Israeli form: a leading 0 plus 8 digits (landline, e.g.
        // 02-123-4567) or 9 digits (mobile, e.g. 050-123-4567). Only the
        // 10-digit mobile case used to be converted, so a landline saved as
        // "02-1234567" never matched an incoming caller ID of "+97221234567"
        // and showed up as an unknown number.
        digits.startsWith("0") && digits.length in 9..10 -> "972" + digits.drop(1)
        else -> digits
      }
    }

    /**
     * Every stored form the same number could have, newest normalisation
     * first. Rows written by older versions of this app kept the raw digits
     * for landlines, so a lookup that only tries the current normalisation
     * would silently stop recognising contacts saved back then.
     */
    fun phoneVariants(raw: String): List<String> {
      val normalized = normalizePhone(raw)
      if (normalized.isEmpty()) return emptyList()
      val digits = raw.filter { it.isDigit() }
      val local = if (normalized.startsWith("972")) "0" + normalized.removePrefix("972") else null
      return listOfNotNull(normalized, digits.takeIf { it != normalized }, local)
        .filter { it.isNotEmpty() }
        .distinct()
    }
  }
}
