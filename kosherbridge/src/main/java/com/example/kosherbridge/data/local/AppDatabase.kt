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
  @Query("SELECT * FROM calls ORDER BY timestamp DESC LIMIT 50")
  fun all(): Flow<List<CallLogEntity>>

  @Insert
  suspend fun insert(call: CallLogEntity): Long

  @Query("UPDATE calls SET state = :state WHERE id = :id")
  suspend fun updateState(id: Long, state: String)
}

@Database(entities = [ContactEntity::class, CallLogEntity::class], version = 1, exportSchema = false)
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
        ).build().also { INSTANCE = it }
      }
  }
}
