package com.example.kosherbridge.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
  @Query("SELECT * FROM contacts ORDER BY favorite DESC, name COLLATE NOCASE")
  fun all(): Flow<List<ContactEntity>>

  @Query("SELECT * FROM contacts WHERE name LIKE '%' || :q || '%' OR phone LIKE '%' || :q || '%' ORDER BY favorite DESC, name COLLATE NOCASE")
  fun search(q: String): Flow<List<ContactEntity>>

  @Query("SELECT * FROM contacts WHERE normalizedPhone = :normalized LIMIT 1")
  suspend fun byPhone(normalized: String): ContactEntity?

  @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
  suspend fun byId(id: Long): ContactEntity?

  @Insert
  suspend fun insert(contact: ContactEntity): Long

  @Update
  suspend fun update(contact: ContactEntity)

  @Delete
  suspend fun delete(contact: ContactEntity)

  @Query("DELETE FROM contacts")
  suspend fun clear()

  // --- contacts together with all phones/emails (for the contacts UI) ---

  @Transaction
  @Query("SELECT * FROM contacts ORDER BY favorite DESC, name COLLATE NOCASE")
  fun allWithDetails(): Flow<List<ContactWithDetails>>

  @Transaction
  @Query(
    "SELECT * FROM contacts c WHERE c.name LIKE '%' || :q || '%' OR c.normalizedPhone LIKE '%' || :q || '%' " +
      "OR EXISTS (SELECT 1 FROM contact_phones p WHERE p.contactId = c.id AND p.normalizedPhone LIKE '%' || :q || '%') " +
      "ORDER BY c.favorite DESC, c.name COLLATE NOCASE",
  )
  fun searchWithDetails(q: String): Flow<List<ContactWithDetails>>

  // --- phones ---

  @Query("SELECT * FROM contact_phones WHERE contactId = :contactId ORDER BY id")
  suspend fun phonesFor(contactId: Long): List<ContactPhoneEntity>

  @Query("SELECT * FROM contact_phones WHERE normalizedPhone = :normalized LIMIT 1")
  suspend fun phoneByNormalized(normalized: String): ContactPhoneEntity?

  @Query("SELECT c.* FROM contacts c INNER JOIN contact_phones p ON p.contactId = c.id WHERE p.normalizedPhone = :normalized LIMIT 1")
  suspend fun contactByPhoneNormalized(normalized: String): ContactEntity?

  @Insert
  suspend fun insertPhone(phone: ContactPhoneEntity): Long

  @Update
  suspend fun updatePhone(phone: ContactPhoneEntity)

  @Query("DELETE FROM contact_phones WHERE contactId = :contactId")
  suspend fun deletePhonesFor(contactId: Long)

  // --- emails ---

  @Query("SELECT * FROM contact_emails WHERE contactId = :contactId ORDER BY id")
  suspend fun emailsFor(contactId: Long): List<ContactEmailEntity>

  @Insert
  suspend fun insertEmail(email: ContactEmailEntity): Long

  @Update
  suspend fun updateEmail(email: ContactEmailEntity)

  @Query("DELETE FROM contact_emails WHERE contactId = :contactId")
  suspend fun deleteEmailsFor(contactId: Long)
}

@Dao
interface CallDao {
  @Query("SELECT * FROM calls ORDER BY timestamp DESC LIMIT 200")
  fun all(): Flow<List<CallLogEntity>>

  @Query("SELECT * FROM calls WHERE followUp = 1 ORDER BY timestamp DESC")
  fun followUps(): Flow<List<CallLogEntity>>

  @Query("SELECT * FROM calls WHERE number LIKE '%' || :q || '%' OR name LIKE '%' || :q || '%' ORDER BY timestamp DESC LIMIT 200")
  fun search(q: String): Flow<List<CallLogEntity>>

  @Query("SELECT * FROM calls WHERE id = :id LIMIT 1")
  suspend fun byId(id: Long): CallLogEntity?

  @Insert
  suspend fun insert(call: CallLogEntity): Long

  @Query("UPDATE calls SET followUp = :value WHERE id = :id")
  suspend fun updateFollowUp(id: Long, value: Boolean)

  @Query("UPDATE calls SET state = :state, missed = :missed, durationSec = :durationSec WHERE id = :id")
  suspend fun finishCall(id: Long, state: String, missed: Boolean, durationSec: Int)

  @Query("DELETE FROM calls")
  suspend fun clear()

  @Query("DELETE FROM calls WHERE id = :id")
  suspend fun deleteById(id: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // Contacts: photo, email and free-text notes (all optional)
    db.execSQL("ALTER TABLE contacts ADD COLUMN photoUri TEXT")
    db.execSQL("ALTER TABLE contacts ADD COLUMN email TEXT")
    db.execSQL("ALTER TABLE contacts ADD COLUMN notes TEXT")
    // Call log: missed-call flag, user follow-up flag and conversation duration
    db.execSQL("ALTER TABLE calls ADD COLUMN missed INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE calls ADD COLUMN followUp INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE calls ADD COLUMN durationSec INTEGER NOT NULL DEFAULT 0")
  }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // Multiple phone numbers and email addresses per contact (Google-Contacts style).
    // The existing single phone/email move into the new tables as the first row,
    // and the contact row keeps them as the "primary" mirror for the dialer.
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS contact_phones (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "contactId INTEGER NOT NULL, " +
        "label TEXT NOT NULL, " +
        "number TEXT NOT NULL, " +
        "normalizedPhone TEXT NOT NULL DEFAULT '', " +
        "FOREIGN KEY(contactId) REFERENCES contacts(id) ON DELETE CASCADE)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_phones_contactId ON contact_phones(contactId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_phones_normalizedPhone ON contact_phones(normalizedPhone)")
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS contact_emails (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "contactId INTEGER NOT NULL, " +
        "label TEXT NOT NULL, " +
        "email TEXT NOT NULL, " +
        "FOREIGN KEY(contactId) REFERENCES contacts(id) ON DELETE CASCADE)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_emails_contactId ON contact_emails(contactId)")

    db.execSQL(
      "INSERT INTO contact_phones (contactId, label, number, normalizedPhone) " +
        "SELECT id, 'נייד', phone, normalizedPhone FROM contacts WHERE phone IS NOT NULL AND phone != ''",
    )
    db.execSQL(
      "INSERT INTO contact_emails (contactId, label, email) " +
        "SELECT id, 'אימייל', email FROM contacts WHERE email IS NOT NULL AND email != ''",
    )
  }
}

@Database(
  entities = [ContactEntity::class, ContactPhoneEntity::class, ContactEmailEntity::class, CallLogEntity::class],
  version = 3,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun contactDao(): ContactDao
  abstract fun callDao(): CallDao

  companion object {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun get(context: Context): AppDatabase =
      INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "kosherbridge.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
      }
  }
}
