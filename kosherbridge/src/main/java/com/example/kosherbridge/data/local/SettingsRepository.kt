package com.example.kosherbridge.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bridge_settings")

data class LastDevice(val name: String, val address: String)

class SettingsRepository(private val context: Context) {

  private object Keys {
    val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    val FULL_SCREEN = booleanPreferencesKey("full_screen_incoming")
    val VIBRATE = booleanPreferencesKey("vibrate")
    val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
    val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
  }

  val autoConnect: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.AUTO_CONNECT] ?: true }

  val fullScreen: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.FULL_SCREEN] ?: true }

  val vibrate: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.VIBRATE] ?: true }

  val lastDevice: Flow<LastDevice?> =
    context.dataStore.data.map { prefs ->
      val address = prefs[Keys.LAST_DEVICE_ADDRESS]
      if (address.isNullOrEmpty()) null
      else LastDevice(prefs[Keys.LAST_DEVICE_NAME] ?: "", address)
    }

  suspend fun setAutoConnect(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_CONNECT] = value }

  suspend fun setFullScreen(value: Boolean) = context.dataStore.edit { it[Keys.FULL_SCREEN] = value }

  suspend fun setVibrate(value: Boolean) = context.dataStore.edit { it[Keys.VIBRATE] = value }

  suspend fun rememberDevice(name: String, address: String) = context.dataStore.edit {
    it[Keys.LAST_DEVICE_NAME] = name
    it[Keys.LAST_DEVICE_ADDRESS] = address
  }

  suspend fun forgetDevice() = context.dataStore.edit {
    it[Keys.LAST_DEVICE_NAME] = ""
    it[Keys.LAST_DEVICE_ADDRESS] = ""
  }
}
