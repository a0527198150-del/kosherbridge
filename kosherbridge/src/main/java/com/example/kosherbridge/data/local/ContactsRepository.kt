package com.example.kosherbridge.data.local

import android.content.Context
import android.provider.ContactsContract
import com.example.kosherbridge.bluetooth.CallDirection
import com.example.kosherbridge.bluetooth.CallState
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

  suspend fun addContact(name: String, phone: String) {
    val clean = phone.trim()
    if (name.isBlank() || clean.isEmpty()) return
    val normalized = normalizePhone(clean)
    if (normalized.isNotEmpty() && db.contactDao().byPhone(normalized) != null) return
    db.contactDao().insert(
      ContactEntity(name = name.trim(), phone = clean, normalizedPhone = normalized),
    )
  }

  suspend fun updateContact(contact: ContactEntity) = db.contactDao().update(contact)

  suspend fun deleteContact(contact: ContactEntity) = db.contactDao().delete(contact)

  suspend fun toggleFavorite(contact: ContactEntity) =
    db.contactDao().update(contact.copy(favorite = !contact.favorite))

  suspend fun nameFor(number: String?): String? {
    if (number.isNullOrBlank()) return null
    val normalized = normalizePhone(number)
    if (normalized.isEmpty()) return null
    return db.contactDao().byPhone(normalized)?.name
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

  suspend fun updateCallState(id: Long, state: CallState) = db.callDao().updateState(id, state.name)

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
