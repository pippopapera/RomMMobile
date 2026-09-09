package com.rommmobile.app.core.storage

import android.net.Uri

/**
 * Where the ROM tree lives. Either a plain filesystem path (All files access / legacy) or a
 * Storage Access Framework tree URI. Serialized as `direct:<path>` or `saf:<uri>`.
 */
sealed interface StorageRoot {
    data class Direct(val path: String) : StorageRoot
    data class Saf(val treeUri: String) : StorageRoot

    fun serialize(): String = when (this) {
        is Direct -> "direct:$path"
        is Saf -> "saf:$treeUri"
    }

    /** Human readable label: the raw path, or the SAF tree's last segment made readable. */
    val displayName: String
        get() = when (this) {
            is Direct -> path
            is Saf -> {
                val doc = runCatching { Uri.parse(treeUri).lastPathSegment }.getOrNull() ?: treeUri
                // "primary:ROMs" -> "Memoria interna/ROMs", "1234-5678:ROMs" -> "SD 1234-5678/ROMs"
                val (vol, rel) = doc.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val volLabel = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
                if (rel.isEmpty()) volLabel else "$volLabel/$rel"
            }
        }

    companion object {
        fun parse(raw: String?): StorageRoot? = when {
            raw.isNullOrBlank() -> null
            raw.startsWith("direct:") -> Direct(raw.removePrefix("direct:"))
            raw.startsWith("saf:") -> Saf(raw.removePrefix("saf:"))
            else -> null
        }
    }
}
