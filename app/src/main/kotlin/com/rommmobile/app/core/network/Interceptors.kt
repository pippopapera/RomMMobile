package com.rommmobile.app.core.network

import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Placeholder base URL for Retrofit; the real host is swapped in per request. */
const val RETROFIT_PLACEHOLDER = "http://romm.invalid/"

/**
 * Rewrites the placeholder host to the active server, keeping an optional path prefix
 * (RomM mounted under /romm behind a reverse proxy).
 */
@Singleton
class HostSelectionInterceptor @Inject constructor(private val serverStore: ServerStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "romm.invalid") return chain.proceed(request)
        val base = serverStore.config.value.activeHttpUrl
            ?: throw ApiException(ApiErrorKind.NOT_CONFIGURED, endpoint = request.url.encodedPath)
        val prefix = base.encodedPath.trimEnd('/')
        val newUrl = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(prefix + request.url.encodedPath)
            .build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}

/**
 * Plain HTTP is fine on the LAN (private ranges, .local) and refused towards the internet
 * unless the user explicitly allowed it in settings. Enforced in code because
 * network_security_config cannot express IP ranges.
 */
@Singleton
class CleartextPolicyInterceptor @Inject constructor(
    private val settings: Provider<SettingsStore>,
    @ApplicationScope scope: CoroutineScope,
) : Interceptor {
    @Volatile var allowInsecureRemote: Boolean = false

    init {
        // Follow the setting from the graph, not from a screen: background downloads resumed by
        // WorkManager run with no ViewModel alive and would otherwise always see `false`.
        scope.launch {
            settings.get().settings.map { it.allowInsecureRemote }.distinctUntilChanged()
                .collect { allowInsecureRemote = it }
        }
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !UrlNormalizer.isPrivateHost(url.host) && !allowInsecureRemote) {
            throw ApiException(ApiErrorKind.CLEARTEXT_BLOCKED, endpoint = url.host)
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Adds `Authorization` (and Cloudflare Access headers) only to requests aimed at one of the
 * user's own server hosts. Cover art from external CDNs never receives credentials.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val authStore: AuthStore,
    private val serverStore: ServerStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cfg = serverStore.config.value
        if (request.url.host !in cfg.knownHosts) return chain.proceed(request)
        val builder = request.newBuilder()
        if (request.header("Authorization") == null) {
            authStore.state.value.authorizationHeader()?.let { builder.header("Authorization", it) }
        }
        applyCloudflareHeaders(builder, cfg)
        builder.header("Accept", request.header("Accept") ?: "application/json, */*;q=0.8")
        return chain.proceed(builder.build())
    }
}

/**
 * Cloudflare Access service-token headers. Values are typed by hand, and OkHttp throws on any
 * character outside 0x20..0x7E — an exception from an interceptor reaches the dispatcher thread
 * and kills the process, so anything unusable is dropped instead of sent.
 */
fun applyCloudflareHeaders(builder: Request.Builder, cfg: ServerConfig) {
    val id = cfg.cfClientId?.trim()
    val secret = cfg.cfClientSecret?.trim()
    if (id.isNullOrEmpty() || secret.isNullOrEmpty()) return
    if (!isHeaderSafe(id) || !isHeaderSafe(secret)) return
    builder.header("CF-Access-Client-Id", id).header("CF-Access-Client-Secret", secret)
}

private fun isHeaderSafe(value: String): Boolean = value.all { it.code in 0x20..0x7E }

/**
 * Turns HTTP failures into [ApiException] with the server's `detail`, and catches the classic
 * reverse-proxy trap: a 200 HTML login page where JSON was expected.
 */
