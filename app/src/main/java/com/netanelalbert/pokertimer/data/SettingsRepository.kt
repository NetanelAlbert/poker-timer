package com.netanelalbert.pokertimer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netanelalbert.pokertimer.model.TimerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "poker_timer")

/**
 * Persists the user's structure and preferences, plus just enough session state to come back to
 * the right level if the process is killed mid-tournament.
 */
class SettingsRepository private constructor(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: Flow<TimerSettings> = context.dataStore.data.map { prefs ->
        val stored = prefs[KEY_SETTINGS] ?: return@map TimerSettings()
        // A settings blob written by an older/newer build should never be fatal: fall back to
        // defaults rather than crashing on launch.
        val parsed = runCatching { json.decodeFromString(TimerSettings.serializer(), stored) }.getOrNull()
        parsed?.takeIf { it.levels.isNotEmpty() } ?: TimerSettings()
    }

    val savedSession: Flow<SavedSession?> = context.dataStore.data.map { prefs ->
        val level = prefs[KEY_SESSION_LEVEL] ?: return@map null
        val remaining = prefs[KEY_SESSION_REMAINING] ?: return@map null
        val levelEnded = prefs[KEY_SESSION_LEVEL_ENDED] ?: false
        SavedSession(level, remaining, levelEnded)
    }

    suspend fun update(transform: (TimerSettings) -> TimerSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SETTINGS]
                ?.let { runCatching { json.decodeFromString(TimerSettings.serializer(), it) }.getOrNull() }
                ?: TimerSettings()
            val updated = transform(current).let {
                // An empty structure would leave the clock with nothing to count.
                if (it.levels.isEmpty()) it.copy(levels = TimerSettings.DEFAULT_LEVELS) else it
            }
            prefs[KEY_SETTINGS] = json.encodeToString(TimerSettings.serializer(), updated)
        }
    }

    /**
     * [levelEnded] marks a save taken while the level's alarm was ringing or snoozed, so a restore
     * knows not to trust [remainingMs] as a countdown to resume — see [TimerEngine.restore].
     */
    suspend fun saveSession(levelIndex: Int, remainingMs: Long, levelEnded: Boolean = false) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SESSION_LEVEL] = levelIndex
            prefs[KEY_SESSION_REMAINING] = remainingMs
            prefs[KEY_SESSION_LEVEL_ENDED] = levelEnded
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SESSION_LEVEL)
            prefs.remove(KEY_SESSION_REMAINING)
            prefs.remove(KEY_SESSION_LEVEL_ENDED)
        }
    }

    data class SavedSession(val levelIndex: Int, val remainingMs: Long, val wasLevelEnded: Boolean = false)

    companion object {
        private val KEY_SETTINGS = stringPreferencesKey("settings_json")
        private val KEY_SESSION_LEVEL = intPreferencesKey("session_level")
        private val KEY_SESSION_REMAINING = longPreferencesKey("session_remaining_ms")
        private val KEY_SESSION_LEVEL_ENDED = booleanPreferencesKey("session_level_ended")

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
