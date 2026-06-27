package com.gogoend.intervalclicker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "click_config")

/** 配置持久化（FR-025）。仅持久化 ClickConfig；准星位置不持久化。 */
class ConfigRepository(private val context: Context) {

    private object Keys {
        val INTERVAL = longPreferencesKey("interval_ms")
        val PRESS = longPreferencesKey("press_ms")
        val FIRE_IMMEDIATELY = booleanPreferencesKey("fire_immediately")
        val ON_CALL = stringPreferencesKey("on_incoming_call")
    }

    val configFlow: Flow<ClickConfig> = context.dataStore.data.map { p ->
        ClickConfig(
            intervalMs = p[Keys.INTERVAL] ?: ClickConfig.DEFAULT_INTERVAL_MS,
            pressDurationMs = p[Keys.PRESS] ?: ClickConfig.DEFAULT_PRESS_DURATION_MS,
            fireImmediately = p[Keys.FIRE_IMMEDIATELY] ?: true,
            onIncomingCall = runCatching {
                CallAction.valueOf(p[Keys.ON_CALL] ?: CallAction.STOP.name)
            }.getOrDefault(CallAction.STOP),
        ).normalized()
    }

    suspend fun save(config: ClickConfig) {
        val c = config.normalized()
        context.dataStore.edit { p ->
            p[Keys.INTERVAL] = c.intervalMs
            p[Keys.PRESS] = c.pressDurationMs
            p[Keys.FIRE_IMMEDIATELY] = c.fireImmediately
            p[Keys.ON_CALL] = c.onIncomingCall.name
        }
    }
}
