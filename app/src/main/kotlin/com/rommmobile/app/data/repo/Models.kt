package com.rommmobile.app.data.repo

import androidx.compose.runtime.Immutable
import com.rommmobile.app.core.network.UrlNormalizer
import com.rommmobile.app.data.api.PlatformDto
import com.rommmobile.app.data.api.RomDto
import com.rommmobile.app.data.db.CollectionEntity
import com.rommmobile.app.data.db.CollectionKind
import com.rommmobile.app.data.db.PlatformEntity
import com.rommmobile.app.data.db.RomEntity
import com.rommmobile.app.data.prefs.SortField
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Immutable
data class Platform(
    val id: Int,
    val slug: String,
    val fsSlug: String,
    val name: String,
    val romCount: Int,
    val firmwareCount: Int,
    val sizeBytes: Long,
) {
    fun iconUrl(base: String?): String? = base?.let { UrlNormalizer.join(it, "assets/platforms/$slug.ico") }
}

@Immutable
@Serializable
data class RomFile(
    val id: Int,
    val fileName: String,
    val filePath: String? = null,
    val sizeBytes: Long = 0,
    val md5: String? = null,
    val sha1: String? = null,
    val crc: String? = null,
    val category: String? = null,
    val isTopLevel: Boolean = true,
)

@Immutable
@Serializable
data class Sibling(
    val id: Int,
    val name: String?,
    val fsNameNoTags: String?,
    val isMain: Boolean = false,
)

@Immutable
data class Rom(
    val id: Int,
    val platformId: Int,
    val platformSlug: String,
    val platformName: String,
    val name: String,
    val fsName: String,
    val fsNameNoExt: String,
    val fsExtension: String,
    val sizeBytes: Long,
    val coverSmall: String?,
    val coverLarge: String?,
    val regions: List<String>,
    val languages: List<String>,
    val revision: String?,
    val siblingCount: Int,
    val hasMultipleFiles: Boolean,
    val md5: String?,
    val sha1: String?,
    val crc: String?,
    val summary: String?,
    val firstReleaseDate: Long?,
    val rating: Double?,
    val genres: List<String>,
    val companies: List<String>,
    val files: List<RomFile>,
    val siblings: List<Sibling>,
    val missingFromFs: Boolean,
) {
    /** Region badge like the web app: first region, upper-cased, max 3 chars. */
    val regionBadge: String? get() = regions.firstOrNull()?.uppercase()?.take(3)?.takeIf { it.isNotBlank() }

    /** Name RomM would give the download: the archive name for multi-file games. */
    val downloadFileName: String get() = if (hasMultipleFiles) "$fsNameNoExt.zip" else fsName

    /** Key used to match local files regardless of compression: lower-case, no extension. */
    val nameKey: String get() = nameKeyOf(fsName)

    companion object {
        fun nameKeyOf(fileName: String): String {
            var s = fileName.lowercase().trim()
            // Strip one archive extension and one inner extension: "game.bin.zip" -> "game".
            for (ext in ARCHIVE_EXTS) if (s.endsWith(ext)) { s = s.removeSuffix(ext); break }
            val dot = s.lastIndexOf('.')
            if (dot > 0 && s.length - dot <= 6) s = s.substring(0, dot)
            return s
        }
        private val ARCHIVE_EXTS = listOf(".zip", ".7z", ".rar")
    }
}

sealed interface LibrarySource {
    @Immutable data class ByPlatform(val platformId: Int, val platformSlug: String) : LibrarySource
    @Immutable data class ByCollection(val id: Int) : LibrarySource
    @Immutable data class ByVirtualCollection(val id: String) : LibrarySource
    @Immutable data class BySmartCollection(val id: Int) : LibrarySource
    @Immutable data object Search : LibrarySource
}

@Immutable
data class LibraryQuery(
    val source: LibrarySource,
    val searchTerm: String? = null,
    val sort: SortField = SortField.NAME,
    val descending: Boolean = false,
    val regions: List<String> = emptyList(),
)

@Immutable
data class LibraryMeta(
    val total: Int? = null,
    /** Letter (`#`, `A`..`Z`, other) to absolute offset, from the server. */
    val charIndex: Map<String, Int> = emptyMap(),
)

@Immutable
data class Collection(
    val kind: CollectionKind,
    val remoteId: String,
    val name: String,
    val description: String?,
    val romCount: Int,
    val coverPaths: List<String>,
    val virtualType: String?,
    val isFavorite: Boolean,
) {
    val key: String get() = "${kind.name.lowercase()}:$remoteId"
    fun coverUrls(base: String?): List<String> = if (base == null) emptyList() else coverPaths.map { UrlNormalizer.join(base, it) }
    fun toSource(): LibrarySource = when (kind) {
        CollectionKind.USER -> LibrarySource.ByCollection(remoteId.toInt())
        CollectionKind.VIRTUAL -> LibrarySource.ByVirtualCollection(remoteId)
        CollectionKind.SMART -> LibrarySource.BySmartCollection(remoteId.toInt())
    }
}

