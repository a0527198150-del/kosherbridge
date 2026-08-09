package com.example.kosherbridge.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

  @Insert
  suspend fun insert(contact: ContactEntity): Long

  @Update
  suspend fun update(contact: ContactEntity)

  @Delete
  suspend fun delete(contact: ContactEntity)
}

@Dao
interface CallDao {
  @Query("SELECT * FROM calls ORDER BY timestamp DESC LIMIT 200")
  fun all(): Flow<List<CallLogEntity>>

  @Query("SELECT * FROM calls WHERE followUp = 1 ORDER BY timestamp DESC")
  fun followUps(): Flow<List<CallLogEntity>>

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

@Database(entities = [ContactEntity::class, CallLogEntity::class], version = 2, exportSchema = false)
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
        ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
      }
  }
}
