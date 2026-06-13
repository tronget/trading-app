package com.trading.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "auth")

/** Хранение JWT в DataStore (переживает перезапуск приложения). */
class TokenStore(private val context: Context) {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")

    val accessToken: Flow<String?> = context.dataStore.data.map { it[accessKey] }

    suspend fun current(): Pair<String?, String?> {
        val prefs = context.dataStore.data.first()
        return prefs[accessKey] to prefs[refreshKey]
    }

    suspend fun save(access: String, refresh: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[accessKey] = access
            if (refresh != null) prefs[refreshKey] = refresh
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
