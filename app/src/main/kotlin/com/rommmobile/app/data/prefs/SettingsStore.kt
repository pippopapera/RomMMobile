package com.rommmobile.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rommmobile.app.core.design.ThemeMode
import com.rommmobile.app.core.input.ButtonMap
import com.rommmobile.app.core.storage.StorageRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ViewMode { GRID, LIST }

enum class SortField(val apiValue: String) {
    NAME("name"),
    RELEASE_DATE("first_release_date"),
    RATING("average_rating"),
    SIZE("fs_size_bytes"),
    ADDED("created_at"),
}

enum class Launcher(val id: String, val folderField: String) {
    ESDE("esde", "esde"),
    COCOON("cocoon", "esde"),
    DAIJISHO("daijisho", "generic"),
    BEACON("beacon", "generic"),
    IISU("iisu", "generic"),
    PEGASUS("pegasus", "generic"),
    CUSTOM("custom", "generic");

    companion object {
        fun fromId(id: String?): Launcher = entries.firstOrNull { it.id == id } ?: ESDE
    }
}

/** Disc-based systems default to one sub-folder per game (m3u + tracks kept together). */
val DEFAULT_FOLDER_PER_GAME = setOf("psx", "ps2", "saturn", "segacd", "dc", "turbografx-16-slash-pc-engine-cd", "3do", "neogeocd", "pcfx", "philips-cd-i")

