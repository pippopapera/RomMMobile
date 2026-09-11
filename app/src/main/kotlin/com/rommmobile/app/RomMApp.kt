package com.rommmobile.app

import com.rommmobile.app.di.ApplicationScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.core.input.GamepadBus
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
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var gamepad: GamepadBus
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        fileLogger.install()
        // The button map is read synchronously on every key, so it lives on the bus and follows
        // the preference from the first moment the process can receive input.
        appScope.launch { settings.buttonMap.collect { gamepad.map = it } }
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
