package app.compresor.execution

import app.compresor.tx

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.compresor.MainActivity
import app.compresor.R
import java.util.Locale

/**
 * Foreground service: keeps compressing with the app closed or the screen off and shows the
 * progress in a notification with Pause and Stop. Android 15 limits this type of service to ~6 h per day.
 */
class CompressionService : Service() {
    private var thread: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACC_PAUSE -> { JobQueue.pauseJob(); refresh(); return START_NOT_STICKY }
            ACC_RESUME -> { JobQueue.resume(); refresh(); return START_NOT_STICKY }
            ACC_STOP -> { JobQueue.stop(); return START_NOT_STICKY }
        }
        val t = JobQueue.current
        if (t == null || t.video.path == null) { stopSelf(); return START_NOT_STICKY }
        if (thread != null) return START_NOT_STICKY
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID_PROGRESS, assemble(t), type)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "compresor:trabajo").apply { acquire(6 * 60 * 60 * 1000L) }
        thread = Thread {
            var last = 0L
            val onProgress: (Progress) -> Unit = { p ->
                t.state.progress = p
                val now = SystemClock.elapsedRealtime()
                if (now - last > 1000) { last = now; refresh() }
            }
            val r = when (val ta = t.task) {
                is Task.Compress -> Compression.compressVideo(applicationContext, t.video.path, ta.settings, t.video.info, t.video.tracks, t.ctrl, onProgress)
                else -> Compression.editFile(applicationContext, t.video.path, t.video.name, ta, t.video.info, t.video.tracks, t.ctrl, onProgress)
            }
            JobQueue.outcome = r
            finish(r)
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun finish(r: Outcome) {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val title: String
        val text: String
        when (r.end) {
            Ending.OK -> {
                title = tx("Done")
                text = tx("%.1f MB → %.1f MB. Tap to save it.").format(Locale.getDefault(), r.originalSize / 1048576.0, r.outputSize / 1048576.0)
            }
            Ending.STOPPED -> { title = tx("Stopped"); text = tx("Nothing was saved. Your video is untouched.") }
            Ending.FAILURE -> { title = tx("Could not finish"); text = tx("Tap to see why. Your video is untouched.") }
        }
        val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
            .setContentIntent(openApp()).setAutoCancel(true).build()
        if (canNotify()) NotificationManagerCompat.from(this).notify(ID_RESULT, n)
        stopSelf()
    }

    private fun canNotify() =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val t = JobQueue.current ?: return
        if (canNotify()) NotificationManagerCompat.from(this).notify(ID_PROGRESS, assemble(t))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(code: String, text: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, code.hashCode(), Intent(this, CompressionService::class.java).setAction(code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, text, pi).build()
    }

    private fun assemble(t: RunningJob): Notification {
        val p = t.state.progress
        val global = ((p.pass - 1) + p.pct) / p.passes
        val pct = (global * 100).toInt().coerceIn(0, 100)
        val active = t.state.secondsActive(System.currentTimeMillis())
        val missing = if (global > 0.02f) (active / global * (1 - global)).toLong() else -1L
        val text = buildString {
            append("$pct %")
            if (t.state.paused) append(tx(" · paused"))
            else if (missing >= 0) append(tx(" · about {0} left", hms(missing)))
            if (p.passes > 1) append(tx(" · pass {0} of {1}", p.pass, p.passes))
        }
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${t.task.verb} ${t.video.name}")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setOnlyAlertOnce(true)
            .setProgress(100, pct, global <= 0f)
            .setContentIntent(openApp())
            .addAction(if (t.state.paused) action(ACC_RESUME, tx("Resume")) else action(ACC_PAUSE, tx("Pause")))
            .addAction(action(ACC_STOP, tx("Stop")))
        return b.build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, tx("Compression in progress"), NotificationManager.IMPORTANCE_LOW)
                .apply { description = tx("Video compression progress") }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, tx("Compression finished"), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = tx("Notice when it finishes or fails") }
        )
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        const val ACC_PAUSE = "app.compresor.PAUSAR"
        const val ACC_RESUME = "app.compresor.REANUDAR"
        const val ACC_STOP = "app.compresor.DETENER"
        private const val CHANNEL_PROGRESS = "progreso"
        private const val CHANNEL_RESULT = "resultado"
        private const val ID_PROGRESS = 1
        private const val ID_RESULT = 2
    }
}
