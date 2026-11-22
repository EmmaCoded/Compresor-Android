package app.compresor.ui

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Player for the cutter preview. It uses MediaPlayer because it allows going to an EXACT frame
 * (SEEK_CLOSEST, Android 8+), so the image matches the white bar when dragging it.
 */
class PlayerControl {
    private var mp: MediaPlayer? = null
    var prepared by mutableStateOf(false); private set
    var error by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var posMs by mutableLongStateOf(0L)

    private var searching = false
    private var pending = -1L
    private var lastPos = 0L
    /** Cap for the preview jumps: seeking RIGHT at the last instant does not show the last frame. */
    var limitMs = Long.MAX_VALUE

    fun prepare(ctx: Context, video: ChosenVideo, holder: SurfaceHolder) {
        releaseResources()
        val m = MediaPlayer()
        mp = m
        try {
            val f = video.path?.takeIf { it.canRead() }
            when {
                f != null -> m.setDataSource(f.path)
                video.uri != null -> m.setDataSource(ctx, video.uri)
                else -> { error = true; return }
            }
            m.setDisplay(holder)
            m.setOnPreparedListener { prepared = true; goTo(lastPos) }                 // the frame of that position is shown
            m.setOnSeekCompleteListener {
                searching = false
                if (pending >= 0) { val p = pending; pending = -1; requestPermission(p) }
            }
            m.setOnErrorListener { _, _, _ -> error = true; playing = false; true }
            m.setOnCompletionListener { playing = false }
            m.prepareAsync()
        } catch (_: Exception) {
            error = true
        }
    }

    private fun requestPermission(destination: Long) {
        val ms = minOf(destination, limitMs)
        val m = mp ?: return
        if (!prepared) { lastPos = ms; return }
        if (searching) { pending = ms; return }            // if a jump is already in progress, only the last request matters
        searching = true
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) m.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else m.seekTo(ms.toInt())
        }.onFailure { searching = false }
    }

    fun goTo(ms: Long) { posMs = ms; lastPos = ms; requestPermission(ms) }

    /** Shows the frame at `ms` WITHOUT moving the position (the frame line): used when dragging the start and end points. */
    fun see(ms: Long) { requestPermission(ms) }

    fun play() { val m = mp ?: return; if (!prepared) return; runCatching { m.start(); playing = true } }
    fun pauseJob() { val m = mp ?: return; runCatching { if (m.isPlaying) m.pause() }; playing = false }
    fun toggle() { if (playing) pauseJob() else play() }

    /** Real position of the player (to move the bar while playing). */
    fun readPos(): Long = runCatching { mp?.currentPosition?.toLong() }.getOrNull() ?: posMs

    fun releaseResources() {
        lastPos = posMs
        runCatching { mp?.release() }
        mp = null; prepared = false; playing = false; searching = false; pending = -1
    }
}

@Composable
fun ViewVideo(control: PlayerControl, video: ChosenVideo, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) { onDispose { control.releaseResources() } }
    AndroidView(
        factory = { c ->
            SurfaceView(c).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { control.prepare(ctx, video, h) }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { control.releaseResources() }
                })
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}
