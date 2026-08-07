package com.example.kosherbridge.data

import android.content.Context
import com.example.kosherbridge.data.local.AppDatabase
import com.example.kosherbridge.data.local.ContactsRepository
import com.example.kosherbridge.data.local.SettingsRepository

/** Tiny manual DI container so the service and the UI share the same instances. */
object ServiceLocator {
  lateinit var app: Context
    private set

  fun init(context: Context) {
    if (!::app.isInitialized) {
      app = context.applicationContext
    }
  }

  val db: AppDatabase by lazy { AppDatabase.get(app) }
  val contacts: ContactsRepository by lazy { ContactsRepository(db, app) }
  val settings: SettingsRepository by lazy { SettingsRepository(app) }
}