/** One snapshot of every preference, for code paths that need several values at once. */
data class Settings(
    val onboardingDone: Boolean,
    val launcher: Launcher,
    val storageRoot: StorageRoot?,
    val viewMode: ViewMode,
    val sortField: SortField,
    val sortDescending: Boolean,
    val concurrency: Int,
    val wifiOnly: Boolean,
    val autoExtractZip: Boolean,
    val autoExtract7z: Boolean,
    val folderPerGameSlugs: Set<String>,
    val screenMarginDp: Int,
    val themeMode: ThemeMode,
    val biosPath: String?,
    val allowInsecureRemote: Boolean,
    val extractOverrides: Map<String, Boolean>,
    val buttonMap: ButtonMap,
    /** True once a map was recorded or edited by the user: the wizard then stops offering to record one. */
    val buttonMapRecorded: Boolean,
)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val ds get() = context.settingsDataStore

    private object K {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val launcher = stringPreferencesKey("launcher")
        val storageRoot = stringPreferencesKey("storage_root")
        val viewMode = stringPreferencesKey("view_mode")
        val sortField = stringPreferencesKey("sort_field")
        val sortDesc = booleanPreferencesKey("sort_desc")
        val concurrency = intPreferencesKey("concurrency")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val autoExtractZip = booleanPreferencesKey("auto_extract_zip")
        val autoExtract7z = booleanPreferencesKey("auto_extract_7z")
        val folderPerGame = stringSetPreferencesKey("folder_per_game")
        val folderPerGameCustomized = booleanPreferencesKey("folder_per_game_customized")
        val screenMargin = intPreferencesKey("screen_margin")
        val themeMode = stringPreferencesKey("theme_mode")
        val biosPath = stringPreferencesKey("bios_path")
        val allowInsecureRemote = booleanPreferencesKey("allow_insecure_remote")
        val extractOverrides = stringSetPreferencesKey("extract_overrides") // "slug=true"
        val buttonMap = stringPreferencesKey("button_map") // "UP:19,DOWN:20,..."
        val buttonMapRecorded = booleanPreferencesKey("button_map_recorded")
    }

    val settings: Flow<Settings> = ds.data
        // A corrupt or unreadable preferences file surfaces on the flow; fall back to defaults
        // instead of throwing into every collector (start-up, downloads, mapping).
        .catch { e -> if (e is java.io.IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { p ->
        Settings(
            onboardingDone = p[K.onboardingDone] ?: false,
            launcher = Launcher.fromId(p[K.launcher]),
            storageRoot = StorageRoot.parse(p[K.storageRoot]),
            viewMode = p[K.viewMode]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.GRID,
            sortField = p[K.sortField]?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME,
            sortDescending = p[K.sortDesc] ?: false,
            concurrency = (p[K.concurrency] ?: 2).coerceIn(1, 5),
            wifiOnly = p[K.wifiOnly] ?: false,
            autoExtractZip = p[K.autoExtractZip] ?: true,
            autoExtract7z = p[K.autoExtract7z] ?: false,
            folderPerGameSlugs = if (p[K.folderPerGameCustomized] == true) (p[K.folderPerGame] ?: emptySet()) else DEFAULT_FOLDER_PER_GAME,
            screenMarginDp = p[K.screenMargin] ?: 0,
            themeMode = p[K.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            biosPath = p[K.biosPath],
            allowInsecureRemote = p[K.allowInsecureRemote] ?: false,
            extractOverrides = (p[K.extractOverrides] ?: emptySet()).mapNotNull { e ->
                val i = e.indexOf('='); if (i <= 0) null else e.substring(0, i) to e.substring(i + 1).toBoolean()
            }.toMap(),
            buttonMap = ButtonMap.decode(p[K.buttonMap]),
            buttonMapRecorded = p[K.buttonMapRecorded] ?: false,
        )
    }

    val onboardingDone: Flow<Boolean> = settings.map { it.onboardingDone }.distinctUntilChanged()
    val themeMode: Flow<ThemeMode> = settings.map { it.themeMode }.distinctUntilChanged()
    val screenMarginDp: Flow<Int> = settings.map { it.screenMarginDp }.distinctUntilChanged()
    val viewMode: Flow<ViewMode> = settings.map { it.viewMode }.distinctUntilChanged()
    val storageRoot: Flow<StorageRoot?> = settings.map { it.storageRoot }.distinctUntilChanged()
    val launcher: Flow<Launcher> = settings.map { it.launcher }.distinctUntilChanged()
    val buttonMap: Flow<ButtonMap> = settings.map { it.buttonMap }.distinctUntilChanged()

    suspend fun snapshot(): Settings = settings.first()

    suspend fun setOnboardingDone(v: Boolean) = ds.edit { it[K.onboardingDone] = v }
    suspend fun setLauncher(v: Launcher) = ds.edit { it[K.launcher] = v.id }
    suspend fun setStorageRoot(v: StorageRoot?) = ds.edit { if (v == null) it.remove(K.storageRoot) else it[K.storageRoot] = v.serialize() }
    suspend fun setViewMode(v: ViewMode) = ds.edit { it[K.viewMode] = v.name }
    suspend fun setSort(field: SortField, descending: Boolean) = ds.edit { it[K.sortField] = field.name; it[K.sortDesc] = descending }
    suspend fun setConcurrency(v: Int) = ds.edit { it[K.concurrency] = v.coerceIn(1, 5) }
    suspend fun setWifiOnly(v: Boolean) = ds.edit { it[K.wifiOnly] = v }
    suspend fun setAutoExtractZip(v: Boolean) = ds.edit { it[K.autoExtractZip] = v }
    suspend fun setAutoExtract7z(v: Boolean) = ds.edit { it[K.autoExtract7z] = v }
    suspend fun setFolderPerGame(slugs: Set<String>) = ds.edit { it[K.folderPerGame] = slugs; it[K.folderPerGameCustomized] = true }
    suspend fun setScreenMargin(v: Int) = ds.edit { it[K.screenMargin] = v.coerceIn(0, 16) }
    suspend fun setThemeMode(v: ThemeMode) = ds.edit { it[K.themeMode] = v.name }
    suspend fun setBiosPath(v: String?) = ds.edit { if (v.isNullOrBlank()) it.remove(K.biosPath) else it[K.biosPath] = v }
    suspend fun setAllowInsecureRemote(v: Boolean) = ds.edit { it[K.allowInsecureRemote] = v }
    suspend fun setButtonMap(v: ButtonMap) = ds.edit { it[K.buttonMap] = v.encode(); it[K.buttonMapRecorded] = true }
    suspend fun setExtractOverride(slug: String, extract: Boolean?) = ds.edit { p ->
        val cur = (p[K.extractOverrides] ?: emptySet()).filterNot { it.startsWith("$slug=") }.toMutableSet()
        if (extract != null) cur += "$slug=$extract"
        p[K.extractOverrides] = cur
    }
}
