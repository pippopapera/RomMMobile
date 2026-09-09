package com.rommmobile.app.data.repo

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.rommmobile.app.BuildConfig
import com.rommmobile.app.core.network.ApiErrorKind
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.AuthMode
import com.rommmobile.app.core.network.AuthStore
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.UrlNormalizer
import com.rommmobile.app.core.network.apiCall
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.api.DeviceAuthInitDto
import com.rommmobile.app.data.api.DeviceInitBody
import com.rommmobile.app.data.api.DeviceTokenBody
import com.rommmobile.app.data.api.ExchangeBody
import com.rommmobile.app.data.api.READ_SCOPES
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.api.UserDto
import com.rommmobile.app.di.ApiClient
import com.rommmobile.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface Session {
    data object NotConfigured : Session
    data object LoggedOut : Session
    data class LoggedIn(val username: String?, val mode: AuthMode) : Session
}

data class ProbeResult(val url: String, val version: String?)

data class PairPayload(val serverUrl: String?, val code: String)

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RommApi,
    @ApiClient private val client: OkHttpClient,
    private val json: Json,
    private val authStore: AuthStore,
    private val serverStore: ServerStore,
    private val log: FileLogger,
    @ApplicationScope scope: CoroutineScope,
) {
    val session: StateFlow<Session> = combine(serverStore.config, authStore.state) { cfg, auth ->
        when {
            !cfg.isConfigured -> Session.NotConfigured
            !auth.isLoggedIn -> Session.LoggedOut
            else -> Session.LoggedIn(auth.username, auth.mode)
        }
    }.stateIn(scope, SharingStarted.Eagerly, initialSession())

    private fun initialSession(): Session {
        val cfg = serverStore.config.value
        val auth = authStore.state.value
        return when {
            !cfg.isConfigured -> Session.NotConfigured
            !auth.isLoggedIn -> Session.LoggedOut
            else -> Session.LoggedIn(auth.username, auth.mode)
        }
    }

    val sessionEvents get() = authStore.events

    /** Reachability test used during onboarding; no credentials involved. */
    suspend fun probe(rawUrl: String, timeoutSeconds: Long = 5): Result<ProbeResult> = withContext(Dispatchers.IO) {
        val url = UrlNormalizer.normalize(rawUrl) ?: return@withContext Result.failure(ApiException(ApiErrorKind.UNKNOWN, detail = rawUrl))
        apiCall {
            val short = client.newBuilder().connectTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(UrlNormalizer.join(url, "api/heartbeat")).get().build()
            short.newCall(req).execute().use { r ->
                val body = r.body.string()
                val version = runCatching { json.parseToJsonElement(body).jsonObject["SYSTEM"]?.jsonObject?.get("VERSION")?.jsonPrimitive?.content }.getOrNull()
                    ?: throw ApiException(ApiErrorKind.NON_JSON, httpCode = r.code, endpoint = "/api/heartbeat")
                ProbeResult(url, version)
            }
        }
    }

    suspend fun saveServer(url: String, version: String?) {
        serverStore.setBaseUrl(url)
        serverStore.setServerVersion(version)
    }

    /**
     * Username + password: OAuth2 password grant with read scopes (no password stored), then
     * Basic as fallback for servers where /api/token refuses the request.
     */
    suspend fun loginWithPassword(username: String, password: String): Result<Unit> = withVerification {
        val oauth = apiCall {
            val t = api.token(grantType = "password", username = username, password = password, scope = READ_SCOPES.joinToString(" "))
            authStore.setOAuth(t.accessToken, t.refreshToken, t.expires, username)
            verifyMe()
        }
        if (oauth.isSuccess) return@withVerification Result.success(Unit)
        val err = oauth.exceptionOrNull() as? ApiException
        log.w("Auth", "token grant failed: ${err?.message}")
        if (err != null && (err.kind == ApiErrorKind.UNAUTHORIZED || (err.httpCode == 400 && err.detail?.contains("password", true) == true))) {
            authStore.clear()
            return@withVerification Result.failure(ApiException(ApiErrorKind.UNAUTHORIZED, detail = err.detail))
        }
        if (err != null && err.kind in setOf(ApiErrorKind.OFFLINE, ApiErrorKind.TIMEOUT, ApiErrorKind.NON_JSON, ApiErrorKind.CLEARTEXT_BLOCKED, ApiErrorKind.TLS)) {
            authStore.clear()
            return@withVerification Result.failure(err)
        }
        authStore.setBasic(username, password)
        val basic = apiCall { verifyMe(); Unit }
        if (basic.isFailure) authStore.clear()
        basic
    }

    /** Marks the window in which a 401 means "wrong credentials", not "session expired". */
    private suspend inline fun <T> withVerification(block: () -> Result<T>): Result<T> {
        authStore.beginVerification()
        return try { block() } finally { authStore.endVerification() }
    }

    suspend fun loginWithClientToken(rawToken: String): Result<Unit> = withVerification {
        val token = rawToken.trim()
        if (token.isEmpty()) return@withVerification Result.failure(ApiException(ApiErrorKind.UNAUTHORIZED))
        authStore.setClientToken(token, null)
        val r = apiCall { verifyMe(); Unit }
        if (r.isFailure) authStore.clear()
        r
    }

    /** QR from the web app: `https://host/pair?code=XXXX-XXXX` (dash optional, case-insensitive). */
    fun parsePairPayload(text: String): PairPayload? {
        val t = text.trim()
        val codeRegex = Regex("([A-Za-z0-9]{4})-?([A-Za-z0-9]{4})")
        return if (t.contains("://")) {
            val url = t.toHttpUrlOrNull() ?: return null
            val code = url.queryParameter("code") ?: return null
            val m = codeRegex.matchEntire(code.trim()) ?: return null
            val base = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
            PairPayload(base, (m.groupValues[1] + m.groupValues[2]).uppercase())
        } else {
            val m = codeRegex.matchEntire(t) ?: return null
            PairPayload(null, (m.groupValues[1] + m.groupValues[2]).uppercase())
        }
    }

    suspend fun exchangePairCode(code: String): Result<Unit> = withVerification {
        val bare = code.replace("-", "").trim().uppercase()
        // Which spelling the server accepts is not documented: try the compact form, then the
        // dashed one the web app puts in the QR, before reporting a failure.
        val forms = if (bare.length == 8) listOf(bare, bare.substring(0, 4) + "-" + bare.substring(4)) else listOf(bare)
        var last: Result<Unit> = Result.failure(ApiException(ApiErrorKind.UNAUTHORIZED))
        for (form in forms) {
            last = apiCall {
                val created = api.exchangePairCode(ExchangeBody(form))
                authStore.setClientToken(created.rawToken, null)
                try { verifyMe() } catch (t: Throwable) { authStore.clear(); throw t }
                Unit
            }
            if (last.isSuccess) break
        }
        last
    }

    suspend fun deviceFlowStart(): Result<DeviceAuthInitDto> = apiCall {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-${System.currentTimeMillis()}"
        api.deviceInit(
            DeviceInitBody(
                clientDeviceIdentifier = "rommmobile-$id",
                name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android" },
                client = "RomMMobile",
                platform = "android",
                clientVersion = BuildConfig.VERSION_NAME,
                requestedScopes = READ_SCOPES,
            )
        )
    }

    /** One poll: true when the user approved and the token is stored, false while pending. */
    suspend fun deviceFlowPoll(deviceCode: String): Result<Boolean> = withVerification {
        val r = apiCall { api.deviceToken(DeviceTokenBody(deviceCode)) }
        r.getOrNull()?.let { t ->
            authStore.setClientToken(t.accessToken, null)
            val me = apiCall { verifyMe() }
            if (me.isFailure) { authStore.clear(); return Result.failure(me.exceptionOrNull()!!) }
            return Result.success(true)
        }
        val e = r.exceptionOrNull() as ApiException
        val detail = e.detail.orEmpty()
        return when {
            e.httpCode == 400 && (detail.contains("pending") || detail.contains("slow_down")) -> Result.success(false)
            e.httpCode == 400 && detail.contains("denied") -> Result.failure(ApiException(ApiErrorKind.FORBIDDEN, detail = detail))
            e.httpCode == 400 && detail.contains("expired") -> Result.failure(ApiException(ApiErrorKind.NOT_FOUND, detail = detail))
            // A dropped packet must not kill a code the user is still typing on their laptop.
            e.kind == ApiErrorKind.OFFLINE || e.kind == ApiErrorKind.TIMEOUT -> Result.success(false)
            else -> Result.failure(e)
        }
    }

    /** True when the stored credentials still open a session. */
    suspend fun validateSession(): Boolean = apiCall { verifyMe() }.isSuccess

    fun logout() {
        authStore.clear()
        log.i("Auth", "logout")
    }

    /**
     * LAN first, remote second, with a short timeout so app start never hangs on a dead tunnel.
     * The first data request never depends on this: callers race it with their own loads.
     */
    suspend fun selectEndpoint() {
        val cfg = serverStore.config.value
        val base = cfg.baseUrl ?: return
        val remote = cfg.remoteUrl
        if (remote.isNullOrBlank()) { serverStore.setActiveUrl(base); return }
        // Two tries. A handheld waking its radio can miss one three-second window while sitting
        // squarely at home, and a session spent on the tunnel is the slowest thing this app can
        // do: every cover and every page goes out through Cloudflare and back.
        var lanOk = false
        repeat(2) { if (!lanOk) lanOk = runCatching { withTimeout(3_500) { probe(base, 3).isSuccess } }.getOrDefault(false) }
        serverStore.setActiveUrl(if (lanOk) base else remote)
        log.i("Auth", "active endpoint: ${if (lanOk) "LAN" else "remote"}")
    }

    private suspend fun verifyMe(): UserDto {
        val el = api.me()
        if (el is JsonNull || el !is JsonObject) throw ApiException(ApiErrorKind.UNAUTHORIZED, detail = "no session")
        val user = json.decodeFromJsonElement(UserDto.serializer(), el)
        authStore.updateUsername(user.username)
        return user
    }
}