@Singleton
class ErrorMappingInterceptor @Inject constructor(private val json: Json) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val path = request.url.encodedPath
        val isContent = path.contains("/content/")
        if (response.isSuccessful) {
            if (!isContent && path.contains("/api/")) {
                val type = response.header("Content-Type").orEmpty()
                if (type.isNotEmpty() && !type.contains("json", ignoreCase = true) && response.code != 204) {
                    response.close()
                    throw ApiException(ApiErrorKind.NON_JSON, httpCode = response.code, detail = type, endpoint = path)
                }
            }
            return response
        }
        val body = runCatching { response.peekBody(64 * 1024).string() }.getOrNull()
        response.close()
        val detail = body?.let(::extractDetail)
        val kind = when (response.code) {
            401 -> ApiErrorKind.UNAUTHORIZED
            403 -> ApiErrorKind.FORBIDDEN
            404 -> ApiErrorKind.NOT_FOUND
            else -> ApiErrorKind.SERVER
        }
        throw ApiException(kind, httpCode = response.code, detail = detail, endpoint = path)
    }

    private fun extractDetail(body: String): String? = runCatching {
        val el = json.parseToJsonElement(body)
        val detail = (el as? JsonObject)?.get("detail") ?: return@runCatching body.take(200)
        when (detail) {
            is JsonPrimitive -> detail.content
            is JsonArray -> detail.joinToString("; ") { item ->
                (item as? JsonObject)?.let { o ->
                    val loc = (o["loc"] as? JsonArray)?.joinToString(".") { it.jsonPrimitive.content }
                    val msg = o["msg"]?.jsonPrimitive?.content
                    listOfNotNull(loc, msg).joinToString(": ")
                } ?: item.toString()
            }
            else -> detail.toString()
        }
    }.getOrElse { body.take(200).ifBlank { null } }
}

/**
 * OAuth refresh on 401. Client tokens and Basic credentials cannot be refreshed: a 401 there
 * means the credentials are gone and the session is cleared (only on 401, never on other 4xx).
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val authStore: AuthStore,
    private val serverStore: ServerStore,
    private val json: Json,
) : Authenticator {

    /** Bare client for the refresh call: no authenticator, no error mapping. */
    private val refreshClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }
    private val refreshing = AtomicBoolean(false)

    override fun authenticate(route: Route?, response: Response): Request? {
        val cfg = serverStore.config.value
        if (response.request.url.host !in cfg.knownHosts) return null
        if (responseCount(response) >= 2) return giveUp()

        val state = authStore.state.value
        when (state.mode) {
            AuthMode.OAUTH -> {
                val sentHeader = response.request.header("Authorization")
                val current = state.authorizationHeader()
                // Another thread already refreshed: just retry with the new token.
                if (sentHeader != null && current != null && sentHeader != current) {
                    return response.request.newBuilder().header("Authorization", current).build()
                }
                val refresh = state.refreshToken ?: return giveUp()
                if (!refreshing.compareAndSet(false, true)) {
                    // Another thread is refreshing. Wait for the token to actually change before
                    // retrying: retrying with the old one would burn this request's last attempt
                    // and clear a session that is about to become valid again.
                    val before = state.accessToken
                    repeat(20) {
                        Thread.sleep(150)
                        val now = authStore.state.value
                        if (!now.isLoggedIn) return null
                        val header = now.authorizationHeader()
                        if (now.accessToken != before && header != null) {
                            return response.request.newBuilder().header("Authorization", header).build()
                        }
                    }
                    return null
                }
                try {
                    val base = cfg.activeHttpUrl ?: return giveUp()
                    val tokenUrl = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/api/token").build()
                    val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).build()
                    // Same Cloudflare headers as any other request: without them a protected
                    // tunnel answers with its login page and the session would be wiped.
                    val req = Request.Builder().url(tokenUrl).post(form).apply { applyCloudflareHeaders(this, cfg) }.build()
                    refreshClient.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return giveUp()
                        val obj = json.parseToJsonElement(r.body.string()).jsonObject
                        val access = obj["access_token"]?.jsonPrimitive?.content ?: return giveUp()
                        val newRefresh = obj["refresh_token"]?.jsonPrimitive?.content ?: refresh
                        val expires = obj["expires"]?.jsonPrimitive?.content?.toLongOrNull()
                        authStore.setOAuth(access, newRefresh, expires, state.username)
                        return response.request.newBuilder().header("Authorization", "Bearer $access").build()
                    }
                } catch (_: Throwable) {
                    return giveUp()
                } finally {
                    refreshing.set(false)
                }
            }
            AuthMode.CLIENT_TOKEN, AuthMode.BASIC -> return giveUp()
            AuthMode.NONE -> return null
        }
    }

    private fun giveUp(): Request? {
        if (authStore.state.value.isLoggedIn) authStore.clear(notify = true)
        return null
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var n = 0
        while (r != null) { n++; r = r.priorResponse }
        return n
    }
}
