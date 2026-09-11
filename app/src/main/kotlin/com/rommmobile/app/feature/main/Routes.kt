package com.rommmobile.app.feature.main

import com.rommmobile.app.data.repo.LibrarySource
import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute
@Serializable data object LoginRoute
@Serializable data object HomeRoute
@Serializable data class GameRoute(val id: Int)
@Serializable data object DownloadsRoute
@Serializable data object SettingsRoute
@Serializable data object FolderMappingsRoute
@Serializable data object DiagnosticsRoute
@Serializable data object LocalLibraryRoute
@Serializable data object FirmwareRoute
@Serializable data object ButtonMapRoute

/** Library screen for a platform or a collection; [kind] is one of platform/collection/virtual/smart. */
@Serializable
data class LibraryRoute(
    val kind: String,
    val id: String,
    val title: String,
    val platformSlug: String = "",
    /** Opened from the "on this device" section: start filtered to downloaded games. */
    val onlyPresent: Boolean = false,
) {
    fun toSource(): LibrarySource = when (kind) {
        "platform" -> LibrarySource.ByPlatform(id.toInt(), platformSlug)
        "collection" -> LibrarySource.ByCollection(id.toInt())
        "virtual" -> LibrarySource.ByVirtualCollection(id)
        "smart" -> LibrarySource.BySmartCollection(id.toInt())
        else -> LibrarySource.Search
    }

    companion object {
        fun platform(id: Int, slug: String, title: String) = LibraryRoute("platform", id.toString(), title, slug)
        fun collection(source: LibrarySource, title: String): LibraryRoute = when (source) {
            is LibrarySource.ByCollection -> LibraryRoute("collection", source.id.toString(), title)
            is LibrarySource.ByVirtualCollection -> LibraryRoute("virtual", source.id, title)
            is LibrarySource.BySmartCollection -> LibraryRoute("smart", source.id.toString(), title)
            is LibrarySource.ByPlatform -> LibraryRoute("platform", source.platformId.toString(), title, source.platformSlug)
            LibrarySource.Search -> LibraryRoute("search", "", title)
        }
    }
}

/** Stable string key for view models scoped to one source. */
val LibrarySource.key: String
    get() = when (this) {
        is LibrarySource.ByPlatform -> "platform:$platformId"
        is LibrarySource.ByCollection -> "collection:$id"
        is LibrarySource.ByVirtualCollection -> "virtual:$id"
        is LibrarySource.BySmartCollection -> "smart:$id"
        LibrarySource.Search -> "search"
    }
