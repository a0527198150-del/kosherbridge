package com.example.kosherbridge.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "contacts")
data class ContactEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  // Primary phone, kept in sync with the first entry of contact_phones so the
  // existing dialing / caller-id code paths keep working unchanged.
  val phone: String,
  val normalizedPhone: String = "",
  val favorite: Boolean = false,
  val photoUri: String? = null, // internal file path of the contact photo (if any)
  val email: String? = null,    // primary email, mirrors the first entry of contact_emails
  val notes: String? = null,
)

/** An additional (or primary) phone number of a contact, with a display label. */
@Entity(
  tableName = "contact_phones",
  foreignKeys = [
    ForeignKey(
      entity = ContactEntity::class,
      parentColumns = ["id"],
      childColumns = ["contactId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("contactId"), Index("normalizedPhone")],
)
data class ContactPhoneEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val contactId: Long,
  val label: String = "נייד", // נייד / בית / עבודה / אחר
  val number: String,
  @ColumnInfo(defaultValue = "''") val normalizedPhone: String = "",
)

/** An email address of a contact, with a display label. */
@Entity(
  tableName = "contact_emails",
  foreignKeys = [
    ForeignKey(
      entity = ContactEntity::class,
      parentColumns = ["id"],
      childColumns = ["contactId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("contactId")],
)
data class ContactEmailEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val contactId: Long,
  val label: String = "אימייל", // אימייל / עבודה / אחר
  val email: String,
)

/** Contact together with all its phone numbers and emails (one DB round-trip). */
data class ContactWithDetails(
  @Embedded val contact: ContactEntity,
  @Relation(parentColumn = "id", entityColumn = "contactId")
  val phones: List<ContactPhoneEntity> = emptyList(),
  @Relation(parentColumn = "id", entityColumn = "contactId")
  val emails: List<ContactEmailEntity> = emptyList(),
) {
  /** Primary phone for dialing; falls back to the first extra number if empty. */
  val primaryPhone: String
    get() = contact.phone.ifBlank { phones.firstOrNull()?.number.orEmpty() }

  fun phoneLabels(): List<Pair<String, String>> {
    // The primary number's label comes from its phone row (if it has one); the
    // other rows follow. Entries are de-duplicated by number and kept non-empty.
    val byNumber = phones.associateBy { it.number }
    val primaryLabel = byNumber[contact.phone]?.label ?: "נייד"
    val extra = phones.filter { it.number != contact.phone }.map { it.label to it.number }
    return (listOf(primaryLabel to contact.phone) + extra)
      .distinctBy { it.second }
      .filter { it.second.isNotBlank() }
  }
}

@Entity(tableName = "calls")
data class CallLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val number: String,
  val name: String?,
  val direction: String, // INCOMING / OUTGOING
  val state: String,     // CallState name
  val timestamp: Long,
  // DEFAULT 0 must match the MIGRATION_1_2 ALTER TABLE statements exactly,
  // otherwise Room's migration validation fails at runtime.
  @ColumnInfo(defaultValue = "0") val missed: Boolean = false,   // incoming call that was never answered
  @ColumnInfo(defaultValue = "0") val followUp: Boolean = false, // marked by the user for follow-up later
  @ColumnInfo(defaultValue = "0") val durationSec: Int = 0,      // conversation length in seconds (0 = not answered)
)
