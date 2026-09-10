package com.rommmobile.app.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.map
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.apiCall
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.api.RomPageDto
import com.rommmobile.app.data.db.RomDao
import com.rommmobile.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RomRepository @Inject constructor(
    private val api: RommApi,
    private val dao: RomDao,
    private val serverStore: ServerStore,
    private val json: Json,
    private val log: FileLogger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val base: String? get() = serverStore.config.value.activeUrl

    val pagingConfig = PagingConfig(
        pageSize = PAGE,
        initialLoadSize = PAGE * 2,
        prefetchDistance = PAGE,
        enablePlaceholders = true,
        jumpThreshold = PAGE * 3,
        // Unbounded on purpose. A platform is a few thousand light records, and a bounded window
        // made Paging drop pages behind the viewport: any cell still composed back there (the
        // focused one is pinned) then asked for its index again, the distance read as a jump,
        // Paging refreshed around it, the viewport turned to placeholders and asked back - and
        // the two ends refetched each other until the screen was left.
        maxSize = PagingConfig.MAX_SIZE_UNBOUNDED,
    )

    /**
     * Lists filtered on the client (the Installed shelf): no placeholders, and three times the
     * page, so a platform where only part of the set is on the card still fills a screen in one
     * or two round trips instead of six.
     */
    private val filteredConfig = PagingConfig(
        pageSize = PAGE * 3,
        initialLoadSize = PAGE * 6,
        prefetchDistance = PAGE * 2,
        enablePlaceholders = false,
        maxSize = PagingConfig.MAX_SIZE_UNBOUNDED,
    )

    /**
     * Server-backed pager. [onMeta] receives `total` and `char_index` from the first page so the
     * alphabet rail can be built from the whole result set, not from the loaded window.
     */
    fun pager(query: LibraryQuery, placeholders: Boolean = true, onMeta: (LibraryMeta) -> Unit): Flow<PagingData<Rom>> {
        val config = if (placeholders) pagingConfig else filteredConfig
        return Pager(config) { RomPagingSource(query, config.pageSize, onMeta) }.flow
    }

    /** Offline fallback: whatever we cached for that platform, sorted by name. */
    fun offlinePager(platformId: Int, placeholders: Boolean = true, term: String? = null): Flow<PagingData<Rom>> =
        Pager(PagingConfig(pageSize = PAGE, enablePlaceholders = placeholders)) {
            val t = term?.trim().orEmpty()
            if (t.isEmpty()) dao.pagingByPlatform(platformId) else dao.pagingByPlatformLike(platformId, "%" + likeEscape(t) + "%")
        }.flow.map { data -> data.map { it.toModel(base, json) } }

    /** A search term is text, not a pattern: its wildcards are quoted for LIKE ... ESCAPE '\\'. */
    private fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    suspend fun cachedCount(platformId: Int): Int = dao.countByPlatform(platformId)

    /** Detail: network first (fresh siblings/files), Room when offline. */
    suspend fun detail(id: Int): Result<Rom> {
        val net = apiCall {
            val dto = api.rom(id)
            val entity = dto.toEntity(json, System.currentTimeMillis())
            dao.upsert(entity)
            entity.toModel(base, json)
        }
        if (net.isSuccess) return net
        val cached = dao.byId(id)?.toModel(base, json)
        return if (cached != null) Result.success(cached) else net
    }

    suspend fun cached(id: Int): Rom? = dao.byId(id)?.toModel(base, json)

    private inner class RomPagingSource(
        private val query: LibraryQuery,
        private val pageSize: Int,
        private val onMeta: (LibraryMeta) -> Unit,
    ) : PagingSource<Int, Rom>() {

        private var total: Int? = null
        private var metaSent = false

        override val jumpingSupported: Boolean get() = true

        override fun getRefreshKey(state: PagingState<Int, Rom>): Int? {
            val anchor = state.anchorPosition ?: return null
            val start = (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0)
            return start - (start % PAGE)
        }

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Rom> {
            // A prepend key may be negative (see prevKey): the page then starts at 0 and is
            // shortened by the same amount, so it ends exactly where the loaded window begins
            // instead of overlapping it and tripping the grid on a duplicate key.
            val raw = params.key ?: 0
            val offset = raw.coerceAtLeast(0)
            val limit = (params.loadSize + minOf(raw, 0)).coerceAtLeast(1)
            return try {
                val firstPage = offset == 0 || total == null
                val page: RomPageDto = fetch(offset, limit, withMeta = firstPage)
                if (page.total != null) total = page.total
                if (!metaSent && (page.charIndex.isNotEmpty() || page.total != null)) {
                    metaSent = true
                    onMeta(LibraryMeta(total = page.total, charIndex = page.charIndex))
                }
                val now = System.currentTimeMillis()
                val entities = page.items.map { it.toEntity(json, now) }
                // Cache asynchronously: never make scrolling wait for SQLite.
                if (entities.isNotEmpty()) scope.launch(Dispatchers.IO) { runCatching { dao.upsertAll(entities) } }
                val items = entities.map { it.toModel(base, json) }
                val t = total
                val nextKey = if (items.isEmpty() || (t != null && offset + items.size >= t)) null else offset + items.size
                val prevKey = if (offset == 0) null else offset - pageSize
                LoadResult.Page(
                    data = items,
                    prevKey = prevKey,
                    nextKey = nextKey,
                    itemsBefore = offset,
                    itemsAfter = if (t != null) (t - offset - items.size).coerceAtLeast(0) else LoadResult.Page.COUNT_UNDEFINED,
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                val e = ApiException.from(t)
                log.w("Roms", "page load failed offset=$offset: ${e.message}")
                LoadResult.Error(e)
            }
        }

        private suspend fun fetch(offset: Int, limit: Int, withMeta: Boolean): RomPageDto {
            val src = query.source
            val orderBy = query.sort.apiValue
            return api.roms(
                platformIds = (src as? LibrarySource.ByPlatform)?.let { listOf(it.platformId) },
                collectionId = (src as? LibrarySource.ByCollection)?.id,
                virtualCollectionId = (src as? LibrarySource.ByVirtualCollection)?.id,
                smartCollectionId = (src as? LibrarySource.BySmartCollection)?.id,
                searchTerm = query.searchTerm?.takeIf { it.isNotBlank() },
                regions = query.regions.takeIf { it.isNotEmpty() },
                orderBy = orderBy,
                orderDir = if (query.descending) "desc" else "asc",
                limit = limit,
                offset = offset,
                groupByMetaId = true,
                withCharIndex = withMeta,
                withTotal = withMeta,
            )
        }
    }

    companion object { const val PAGE = 40 }
}
