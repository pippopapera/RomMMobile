package com.rommmobile.app.core.network

import android.content.Context
import com.rommmobile.app.R
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class ApiErrorKind {
    NOT_CONFIGURED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    NON_JSON,
    TIMEOUT,
    OFFLINE,
    TLS,
    CLEARTEXT_BLOCKED,
    SERVER,
    PARSE,
    CANCELLED,
    UNKNOWN,
}

/**
 * The only exception type the UI ever needs to understand. Extends IOException so it can be
 * raised from OkHttp interceptors and still flow through coroutine cancellation correctly.
 */
class ApiException(
    val kind: ApiErrorKind,
    val httpCode: Int = 0,
    val detail: String? = null,
    val endpoint: String? = null,
    cause: Throwable? = null,
) : IOException("$kind${if (httpCode != 0) " ($httpCode)" else ""}${detail?.let { ": $it" } ?: ""}", cause) {

    /** Human readable, in the device language. Tells what happened and what to do. */
    fun userMessage(context: Context): String = when (kind) {
        ApiErrorKind.NOT_CONFIGURED -> context.getString(R.string.error_not_configured)
        ApiErrorKind.UNAUTHORIZED -> context.getString(R.string.error_unauthorized)
        ApiErrorKind.FORBIDDEN -> context.getString(R.string.error_forbidden, detail ?: "")
        ApiErrorKind.NOT_FOUND -> context.getString(R.string.error_not_found, endpoint ?: "")
        ApiErrorKind.NON_JSON -> context.getString(R.string.error_non_json)
        ApiErrorKind.TIMEOUT -> context.getString(R.string.error_timeout)
        ApiErrorKind.OFFLINE -> context.getString(R.string.error_offline)
        ApiErrorKind.TLS -> context.getString(R.string.error_tls)
        ApiErrorKind.CLEARTEXT_BLOCKED -> context.getString(R.string.error_cleartext_blocked)
        ApiErrorKind.SERVER -> context.getString(R.string.error_server, httpCode, detail ?: "")
        ApiErrorKind.PARSE -> context.getString(R.string.error_parse)
        ApiErrorKind.CANCELLED -> context.getString(R.string.error_cancelled)
        ApiErrorKind.UNKNOWN -> context.getString(R.string.error_unknown, detail ?: cause?.javaClass?.simpleName ?: "")
    }

    companion object {
        fun from(t: Throwable): ApiException = when (t) {
            is ApiException -> t
            is kotlinx.coroutines.CancellationException -> ApiException(ApiErrorKind.CANCELLED, cause = t)
            is SocketTimeoutException -> ApiException(ApiErrorKind.TIMEOUT, cause = t)
            is InterruptedIOException -> if (t.message?.contains("timeout", true) == true) ApiException(ApiErrorKind.TIMEOUT, cause = t) else ApiException(ApiErrorKind.CANCELLED, cause = t)
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> ApiException(ApiErrorKind.OFFLINE, cause = t)
            is SSLException -> ApiException(ApiErrorKind.TLS, detail = t.message, cause = t)
            is SocketException -> ApiException(ApiErrorKind.OFFLINE, detail = t.message, cause = t)
            is SerializationException -> ApiException(ApiErrorKind.PARSE, detail = t.message?.take(160), cause = t)
            is IllegalArgumentException -> if (t.message?.contains("Unexpected JSON", true) == true) ApiException(ApiErrorKind.PARSE, detail = t.message?.take(160), cause = t) else ApiException(ApiErrorKind.UNKNOWN, detail = t.message, cause = t)
            is IOException -> ApiException(ApiErrorKind.OFFLINE, detail = t.message, cause = t)
            else -> ApiException(ApiErrorKind.UNKNOWN, detail = t.message, cause = t)
        }
    }
}

/** Runs [block] mapping every failure to [ApiException]; cancellation is rethrown untouched. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(ApiException.from(t))
}

fun Throwable.asApi(): ApiException = ApiException.from(this)
