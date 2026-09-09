package com.rommmobile.app.data.mapping

import android.content.Context
import com.rommmobile.app.core.storage.normalizeFolderName
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.prefs.Launcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PlatformMapEntry(
    val slug: String,
    val label: String = "",
    val esde: String? = null,
    val esdeAlt: List<String> = emptyList(),
    val generic: String = "",
    val aliases: List<String> = emptyList(),
    val note: String? = null,
)

@Serializable
data class LauncherDefaults(
    val roots: List<String> = emptyList(),
    val folderField: String = "generic",
    val sdCardRootPattern: String? = null,
    val inherits: String? = null,
)

@Serializable
data class PlatformMapFile(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    val platforms: List<PlatformMapEntry> = emptyList(),
    val launcherDefaults: Map<String, LauncherDefaults> = emptyMap(),
)

/**
 * Seed mapping RomM slug -> launcher folder. The list of platforms that actually exist comes
 * from the server; an unknown slug is not an error, it just falls back to the slug itself.
 */
class PlatformMap(private val file: PlatformMapFile) {
    private val bySlug: Map<String, PlatformMapEntry> = file.platforms.associateBy { it.slug }

    fun entry(slug: String): PlatformMapEntry? = bySlug[slug]

    fun defaultsFor(launcher: Launcher): LauncherDefaults? = file.launcherDefaults[launcher.id]

    /** Preferred root paths for the launcher, expanded from the JSON `roots`. */
    fun rootNamesFor(launcher: Launcher): List<String> {
        val d = defaultsFor(launcher) ?: return listOf("ROMs")
        val inherited = d.inherits?.let { file.launcherDefaults[it]?.roots } ?: emptyList()
        return (d.roots + inherited).map { it.substringAfterLast('/') }.distinct().ifEmpty { listOf("ROMs") }
    }

    /** Folder the chosen launcher expects; null when ES-DE has no such system (caller warns). */
    fun presetFolder(slug: String, launcher: Launcher): PresetFolder {
        val e = entry(slug) ?: return PresetFolder(slug, supported = true, fromEsde = false)
        return if (launcher.folderField == "esde") {
            if (e.esde != null) PresetFolder(e.esde, supported = true, fromEsde = true)
            else PresetFolder(e.generic.ifEmpty { slug }, supported = false, fromEsde = false)
        } else {
            PresetFolder(e.generic.ifEmpty { slug }, supported = true, fromEsde = false)
        }
    }

    /** Every folder name that may legitimately contain this platform, normalized. */
    fun aliasKeys(slug: String): Set<String> {
        val e = entry(slug)
        val raw = buildList {
            add(slug)
            if (e != null) {
                e.esde?.let { add(it) }
                addAll(e.esdeAlt)
                if (e.generic.isNotEmpty()) add(e.generic)
                addAll(e.aliases)
                if (e.label.isNotEmpty()) add(e.label)
            }
        }
        return raw.map(::normalizeFolderName).filter { it.isNotEmpty() }.toSet()
    }

    /** Folder names preferred over others when several candidates match (e.g. `fbneo` over `arcade`). */
    fun preferredCandidates(slug: String): List<String> = when (slug) {
        "arcade" -> listOf("fbneo", "arcade", "mame")
        else -> emptyList()
    }
}

data class PresetFolder(val name: String, val supported: Boolean, val fromEsde: Boolean)

@Singleton
class PlatformMapLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val log: FileLogger,
) {
    @Volatile private var cached: PlatformMap? = null

    /** Asset copy, overridable without recompiling from `Android/data/<pkg>/files/platform_map.json`. */
    fun load(): PlatformMap {
        cached?.let { return it }
        val override = context.getExternalFilesDir(null)?.let { File(it, "platform_map.json") }
        val text = if (override != null && override.isFile) {
            log.i("PlatformMap", "using override ${override.absolutePath}")
            runCatching { override.readText() }.getOrNull()
        } else null
        val parsed = text?.let { runCatching { json.decodeFromString<PlatformMapFile>(it) }.getOrElse { e -> log.w("PlatformMap", "override invalid, using asset", e); null } }
            ?: json.decodeFromString<PlatformMapFile>(context.assets.open("platform_map.json").bufferedReader().use { it.readText() })
        return PlatformMap(parsed).also { cached = it }
    }
}
