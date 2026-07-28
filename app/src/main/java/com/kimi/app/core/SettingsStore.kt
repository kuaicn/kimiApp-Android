package com.kimi.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.f4b6a3.ulid.UlidCreator
import com.kimi.app.data.api.ServerProfile
import com.kimi.app.data.api.wireJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "kimi_settings")

@Serializable
data class StoredServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String? = null,
) {
    fun toProfile(): ServerProfile = ServerProfile(id, name, baseUrl, token)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 本地配置（≈ kimi-web 的 lib/storage.ts 持久化键） */
class SettingsStore(private val context: Context) {

    private object Keys {
        val SERVERS = stringPreferencesKey("servers_json")
        val ACTIVE_SERVER = stringPreferencesKey("active_server_id")
        val CLIENT_ID = stringPreferencesKey("client_id")
        val THEME = stringPreferencesKey("theme_mode")
        val DRAFTS = stringPreferencesKey("drafts_json")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LAST_SESSION = stringPreferencesKey("last_session_id")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models_json")
    }

    private val serverListSerializer = ListSerializer(StoredServer.serializer())
    private val draftMapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val stringSetSerializer = ListSerializer(String.serializer())

    // ---- 服务器配置 ----

    val serversFlow: Flow<List<StoredServer>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SERVERS]?.let { json ->
            runCatching { wireJson.decodeFromString(serverListSerializer, json) }.getOrNull()
        } ?: emptyList()
    }

    val activeServerIdFlow: Flow<String?> = context.dataStore.data.map { it[Keys.ACTIVE_SERVER] }

    val activeServerFlow: Flow<StoredServer?> = context.dataStore.data.map { prefs ->
        val id = prefs[Keys.ACTIVE_SERVER] ?: return@map null
        prefs[Keys.SERVERS]?.let { json ->
            runCatching { wireJson.decodeFromString(serverListSerializer, json) }.getOrNull()
                ?.firstOrNull { it.id == id }
        }
    }

    suspend fun upsertServer(server: StoredServer) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SERVERS]?.let { json ->
                runCatching { wireJson.decodeFromString(serverListSerializer, json) }.getOrNull()
            } ?: emptyList()
            val next = current.filterNot { it.id == server.id } + server
            prefs[Keys.SERVERS] = wireJson.encodeToString(serverListSerializer, next)
        }
    }

    suspend fun removeServer(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SERVERS]?.let { json ->
                runCatching { wireJson.decodeFromString(serverListSerializer, json) }.getOrNull()
            } ?: emptyList()
            prefs[Keys.SERVERS] = wireJson.encodeToString(serverListSerializer, current.filterNot { it.id == id })
            if (prefs[Keys.ACTIVE_SERVER] == id) prefs.remove(Keys.ACTIVE_SERVER)
        }
    }

    suspend fun setActiveServer(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_SERVER) else prefs[Keys.ACTIVE_SERVER] = id
        }
    }

    suspend fun addServer(baseUrl: String, token: String?, name: String = ""): StoredServer {
        val server = StoredServer(
            id = UlidCreator.getUlid().toString(),
            name = name.ifBlank { baseUrl.removePrefix("http://").removePrefix("https://") },
            baseUrl = baseUrl.trim().trimEnd('/'),
            token = token?.takeIf { it.isNotBlank() },
        )
        upsertServer(server)
        setActiveServer(server.id)
        return server
    }

    suspend fun updateToken(serverId: String, token: String?) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SERVERS]?.let { json ->
                runCatching { wireJson.decodeFromString(serverListSerializer, json) }.getOrNull()
            } ?: return@edit
            prefs[Keys.SERVERS] = wireJson.encodeToString(
                serverListSerializer,
                current.map { if (it.id == serverId) it.copy(token = token?.takeIf { t -> t.isNotBlank() }) else it },
            )
        }
    }

    // ---- 客户端标识 ----

    suspend fun clientId(): String {
        val prefs = context.dataStore.data.first()
        prefs[Keys.CLIENT_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.CLIENT_ID] = id }
        return id
    }

    // ---- 外观 ----

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        when (prefs[Keys.THEME]) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit {
            it[Keys.THEME] = when (mode) {
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
                ThemeMode.SYSTEM -> "system"
            }
        }
    }

    // ---- 草稿 ----

    val draftsFlow: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.DRAFTS]?.let { json ->
            runCatching { wireJson.decodeFromString(draftMapSerializer, json) }.getOrNull()
        } ?: emptyMap()
    }

    suspend fun saveDraft(sessionKey: String, text: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.DRAFTS]?.let { json ->
                runCatching { wireJson.decodeFromString(draftMapSerializer, json) }.getOrNull()
            } ?: emptyMap()
            val next = if (text.isBlank()) current - sessionKey else current + (sessionKey to text)
            prefs[Keys.DRAFTS] = wireJson.encodeToString(draftMapSerializer, next)
        }
    }

    // ---- 引导 / 上次会话 ----

    val onboardedFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded() {
        context.dataStore.edit { it[Keys.ONBOARDED] = true }
    }

    suspend fun lastSessionId(): String? = context.dataStore.data.first()[Keys.LAST_SESSION]

    suspend fun setLastSession(id: String?) {
        context.dataStore.edit {
            if (id == null) it.remove(Keys.LAST_SESSION) else it[Keys.LAST_SESSION] = id
        }
    }

    // ---- 收藏模型 ----

    val favoriteModelsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.FAVORITE_MODELS]?.let { json ->
            runCatching { wireJson.decodeFromString(stringSetSerializer, json) }.getOrNull()
        }?.toSet() ?: emptySet()
    }

    suspend fun toggleFavoriteModel(modelKey: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_MODELS]?.let { json ->
                runCatching { wireJson.decodeFromString(stringSetSerializer, json) }.getOrNull()
            } ?: emptyList()
            val next = if (modelKey in current) current - modelKey else current + modelKey
            prefs[Keys.FAVORITE_MODELS] = wireJson.encodeToString(stringSetSerializer, next)
        }
    }
}
