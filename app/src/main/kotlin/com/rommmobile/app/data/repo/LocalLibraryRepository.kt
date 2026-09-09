package com.rommmobile.app.data.repo

import com.rommmobile.app.core.storage.FileGateway
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.db.LocalFileDao
import com.rommmobile.app.data.db.LocalFileEntity
import com.rommmobile.app.data.db.LocalSummaryRow
import com.rommmobile.app.data.mapping.MappingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Index of what is physically on the device. Rebuilt incrementally: one platform at a time,
 * at start-up, after each download and on demand from settings. Presence badges on cards
 * read from this table, never from the filesystem on the UI path.
 */
@Singleton
class LocalLibraryRepository @Inject constructor(
    private val dao: LocalFileDao,
    private val mapping: MappingRepository,
    private val log: FileLogger,
) {
    private val scanLock = Mutex()

    private companion object {
        // Deliberately conservative: an extension goes here only if NO console uses it for a
        // ROM. "md" (Mega Drive), "dat" and "bin" belong to games and must never be listed.
        val SIDECAR_EXTENSIONS = setOf(
            "txt", "xml", "json", "ini", "cfg", "nfo", "html",
            "png", "jpg", "jpeg", "webp", "bmp", "mp4", "webm",
            "sav", "srm", "state", "st0", "st1", "ss0", "rtc", "eep", "mcr", "mcd", "vmp",
            "ips", "bps", "ups", "xdelta", "log", "bak", "tmp", "part", "rommpart",
        )
    }

    /**
     * Set of normalized name keys present for the platform; cheap to diff in the UI. Room
     * invalidates per TABLE, so every platform's rescan re-runs every observer: the
     * distinctUntilChanged is what keeps a scan of twenty consoles from rebuilding the one list
     * on screen twenty times.
     */
    fun presenceKeys(platformSlug: String): Flow<Set<String>> =
        dao.observeKeys(platformSlug).map<List<String>, Set<String>> { it.toHashSet() }.distinctUntilChanged()

    /** slug -> name keys, for mixed-platform lists (collections, search). */
    fun presenceMap(): Flow<Map<String, Set<String>>> = dao.observeAll().map<List<LocalFileEntity>, Map<String, Set<String>>> { rows ->
        val out = HashMap<String, HashSet<String>>()
        for (r in rows) out.getOrPut(r.platformSlug) { HashSet() }.add(r.nameKey)
        out
    }.distinctUntilChanged()

    fun files(platformSlug: String): Flow<List<LocalFileEntity>> = dao.observeBySlug(platformSlug)
    fun allFiles(): Flow<List<LocalFileEntity>> = dao.observeAll()
    fun summary(): Flow<List<LocalSummaryRow>> = dao.observeSummary()

    suspend fun isPresent(platformSlug: String, fileName: String): Boolean =
        dao.countByKey(platformSlug, Rom.nameKeyOf(fileName)) > 0

    /**
     * Rebuilds the whole index. [extraDirs] carries slug -> folders for platforms that have no
     * saved mapping; without it a platform whose folder was never mapped stays invisible even
     * though its games are sitting on the card.
     */
    suspend fun rescanAll(extraDirs: Map<String, List<String>> = emptyMap()) {
        val gw = mapping.gateway() ?: return
        val mappings = mapping.observeAll().first()
        val mapped = mappings.map { it.platformSlug }.toSet()
        for (m in mappings) rescan(m.platformSlug, listOf(m.relDir), gw)
        for ((slug, dirs) in extraDirs) if (slug !in mapped) rescan(slug, dirs, gw)
        // Folders can vanish (root or launcher changed): the index would otherwise keep claiming
        // those games are still on the device.
        val known = mapped + extraDirs.keys
        dao.observeAll().first().map { it.platformSlug }.distinct()
            .filterNot { it in known }
            .forEach { dao.deleteBySlug(it) }
    }

    /** Discovers unmapped folders too, then rebuilds. This is what the manual sync runs. */
    suspend fun sync(platforms: List<Platform>) {
        val extra = runCatching { mapping.candidateDirs(platforms) }.getOrDefault(emptyMap())
        rescanAll(extra)
    }

    suspend fun rescan(platformSlug: String) {
        val gw = mapping.gateway() ?: return
        val m = mapping.get(platformSlug) ?: return
        rescan(platformSlug, listOf(m.relDir), gw)
    }

    private suspend fun rescan(platformSlug: String, relDirs: List<String>, gw: FileGateway) = withContext(Dispatchers.IO) {
        scanLock.withLock {
            val now = System.currentTimeMillis()
            val previous = dao.observeBySlug(platformSlug).first().associateBy { it.fileName }
            val rows = ArrayList<LocalFileEntity>()
            val seen = HashSet<String>()
            for (relDir in relDirs) {
                val entries = runCatching { gw.list(relDir) }.getOrElse { e ->
                    log.w("LocalLib", "scan failed for $platformSlug in $relDir", e); continue
                }
                for (f in entries) {
                    if (isSidecar(f.name) || !seen.add(f.name)) continue
                    rows += LocalFileEntity(
                        platformSlug = platformSlug,
                        fileName = f.name,
                        nameKey = Rom.nameKeyOf(f.name),
                        sizeBytes = f.sizeBytes,
                        relPath = if (relDir.isEmpty()) f.name else "$relDir/${f.name}",
                        isDirectory = f.isDirectory,
                        downloadedByApp = previous[f.name]?.downloadedByApp ?: false,
                        verifiedAt = now,
                    )
                }
            }
            dao.replaceForSlug(platformSlug, rows)
            log.i("LocalLib", "$platformSlug: ${rows.size} entries in ${relDirs.joinToString()}")
        }
    }

    /**
     * Files that live next to ROMs but are not games. Indexing them would make the app claim a
     * game is present because a patch or a screenshot happens to share its name.
     */
    private fun isSidecar(name: String): Boolean {
        if (name.startsWith(".")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SIDECAR_EXTENSIONS
    }

    /** Called by the download engine right after a successful move: instant badge, no rescan. */
    suspend fun registerDownloaded(platformSlug: String, relPath: String, fileName: String, sizeBytes: Long, isDirectory: Boolean) {
        dao.upsertAll(
            listOf(
                LocalFileEntity(
                    platformSlug = platformSlug,
                    fileName = fileName,
                    nameKey = Rom.nameKeyOf(fileName),
                    sizeBytes = sizeBytes,
                    relPath = relPath,
                    isDirectory = isDirectory,
                    downloadedByApp = true,
                    verifiedAt = System.currentTimeMillis(),
                )
            )
        )
    }

    /**
     * Removes one indexed file from the device. Irreversible and it does not ask, so every caller
     * has to confirm first. It deliberately no longer refuses files the app did not download
     * itself: freeing space is the whole point, and the user knows what is on their own card.
     */
    suspend fun deleteEntry(entry: LocalFileEntity): Boolean = withContext(Dispatchers.IO) {
        val gw = mapping.gateway() ?: return@withContext false
        val dir = entry.relPath.substringBeforeLast('/', "")
        val ok = gw.delete(dir, entry.fileName)
        if (ok) dao.delete(entry.platformSlug, entry.fileName)
        ok
    }

    /** Every file on the device that belongs to this game. */
    suspend fun entriesFor(platformSlug: String, nameKey: String): List<LocalFileEntity> =
        withContext(Dispatchers.IO) { dao.byKey(platformSlug, nameKey) }

    /** Deletes a game from the device, server untouched. @return how many files went. */
    suspend fun deleteGame(platformSlug: String, nameKey: String): Int =
        entriesFor(platformSlug, nameKey).count { deleteEntry(it) }
}
