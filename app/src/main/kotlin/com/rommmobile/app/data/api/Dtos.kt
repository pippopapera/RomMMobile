package com.rommmobile.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Every field the server marks "required" is still nullable / defaulted here on purpose:
 * romm-mobile crashed whole libraries on one record with a missing field. We never do.
 */

@Serializable
data class HeartbeatDto(@SerialName("SYSTEM") val system: SystemDto? = null)

@Serializable
data class SystemDto(
    @SerialName("VERSION") val version: String? = null,
    @SerialName("SHOW_SETUP_WIZARD") val showSetupWizard: Boolean = false,
)

@Serializable
data class PlatformDto(
    val id: Int,
    val slug: String = "",
    @SerialName("fs_slug") val fsSlug: String? = null,
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("rom_count") val romCount: Int = 0,
    @SerialName("firmware_count") val firmwareCount: Int = 0,
    @SerialName("fs_size_bytes") val fsSizeBytes: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RomMetadataDto(
    val genres: List<String> = emptyList(),
    val franchises: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    @SerialName("game_modes") val gameModes: List<String> = emptyList(),
    @SerialName("player_count") val playerCount: String? = null,
    @SerialName("first_release_date") val firstReleaseDate: Long? = null,
    @SerialName("average_rating") val averageRating: Double? = null,
)

@Serializable
data class RomFileDto(
    val id: Int,
    @SerialName("rom_id") val romId: Int = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    @SerialName("is_top_level") val isTopLevel: Boolean = true,
    @SerialName("crc_hash") val crcHash: String? = null,
    @SerialName("md5_hash") val md5Hash: String? = null,
    @SerialName("sha1_hash") val sha1Hash: String? = null,
    val category: String? = null,
)

@Serializable
data class SiblingDto(
    val id: Int,
    val name: String? = null,
    @SerialName("fs_name_no_tags") val fsNameNoTags: String? = null,
    @SerialName("fs_name_no_ext") val fsNameNoExt: String? = null,
    @SerialName("is_main_sibling") val isMainSibling: Boolean = false,
)

@Serializable
data class RomDto(
    val id: Int,
    @SerialName("platform_id") val platformId: Int = 0,
    @SerialName("platform_slug") val platformSlug: String? = null,
    @SerialName("platform_fs_slug") val platformFsSlug: String? = null,
    @SerialName("platform_display_name") val platformDisplayName: String? = null,
    @SerialName("platform_custom_name") val platformCustomName: String? = null,
    @SerialName("fs_name") val fsName: String? = null,
    @SerialName("fs_name_no_tags") val fsNameNoTags: String? = null,
    @SerialName("fs_name_no_ext") val fsNameNoExt: String? = null,
    @SerialName("fs_extension") val fsExtension: String? = null,
    @SerialName("fs_size_bytes") val fsSizeBytes: Long = 0,
    val name: String? = null,
    val slug: String? = null,
    val summary: String? = null,
    @SerialName("path_cover_small") val pathCoverSmall: String? = null,
    @SerialName("path_cover_large") val pathCoverLarge: String? = null,
    @SerialName("url_cover") val urlCover: String? = null,
    val revision: String? = null,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("crc_hash") val crcHash: String? = null,
    @SerialName("md5_hash") val md5Hash: String? = null,
    @SerialName("sha1_hash") val sha1Hash: String? = null,
    @SerialName("has_multiple_files") val hasMultipleFiles: Boolean = false,
    @SerialName("has_simple_single_file") val hasSimpleSingleFile: Boolean = true,
    @SerialName("has_nested_single_file") val hasNestedSingleFile: Boolean = false,
    val files: List<RomFileDto> = emptyList(),
    @SerialName("sibling_roms") val siblingRoms: List<SiblingDto> = emptyList(),
    val metadatum: RomMetadataDto? = null,
    @SerialName("missing_from_fs") val missingFromFs: Boolean = false,
    @SerialName("youtube_video_id") val youtubeVideoId: String? = null,
    @SerialName("merged_screenshots") val mergedScreenshots: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RomPageDto(
    val items: List<RomDto> = emptyList(),
    val total: Int? = null,
    val limit: Int = 0,
    val offset: Int = 0,
    @SerialName("char_index") val charIndex: Map<String, Int> = emptyMap(),
)

@Serializable
data class CollectionDto(
    val id: Int,
    val name: String = "",
    val description: String? = null,
    @SerialName("rom_count") val romCount: Int = 0,
    @SerialName("path_cover_small") val pathCoverSmall: String? = null,
    @SerialName("path_cover_large") val pathCoverLarge: String? = null,
    @SerialName("path_covers_small") val pathCoversSmall: List<String> = emptyList(),
    @SerialName("url_cover") val urlCover: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("owner_username") val ownerUsername: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class VirtualCollectionDto(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val type: String = "",
    @SerialName("rom_count") val romCount: Int = 0,
    @SerialName("path_cover_small") val pathCoverSmall: String? = null,
    @SerialName("path_covers_small") val pathCoversSmall: List<String> = emptyList(),
)

@Serializable
data class SmartCollectionDto(
    val id: Int,
    val name: String = "",
    val description: String? = null,
    @SerialName("rom_count") val romCount: Int = 0,
    @SerialName("path_cover_small") val pathCoverSmall: String? = null,
    @SerialName("path_covers_small") val pathCoversSmall: List<String> = emptyList(),
    @SerialName("filter_summary") val filterSummary: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class UserDto(
    val id: Int = 0,
    val username: String = "",
    val role: String? = null,
    @SerialName("oauth_scopes") val oauthScopes: List<String> = emptyList(),
    @SerialName("avatar_path") val avatarPath: String? = null,
)

@Serializable
data class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    val expires: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires") val refreshExpires: Long? = null,
)

@Serializable
data class ExchangeBody(val code: String)

@Serializable
data class ClientTokenCreateDto(
    val id: Int = 0,
    val name: String? = null,
    val scopes: List<String> = emptyList(),
    @SerialName("raw_token") val rawToken: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("user_id") val userId: Int = 0,
)

/** `encodeDefaults = false`, so no field the server may need is left to a default. */
@Serializable
data class DeviceInitBody(
    @SerialName("client_device_identifier") val clientDeviceIdentifier: String,
    val name: String,
    val client: String,
    val platform: String?,
    @SerialName("client_version") val clientVersion: String?,
    @SerialName("requested_scopes") val requestedScopes: List<String>,
)

@Serializable
data class DeviceAuthInitDto(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_path") val verificationPath: String? = null,
    @SerialName("verification_path_complete") val verificationPathComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 600,
    val interval: Int = 5,
)

@Serializable
data class DeviceTokenBody(@SerialName("device_code") val deviceCode: String)

@Serializable
data class DeviceAuthTokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("device_id") val deviceId: String? = null,
    val scopes: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class FirmwareDto(
    val id: Int,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    @SerialName("crc_hash") val crcHash: String? = null,
    @SerialName("md5_hash") val md5Hash: String? = null,
    @SerialName("sha1_hash") val sha1Hash: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("missing_from_fs") val missingFromFs: Boolean = false,
)

/** Read scopes the app needs; write scopes are never requested. */
val READ_SCOPES = listOf("me.read", "roms.read", "platforms.read", "collections.read", "firmware.read", "assets.read", "roms.user.read")
