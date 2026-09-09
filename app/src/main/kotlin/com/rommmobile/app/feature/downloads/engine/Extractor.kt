package com.rommmobile.app.feature.downloads.engine

import com.rommmobile.app.core.storage.FileGateway
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Streams archive members straight into the destination gateway. Nothing is buffered in
 * memory (romm-mobile crashed on large zips exactly because it did) and nested folders
 * are preserved. Entries are written under a hidden staging folder first and moved into
 * place only when every member succeeded, so a frontend scanning mid-way sees nothing.
 */
class Extractor(private val gateway: FileGateway) {

    data class Result(val topLevelNames: List<String>, val bytesWritten: Long)

    /** Raised when [abort] asks the extraction to stop; the staging folder is removed first. */
    class Aborted : java.io.IOException("extraction aborted")

    /** [onProgress] receives bytes consumed from the archive file (0..archive.length()). */
    suspend fun extractZip(archive: File, relDir: String, stagingName: String, abort: () -> Boolean, onProgress: (Long) -> Unit): Result {
        val staging = joinRel(relDir, stagingName)
        if (!gateway.ensureDir(staging)) throw IOException("cannot create $staging")
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 18))
        val top = LinkedHashSet<String>()
        var written = 0L
        try {
            ZipInputStream(counting).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    if (abort()) throw Aborted()
                    val entry = zip.nextEntry ?: break
                    val name = sanitize(entry.name) ?: continue
                    if (entry.isDirectory) { gateway.ensureDir(joinRel(staging, name)); top += name.substringBefore('/'); continue }
                    top += name.substringBefore('/')
                    written += writeMember(staging, name, zip, abort) { onProgress(counting.count) }
                    zip.closeEntry()
                }
            }
            return finish(relDir, staging, top.toList(), written)
        } catch (t: Throwable) {
            runCatching { gateway.delete(relDir, stagingName) }
            throw t
        }
    }

    suspend fun extract7z(archive: File, relDir: String, stagingName: String, abort: () -> Boolean, onProgress: (Long) -> Unit): Result {
        val staging = joinRel(relDir, stagingName)
        if (!gateway.ensureDir(staging)) throw IOException("cannot create $staging")
        val top = LinkedHashSet<String>()
        var written = 0L
        try {
            SevenZFile.builder().setFile(archive).get().use { sz ->
                while (true) {
                    coroutineContext.ensureActive()
                    if (abort()) throw Aborted()
                    val entry = sz.nextEntry ?: break
                    val name = sanitize(entry.name) ?: continue
                    if (entry.isDirectory) { gateway.ensureDir(joinRel(staging, name)); top += name.substringBefore('/'); continue }
                    top += name.substringBefore('/')
                    val memberStream = sz.getInputStream(entry)
                    // Solid 7z archives give no per-byte position: report uncompressed bytes written so far.
                    written += writeMember(staging, name, memberStream, abort) { onProgress(written) }
                }
            }
            return finish(relDir, staging, top.toList(), written)
        } catch (t: Throwable) {
            runCatching { gateway.delete(relDir, stagingName) }
            throw t
        }
    }

    private suspend fun writeMember(staging: String, name: String, input: InputStream, abort: () -> Boolean, tick: () -> Unit): Long {
        val parent = name.substringBeforeLast('/', "")
        val file = name.substringAfterLast('/')
        val dir = joinRel(staging, parent)
        if (parent.isNotEmpty()) gateway.ensureDir(dir)
        var total = 0L
        gateway.openOutput(dir, file).use { out ->
            val buf = ByteArray(1 shl 18)
            var lastTick = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total - lastTick > (1L shl 20)) {
                    lastTick = total
                    coroutineContext.ensureActive()
                    if (abort()) throw Aborted()
                    tick()
                }
            }
            out.flush()
        }
        return total
    }

    /**
     * Publishes the staged entries. If one move fails the ones already published are removed
     * again: a frontend must never be left with half a game.
     */
    private fun finish(relDir: String, staging: String, top: List<String>, written: Long): Result {
        val stagingName = staging.substringAfterLast('/')
        val moved = ArrayList<String>(top.size)
        for (name in top) {
            if (!gateway.moveWithin(staging, name, relDir)) {
                moved.forEach { runCatching { gateway.delete(relDir, it) } }
                throw IOException("cannot move $name into place")
            }
            moved += name
        }
        gateway.delete(relDir, stagingName)
        return Result(top, written)
    }

    private fun sanitize(raw: String): String? {
        val n = raw.replace('\\', '/').trimStart('/')
        if (n.isEmpty() || n.startsWith("__MACOSX/") || n.split('/').any { it == ".." || it == "." } ) return null
        if (n.substringAfterLast('/') == ".DS_Store" || n.substringAfterLast('/').startsWith("._")) return null
        return n
    }

    private fun joinRel(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a/$b"
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object {
        fun isZip(name: String) = name.endsWith(".zip", ignoreCase = true)
        fun is7z(name: String) = name.endsWith(".7z", ignoreCase = true)
    }
}
