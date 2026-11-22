package app.compresor.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.compresor.engine.InfoVideo

/** `path` = copy of the video in the cache (the one ffmpeg opens). null = sample video (cannot be compressed). */
data class ChosenVideo(
    val uri: Uri?, val name: String, val info: InfoVideo,
    val path: java.io.File? = null,
    val tracks: List<app.compresor.engine.Track> = listOf(app.compresor.engine.Track("aac", 128, 2)),
    /** true = `path` is a copy the app made in its cache (it can be deleted). false = it is the user's ORIGINAL video: NEVER deleted. */
    val isCopy: Boolean = false,
)

/** Deletes the temporary copy. If the video was read directly from its folder, it touches nothing. */
fun ChosenVideo.deleteCopy() { if (isCopy) path?.delete() }

/** Sample video to try the screen without a file (opened with --ez demo true). */
fun videoDemo() = ChosenVideo(
    null, "partida_larga.mkv",
    InfoVideo(1920, 1080, 60.0, 92.0, kbps = 8000, codec = "hevc", sizeBytes = 27_091_148L),
)

/**
 * Reads the basics of the video with MediaMetadataRetriever (comes with Android, no ffprobe).
 * Later ffprobe replaces it, which gives more data (audio tracks, exact codec).
 */
fun readVideo(ctx: Context, uri: Uri): ChosenVideo? {
    var name = "video"
    var size = 0L
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n) ?: name
                if (s >= 0) size = c.getLong(s)
            }
        }
    }
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        fun metadata(k: Int) = r.extractMetadata(k)
        var w = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        var h = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rot = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        val dur = (metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
        val kbps = (metadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
        // fps: Android 23+ gives it; otherwise 30 is assumed
        val fps = if (android.os.Build.VERSION.SDK_INT >= 23)
            metadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 30.0 else 30.0
        ChosenVideo(uri, name, InfoVideo(w, h, fps, dur, kbps, "", size))
    } catch (e: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}
