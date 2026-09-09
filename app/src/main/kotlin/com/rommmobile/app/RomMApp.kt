package com.rommmobile.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.rommmobile.app.core.network.ImageHttpClient
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.feature.downloads.engine.DownloadEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RomMApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var imageHttpClient: ImageHttpClient
    @Inject lateinit var downloadEngine: DownloadEngine
    @Inject lateinit var fileLogger: FileLogger

    override fun onCreate() {
        super.onCreate()
        fileLogger.install()
        // Resume any queued downloads that survived a process death.
        downloadEngine.resumeAfterProcessStart()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient.client }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("covers"))
                    .maxSizeBytes(384L * 1024 * 1024)
                    .build()
            }
            .crossfade(120)
            .build()
}
