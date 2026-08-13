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

/**
 * The connection channel for the current player, resolved from the user's
 * manual choice (if any) and the channel that proved to work on this exact
 * player before. Values: "AUTO" (let the app probe), "DIRECT" (in-process
 * hidden API), "SHIZUKU" (privileged process), "RAW" (direct RFCOMM).
 */
data class ChannelState(val effective: String, val manual: String, val learned: String)

private fun channelManualKey(fp: String) = stringPreferencesKey("channel_manual_$fp")
private fun channelLearnedKey(fp: String) = stringPreferencesKey("channel_learned_$fp")

class SettingsRepository(private val context: Context) {

  private object Keys {
    val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    val FULL_SCREEN = booleanPreferencesKey("full_screen_incoming")
    val VIBRATE = booleanPreferencesKey("vibrate")
    val KEY_TONE = booleanPreferencesKey("key_tone")
    val AUTO_AUDIO = booleanPreferencesKey("auto_audio")
    val VOLUME_BOOST = booleanPreferencesKey("volume_boost")
    val PROFILE_GUARD = booleanPreferencesKey("raw_profile_guard")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
    val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
  }

  val autoConnect: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.AUTO_CONNECT] ?: true }

  val fullScreen: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.FULL_SCREEN] ?: true }

  val vibrate: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.VIBRATE] ?: true }

  /** Play DTMF tones when pressing the dialer keys. */
  val keyTone: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.KEY_TONE] ?: true }

  /** Actively route and keep the call audio (SCO) alive during calls. */
  val autoAudio: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.AUTO_AUDIO] ?: true }

  /** Push the call stream volume to maximum while a call is active. */
  val volumeBoost: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.VOLUME_BOOST] ?: true }

  /** Disable the system HEADSET/A2DP profiles before a raw RFCOMM connect. */
  val profileGuard: Flow<Boolean> =
    context.dataStore.data.map { it[Keys.PROFILE_GUARD] ?: true }

  /** "SYSTEM", "LIGHT" or "DARK" - app-wide appearance. */
  val themeMode: Flow<String> =
    context.dataStore.data.map { it[Keys.THEME_MODE] ?: "SYSTEM" }

  val lastDevice: Flow<LastDevice?> =
    context.dataStore.data.map { prefs ->
      val address = prefs[Keys.LAST_DEVICE_ADDRESS]
      if (address.isNullOrEmpty()) null
      else LastDevice(prefs[Keys.LAST_DEVICE_NAME] ?: "", address)
    }

  suspend fun setAutoConnect(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_CONNECT] = value }

  suspend fun setFullScreen(value: Boolean) = context.dataStore.edit { it[Keys.FULL_SCREEN] = value }

  suspend fun setVibrate(value: Boolean) = context.dataStore.edit { it[Keys.VIBRATE] = value }

  suspend fun setKeyTone(value: Boolean) = context.dataStore.edit { it[Keys.KEY_TONE] = value }

  suspend fun setAutoAudio(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_AUDIO] = value }

  suspend fun setVolumeBoost(value: Boolean) = context.dataStore.edit { it[Keys.VOLUME_BOOST] = value }

  suspend fun setProfileGuard(value: Boolean) = context.dataStore.edit { it[Keys.PROFILE_GUARD] = value }

  suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[Keys.THEME_MODE] = mode }

  suspend fun rememberDevice(name: String, address: String) = context.dataStore.edit {
    it[Keys.LAST_DEVICE_NAME] = name
    it[Keys.LAST_DEVICE_ADDRESS] = address
  }

  suspend fun forgetDevice() = context.dataStore.edit {
    it[Keys.LAST_DEVICE_NAME] = ""
    it[Keys.LAST_DEVICE_ADDRESS] = ""
  }

  // ------------------------------------------------------------------ channel

  /**
   * The resolved channel for this player (fingerprint): the user's manual
   * choice wins; otherwise the channel learned from a previous successful
   * connection on this exact player; otherwise AUTO (full probing).
   */
  fun effectiveChannel(fp: String): Flow<String> =
    context.dataStore.data.map { prefs ->
      val manual = prefs[channelManualKey(fp)] ?: "AUTO"
      if (manual != "AUTO") manual
      else prefs[channelLearnedKey(fp)]?.takeIf { it.isNotBlank() } ?: "AUTO"
    }

  /** Full channel state (effective + manual + learned) for the settings UI. */
  fun channelState(fp: String): Flow<ChannelState> =
    context.dataStore.data.map { prefs ->
      val manual = prefs[channelManualKey(fp)] ?: "AUTO"
      val learned = prefs[channelLearnedKey(fp)] ?: ""
      ChannelState(
        effective = if (manual != "AUTO") manual else if (learned.isNotBlank()) learned else "AUTO",
        manual = manual,
        learned = learned,
      )
    }

  /** The user's explicit choice for this player ("AUTO" = let the app decide). */
  suspend fun setChannel(fp: String, mode: String) =
    context.dataStore.edit { it[channelManualKey(fp)] = mode }

  /** Records that a backend proved itself working on this exact player. */
  suspend fun learnChannel(fp: String, backend: String) =
    context.dataStore.edit { it[channelLearnedKey(fp)] = backend }
}
