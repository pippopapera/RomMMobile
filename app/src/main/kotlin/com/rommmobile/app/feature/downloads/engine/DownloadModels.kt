package com.rommmobile.app.feature.downloads.engine

import androidx.compose.runtime.Immutable
import com.rommmobile.app.data.db.DownloadState

@Immutable
data class DownloadProgress(
    val id: Long,
    val downloaded: Long,
    val total: Long,
    val bytesPerSecond: Long,
    val state: DownloadState,
) {
    val percent: Int get() = if (total <= 0) 0 else ((downloaded * 100) / total).toInt().coerceIn(0, 100)
    val etaSeconds: Long get() = if (bytesPerSecond <= 0 || total <= 0) -1 else ((total - downloaded) / bytesPerSecond)
}

/** Aggregate shown in the persistent notification and the mini bar. */
@Immutable
data class QueueSummary(
    val activeCount: Int,
    val pendingCount: Int,
    val currentTitle: String?,
    val currentPercent: Int,
    val totalBytesPerSecond: Long,
) {
    val isEmpty: Boolean get() = activeCount == 0 && pendingCount == 0
}

/** Why a task stopped, as seen by the scheduler. */
sealed interface TaskOutcome {
    data object Completed : TaskOutcome
    data object Paused : TaskOutcome
    data object Cancelled : TaskOutcome
    data class Failed(val message: String, val retryable: Boolean) : TaskOutcome
    /** Network gone or Wi-Fi only violated: put back in PENDING and wait for the worker. */
    data object Deferred : TaskOutcome
}
