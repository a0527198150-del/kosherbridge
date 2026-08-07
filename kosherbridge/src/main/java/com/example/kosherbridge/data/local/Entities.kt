package com.example.kosherbridge.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val phone: String,
  val normalizedPhone: String = "",
  val favorite: Boolean = false,
)

@Entity(tableName = "calls")
data class CallLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val number: String,
  val name: String?,
  val direction: String, // INCOMING / OUTGOING
  val state: String,     // CallState name
  val timestamp: Long,
)