/* ---------------- mappers ---------------- */

fun PlatformDto.toEntity(now: Long) = PlatformEntity(
    id = id,
    slug = slug,
    fsSlug = fsSlug ?: slug,
    name = customName?.takeIf { it.isNotBlank() } ?: displayName?.takeIf { it.isNotBlank() } ?: name ?: slug,
    romCount = romCount,
    firmwareCount = firmwareCount,
    sizeBytes = fsSizeBytes,
    updatedAt = now,
)

fun PlatformEntity.toModel() = Platform(id, slug, fsSlug, name, romCount, firmwareCount, sizeBytes)

private const val SEP = "\u001F" // ASCII unit separator: never appears in names
private fun List<String>.pack() = joinToString(SEP)
private fun String.unpack(): List<String> = if (isEmpty()) emptyList() else split(SEP)

fun RomDto.toEntity(json: Json, now: Long): RomEntity {
    val safeFsName = fsName ?: (fsNameNoExt?.let { n -> fsExtension?.let { "$n.$it" } ?: n }) ?: "rom_$id"
    val noExt = fsNameNoExt ?: safeFsName.substringBeforeLast('.', safeFsName)
    val files = files.map { RomFile(it.id, it.fileName, it.filePath, it.fileSizeBytes, it.md5Hash, it.sha1Hash, it.crcHash, it.category, it.isTopLevel) }
    val siblings = siblingRoms.map { Sibling(it.id, it.name, it.fsNameNoTags, it.isMainSibling) }
    return RomEntity(
        id = id,
        platformId = platformId,
        platformSlug = platformSlug ?: platformFsSlug ?: "",
        platformName = platformCustomName?.takeIf { it.isNotBlank() } ?: platformDisplayName ?: platformSlug ?: "",
        name = name?.takeIf { it.isNotBlank() } ?: fsNameNoTags ?: noExt,
        fsName = safeFsName,
        fsNameNoExt = noExt,
        fsExtension = fsExtension ?: safeFsName.substringAfterLast('.', ""),
        sizeBytes = fsSizeBytes,
        coverSmallPath = pathCoverSmall?.takeIf { it.isNotBlank() },
        coverLargePath = pathCoverLarge?.takeIf { it.isNotBlank() },
        urlCover = urlCover?.takeIf { it.isNotBlank() },
        regions = regions.pack(),
        languages = languages.pack(),
        revision = revision,
        siblingCount = siblingRoms.size,
        hasMultipleFiles = hasMultipleFiles,
        md5 = md5Hash,
        sha1 = sha1Hash,
        crc = crcHash,
        summary = summary,
        firstReleaseDate = metadatum?.firstReleaseDate,
        rating = metadatum?.averageRating,
        genres = (metadatum?.genres ?: emptyList()).pack(),
        companies = (metadatum?.companies ?: emptyList()).pack(),
        filesJson = if (files.isEmpty()) null else json.encodeToString(files),
        siblingsJson = if (siblings.isEmpty()) null else json.encodeToString(siblings),
        missingFromFs = missingFromFs,
        cachedAt = now,
    )
}

fun RomEntity.toModel(base: String?, json: Json): Rom = Rom(
    id = id,
    platformId = platformId,
    platformSlug = platformSlug,
    platformName = platformName,
    name = name,
    fsName = fsName,
    fsNameNoExt = fsNameNoExt,
    fsExtension = fsExtension,
    sizeBytes = sizeBytes,
    // Server cache first (includes user-uploaded covers), external CDN only as fallback.
    coverSmall = coverSmallPath?.let { p -> base?.let { UrlNormalizer.join(it, p) } } ?: urlCover,
    coverLarge = coverLargePath?.let { p -> base?.let { UrlNormalizer.join(it, p) } } ?: urlCover,
    regions = regions.unpack(),
    languages = languages.unpack(),
    revision = revision,
    siblingCount = siblingCount,
    hasMultipleFiles = hasMultipleFiles,
    md5 = md5,
    sha1 = sha1,
    crc = crc,
    summary = summary,
    firstReleaseDate = firstReleaseDate,
    rating = rating,
    genres = genres.unpack(),
    companies = companies.unpack(),
    files = filesJson?.let { runCatching { json.decodeFromString<List<RomFile>>(it) }.getOrNull() } ?: emptyList(),
    siblings = siblingsJson?.let { runCatching { json.decodeFromString<List<Sibling>>(it) }.getOrNull() } ?: emptyList(),
    missingFromFs = missingFromFs,
)

fun CollectionEntity.toModel() = Collection(kind, remoteId, name, description, romCount, coverPaths.unpack(), virtualType, isFavorite)

fun Collection.toEntity(sortOrder: Int) = CollectionEntity(
    key = key, kind = kind, remoteId = remoteId, name = name, description = description, romCount = romCount,
    coverPaths = coverPaths.pack(), virtualType = virtualType, isFavorite = isFavorite, sortOrder = sortOrder,
)
