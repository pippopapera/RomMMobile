package com.rommmobile.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val id: Int,
    val slug: String,
    val fsSlug: String,
    val name: String,
    val romCount: Int,
    val firmwareCount: Int,
    val sizeBytes: Long,
    val updatedAt: Long,
)

/**
 * Cache of ROM records seen so far (pages browsed, details opened). Not the source of truth,
 * but enough to browse offline, open details instantly and rebuild download metadata.
 */
@Entity(
    tableName = "roms",
    indices = [Index("platformId", "name"), Index("platformSlug"), Index("fsName")],
)
data class RomEntity(
    @PrimaryKey val id: Int,
    val platformId: Int,
    val platformSlug: String,
    val platformName: String,
    val name: String,
    val fsName: String,
    val fsNameNoExt: String,
    val fsExtension: String,
    val sizeBytes: Long,
    val coverSmallPath: String?,
    val coverLargePath: String?,
    val urlCover: String?,
    val regions: String,
    val languages: String,
    val revision: String?,
    val siblingCount: Int,
    val hasMultipleFiles: Boolean,
    val md5: String?,
    val sha1: String?,
    val crc: String?,
    val summary: String?,
    val firstReleaseDate: Long?,
    val rating: Double?,
    val genres: String,
    val companies: String,
    val filesJson: String?,
    val siblingsJson: String?,
    val missingFromFs: Boolean,
    val cachedAt: Long,
)

enum class DownloadState {
    PENDING, DOWNLOADING, VERIFYING, EXTRACTING, MOVING, COMPLETED, FAILED, CANCELLED, PAUSED, SKIPPED;

    val isActive: Boolean get() = this == DOWNLOADING || this == VERIFYING || this == EXTRACTING || this == MOVING
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == SKIPPED
}

enum class DownloadKind { ROM, FIRMWARE }

@Entity(tableName = "downloads", indices = [Index("state"), Index("romId", "fileName", unique = true)])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: DownloadKind,
    val romId: Int,
    val title: String,
    val platformSlug: String,
    val platformName: String,
    /** Final file name in the destination; may be refined from Content-Disposition. */
    val fileName: String,
    val url: String,
    /** Destination directory relative to the storage root, e.g. `snes` or `psx/Final Fantasy VII`. */
    val targetRelDir: String,
    val extract: Boolean,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: DownloadState,
    val error: String?,
    val attempt: Int,
    val priority: Int,
    /** Epoch millis before which the scheduler must not start this item (retry back-off). */
    val notBefore: Long,
    val md5: String?,
    val sha1: String?,
    val crc: String?,
    val coverSmallPath: String?,
    /** Where the file(s) ended up, for "open folder" and local-library bookkeeping. */
    val resultPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

/** Index of files physically present in mapped folders, keyed by normalized name. */
@Entity(tableName = "local_files", primaryKeys = ["platformSlug", "fileName"], indices = [Index("platformSlug", "nameKey")])
data class LocalFileEntity(
    val platformSlug: String,
    val fileName: String,
    val nameKey: String,
    val sizeBytes: Long,
    val relPath: String,
    val isDirectory: Boolean,
    val downloadedByApp: Boolean,
    val verifiedAt: Long,
)

enum class MappingSource { USER, DISCOVERED, PRESET, CREATED }

@Entity(tableName = "folder_mappings")
data class FolderMappingEntity(
    @PrimaryKey val platformSlug: String,
    /** Directory relative to the storage root. */
    val relDir: String,
    val source: MappingSource,
    val fileCount: Int,
    val updatedAt: Long,
)

enum class CollectionKind { USER, VIRTUAL, SMART }

@Entity(tableName = "collections", indices = [Index("kind")])
data class CollectionEntity(
    @PrimaryKey val key: String,
    val kind: CollectionKind,
    val remoteId: String,
    val name: String,
    val description: String?,
    val romCount: Int,
    val coverPaths: String,
    val virtualType: String?,
    val isFavorite: Boolean,
    val sortOrder: Int,
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val term: String,
    val at: Long,
)
