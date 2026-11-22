package app.compresor.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.compresor.engine.tileCenterMs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The frames of the time strip. One is taken per tile, only for those in view, with a reduced
 * keyframe (fast). A single thread because MediaMetadataRetriever does not support parallel use.
 */
class Frames(private val ctx: Context, private val video: ChosenVideo, private val heightPx: Int, private val durationMs: Long) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val thread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
    private var retriever: MediaMetadataRetriever? = null
    private var noSupport = false

    /** Goes up every time a new frame arrives: the bar is redrawn. */
    var version by mutableIntStateOf(0); private set

    private fun keyName(level: Int, i: Int) = "${level}_$i"
    fun fetch(level: Int, i: Int): ImageBitmap? = cache.get(keyName(level, i))

    private fun open(): MediaMetadataRetriever? {
        retriever?.let { return it }
        if (noSupport) return null
        return runCatching {
            MediaMetadataRetriever().also { r ->
                val f = video.path?.takeIf { it.canRead() }
                if (f != null) r.setDataSource(f.path) else r.setDataSource(ctx, video.uri ?: error("no source"))
                retriever = r
            }
        }.getOrElse { noSupport = true; null }
    }

    /** Loads the requested tiles that are missing. Cancelled if what is in view changes. */
    suspend fun load(level: Int, range: IntRange, n: Int) {
        for (i in range) {
            if (cache.get(keyName(level, i)) != null) continue
            withContext(thread) {
                ensureActive()
                val r = open() ?: return@withContext
                val t = tileCenterMs(i, n, durationMs) * 1000
                val aspect = video.info.width.toFloat() / video.info.height
                val w = (heightPx * aspect).toInt().coerceAtLeast(2)
                fun frame(us: Long): Bitmap? = runCatching {
                    if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, heightPx)
                    else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                // near the end of the file there is sometimes no frame: a bit earlier is tried
                val bmp: Bitmap? = frame(t) ?: frame(maxOf(0L, t - 600_000L)) ?: frame(maxOf(0L, t - 1_500_000L))
                if (bmp != null) { cache.put(keyName(level, i), bmp.asImageBitmap()); version++ }
            }
        }
    }

    fun releaseResources() {
        runCatching { retriever?.release() }
        retriever = null
        cache.evictAll()
    }
}
