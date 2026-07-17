package com.akashic.mobile.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val currentServerId: String?,
    val currentSessionId: String?,
    val theme: String,
)

class AppPreferences(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { values ->
        AppSettings(
            currentServerId = values[CURRENT_SERVER_ID],
            currentSessionId = values[CURRENT_SESSION_ID],
            theme = values[THEME] ?: "system",
        )
    }

    suspend fun selectServer(serverId: String?) {
        context.settingsDataStore.edit { values ->
            if (serverId == null) values.remove(CURRENT_SERVER_ID) else values[CURRENT_SERVER_ID] = serverId
        }
    }

    suspend fun selectSession(sessionId: String?) {
        context.settingsDataStore.edit { values ->
            if (sessionId == null) values.remove(CURRENT_SESSION_ID) else values[CURRENT_SESSION_ID] = sessionId
        }
    }

    suspend fun setTheme(theme: String) {
        require(theme in setOf("system", "light", "dark")) { "Unsupported theme: $theme" }
        context.settingsDataStore.edit { values -> values[THEME] = theme }
    }

    private companion object {
        val CURRENT_SERVER_ID = stringPreferencesKey("current_server_id")
        val CURRENT_SESSION_ID = stringPreferencesKey("current_session_id")
        val THEME = stringPreferencesKey("theme")
    }
}
