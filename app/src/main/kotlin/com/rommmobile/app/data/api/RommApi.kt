package com.rommmobile.app.data.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * RomM 5.x contract, verified against the user's server `/openapi.json` (5.2.0).
 * Paths are relative to the placeholder base; [com.rommmobile.app.core.network.HostSelectionInterceptor]
 * swaps in the real host.
 */
interface RommApi {

    @GET("api/heartbeat")
    suspend fun heartbeat(): HeartbeatDto

    /** Returns JSON `null` when no session: parsed by hand for that reason. */
    @GET("api/users/me")
    suspend fun me(): JsonElement

    @FormUrlEncoded
    @POST("api/token")
    suspend fun token(
        @Field("grant_type") grantType: String,
        @Field("username") username: String? = null,
        @Field("password") password: String? = null,
        @Field("scope") scope: String? = null,
        @Field("refresh_token") refreshToken: String? = null,
    ): TokenDto

    @POST("api/client-tokens/exchange")
    suspend fun exchangePairCode(@Body body: ExchangeBody): ClientTokenCreateDto

    @POST("api/auth/device/init")
    suspend fun deviceInit(@Body body: DeviceInitBody): DeviceAuthInitDto

    @POST("api/auth/device/token")
    suspend fun deviceToken(@Body body: DeviceTokenBody): DeviceAuthTokenDto

    @GET("api/platforms")
    suspend fun platforms(): List<PlatformDto>

    /**
     * `with_rom_id_index` defaults to true server-side and would ship the id of every ROM of the
     * platform with each page: always off. `char_index` and `total` only on the first page.
     */
    @GET("api/roms")
    suspend fun roms(
        @Query("platform_ids") platformIds: List<Int>? = null,
        @Query("collection_id") collectionId: Int? = null,
        @Query("virtual_collection_id") virtualCollectionId: String? = null,
        @Query("smart_collection_id") smartCollectionId: Int? = null,
        @Query("search_term") searchTerm: String? = null,
        @Query("regions") regions: List<String>? = null,
        @Query("order_by") orderBy: String? = "name",
        @Query("order_dir") orderDir: String = "asc",
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("group_by_meta_id") groupByMetaId: Boolean = true,
        @Query("with_char_index") withCharIndex: Boolean,
        @Query("with_total") withTotal: Boolean,
        @Query("with_filter_values") withFilterValues: Boolean = false,
        @Query("with_rom_id_index") withRomIdIndex: Boolean = false,
        @Query("with_files") withFiles: Boolean = false,
    ): RomPageDto

    @GET("api/roms/{id}")
    suspend fun rom(@Path("id") id: Int): RomDto

    @GET("api/collections")
    suspend fun collections(): List<CollectionDto>

    @GET("api/collections/virtual")
    suspend fun virtualCollections(@Query("type") type: String, @Query("limit") limit: Int? = null): List<VirtualCollectionDto>

    @GET("api/collections/smart")
    suspend fun smartCollections(): List<SmartCollectionDto>

    @GET("api/firmware")
    suspend fun firmware(@Query("platform_id") platformId: Int? = null): List<FirmwareDto>
}
