package com.rommmobile.app.core.network

import com.rommmobile.app.BuildConfig
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.di.ApiClient
import com.rommmobile.app.di.DownloadClient
import com.rommmobile.app.di.ImageClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton @ApiClient
    fun apiClient(
        hostSelection: HostSelectionInterceptor,
        cleartext: CleartextPolicyInterceptor,
        auth: AuthInterceptor,
        errors: ErrorMappingInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(hostSelection)
        .addInterceptor(cleartext)
        .addInterceptor(auth)
        .addInterceptor(errors)
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        .authenticator(authenticator)
        .retryOnConnectionFailure(true)
        .build()

    /** Long-lived streams: generous read timeout, no call timeout, its own dispatcher. */
    @Provides @Singleton @DownloadClient
    fun downloadClient(
        cleartext: CleartextPolicyInterceptor,
        auth: AuthInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(cleartext)
        .addInterceptor(auth)
        .authenticator(authenticator)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 6 })
        .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
        .build()

    /** Covers and icons: credentials only towards the user's hosts, many small parallel requests. */
    @Provides @Singleton @ImageClient
    fun imageClient(
        cleartext: CleartextPolicyInterceptor,
        auth: AuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(cleartext)
        .addInterceptor(auth)
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 12 })
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .build()

    @Provides @Singleton
    fun retrofit(@ApiClient client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(RETROFIT_PLACEHOLDER)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun rommApi(retrofit: Retrofit): RommApi = retrofit.create(RommApi::class.java)
}
