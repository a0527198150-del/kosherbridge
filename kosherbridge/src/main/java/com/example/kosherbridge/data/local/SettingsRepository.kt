package com.example.kosherbridge.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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

/** The user's call-audio choice for this player. See [SettingsRepository.audioMode]. */
private fun audioModeKey(fp: String) = stringPreferencesKey("audio_mode_$fp")

/** Set once this player has PROVEN it cannot carry call audio. */
private fun audioImpossibleKey(fp: String) = booleanPreferencesKey("audio_impossible_$fp")

class SettingsRepository(private val context: Context) : PolicyStore {

  /**
   * Every read goes through here rather than straight to `dataStore.data`.
   *
   * DataStore surfaces a corrupt or unreadable preferences file as an
   * IOException *inside the flow*, which cancels the collector. The collectors
   * here are not screens that can be reopened - they are the service's own
   * settings observers, started once in BridgeService.onCreate. One bad read
   * ended them permanently: the channel choice, the audio mode and the
   * auto-connect preference simply stopped being applied for the rest of the
   * process's life, with nothing on screen to say so. Losing preferences to
   * defaults is a bad day; losing the observers is a broken bridge, so an
   * unreadable file falls back to defaults and the flow keeps running. This is
   * the handling DataStore's own documentation prescribes.
   */
  private val prefs: Flow<Preferences> = context.dataStore.data.catch { error ->
    if (error is IOException) emit(emptyPreferences()) else throw error
  }

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
    prefs.map { it[Keys.AUTO_CONNECT] ?: true }

  val fullScreen: Flow<Boolean> =
    prefs.map { it[Keys.FULL_SCREEN] ?: true }

  val vibrate: Flow<Boolean> =
    prefs.map { it[Keys.VIBRATE] ?: true }

  /** Play DTMF tones when pressing the dialer keys. */
  val keyTone: Flow<Boolean> =
    prefs.map { it[Keys.KEY_TONE] ?: true }

  /** Actively route and keep the call audio (SCO) alive during calls. */
  val autoAudio: Flow<Boolean> =
    prefs.map { it[Keys.AUTO_AUDIO] ?: true }

  /** Push the call stream volume to maximum while a call is active. */
  val volumeBoost: Flow<Boolean> =
    prefs.map { it[Keys.VOLUME_BOOST] ?: true }

  /** Disable the system HEADSET/A2DP profiles before a raw RFCOMM connect. */
  val profileGuard: Flow<Boolean> =
    prefs.map { it[Keys.PROFILE_GUARD] ?: true }

  /** "SYSTEM", "LIGHT" or "DARK" - app-wide appearance. */
  val themeMode: Flow<String> =
    prefs.map { it[Keys.THEME_MODE] ?: "SYSTEM" }

  val lastDevice: Flow<LastDevice?> =
    prefs.map { prefs ->
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
    prefs.map { prefs ->
      val manual = prefs[channelManualKey(fp)] ?: "AUTO"
      if (manual != "AUTO") manual
      else prefs[channelLearnedKey(fp)]?.takeIf { it.isNotBlank() } ?: "AUTO"
    }

  /** Full channel state (effective + manual + learned) for the settings UI. */
  fun channelState(fp: String): Flow<ChannelState> =
    prefs.map { prefs ->
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

  // -------------------------------------------------------------- call audio

  /**
   * Where call audio should go on this player.
   *
   *  - `AUTO`   - try the player, and fall back to the phone when it proves
   *               impossible. Once proven, stop trying (see [audioImpossible]).
   *  - `PLAYER` - always try the player, on every call, even after failures.
   *  - `PHONE`  - never try: the voice stays on the kosher phone and the player
   *               is a remote control. The honest setting for a player whose
   *               stack cannot carry call audio at all.
   */
  fun audioMode(fp: String): Flow<String> =
    prefs.map { it[audioModeKey(fp)] ?: "AUTO" }

  suspend fun setAudioMode(fp: String, mode: String) =
    context.dataStore.edit { it[audioModeKey(fp)] = mode }

  /**
   * True once this player has proven it cannot receive call audio.
   *
   * Worth persisting rather than re-deriving per call: proving it costs a
   * six-second window in which the app holds the player's audio pipeline for a
   * route that will not appear. Doing that on every single call, forever, on a
   * device where the answer can never change, is a defect - so the answer is
   * remembered per player.
   */
  fun audioImpossible(fp: String): Flow<Boolean> =
    prefs.map { it[audioImpossibleKey(fp)] ?: false }

  suspend fun setAudioImpossible(fp: String, value: Boolean) =
    context.dataStore.edit { it[audioImpossibleKey(fp)] = value }

  // ------------------------------------------------------------ policy record

  /**
   * The original connection policy this app recorded before the first
   * FORBIDDEN write, so it survives a process restart and can always be
   * restored. Connection policy itself is a persistent per-device setting held
   * by the Bluetooth stack (it survives app restarts, reboots and uninstall), so
   * an in-memory-only record dies with the process and restore becomes
   * impossible after any restart. Keys look like
   * `policy_AA:BB:CC:DD:EE:FF_16` (address uses ':', profile id is the tail).
   */
  private fun policyKey(address: String, profileId: Int) =
    intPreferencesKey("policy_${address}_$profileId")

  /** Persists the original policy recorded for (address, profile) — a no-op once
   * the original has already been recorded elsewhere. */
  override suspend fun save(address: String, profileId: Int, policy: Int) {
    context.dataStore.edit { it[policyKey(address, profileId)] = policy }
  }

  /** Removes a recorded original once it has been successfully restored. */
  override suspend fun clear(address: String, profileId: Int) {
    context.dataStore.edit { it.remove(policyKey(address, profileId)) }
  }

  /** Loads every persisted original, keyed `"address:profileId"` -> original Int. */
  override suspend fun loadAll(): Map<String, Int> =
    prefs.first().asMap().mapNotNull { (key, value) ->
      val name = key.name
      val prefix = "policy_"
      if (!name.startsWith(prefix)) return@mapNotNull null
      val rest = name.removePrefix(prefix)
      // rest = "<address>_<profileId>" — a MAC address contains no '_', so the
      // LAST underscore is the address/profile separator.
      val sep = rest.lastIndexOf('_')
      if (sep <= 0 || sep >= rest.length - 1) return@mapNotNull null
      val address = rest.substring(0, sep)
      val profileId = rest.substring(sep + 1).toIntOrNull() ?: return@mapNotNull null
      val policy = value as? Int ?: return@mapNotNull null
      "$address:$profileId" to policy
    }.toMap()
}
