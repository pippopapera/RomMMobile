package com.rommmobile.app.data.repo

import com.rommmobile.app.core.network.apiCall
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.db.PlatformDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlatformRepository @Inject constructor(
    private val api: RommApi,
    private val dao: PlatformDao,
) {
    /** Only platforms with ROMs, labelled with `custom_name` / `display_name`, never bare `name`. */
    val platforms: Flow<List<Platform>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun refresh(): Result<List<Platform>> = apiCall {
        val now = System.currentTimeMillis()
        val entities = api.platforms().map { it.toEntity(now) }
        dao.replaceAll(entities)
        entities.filter { it.romCount > 0 }.map { it.toModel() }
    }

    suspend fun cachedCount(): Int = dao.count()
    suspend fun byId(id: Int): Platform? = dao.byId(id)?.toModel()
    suspend fun bySlug(slug: String): Platform? = dao.bySlug(slug)?.toModel()
    suspend fun all(): List<Platform> = dao.all().filter { it.romCount > 0 }.map { it.toModel() }
}
