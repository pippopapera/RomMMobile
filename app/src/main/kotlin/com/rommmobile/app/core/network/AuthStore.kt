package com.rommmobile.app.core.network

import android.util.Base64
import com.rommmobile.app.core.util.SecureStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthMode { NONE, OAUTH, CLIENT_TOKEN, BASIC }

data class AuthState(
    val mode: AuthMode = AuthMode.NONE,
    val username: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Epoch millis; null when the token has no known expiry (client tokens). */
    val accessExpiresAt: Long? = null,
    val password: String? = null,
) {
    val isLoggedIn: Boolean get() = mode != AuthMode.NONE

    fun authorizationHeader(): String? = when (mode) {
        AuthMode.NONE -> null
        AuthMode.OAUTH, AuthMode.CLIENT_TOKEN -> accessToken?.let { "Bearer $it" }
        AuthMode.BASIC -> basicHeader(username.orEmpty(), password.orEmpty())
    }

    companion object {
        /** Always UTF-8 bytes: passwords with `<` or accents broke romm-mobile's btoa. */
        fun basicHeader(user: String, pass: String): String =
            "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}

sealed interface SessionEvent {
    data object Expired : SessionEvent
}

/** Credentials live encrypted in [SecureStore]; a hot copy is kept for interceptors. */
@Singleton
class AuthStore @Inject constructor(private val secure: SecureStore) {

    private object K {
        const val MODE = "auth_mode"
        const val USER = "auth_user"
        const val ACCESS = "auth_access"
        const val REFRESH = "auth_refresh"
        const val EXPIRES = "auth_expires"
        const val PASSWORD = "auth_password"
    }

    // Lazy for the same reason as ServerStore: load() performs several Android Keystore
    // operations, which are slow and must not run while the first frame is being drawn.
    private val _state: MutableStateFlow<AuthState> by lazy { MutableStateFlow(load()) }
    val state: StateFlow<AuthState> get() = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private fun load(): AuthState {
        val mode = secure.get(K.MODE)?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() } ?: AuthMode.NONE
        return AuthState(
            mode = mode,
            username = secure.get(K.USER),
            accessToken = secure.get(K.ACCESS),
            refreshToken = secure.get(K.REFRESH),
            accessExpiresAt = secure.get(K.EXPIRES)?.toLongOrNull(),
            password = if (mode == AuthMode.BASIC) secure.get(K.PASSWORD) else null,
        )
    }

    fun setOAuth(accessToken: String, refreshToken: String?, expiresInSeconds: Long?, username: String?) {
        val expiresAt = expiresInSeconds?.let { System.currentTimeMillis() + it * 1000 }
        persist(AuthState(AuthMode.OAUTH, username, accessToken, refreshToken, expiresAt))
    }

    fun setClientToken(token: String, username: String?) {
        persist(AuthState(AuthMode.CLIENT_TOKEN, username, token, null, null))
    }

    fun setBasic(username: String, password: String) {
        persist(AuthState(AuthMode.BASIC, username, null, null, null, password))
    }

    fun updateUsername(username: String?) {
        if (username == null || username == _state.value.username) return
        persist(_state.value.copy(username = username))
    }

    /**
     * True while credentials are being tried out (login screens set them, then call /users/me).
     * A 401 during that window means "wrong credentials", not "your session died", and must not
     * kick the user out of the wizard they are standing in.
     */
    @Volatile private var verifying = false

    fun beginVerification() { verifying = true }
    fun endVerification() { verifying = false }

    /** Session only: settings, folder mapping and download history are untouched. */
    fun clear(notify: Boolean = false) {
        persist(AuthState())
        if (notify && !verifying) _events.tryEmit(SessionEvent.Expired)
    }

    private fun persist(s: AuthState) {
        secure.put(K.MODE, s.mode.name)
        secure.put(K.USER, s.username)
        secure.put(K.ACCESS, s.accessToken)
        secure.put(K.REFRESH, s.refreshToken)
        secure.put(K.EXPIRES, s.accessExpiresAt?.toString())
        secure.put(K.PASSWORD, s.password)
        _state.value = s
    }
}
