package com.rommmobile.app.feature.downloads.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.db.DownloadDao
import com.rommmobile.app.data.db.DownloadEntity
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.di.ApplicationScope
import com.rommmobile.app.di.DownloadClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scheduler for the persistent queue. The queue itself lives in Room; this class only decides
 * what runs now. Execution happens inside [DownloadWorker] (a foreground WorkManager job) so
 * downloads survive the app being swiped away and restart on their own after a reboot or
 * when the network comes back.
 */
@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val settings: SettingsStore,
    private val mapping: MappingRepository,
    private val localLibrary: LocalLibraryRepository,
    @DownloadClient private val client: OkHttpClient,
    private val serverStore: com.rommmobile.app.core.network.ServerStore,
    private val log: FileLogger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

    private val _finishedEvents = MutableStateFlow<Pair<Long, TaskOutcome>?>(null)
    /** Last finished task; observed by the UI to toast "saved to ROMs/snes". */
    val finishedEvents: StateFlow<Pair<Long, TaskOutcome>?> = _finishedEvents.asStateFlow()

    private data class Running(val job: Job, val task: DownloadTask)
    private val running = ConcurrentHashMap<Long, Running>()
    private val schedulerLock = Mutex()
    @Volatile private var stopRequested = false

    /** Called at process start: anything that was mid-flight is queued again from its byte offset. */
    fun resumeAfterProcessStart() {
        scope.launch(Dispatchers.IO) {
            dao.requeueInterrupted()
            pruneOrphanParts()
            if (dao.countOpen() > 0) kick()
        }
    }

    /** Removes `.part` files whose queue entry is gone (cleared list, failed and deleted). */
    private suspend fun pruneOrphanParts() {
        val dir = java.io.File(context.cacheDir, "downloads")
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".part").toLongOrNull() ?: return@forEach
            val e = dao.byId(id)
            if (e == null || e.state.isTerminal) f.delete()
        }
        pruneStagingLeftovers()
    }

    /**
     * A process death mid-move can leave `.name.rommpart` or `.romm-<id>.tmp` behind in the
     * frontend folders. They are hidden, but they still waste space and confuse a rescan.
     */
    private suspend fun pruneStagingLeftovers() {
        val gw = runCatching { mapping.gateway() }.getOrNull() ?: return
        val open = dao.observeOpen().first().map { ".romm-" + it.id + ".tmp" }.toSet()
        for (m in mapping.observeAll().first()) {
            val entries = runCatching { gw.list(m.relDir) }.getOrNull() ?: continue
            for (e in entries) {
                val stale = (e.name.startsWith(".romm-") && e.name.endsWith(".tmp") && e.name !in open) ||
                    (e.name.startsWith(".") && e.name.endsWith(".rommpart"))
                if (stale) {
                    runCatching { gw.delete(m.relDir, e.name) }
                    log.i("Download", "removed leftover ${m.relDir}/${e.name}")
                }
            }
        }
    }

    /** Ensures the worker is scheduled; harmless when it is already running. */
    fun kick() {
        scope.launch(Dispatchers.IO) {
            val wifiOnly = settings.snapshot().wifiOnly
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Set while a run is in flight; the worker only announces a finished queue if it is true. */
    @Volatile var completedInRun = false
        private set

    /**
     * Runs tasks until the queue has nothing runnable. Returns true when drained, false when
     * items remain PENDING because the network went away (the worker then retries later).
     */
    suspend fun runUntilIdle(onSummary: suspend (QueueSummary) -> Unit): Boolean {
        stopRequested = false
        completedInRun = false
        var deferred = false
        while (!stopRequested) {
            val launched = schedulerLock.withLock { fillSlots() }
            if (launched == null) { deferred = true; break }
            val open = dao.countOpen()
            if (open == 0 && running.isEmpty()) break
            onSummary(summary())
            delay(1_000)
        }
        // Wait for in-flight tasks to settle before telling WorkManager we are done.
        while (running.isNotEmpty()) { onSummary(summary()); delay(500) }
        return !deferred && dao.countOpen() == 0
    }

    /** Starts as many pending tasks as concurrency allows. Null = network not usable now. */
    private suspend fun fillSlots(): Int? {
        val s = settings.snapshot()
        if (!networkUsable(s.wifiOnly)) {
            if (running.isEmpty()) return null
            return 0
        }
        val free = s.concurrency - running.size
        if (free <= 0) return 0
        val next = dao.nextPending(free).filter { !running.containsKey(it.id) }
        for (e in next) launchTask(e)
        return next.size
    }

    private fun launchTask(entity: DownloadEntity) {
        val task = DownloadTask(context, client, serverStore, dao, mapping, localLibrary, log) { p -> _progress.update { it + (p.id to p) } }
        val job = scope.launch(Dispatchers.IO) {
            val outcome = task.run(entity)
            handleOutcome(entity, outcome)
        }
        running[entity.id] = Running(job, task)
        job.invokeOnCompletion { running.remove(entity.id) }
    }

    private suspend fun handleOutcome(entity: DownloadEntity, outcome: TaskOutcome) {
        when (outcome) {
            TaskOutcome.Completed -> { completedInRun = true; _progress.update { it - entity.id } }
            TaskOutcome.Paused -> { dao.setState(entity.id, DownloadState.PAUSED); _progress.update { it - entity.id } }
            TaskOutcome.Cancelled -> { dao.setState(entity.id, DownloadState.CANCELLED); _progress.update { it - entity.id } }
            TaskOutcome.Deferred -> {
                // Connection lost. With the network still up this is the server refusing or
                // dropping us, so it needs a back-off: without one the scheduler would relaunch
                // the same task every second forever and the queue would never end.
                val fresh = dao.byId(entity.id)
                val netUp = networkUsable(settings.snapshot().wifiOnly)
                when {
                    !netUp -> dao.scheduleRetry(entity.id, System.currentTimeMillis() + 30_000, "offline")
                    (fresh?.attempt ?: 0) < MAX_NETWORK_ATTEMPTS -> {
                        dao.bumpAttempt(entity.id)
                        val backoff = BACKOFF_MS.getOrElse(fresh?.attempt ?: 0) { BACKOFF_MS.last() }
                        dao.scheduleRetry(entity.id, System.currentTimeMillis() + backoff, "network")
                    }
                    else -> dao.setState(entity.id, DownloadState.FAILED, "network")
                }
                _progress.update { it - entity.id }
            }
            is TaskOutcome.Failed -> {
                val fresh = dao.byId(entity.id) ?: return
                if (outcome.retryable && fresh.attempt < MAX_ATTEMPTS) {
                    dao.bumpAttempt(entity.id)
                    val backoff = BACKOFF_MS.getOrElse(fresh.attempt) { BACKOFF_MS.last() }
                    // Stays PENDING but invisible to nextPending() until the back-off elapses.
                    dao.scheduleRetry(entity.id, System.currentTimeMillis() + backoff, outcome.message)
                    log.w("Download", "retry ${entity.fileName} in ${backoff}ms (${outcome.message})")
                    _progress.update { it - entity.id }
                } else {
                    dao.setState(entity.id, DownloadState.FAILED, outcome.message)
                    _progress.update { it + (entity.id to DownloadProgress(entity.id, fresh.downloadedBytes, fresh.totalBytes, 0, DownloadState.FAILED)) }
                }
            }
        }
        _finishedEvents.value = entity.id to outcome
    }

    private suspend fun summary(): QueueSummary {
        val open = dao.observeOpen().first()
        val active = open.filter { it.state.isActive }
        val pending = open.count { it.state == DownloadState.PENDING }
        val current = active.firstOrNull()
        val p = current?.let { _progress.value[it.id] }
        return QueueSummary(
            activeCount = active.size,
            pendingCount = pending,
            currentTitle = current?.title,
            currentPercent = p?.percent ?: 0,
            totalBytesPerSecond = _progress.value.values.sumOf { it.bytesPerSecond },
        )
    }

    /* ---------------- user actions ---------------- */

    // A running task is never job-cancelled: requestStop() aborts its HTTP call and lets it
    // unwind on a live coroutine, so the resulting state actually reaches the database.

    fun pause(id: Long) = scope.launch(Dispatchers.IO) {
        running[id]?.task?.requestStop(DownloadState.PAUSED)
            ?: dao.setState(id, DownloadState.PAUSED)
    }

    fun resume(id: Long) = scope.launch(Dispatchers.IO) {
        dao.scheduleRetry(id, 0, null)
        kick()
    }

    fun cancel(id: Long) = scope.launch(Dispatchers.IO) {
        running[id]?.task?.requestStop(DownloadState.CANCELLED) ?: run {
            dao.setState(id, DownloadState.CANCELLED)
            java.io.File(context.cacheDir, "downloads/$id.part").delete()
        }
    }

    fun retry(id: Long) = scope.launch(Dispatchers.IO) {
        val e = dao.byId(id) ?: return@launch
        dao.upsert(e.copy(state = DownloadState.PENDING, error = null, attempt = 0, notBefore = 0, updatedAt = System.currentTimeMillis()))
        _progress.update { it - id }
        kick()
    }

    fun remove(id: Long) = scope.launch(Dispatchers.IO) {
        running[id]?.let { it.task.requestStop(DownloadState.CANCELLED); it.job.join() }
        dao.delete(id)
        java.io.File(context.cacheDir, "downloads/$id.part").delete()
        _progress.update { it - id }
    }

    fun pauseAll() = scope.launch(Dispatchers.IO) {
        running.values.forEach { it.task.requestStop(DownloadState.PAUSED) }
        dao.pauseAllOpen()
    }

    fun resumeAll() = scope.launch(Dispatchers.IO) {
        dao.resumeAllPaused()
        kick()
    }

    fun cancelAll() = scope.launch(Dispatchers.IO) {
        running.values.forEach { it.task.requestStop(DownloadState.CANCELLED) }
        dao.observeOpen().first().forEach { dao.setState(it.id, DownloadState.CANCELLED) }
        java.io.File(context.cacheDir, "downloads").listFiles()?.forEach { it.delete() }
    }

    fun clearFinished() = scope.launch(Dispatchers.IO) {
        dao.clearFinished()
        _progress.update { m -> m.filterValues { !it.state.isTerminal } }
    }

    fun clearFailedFromProgress(id: Long) { _progress.update { it - id } }

    /** True when the current default network satisfies the user's Wi-Fi-only choice. */
    fun networkUsable(wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            // LAN-only networks (no internet validation) are still fine for a local server.
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return false
        }
        if (!wifiOnly) return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    companion object {
        const val WORK_NAME = "romm-downloads"
        const val WORK_TAG = "downloads"
        const val MAX_ATTEMPTS = 3
        /** Connection drops get more patience than hard errors: the server may be rebooting. */
        const val MAX_NETWORK_ATTEMPTS = 8
        private val BACKOFF_MS = longArrayOf(2_000, 8_000, 30_000)
    }
}
