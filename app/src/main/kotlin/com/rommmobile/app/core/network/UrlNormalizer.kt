package com.rommmobile.app.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlNormalizer {

    /**
     * Turns whatever the user typed into a canonical base URL:
     * `192.168.50.11:8080` -> `http://192.168.50.11:8080`, strips trailing slashes and
     * fragments, keeps an optional path prefix (reverse proxies mounting RomM under /romm).
     * Returns null when the input cannot be a URL at all.
     */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        val url: HttpUrl = s.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        val path = url.encodedPath.trimEnd('/')
        val builder = url.newBuilder().encodedPath(if (path.isEmpty()) "/" else path).query(null).fragment(null)
        val normalized = builder.build().toString().trimEnd('/')
        return normalized
    }

    /** Private networks and mDNS names are allowed to speak plain HTTP; the internet is not. */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "::1" || h == "[::1]") return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal")) return true
        // A name with no dot cannot be resolved on the public internet: it is a LAN host
        // (router DNS / NetBIOS), the exact "http://romm:8080" case.
        if (!h.contains('.') && !h.contains(':')) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt(); val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        return h.startsWith("[fd") || h.startsWith("[fe80") || h.startsWith("fd") || h.startsWith("fe80")
    }

    /** `{base}{path}` with exactly one slash between the two. */
    fun join(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')
}
