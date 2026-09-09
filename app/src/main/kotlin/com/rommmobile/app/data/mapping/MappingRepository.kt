package com.rommmobile.app.data.mapping

import android.content.Context
import androidx.compose.runtime.Immutable
import com.rommmobile.app.core.storage.FileGateway
import com.rommmobile.app.core.storage.FileGatewayFactory
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.storage.normalizeFolderName
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.db.FolderMappingDao
import com.rommmobile.app.data.db.FolderMappingEntity
import com.rommmobile.app.data.db.MappingSource
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class FolderMapping(
    val platformSlug: String,
    val relDir: String,
    val source: MappingSource,
    val fileCount: Int,
)

@Immutable
data class FolderCandidate(val relDir: String, val fileCount: Int)

/** Outcome of the resolution cascade for one platform. */
sealed interface Resolution {
    @Immutable data class Resolved(val mapping: FolderMapping) : Resolution
    @Immutable data class Ambiguous(val candidates: List<FolderCandidate>, val preselected: String) : Resolution
    /** Nothing exists yet: propose creating [proposedName]; [supportedByLauncher] false = ES-DE has no such system. */
    @Immutable data class NeedsCreate(val proposedName: String, val supportedByLauncher: Boolean) : Resolution
    @Immutable data object NoRoot : Resolution
}

/**
 * The reason the app exists: turn "platform on the server" into "folder the frontend watches"
 * without asking. Order: user override > existing folder > launcher preset > create.
 */
@Singleton
class MappingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FolderMappingDao,
    private val settings: SettingsStore,
    private val loader: PlatformMapLoader,
    private val log: FileLogger,
) {
    val map: PlatformMap get() = loader.load()

    fun observeAll(): Flow<List<FolderMapping>> = dao.observeAll().map { list -> list.map { it.toModel() } }
    fun observe(slug: String): Flow<FolderMapping?> = dao.observeBySlug(slug).map { it?.toModel() }
    suspend fun get(slug: String): FolderMapping? = dao.bySlug(slug)?.toModel()

    suspend fun gateway(): FileGateway? {
        val root = settings.storageRoot.first() ?: return null
        return FileGatewayFactory.create(context, root)
    }

    fun gatewayFor(root: StorageRoot): FileGateway = FileGatewayFactory.create(context, root)

    suspend fun resolve(platform: Platform, gatewayOverride: FileGateway? = null, launcherOverride: Launcher? = null): Resolution = withContext(Dispatchers.IO) {
        val gateway = gatewayOverride ?: gateway() ?: return@withContext Resolution.NoRoot
        val launcher = launcherOverride ?: settings.launcher.first()
        dao.bySlug(platform.slug)?.let { existing ->
            // A stored mapping stays valid only while its folder exists.
            val stillThere = existing.source == MappingSource.USER || runCatching {
                gateway.list(existing.relDir.substringBeforeLast('/', "")).any { it.isDirectory && it.name == existing.relDir.substringAfterLast('/') }
            }.getOrDefault(true)
            if (stillThere) {
                return@withContext Resolution.Resolved(existing.toModel())
            }
        }
        val aliases = map.aliasKeys(platform.slug) + normalizeFolderName(platform.fsSlug)
        val dirs = runCatching { gateway.list("") }.getOrElse { e -> log.w("Mapping", "cannot list root", e); emptyList() }
            .filter { it.isDirectory }
        val hits = dirs.filter { normalizeFolderName(it.name) in aliases }
        when {
            hits.size == 1 -> {
                val name = hits.first().name
                val count = countFiles(gateway, name)
                return@withContext Resolution.Resolved(save(platform.slug, name, MappingSource.DISCOVERED, count))
            }
            hits.size > 1 -> {
                val candidates = hits.map { FolderCandidate(it.name, countFiles(gateway, it.name)) }
                val preferred = map.preferredCandidates(platform.slug)
                val pre = candidates.firstOrNull { c -> preferred.any { it.equals(c.relDir, true) } }
                    ?: candidates.maxByOrNull { it.fileCount }!!
                return@withContext Resolution.Ambiguous(candidates.sortedByDescending { it.fileCount }, pre.relDir)
            }
        }
        val preset = map.presetFolder(platform.slug, launcher)
        Resolution.NeedsCreate(preset.name, preset.supported)
    }

    /**
     * Every root folder whose name matches one of a platform's aliases, whether or not the platform
     * has a saved mapping. One listing of the root serves all of them.
     *
     * The presence index needs this: a platform whose folder was ambiguous at setup has no mapping
     * at all, so its games would stay invisible under "Installed" until the user downloaded
     * something for it. Choosing a download target stays a separate, explicit decision.
     */
    suspend fun candidateDirs(platforms: List<Platform>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val gateway = gateway() ?: return@withContext emptyMap()
        val dirs = runCatching { gateway.list("") }.getOrElse { e ->
            log.w("Mapping", "cannot list root for candidates", e); return@withContext emptyMap()
        }.filter { it.isDirectory }
        val byName = HashMap<String, String>(dirs.size)
        for (d in dirs) byName.putIfAbsent(normalizeFolderName(d.name), d.name)
        platforms.associate { p ->
            val aliases = map.aliasKeys(p.slug) + normalizeFolderName(p.fsSlug)
            p.slug to aliases.mapNotNull { byName[it] }.distinct()
        }.filterValues { it.isNotEmpty() }
    }

    /** Runs the cascade for every platform; used by the onboarding summary and the settings table. */
    suspend fun discoverAll(platforms: List<Platform>, gateway: FileGateway? = null, launcher: Launcher? = null): List<Pair<Platform, Resolution>> =
        platforms.map { it to resolve(it, gateway, launcher) }

    suspend fun setUserMapping(slug: String, relDir: String): Unit = withContext(Dispatchers.IO) {
        val gw = gateway()
        val count = gw?.let { countFiles(it, relDir) } ?: 0
        save(slug, relDir, MappingSource.USER, count)
    }

    suspend fun createFolder(slug: String, name: String): FolderMapping? = withContext(Dispatchers.IO) {
        val gw = gateway() ?: return@withContext null
        if (!gw.ensureDir(name)) return@withContext null
        save(slug, name, MappingSource.CREATED, 0)
    }

    suspend fun chooseCandidate(slug: String, relDir: String): FolderMapping = withContext(Dispatchers.IO) {
        val gw = gateway()
        save(slug, relDir, MappingSource.DISCOVERED, gw?.let { countFiles(it, relDir) } ?: 0)
    }

    suspend fun clear(slug: String) = dao.delete(slug)

    /** Launcher or root changed: user overrides survive, everything automatic is recomputed. */
    suspend fun resetAutomatic() = dao.deleteAutomatic()

    suspend fun refreshCounts() = withContext(Dispatchers.IO) {
        val gw = gateway() ?: return@withContext
        for (m in dao.all()) dao.setFileCount(m.platformSlug, countFiles(gw, m.relDir))
    }

    private suspend fun save(slug: String, relDir: String, source: MappingSource, count: Int): FolderMapping {
        val e = FolderMappingEntity(slug, relDir, source, count, System.currentTimeMillis())
        dao.upsert(e)
        log.i("Mapping", "$slug -> $relDir ($source, $count files)")
        return e.toModel()
    }

    private fun countFiles(gw: FileGateway, relDir: String): Int =
        runCatching { gw.list(relDir).size }.getOrDefault(0)

    private fun FolderMappingEntity.toModel() = FolderMapping(platformSlug, relDir, source, fileCount)
}
