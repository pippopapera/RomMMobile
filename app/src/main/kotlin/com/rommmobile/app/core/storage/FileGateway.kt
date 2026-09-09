package com.rommmobile.app.core.storage

import android.content.Context
import java.io.File
import java.io.OutputStream

data class FileInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Single abstraction over `java.io.File` (All files access) and the Storage Access Framework.
 * Every path is relative to the chosen storage root (`""` = the root itself, `"psx/Game"` nested).
 * All methods block: call them on Dispatchers.IO.
 */
interface FileGateway {
    val root: StorageRoot

    fun exists(relDir: String, name: String): Boolean
    fun list(relDir: String): List<FileInfo>
    fun listDirs(relDir: String): List<String> = list(relDir).filter { it.isDirectory }.map { it.name }
    fun ensureDir(relDir: String): Boolean
    fun openOutput(relDir: String, name: String): OutputStream
    /** Moves a private temp file into place. Verified by size afterwards, never trusted blindly. */
    fun moveIn(src: File, relDir: String, name: String): Boolean
    fun delete(relDir: String, name: String): Boolean
    /** Moves an entry between two directories under the same root (rename when possible). */
    fun moveWithin(fromRelDir: String, name: String, toRelDir: String): Boolean
    fun sizeOf(relDir: String, name: String): Long
    fun freeSpaceBytes(): Long
    fun displayPath(relDir: String): String
    fun isWritable(): Boolean
}

object FileGatewayFactory {
    fun create(context: Context, root: StorageRoot): FileGateway = when (root) {
        is StorageRoot.Direct -> DirectFileGateway(root)
        is StorageRoot.Saf -> SafFileGateway(context, root)
    }
}

/** Names are compared case-insensitively and without spaces, dashes and underscores. */
fun normalizeFolderName(name: String): String = buildString(name.length) {
    for (c in name) {
        if (c == ' ' || c == '-' || c == '_' || c == '.') continue
        append(c.lowercaseChar())
    }
}
