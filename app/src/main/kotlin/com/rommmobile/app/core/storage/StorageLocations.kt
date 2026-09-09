package com.rommmobile.app.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

data class RootCandidate(
    val path: String,
    val label: String,
    val exists: Boolean,
    val subfolderCount: Int,
    val isRemovable: Boolean,
)

/** Finds where a launcher is likely to keep its ROM tree, on internal memory and SD cards. */
object StorageLocations {

    private val COMMON_NAMES = listOf("ROMs", "Roms", "roms", "ROM", "Games", "Emulation/roms")

    fun volumes(context: Context): List<Pair<File, Boolean>> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val out = LinkedHashMap<String, Pair<File, Boolean>>()
        for (v in sm.storageVolumes) {
            val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else runCatching {
                val m = v.javaClass.getMethod("getPath"); (m.invoke(v) as? String)?.let(::File)
            }.getOrNull()
            if (dir != null && v.state == Environment.MEDIA_MOUNTED) out[dir.absolutePath] = dir to v.isRemovable
        }
        if (out.isEmpty()) {
            val primary = Environment.getExternalStorageDirectory()
            out[primary.absolutePath] = primary to false
        }
        // App-specific external dirs also reveal mounted SD cards on old Android versions.
        context.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            val root = f.absolutePath.substringBefore("/Android/")
            if (root.isNotEmpty() && !out.containsKey(root)) out[root] = File(root) to true
        }
        return out.values.toList()
    }

    fun candidates(context: Context, preferredNames: List<String>): List<RootCandidate> {
        val names = (preferredNames + COMMON_NAMES).distinct()
        val out = ArrayList<RootCandidate>()
        val seen = HashSet<String>()
        for ((vol, removable) in volumes(context)) {
            val volLabel = if (removable) "SD" else "Internal"
            for (n in names) {
                val f = File(vol, n)
                val path = f.absolutePath
                // Android storage is case-insensitive: ROMs, roms and Roms are one folder, and
                // listing them three times makes the user pick between identical options.
                val exists = f.isDirectory
                val key = if (exists) runCatching { f.canonicalPath }.getOrDefault(path).lowercase() else path.lowercase()
                if (!seen.add(key)) continue
                val count = if (exists) (f.listFiles { c -> c.isDirectory }?.size ?: 0) else 0
                out += RootCandidate(path, "$volLabel · ${f.name}", exists, count, removable)
            }
        }
        // Existing folders first, the most populated on top; then proposals on internal memory.
        return out.sortedWith(compareByDescending<RootCandidate> { it.exists }.thenByDescending { it.subfolderCount }.thenBy { it.isRemovable })
    }

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Intent to the "All files access" screen for this app, or the generic list when unsupported. */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        if (specific.resolveActivity(context.packageManager) != null) return specific
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return if (generic.resolveActivity(context.packageManager) != null) generic else null
    }

    fun openTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    /** Android TV / Fire TV frequently ship without any SAF picker at all. */
    fun canOpenTree(context: Context): Boolean = openTreeIntent().resolveActivity(context.packageManager) != null

    /**
     * Real filesystem path behind a SAF tree URI, when there is one. With All files access we
     * prefer it: direct writes are faster than SAF and the files are immediately visible to
     * every emulator, which is the whole point of the app.
     */
    fun treeUriToPath(uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val rel = parts.getOrElse(1) { "" }
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        if (rel.isEmpty()) base else "$base/$rel"
    }.getOrNull()

    /** Direct path when it is usable, otherwise the SAF tree the picker gave us. */
    fun rootFromTree(context: Context, uri: Uri): StorageRoot {
        if (hasAllFilesAccess(context)) {
            val path = treeUriToPath(uri)
            if (path != null && (File(path).isDirectory || File(path).mkdirs())) {
                val probe = File(path, ".romm_write_test")
                val writable = runCatching { probe.writeText("ok"); probe.delete(); true }.getOrDefault(false)
                if (writable) return StorageRoot.Direct(path)
            }
        }
        return StorageRoot.Saf(uri.toString())
    }

    fun persistTree(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
