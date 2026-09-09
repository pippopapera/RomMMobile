package com.rommmobile.app.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rommmobile.app.core.util.SecureStore
import com.rommmobile.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "server")

data class ServerConfig(
    /** LAN address, e.g. http://192.168.50.11:8080 */
    val baseUrl: String? = null,
    /** Optional remote address (Cloudflare tunnel), e.g. https://romm.example.com */
    val remoteUrl: String? = null,
    val cfClientId: String? = null,
    val cfClientSecret: String? = null,
    /** Address currently in use; LAN when reachable, otherwise remote. */
    val activeUrl: String? = null,
    val serverVersion: String? = null,
) {
    val activeHttpUrl: HttpUrl? get() = activeUrl?.toHttpUrlOrNull()
    val isConfigured: Boolean get() = !baseUrl.isNullOrBlank()
    val knownHosts: Set<String> get() = listOfNotNull(baseUrl?.toHttpUrlOrNull()?.host, remoteUrl?.toHttpUrlOrNull()?.host).toSet()
}

/**
 * Server addresses. Kept in a hot [StateFlow] so OkHttp interceptors can read them without
 * suspending; the initial value is loaded synchronously once at construction (tiny file).
 */
@Singleton
class ServerStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secure: SecureStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private object K {
        val baseUrl = stringPreferencesKey("base_url")
        val remoteUrl = stringPreferencesKey("remote_url")
        val cfClientId = stringPreferencesKey("cf_client_id")
        val serverVersion = stringPreferencesKey("server_version")
    }
    private companion object { const val CF_SECRET = "cf_client_secret" }

    // Built on first access, not in the constructor: this singleton is injected during
    // Application.onCreate and the DataStore read plus the Keystore round-trips behind
    // SecureStore would run on the main thread, delaying every cold start.
    private val _config: MutableStateFlow<ServerConfig> by lazy {
        MutableStateFlow(runBlocking(Dispatchers.IO) { load() })
    }
    val config: StateFlow<ServerConfig> get() = _config.asStateFlow()

    private suspend fun load(): ServerConfig {
        val p = context.serverDataStore.data.first()
        val base = p[K.baseUrl]
        return ServerConfig(
            baseUrl = base,
            remoteUrl = p[K.remoteUrl],
            cfClientId = p[K.cfClientId],
            cfClientSecret = secure.get(CF_SECRET),
            activeUrl = base,
            serverVersion = p[K.serverVersion],
        )
    }

    suspend fun setBaseUrl(url: String?) {
        context.serverDataStore.edit { if (url == null) it.remove(K.baseUrl) else it[K.baseUrl] = url }
        _config.value = _config.value.copy(baseUrl = url, activeUrl = url ?: _config.value.remoteUrl)
    }

    suspend fun setRemoteUrl(url: String?) {
        context.serverDataStore.edit { if (url.isNullOrBlank()) it.remove(K.remoteUrl) else it[K.remoteUrl] = url }
        _config.value = _config.value.copy(remoteUrl = url?.takeIf { it.isNotBlank() })
    }

    suspend fun setCloudflareAccess(clientId: String?, clientSecret: String?) {
        context.serverDataStore.edit { if (clientId.isNullOrBlank()) it.remove(K.cfClientId) else it[K.cfClientId] = clientId }
        secure.put(CF_SECRET, clientSecret?.takeIf { it.isNotBlank() })
        _config.value = _config.value.copy(cfClientId = clientId?.takeIf { it.isNotBlank() }, cfClientSecret = clientSecret?.takeIf { it.isNotBlank() })
    }

    fun setActiveUrl(url: String?) {
        _config.value = _config.value.copy(activeUrl = url)
    }

    fun setServerVersion(version: String?) {
        _config.value = _config.value.copy(serverVersion = version)
        scope.launch(Dispatchers.IO) {
            context.serverDataStore.edit { if (version == null) it.remove(K.serverVersion) else it[K.serverVersion] = version }
        }
    }

    suspend fun clearAll() {
        context.serverDataStore.edit { it.clear() }
        secure.remove(CF_SECRET)
        _config.value = ServerConfig()
    }
}
