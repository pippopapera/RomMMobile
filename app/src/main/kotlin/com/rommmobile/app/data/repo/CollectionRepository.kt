package com.rommmobile.app.data.repo

import com.rommmobile.app.core.network.apiCall
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.db.CollectionDao
import com.rommmobile.app.data.db.CollectionKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Virtual collection kinds exposed by RomM 5.x; their ids are strings, not integers. */
val VIRTUAL_TYPES = listOf("collection", "franchise", "genre", "company", "mode")

@Singleton
class CollectionRepository @Inject constructor(
    private val api: RommApi,
    private val dao: CollectionDao,
) {
    fun observe(kind: CollectionKind): Flow<List<Collection>> = dao.observeByKind(kind).map { l -> l.map { it.toModel() } }

    suspend fun byKey(key: String): Collection? = dao.byKey(key)?.toModel()

    suspend fun refreshUser(): Result<Unit> = apiCall {
        val items = api.collections().mapIndexed { i, c ->
            Collection(CollectionKind.USER, c.id.toString(), c.name, c.description, c.romCount,
                covers(c.pathCoverSmall, c.pathCoversSmall), null, c.isFavorite).toEntity(if (c.isFavorite) -1 else i)
        }
        dao.replaceKind(CollectionKind.USER, items)
    }

    suspend fun refreshSmart(): Result<Unit> = apiCall {
        val items = api.smartCollections().mapIndexed { i, c ->
            Collection(CollectionKind.SMART, c.id.toString(), c.name, c.description ?: c.filterSummary, c.romCount,
                covers(c.pathCoverSmall, c.pathCoversSmall), null, false).toEntity(i)
        }
        dao.replaceKind(CollectionKind.SMART, items)
    }

    /** All five virtual kinds in parallel; a failure in one kind does not hide the others. */
    suspend fun refreshVirtual(): Result<Unit> = apiCall {
        coroutineScope {
            val results = VIRTUAL_TYPES.map { type -> async { runCatching { api.virtualCollections(type) } } }.map { it.await() }
            val ok = results.filter { it.isSuccess }
            if (ok.isEmpty()) results.first().getOrThrow()
            val items = results.flatMapIndexed { ti, r ->
                r.getOrNull().orEmpty().mapIndexed { i, c ->
                    Collection(CollectionKind.VIRTUAL, c.id, c.name, c.description, c.romCount,
                        covers(c.pathCoverSmall, c.pathCoversSmall), c.type.ifEmpty { VIRTUAL_TYPES[ti] }, false).toEntity(ti * 10_000 + i)
                }
            }
            if (ok.size == VIRTUAL_TYPES.size) dao.replaceKind(CollectionKind.VIRTUAL, items) else dao.upsertAll(items)
        }
    }

    private fun covers(single: String?, many: List<String>): List<String> =
        (many.take(4).ifEmpty { listOfNotNull(single) }).filter { it.isNotBlank() }
}
