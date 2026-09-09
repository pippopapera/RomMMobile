package com.rommmobile.app.core.storage

import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class DirectFileGateway(override val root: StorageRoot.Direct) : FileGateway {

    private val rootFile = File(root.path)

    private fun dir(relDir: String): File = if (relDir.isEmpty()) rootFile else File(rootFile, relDir)

    override fun exists(relDir: String, name: String): Boolean = File(dir(relDir), name).exists()

    override fun list(relDir: String): List<FileInfo> {
        val d = dir(relDir)
        val children = d.listFiles() ?: return emptyList()
        return children.mapNotNull { f ->
            if (f.name.startsWith(".")) return@mapNotNull null
            FileInfo(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified())
        }
    }

    override fun ensureDir(relDir: String): Boolean {
        val d = dir(relDir)
        return d.isDirectory || d.mkdirs()
    }

    override fun openOutput(relDir: String, name: String): OutputStream {
        ensureDir(relDir)
        return FileOutputStream(File(dir(relDir), name))
    }

    override fun moveIn(src: File, relDir: String, name: String): Boolean {
        if (!ensureDir(relDir)) return false
        val dst = File(dir(relDir), name)
        val expected = src.length()
        if (dst.exists()) dst.delete()
        if (src.renameTo(dst) && dst.length() == expected) return true
        // Different volume (cache on internal, ROMs on SD): stream copy, fsync, verify, delete.
        // Hidden temp name: a frontend scanning mid-copy must not pick the partial file up.
        val tmp = File(dir(relDir), ".$name.rommpart")
        try {
            src.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out, 1 shl 18)
                    out.fd.sync()
                }
            }
            if (tmp.length() != expected) { tmp.delete(); return false }
            if (!tmp.renameTo(dst)) { tmp.delete(); return false }
            if (dst.length() != expected) return false
            src.delete()
            return true
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    override fun delete(relDir: String, name: String): Boolean {
        val f = File(dir(relDir), name)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    override fun moveWithin(fromRelDir: String, name: String, toRelDir: String): Boolean {
        if (!ensureDir(toRelDir)) return false
        val src = File(dir(fromRelDir), name)
        val dst = File(dir(toRelDir), name)
        if (dst.exists()) { if (dst.isDirectory) dst.deleteRecursively() else dst.delete() }
        if (src.renameTo(dst)) return true
        if (src.isDirectory) {
            val ok = src.copyRecursively(dst, overwrite = true)
            if (ok) src.deleteRecursively()
            return ok
        }
        return moveIn(src, toRelDir, name)
    }

    override fun sizeOf(relDir: String, name: String): Long = File(dir(relDir), name).let { if (it.exists()) it.length() else -1L }

    override fun freeSpaceBytes(): Long = runCatching {
        val probe = generateSequence(rootFile) { it.parentFile }.firstOrNull { it.exists() } ?: return -1L
        StatFs(probe.absolutePath).availableBytes
    }.getOrDefault(-1L)

    override fun displayPath(relDir: String): String = dir(relDir).absolutePath

    override fun isWritable(): Boolean {
        if (!rootFile.isDirectory && !rootFile.mkdirs()) return false
        val probe = File(rootFile, ".romm_write_test")
        return try {
            probe.writeText("ok"); probe.delete()
        } catch (_: Throwable) { false }
    }
}
