package com.example.kosherbridge.data.local

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.example.kosherbridge.bluetooth.CallDirection
import com.example.kosherbridge.bluetooth.CallState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ContactsRepository(
  private val db: AppDatabase,
  private val context: Context,
) {
  fun allContacts(): Flow<List<ContactEntity>> = db.contactDao().all()

  fun searchContacts(query: String): Flow<List<ContactEntity>> =
    if (query.isBlank()) allContacts() else db.contactDao().search(query.trim())

  fun recentCalls(): Flow<List<CallLogEntity>> = db.callDao().all()

  /** Calls the user flagged for follow-up ("handle later"), newest first. */
  fun followUps(): Flow<List<CallLogEntity>> = db.callDao().followUps()

  suspend fun addContact(
    name: String,
    phone: String,
    photoUri: String? = null,
    email: String? = null,
    notes: String? = null,
  ) {
    val clean = phone.trim()
    if (name.isBlank() || clean.isEmpty()) return
    val normalized = normalizePhone(clean)
    if (normalized.isNotEmpty() && db.contactDao().byPhone(normalized) != null) return
    db.contactDao().insert(
      ContactEntity(
        name = name.trim(),
        phone = clean,
        normalizedPhone = normalized,
        photoUri = photoUri,
        email = email,
        notes = notes,
      ),
    )
  }

  suspend fun updateContact(contact: ContactEntity) = db.contactDao().update(contact)

  suspend fun deleteContact(contact: ContactEntity) {
    deleteContactPhoto(contact.photoUri)
    db.contactDao().delete(contact)
  }

  suspend fun toggleFavorite(contact: ContactEntity) =
    db.contactDao().update(contact.copy(favorite = !contact.favorite))

  suspend fun nameFor(number: String?): String? {
    if (number.isNullOrBlank()) return null
    val normalized = normalizePhone(number)
    if (normalized.isEmpty()) return null
    return db.contactDao().byPhone(normalized)?.name
  }

  /** Full contact for a call number, used e.g. to show the photo on the incoming-call screen. */
  suspend fun contactFor(number: String?): ContactEntity? {
    if (number.isNullOrBlank()) return null
    val normalized = normalizePhone(number)
    if (normalized.isEmpty()) return null
    return db.contactDao().byPhone(normalized)
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
        if (db.contactDao().byPhone(normalized) == null) {
          db.contactDao().insert(
            ContactEntity(name = name, phone = number.trim(), normalizedPhone = normalized),
          )
          added++
        }
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
