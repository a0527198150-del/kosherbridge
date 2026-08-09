package com.example.kosherbridge.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val phone: String,
  val normalizedPhone: String = "",
  val favorite: Boolean = false,
  val photoUri: String? = null, // internal file path of the contact photo (if any)
  val email: String? = null,
  val notes: String? = null,
)

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
