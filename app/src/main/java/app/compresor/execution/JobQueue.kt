package app.compresor.execution

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.compresor.engine.VideoSettings
import app.compresor.ui.ChosenVideo

/** State of a running job. Written by the service and read by the screen (and the notification). */
class JobState {
    var progress by mutableStateOf(Progress())
    var paused by mutableStateOf(false)
    val start = System.currentTimeMillis()
    private var pausedSince = 0L
    private var totalPaused = 0L

    fun markPaused(p: Boolean) {
        val now = System.currentTimeMillis()
        if (p && pausedSince == 0L) pausedSince = now
        else if (!p && pausedSince != 0L) { totalPaused += now - pausedSince; pausedSince = 0L }
        paused = p
    }

    /** Seconds really working: time paused does not count (so the "left" estimate does not inflate). */
    fun secondsActive(now: Long): Long {
        val isPaused = if (pausedSince != 0L) now - pausedSince else 0L
        return maxOf(0L, (now - start - totalPaused - isPaused) / 1000)
    }
}

class RunningJob(
    val video: ChosenVideo, val task: Task,
    val ctrl: ProcessControl = ProcessControl(), val state: JobState = JobState(),
) {
    /** The compression settings (the default values if the task is cut or crop). */
    val settings: VideoSettings get() = (task as? Task.Compress)?.settings ?: VideoSettings()
}

fun hms(s: Long): String =
    if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

/**
 * One job at a time (same as in the .py: a single ffmpeg). The job lives in the foreground service,
 * so it keeps going even if the screen is closed; the screen only watches it.
 */
object JobQueue {
    var current by mutableStateOf<RunningJob?>(null)
    var outcome by mutableStateOf<Outcome?>(null)

    fun begin(ctx: Context, video: ChosenVideo, task: Task) {
        outcome = null
        current = RunningJob(video, task)
        ContextCompat.startForegroundService(ctx, Intent(ctx, CompressionService::class.java))
    }

    fun pauseJob() { current?.let { it.ctrl.pauseJob(); it.state.markPaused(true) } }
    fun resume() { current?.let { it.ctrl.resume(); it.state.markPaused(false) } }
    fun stop() { current?.ctrl?.stop() }
    fun clear() { current = null; outcome = null }
}
