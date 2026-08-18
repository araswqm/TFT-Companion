package com.araswqm.tftcompanion.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// PreferencesDataStore singleton - aynı veri deposuna iki kez bağlanmayı engellemek için
// top-level extension property olarak tanımlanır.
private val Context.dataStore by preferencesDataStore(name = "settings")

private object Keys {
    val MODE = stringPreferencesKey("media_mode")
    val SSID = stringPreferencesKey("esp32_ssid")
    val PASSWORD = stringPreferencesKey("esp32_password")
    val IP = stringPreferencesKey("esp32_ip")
    val PORT = stringPreferencesKey("esp32_port")
    val DARK_THEME = booleanPreferencesKey("dark_theme")
    val SPIN_ENABLED = booleanPreferencesKey("spin_enabled")
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            mode = prefs.mode(),
            ssid = prefs[Keys.SSID] ?: AppSettings().ssid,
            password = prefs[Keys.PASSWORD] ?: AppSettings().password,
            ip = prefs[Keys.IP] ?: AppSettings().ip,
            port = prefs[Keys.PORT]?.toIntOrNull() ?: AppSettings().port,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            spinEnabled = prefs[Keys.SPIN_ENABLED] ?: true,
        )
    }

    suspend fun setMode(mode: MediaMode) = update { it[Keys.MODE] = mode.name }

    suspend fun setEsp32(ssid: String, password: String, ip: String, port: Int) = update {
        it[Keys.SSID] = ssid
        it[Keys.PASSWORD] = password
        it[Keys.IP] = ip
        it[Keys.PORT] = port.toString()
    }

    suspend fun setDarkTheme(dark: Boolean) = update { it[Keys.DARK_THEME] = dark }

    suspend fun setSpinEnabled(enabled: Boolean) = update { it[Keys.SPIN_ENABLED] = enabled }

    private fun Preferences.mode(): MediaMode =
        runCatching { MediaMode.valueOf(this[Keys.MODE] ?: MediaMode.AUTO.name) }
            .getOrDefault(MediaMode.AUTO)

    private suspend fun update(
        block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ) {
        context.dataStore.edit { block(it) }
    }
}
