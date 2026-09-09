package com.rommmobile.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlatformDao {
    @Query("SELECT * FROM platforms WHERE romCount > 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlatformEntity>>

    @Query("SELECT * FROM platforms ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<PlatformEntity>

    @Query("SELECT * FROM platforms WHERE id = :id")
    suspend fun byId(id: Int): PlatformEntity?

    @Query("SELECT * FROM platforms WHERE slug = :slug")
    suspend fun bySlug(slug: String): PlatformEntity?

    @Upsert
    suspend fun upsertAll(items: List<PlatformEntity>)

    @Query("DELETE FROM platforms WHERE id NOT IN (:keep)")
    suspend fun deleteNotIn(keep: List<Int>)

    @Transaction
    suspend fun replaceAll(items: List<PlatformEntity>) {
        upsertAll(items)
        deleteNotIn(items.map { it.id })
    }

    @Query("SELECT COUNT(*) FROM platforms")
    suspend fun count(): Int
}

@Dao
interface RomDao {
    @Upsert
    suspend fun upsertAll(items: List<RomEntity>)

    @Upsert
    suspend fun upsert(item: RomEntity)

    @Query("SELECT * FROM roms WHERE id = :id")
    suspend fun byId(id: Int): RomEntity?

    @Query("SELECT * FROM roms WHERE platformId = :platformId ORDER BY name COLLATE NOCASE, id")
    fun pagingByPlatform(platformId: Int): PagingSource<Int, RomEntity>

    @Query("SELECT COUNT(*) FROM roms WHERE platformId = :platformId")
    suspend fun countByPlatform(platformId: Int): Int

    @Query("DELETE FROM roms WHERE cachedAt < :olderThan")
    suspend fun prune(olderThan: Long)

    @Query("SELECT COUNT(*) FROM roms")
    suspend fun count(): Int
}

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(item: DownloadEntity): Long

    @Upsert
    suspend fun upsert(item: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE romId = :romId AND fileName = :fileName LIMIT 1")
    suspend fun byRomAndName(romId: Int, fileName: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY CASE state WHEN 'DOWNLOADING' THEN 0 WHEN 'VERIFYING' THEN 0 WHEN 'EXTRACTING' THEN 0 WHEN 'MOVING' THEN 0 WHEN 'PENDING' THEN 1 WHEN 'PAUSED' THEN 2 WHEN 'FAILED' THEN 3 ELSE 4 END, priority DESC, createdAt ASC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE state IN ('PENDING','DOWNLOADING','VERIFYING','EXTRACTING','MOVING','PAUSED')")
    fun observeOpen(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE romId = :romId AND state NOT IN ('COMPLETED','FAILED','CANCELLED','SKIPPED')")
    fun observeOpenForRom(romId: Int): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE state = 'PENDING' AND notBefore <= :now ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    suspend fun nextPending(limit: Int, now: Long = System.currentTimeMillis()): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state IN ('DOWNLOADING','VERIFYING','EXTRACTING','MOVING')")
    suspend fun active(): List<DownloadEntity>

    /** Items the scheduler still has to deal with: paused ones are not counted. */
    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('PENDING','DOWNLOADING','VERIFYING','EXTRACTING','MOVING')")
    suspend fun countOpen(): Int

    @Query("UPDATE downloads SET state = 'PENDING', error = :error, notBefore = :notBefore, updatedAt = :now WHERE id = :id")
    suspend fun scheduleRetry(id: Long, notBefore: Long, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: DownloadState, error: String? = null, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET state = 'PENDING', error = NULL, updatedAt = :now WHERE state IN ('DOWNLOADING','VERIFYING','EXTRACTING','MOVING')")
    suspend fun requeueInterrupted(now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET state = 'PAUSED', updatedAt = :now WHERE state IN ('PENDING','DOWNLOADING')")
    suspend fun pauseAllOpen(now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET state = 'PENDING', error = NULL, notBefore = 0, updatedAt = :now WHERE state = 'PAUSED'")
    suspend fun resumeAllPaused(now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, updatedAt = :now WHERE id = :id")
    suspend fun setProgress(id: Long, downloaded: Long, total: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET fileName = :fileName WHERE id = :id")
    suspend fun setFileName(id: Long, fileName: String)

    @Query("UPDATE downloads SET state = 'COMPLETED', resultPath = :resultPath, completedAt = :now, updatedAt = :now, downloadedBytes = totalBytes WHERE id = :id")
    suspend fun complete(id: Long, resultPath: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET attempt = attempt + 1 WHERE id = :id")
    suspend fun bumpAttempt(id: Long)

    @Query("DELETE FROM downloads WHERE state IN ('COMPLETED','CANCELLED','SKIPPED')")
    suspend fun clearFinished()

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED' ORDER BY completedAt DESC LIMIT :limit")
    suspend fun recentCompleted(limit: Int): List<DownloadEntity>

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DownloadEntity>
}

@Dao
interface LocalFileDao {
    @Query("SELECT nameKey FROM local_files WHERE platformSlug = :slug")
    fun observeKeys(slug: String): Flow<List<String>>

    @Query("SELECT * FROM local_files WHERE platformSlug = :slug ORDER BY fileName COLLATE NOCASE")
    fun observeBySlug(slug: String): Flow<List<LocalFileEntity>>

    @Query("SELECT * FROM local_files ORDER BY platformSlug, fileName COLLATE NOCASE")
    fun observeAll(): Flow<List<LocalFileEntity>>

    @Query("SELECT platformSlug, COUNT(*) AS count, SUM(sizeBytes) AS bytes FROM local_files GROUP BY platformSlug")
    fun observeSummary(): Flow<List<LocalSummaryRow>>

    @Query("SELECT COUNT(*) FROM local_files WHERE platformSlug = :slug AND nameKey = :nameKey")
    suspend fun countByKey(slug: String, nameKey: String): Int

    @Query("SELECT * FROM local_files WHERE platformSlug = :slug AND nameKey = :nameKey")
    suspend fun byKey(slug: String, nameKey: String): List<LocalFileEntity>

    @Upsert
    suspend fun upsertAll(items: List<LocalFileEntity>)

    @Query("DELETE FROM local_files WHERE platformSlug = :slug")
    suspend fun deleteBySlug(slug: String)

    @Query("DELETE FROM local_files WHERE platformSlug = :slug AND fileName = :fileName")
    suspend fun delete(slug: String, fileName: String)

    @Transaction
    suspend fun replaceForSlug(slug: String, items: List<LocalFileEntity>) {
        deleteBySlug(slug)
        if (items.isNotEmpty()) upsertAll(items)
    }
}

data class LocalSummaryRow(val platformSlug: String, val count: Int, val bytes: Long)

@Dao
interface FolderMappingDao {
    @Query("SELECT * FROM folder_mappings ORDER BY platformSlug")
    fun observeAll(): Flow<List<FolderMappingEntity>>

    @Query("SELECT * FROM folder_mappings")
    suspend fun all(): List<FolderMappingEntity>

    @Query("SELECT * FROM folder_mappings WHERE platformSlug = :slug")
    suspend fun bySlug(slug: String): FolderMappingEntity?

    @Query("SELECT * FROM folder_mappings WHERE platformSlug = :slug")
    fun observeBySlug(slug: String): Flow<FolderMappingEntity?>

    @Upsert
    suspend fun upsert(item: FolderMappingEntity)

    @Query("DELETE FROM folder_mappings WHERE platformSlug = :slug")
    suspend fun delete(slug: String)

    @Query("DELETE FROM folder_mappings WHERE source != 'USER'")
    suspend fun deleteAutomatic()

    @Query("DELETE FROM folder_mappings")
    suspend fun deleteAll()

    @Query("UPDATE folder_mappings SET fileCount = :count WHERE platformSlug = :slug")
    suspend fun setFileCount(slug: String, count: Int)
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections WHERE kind = :kind ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeByKind(kind: CollectionKind): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE key = :key")
    suspend fun byKey(key: String): CollectionEntity?

    @Upsert
    suspend fun upsertAll(items: List<CollectionEntity>)

    @Query("SELECT key FROM collections WHERE kind = :kind")
    suspend fun keysOf(kind: CollectionKind): List<String>

    @Query("DELETE FROM collections WHERE key IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    @Transaction
    suspend fun replaceKind(kind: CollectionKind, items: List<CollectionEntity>) {
        upsertAll(items)
        // Virtual collections run to tens of thousands of rows; one NOT IN would blow past
        // SQLite's 999-variable limit on Android 8-10. Diff in Kotlin, delete in chunks.
        val keep = items.map { it.key }.toHashSet()
        val stale = keysOf(kind).filterNot { it in keep }
        stale.chunked(500).forEach { deleteKeys(it) }
    }
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY at DESC LIMIT 12")
    fun observeRecent(): Flow<List<RecentSearchEntity>>

    @Upsert
    suspend fun upsert(item: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE term = :term")
    suspend fun delete(term: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clear()
}
