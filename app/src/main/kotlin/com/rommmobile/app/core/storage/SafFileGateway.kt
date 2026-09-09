package com.rommmobile.app.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Storage Access Framework implementation on top of DocumentsContract (no DocumentFile:
 * it re-queries the provider for every property and is painfully slow on big folders).
 *
 * Lessons from romm-mobile: `moveDocument` throws spurious errors even when it works and
 * cross-provider moves are unsupported, so files are streamed in and verified by size.
 */
class SafFileGateway(private val context: Context, override val root: StorageRoot.Saf) : FileGateway {

    private val resolver = context.contentResolver
    private val treeUri: Uri = Uri.parse(root.treeUri)
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** relDir -> document id, filled lazily; invalidated when directories are created. */
    private val dirCache = HashMap<String, String>()

    private data class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun children(docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<Child>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                out += Child(
                    docId = c.getString(0),
                    name = name,
                    isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return out
    }

    private fun dirDocId(relDir: String, create: Boolean): String? {
        if (relDir.isEmpty()) return rootDocId
        dirCache[relDir]?.let { return it }
        val parentRel = relDir.substringBeforeLast('/', "")
        val name = relDir.substringAfterLast('/')
        val parentId = dirDocId(parentRel, create) ?: return null
        val existing = children(parentId).firstOrNull { it.isDir && it.name.equals(name, ignoreCase = true) }
        val id = existing?.docId ?: if (create) {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            val created = DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: return null
            DocumentsContract.getDocumentId(created)
        } else return null
        dirCache[relDir] = id
        return id
    }

    private fun childByName(relDir: String, name: String): Child? {
        val id = dirDocId(relDir, create = false) ?: return null
        return children(id).firstOrNull { it.name == name } ?: children(id).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    override fun exists(relDir: String, name: String): Boolean = childByName(relDir, name) != null

    override fun list(relDir: String): List<FileInfo> {
        val id = dirDocId(relDir, create = false) ?: return emptyList()
        return children(id).filterNot { it.name.startsWith(".") }.map { FileInfo(it.name, it.isDir, it.size, it.modified) }
    }

    override fun ensureDir(relDir: String): Boolean = dirDocId(relDir, create = true) != null

    override fun openOutput(relDir: String, name: String): OutputStream {
        val dirId = dirDocId(relDir, create = true) ?: throw FileNotFoundException("Cannot create $relDir")
        childByName(relDir, name)?.let { existing ->
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.docId))
        }
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
        val mime = mimeFor(name)
        val fileUri = DocumentsContract.createDocument(resolver, dirUri, mime, name) ?: throw FileNotFoundException("Cannot create $name in $relDir")
        // Some providers append an extension for known mime types: rename back if needed.
        val created = childByName(relDir, name)
        if (created == null) {
            runCatching { DocumentsContract.renameDocument(resolver, fileUri, name) }
        }
        return resolver.openOutputStream(fileUri, "w") ?: throw FileNotFoundException("Cannot open $name for writing")
    }

    /**
     * The frontend must never see a half-written ROM, so the copy goes to a hidden temp name
     * and only a rename (cheap, atomic for the provider) publishes the final name. Writing
     * straight to `name` would leave a scannable partial file for the whole copy.
     */
    override fun moveIn(src: File, relDir: String, name: String): Boolean {
        val expected = src.length()
        val tmpName = ".$name.rommpart"
        runCatching { delete(relDir, tmpName) }
        openOutput(relDir, tmpName).use { out -> src.inputStream().use { it.copyTo(out, 1 shl 18) } }
        if (sizeOf(relDir, tmpName) != expected) {
            delete(relDir, tmpName)
            return false
        }
        // Clear a stale destination only now that the new content is complete on disk.
        childByName(relDir, name)?.let { existing ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.docId)) }
        }
        val tmp = childByName(relDir, tmpName) ?: return false
        runCatching { DocumentsContract.renameDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, tmp.docId), name) }
        if (sizeOf(relDir, name) != expected) {
            delete(relDir, tmpName)
            return false
        }
        src.delete()
        return true
    }

    override fun delete(relDir: String, name: String): Boolean {
        val child = childByName(relDir, name) ?: return false
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)) }.getOrDefault(false)
        if (child.isDir) dirCache.keys.removeAll { it == "$relDir/$name".trimStart('/') || it.startsWith("$relDir/$name/".trimStart('/')) }
        return ok
    }

    override fun moveWithin(fromRelDir: String, name: String, toRelDir: String): Boolean {
        val child = childByName(fromRelDir, name) ?: return false
        val fromId = dirDocId(fromRelDir, create = false) ?: return false
        val toId = dirDocId(toRelDir, create = true) ?: return false
        childByName(toRelDir, name)?.let { existing ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.docId)) }
        }
        // moveDocument is known to throw even when it succeeded: always re-check by listing.
        runCatching {
            DocumentsContract.moveDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId),
                DocumentsContract.buildDocumentUriUsingTree(treeUri, fromId),
                DocumentsContract.buildDocumentUriUsingTree(treeUri, toId),
            )
        }
        dirCache.keys.removeAll { it.startsWith("$fromRelDir/$name".trimStart('/')) }
        val moved = childByName(toRelDir, name)
        if (moved != null && (moved.isDir || moved.size == child.size)) return true
        // Provider without move support: stream copy for plain files.
        if (child.isDir) return false
        val srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
        resolver.openInputStream(srcUri)?.use { input -> openOutput(toRelDir, name).use { input.copyTo(it, 1 shl 18) } } ?: return false
        if (sizeOf(toRelDir, name) != child.size) { delete(toRelDir, name); return false }
        runCatching { DocumentsContract.deleteDocument(resolver, srcUri) }
        return true
    }

    override fun sizeOf(relDir: String, name: String): Long = childByName(relDir, name)?.size ?: -1L

    override fun freeSpaceBytes(): Long = runCatching {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            android.system.Os.fstatvfs(pfd.fileDescriptor).let { it.f_bavail * it.f_frsize }
        } ?: -1L
    }.getOrDefault(-1L)

    override fun displayPath(relDir: String): String = if (relDir.isEmpty()) root.displayName else root.displayName + "/" + relDir

    override fun isWritable(): Boolean = runCatching {
        val persisted = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        persisted && dirDocId("", create = false) != null && children(rootDocId).let { true }
    }.getOrDefault(false)

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "txt", "m3u", "cue" -> "text/plain"
        else -> "application/octet-stream"
    }
}
