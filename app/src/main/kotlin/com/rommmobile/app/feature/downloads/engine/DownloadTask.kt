package com.rommmobile.app.feature.downloads.engine

import android.content.Context
import android.os.StatFs
import com.rommmobile.app.core.storage.DirectFileGateway
import com.rommmobile.app.core.storage.FileGateway
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.UrlNormalizer
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.db.DownloadDao
import com.rommmobile.app.data.db.DownloadEntity
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.repo.LocalLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

/**
 * One download from first byte to a file the frontend can see:
 * DOWNLOADING (.part in private cache, HTTP Range resume, streaming hash)
 * -> VERIFYING (md5/sha1/crc when the server knows them)
 * -> EXTRACTING (zip / 7z, staged) or MOVING (verified copy)
 * -> COMPLETED (local index updated).
 */
class DownloadTask(
    private val context: Context,
    private val client: OkHttpClient,
    private val serverStore: ServerStore,
    private val dao: DownloadDao,
    private val mapping: MappingRepository,
    private val localLibrary: LocalLibraryRepository,
    private val log: FileLogger,
    private val publish: (DownloadProgress) -> Unit,
) {
    /**
     * Why the engine asked this task to stop. The coroutine is deliberately NOT cancelled:
     * `withContext` rethrows the cancellation even when the body catches it and returns a
     * value, so the outcome would never reach the database. Instead the HTTP call is aborted
     * and the loops below unwind normally, leaving the DB writes on a live coroutine.
     */
    @Volatile private var stopReason: DownloadState? = null

    private var call: Call? = null

    /** Abort this task; [run] returns the matching outcome instead of throwing. */
    fun requestStop(reason: DownloadState) {
        stopReason = reason
        runCatching { call?.cancel() }
    }

    private fun stopOutcome(part: File, reason: DownloadState): TaskOutcome = when (reason) {
        DownloadState.PAUSED -> TaskOutcome.Paused
        DownloadState.CANCELLED -> { part.delete(); TaskOutcome.Cancelled }
        else -> TaskOutcome.Deferred
    }

    suspend fun run(initial: DownloadEntity): TaskOutcome = withContext(Dispatchers.IO) {
        var entity = initial
        val part = partFile(entity.id)
        try {
            val gateway = gatewayFor(entity) ?: return@withContext TaskOutcome.Failed("no_root", retryable = false)
            dao.setState(entity.id, DownloadState.DOWNLOADING)
            publish(DownloadProgress(entity.id, entity.downloadedBytes, entity.totalBytes, 0, DownloadState.DOWNLOADING))

            // ---- resume bookkeeping ----
            var existing = if (part.exists()) part.length() else 0L
            if (existing > 0 && entity.downloadedBytes == 0L) { part.delete(); existing = 0 }

            val hasher = Hasher.forEntity(entity)
            if (existing > 0 && hasher != null) hasher.feedFile(part)

            // ---- request ----
            // Queue entries store a relative path; the host is whichever endpoint is active now.
            val absoluteUrl = if (entity.url.startsWith("http", ignoreCase = true)) entity.url else {
                val base = serverStore.config.value.activeUrl ?: return@withContext TaskOutcome.Failed("no_server", retryable = true)
                UrlNormalizer.join(base, entity.url)
            }
            val reqBuilder = Request.Builder().url(absoluteUrl).get()
            if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")
            val c = client.newCall(reqBuilder.build()).also { call = it }
            val response = c.execute()
            response.use { r ->
                var append = false
                when (r.code) {
                    206 -> append = true
                    200 -> { existing = 0; hasher?.reset() }
                    416 -> {
                        // Range beyond the end: either already complete or the server changed the file.
                        if (entity.totalBytes > 0 && existing >= entity.totalBytes) {
                            return@withContext finish(entity, part, gateway, hasher)
                        }
                        part.delete(); existing = 0; hasher?.reset()
                        return@withContext TaskOutcome.Failed("range_reset", retryable = true)
                    }
                    401, 403 -> return@withContext TaskOutcome.Failed("http_${r.code}", retryable = false)
                    404 -> return@withContext TaskOutcome.Failed("http_404", retryable = false)
                    in 500..599 -> return@withContext TaskOutcome.Failed("http_${r.code}", retryable = true)
                    else -> return@withContext TaskOutcome.Failed("http_${r.code}", retryable = false)
                }
                val body = r.body
                val remaining = body.contentLength()
                // `total` is only trustworthy when the server states it. RomM streams the
                // on-the-fly zip of a multi-file game without Content-Length, and the ROM's
                // fs_size_bytes is the size of the folder, not of the archive: treating it as
                // the expected size would reject every complete multi-disc download.
                val declaredTotal: Long? = when {
                    r.code == 206 -> parseContentRangeTotal(r.header("Content-Range")) ?: (if (remaining >= 0) existing + remaining else null)
                    remaining >= 0 -> remaining
                    else -> null
                }
                val total = declaredTotal ?: entity.totalBytes
                val serverName = parseContentDisposition(r.header("Content-Disposition"))
                if (serverName != null && serverName != entity.fileName && entity.md5 == null) {
                    dao.setFileName(entity.id, serverName)
                    entity = entity.copy(fileName = serverName)
                }
                if (total > 0) dao.setProgress(entity.id, existing, total)
                entity = entity.copy(totalBytes = total)

                // ---- disk space ----
                // Two volumes matter: the destination, and the internal cache where the .part
                // is written (they are different devices whenever the ROM folder is on SD).
                if (total > 0) {
                    val pending = total - existing
                    val free = gateway.freeSpaceBytes()
                    val need = pending * (if (entity.extract) 2.5 else 1.05) + 32L * 1024 * 1024
                    if (free in 0 until need.toLong()) return@withContext TaskOutcome.Failed("no_space", retryable = false)
                    val cacheFree = runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrDefault(-1L)
                    if (cacheFree in 0 until (pending + 32L * 1024 * 1024)) {
                        return@withContext TaskOutcome.Failed("no_space", retryable = false)
                    }
                }

                // ---- stream ----
                val speed = SpeedMeter()
                var downloaded = existing
                var lastPublish = 0L
                var lastPersist = 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 18)
                        while (true) {
                            coroutineContext.ensureActive()
                            stopReason?.let { reason ->
                                out.flush()
                                dao.setProgress(entity.id, downloaded, total)
                                return@withContext stopOutcome(part, reason)
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            hasher?.update(buf, n)
                            downloaded += n
                            val now = System.nanoTime()
                            if (now - lastPublish > 250_000_000L) {
                                lastPublish = now
                                publish(DownloadProgress(entity.id, downloaded, total, speed.sample(downloaded), DownloadState.DOWNLOADING))
                            }
                            if (now - lastPersist > 1_500_000_000L) {
                                lastPersist = now
                                dao.setProgress(entity.id, downloaded, total)
                            }
                        }
                        out.fd.sync()
                    }
                }
                // Only a server-declared size can prove truncation.
                if (declaredTotal != null && declaredTotal > 0 && downloaded != declaredTotal) {
                    dao.setProgress(entity.id, downloaded, declaredTotal)
                    return@withContext TaskOutcome.Failed("truncated", retryable = true)
                }
                dao.setProgress(entity.id, downloaded, if (total > 0) total else downloaded)
                entity = entity.copy(downloadedBytes = downloaded, totalBytes = if (total > 0) total else downloaded)
            }
            finish(entity, part, gateway, hasher)
        } catch (c: CancellationException) {
            // The whole scope is going away (process shutdown): keep the .part, let the
            // engine requeue this item at the next start. Never swallow this.
            call?.cancel()
            throw c
        } catch (t: Throwable) {
            call?.cancel()
            // An abort we asked for surfaces as a socket error: report the intended outcome.
            stopReason?.let { return@withContext stopOutcome(part, it) }
            log.w("Download", "task ${entity.id} failed: ${t.javaClass.simpleName}: ${t.message}")
            when (t) {
                is UnknownHostException, is SocketTimeoutException, is SocketException, is InterruptedIOException -> TaskOutcome.Deferred
                is IOException -> TaskOutcome.Failed(t.message ?: "io", retryable = true)
                else -> TaskOutcome.Failed(t.message ?: t.javaClass.simpleName, retryable = false)
            }
        }
    }

    private suspend fun finish(entity: DownloadEntity, part: File, gateway: FileGateway, hasher: Hasher?): TaskOutcome {
        // ---- verify ----
        if (hasher != null) {
            dao.setState(entity.id, DownloadState.VERIFYING)
            publish(DownloadProgress(entity.id, entity.totalBytes, entity.totalBytes, 0, DownloadState.VERIFYING))
            if (!hasher.matches()) {
                part.delete()
                dao.setProgress(entity.id, 0, entity.totalBytes)
                return TaskOutcome.Failed("hash_mismatch", retryable = true)
            }
        }
        // Firmware targets carry an absolute file path ("abs:/…/RetroArch/system/bios.bin");
        // gatewayFor() already rooted the gateway at its directory, so nothing is relative here.
        val relDir = if (entity.targetRelDir.startsWith("abs:")) "" else entity.targetRelDir
        if (!gateway.ensureDir(relDir)) return TaskOutcome.Failed("cannot_create_dir", retryable = false)

        val extract = entity.extract && (Extractor.isZip(entity.fileName) || Extractor.is7z(entity.fileName))
        val resultPath: String
        if (extract) {
            dao.setState(entity.id, DownloadState.EXTRACTING)
            val size = part.length().coerceAtLeast(1)
            val extractor = Extractor(gateway)
            val staging = ".romm-${entity.id}.tmp"
            val onProgress: (Long) -> Unit = { consumed ->
                publish(DownloadProgress(entity.id, consumed.coerceAtMost(size), size, 0, DownloadState.EXTRACTING))
            }
            val abort: () -> Boolean = { stopReason != null }
            val res = try {
                if (Extractor.isZip(entity.fileName)) extractor.extractZip(part, relDir, staging, abort, onProgress)
                else extractor.extract7z(part, relDir, staging, abort, onProgress)
            } catch (a: Extractor.Aborted) {
                return stopOutcome(part, stopReason ?: DownloadState.CANCELLED)
            } catch (t: Throwable) {
                // A broken archive stays broken: keeping the .part would make every retry resume
                // at EOF, get a 416 and extract the same corrupt bytes forever.
                part.delete()
                dao.setProgress(entity.id, 0, entity.totalBytes)
                throw t
            }
            part.delete()
            resultPath = gateway.displayPath(relDir)
            for (name in res.topLevelNames) {
                val isDir = gateway.list(relDir).firstOrNull { it.name == name }?.isDirectory ?: false
                localLibrary.registerDownloaded(entity.platformSlug, joinRel(relDir, name), name, if (isDir) 0 else gateway.sizeOf(relDir, name), isDir)
            }
            // A folder-per-game download counts as one present game under its folder name too.
            if (relDir.contains('/')) {
                val folder = relDir.substringAfterLast('/')
                localLibrary.registerDownloaded(entity.platformSlug, relDir, folder, 0, true)
            }
        } else {
            dao.setState(entity.id, DownloadState.MOVING)
            publish(DownloadProgress(entity.id, entity.totalBytes, entity.totalBytes, 0, DownloadState.MOVING))
            val expected = part.length()
            if (!gateway.moveIn(part, relDir, entity.fileName)) return TaskOutcome.Failed("move_failed", retryable = true)
            val actual = gateway.sizeOf(relDir, entity.fileName)
            if (actual != expected) {
                gateway.delete(relDir, entity.fileName)
                return TaskOutcome.Failed("move_size_mismatch", retryable = true)
            }
            resultPath = gateway.displayPath(relDir) + "/" + entity.fileName
            localLibrary.registerDownloaded(entity.platformSlug, joinRel(relDir, entity.fileName), entity.fileName, actual, false)
            if (relDir.contains('/')) {
                localLibrary.registerDownloaded(entity.platformSlug, relDir, relDir.substringAfterLast('/'), 0, true)
            }
        }
        dao.complete(entity.id, resultPath)
        publish(DownloadProgress(entity.id, entity.totalBytes, entity.totalBytes, 0, DownloadState.COMPLETED))
        log.i("Download", "completed ${entity.fileName} -> $resultPath")
        return TaskOutcome.Completed
    }

    private suspend fun gatewayFor(entity: DownloadEntity): FileGateway? {
        if (entity.targetRelDir.startsWith("abs:")) {
            // Firmware target outside the ROM root (RetroArch/system): plain path only.
            return DirectFileGateway(StorageRoot.Direct(entity.targetRelDir.removePrefix("abs:").substringBeforeLast('/')))
        }
        return mapping.gateway()
    }

    private fun partFile(id: Long): File = File(context.cacheDir, "downloads").apply { mkdirs() }.let { File(it, "$id.part") }

    private fun joinRel(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

    private fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.takeIf { it.isNotEmpty() && it != "*" }?.toLongOrNull()

    private fun parseContentDisposition(header: String?): String? {
        if (header == null) return null
        Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)?.groupValues?.get(1)?.let {
            return runCatching { java.net.URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull()?.substringAfterLast('/')?.takeIf { n -> n.isNotBlank() }
        }
        Regex("filename=\"?([^\";]+)\"?").find(header)?.groupValues?.get(1)?.let { return it.trim().substringAfterLast('/').takeIf { n -> n.isNotBlank() } }
        return null
    }

    /** Streaming md5 / sha1 / crc32 verifier; re-fed from disk on resume. */
    private class Hasher private constructor(private val algo: String, private val expected: String) {
        private var digest: MessageDigest? = if (algo == "crc") null else MessageDigest.getInstance(algo)
        private var crc: CRC32? = if (algo == "crc") CRC32() else null

        fun update(buf: ByteArray, n: Int) { digest?.update(buf, 0, n); crc?.update(buf, 0, n) }
        fun reset() { digest?.reset(); crc?.reset() }
        fun feedFile(f: File) { f.inputStream().use { input -> val b = ByteArray(1 shl 18); while (true) { val n = input.read(b); if (n < 0) break; update(b, n) } } }
        fun matches(): Boolean {
            val actual = digest?.digest()?.joinToString("") { "%02x".format(it) } ?: "%08x".format(crc!!.value)
            return actual.equals(expected.trim(), ignoreCase = true)
        }

        companion object {
            /**
             * Only plain files are verified. RomM hashes the CONTENT of an archive (so its
             * md5/sha1/crc match the No-Intro/Redump entry of the ROM inside), not the archive
             * bytes we receive, and multi-file games are zipped on the fly with no hash at all.
             * Hashing those would reject every good download.
             */
            fun forEntity(e: DownloadEntity): Hasher? {
                val name = e.fileName.lowercase()
                val isArchive = name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".rar")
                if (isArchive) return null
                return when {
                    !e.md5.isNullOrBlank() -> Hasher("MD5", e.md5)
                    !e.sha1.isNullOrBlank() -> Hasher("SHA-1", e.sha1)
                    !e.crc.isNullOrBlank() -> Hasher("crc", e.crc)
                    else -> null
                }
            }
        }
    }

    /** Average of the last 5 one-second samples, like romm-mobile but without the 3 s UI lag. */
    private class SpeedMeter {
        private val samples = LongArray(5)
        private var idx = 0
        private var lastBytes = -1L
        private var lastAt = 0L
        private var value = 0L
        fun sample(bytes: Long): Long {
            val now = System.nanoTime()
            if (lastBytes < 0) { lastBytes = bytes; lastAt = now; return 0 }
            val dt = now - lastAt
            if (dt < 1_000_000_000L) return value
            val rate = ((bytes - lastBytes) * 1_000_000_000L) / dt
            samples[idx % samples.size] = rate; idx++
            lastBytes = bytes; lastAt = now
            val n = minOf(idx, samples.size)
            value = samples.take(n).sum() / n
            return value
        }
    }
}
