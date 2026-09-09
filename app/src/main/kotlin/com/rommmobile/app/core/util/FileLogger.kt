package com.rommmobile.app.core.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rotating on-disk log (2 files x 1 MB) exportable from the diagnostics screen. Without it,
 * diagnosing a problem on a Chinese handheld over chat is guesswork.
 */
@Singleton
class FileLogger @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File get() = File(context.filesDir, "logs").apply { mkdirs() }
    private val current: File get() = File(dir, "app.log")
    private val previous: File get() = File(dir, "app.log.1")
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "file-logger").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write("E", "CRASH", "Uncaught on ${t.name}: ${Log.getStackTraceString(e)}")
                executor.shutdown()
                executor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            previousHandler?.uncaughtException(t, e)
        }
        i("App", "process start")
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable? = null) { Log.w(tag, msg, tr); write("W", tag, msg + (tr?.let { "\n" + Log.getStackTraceString(it) } ?: "")) }
    fun e(tag: String, msg: String, tr: Throwable? = null) { Log.e(tag, msg, tr); write("E", tag, msg + (tr?.let { "\n" + Log.getStackTraceString(it) } ?: "")) }

    private fun write(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg\n"
        executor.execute {
            try {
                val f = current
                if (f.length() > MAX_BYTES) {
                    previous.delete()
                    f.renameTo(previous)
                }
                current.appendText(line)
            } catch (_: Throwable) {
            }
        }
    }

    /** Merges both rotation files into one export in the cache dir. */
    fun export(): File {
        val out = File(context.cacheDir, "rommmobile-log.txt")
        out.outputStream().use { os ->
            if (previous.exists()) previous.inputStream().use { it.copyTo(os) }
            if (current.exists()) current.inputStream().use { it.copyTo(os) }
        }
        return out
    }

    fun tail(lines: Int): List<String> {
        val all = ArrayList<String>()
        if (previous.exists()) all += previous.readLines()
        if (current.exists()) all += current.readLines()
        return if (all.size <= lines) all else all.subList(all.size - lines, all.size)
    }

    private companion object { const val MAX_BYTES = 1L * 1024 * 1024 }
}
