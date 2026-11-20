package com.melodify.melodify.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.melodify.melodify.player.RepeatMode
import com.melodify.melodify.player.ShuffleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "melodify_prefs")

/**
 * Persists user preferences that survive process death:
 *  - last selected EQ preset / gains
 *  - repeat & shuffle mode
 *  - UI state (e.g. last screen)
 */
class PreferencesStore(private val context: Context) {

    val repeatMode: Flow<RepeatMode> = context.dataStore.data.map { p ->
        runCatching { RepeatMode.values()[p[KEY_REPEAT] ?: 0] }.getOrDefault(RepeatMode.OFF)
    }

    val shuffleMode: Flow<ShuffleMode> = context.dataStore.data.map { p ->
        if (p[KEY_SHUFFLE] == true) ShuffleMode.ON else ShuffleMode.OFF
    }

    suspend fun setRepeatMode(mode: RepeatMode) {
        context.dataStore.edit { it[KEY_REPEAT] = mode.ordinal }
    }

    suspend fun setShuffleMode(mode: ShuffleMode) {
        context.dataStore.edit { it[KEY_SHUFFLE] = (mode == ShuffleMode.ON) }
    }

    companion object {
        private val KEY_REPEAT = intPreferencesKey("repeat_mode")
        private val KEY_SHUFFLE = booleanPreferencesKey("shuffle_mode")
    }
}
