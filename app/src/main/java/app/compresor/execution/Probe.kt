package app.compresor.execution

import app.compresor.tx

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.compresor.engine.InfoAudio
import app.compresor.engine.InfoVideo
import app.compresor.engine.Track
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Reads a video with ffprobe (libffprobe.so) and copies it to the cache so ffmpeg can open it. */
object Probe {

    fun nameOf(ctx: Context, uri: Uri): String {
        var name = "video"
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0) name = c.getString(n) ?: name
                }
            }
        }
        return name
    }

    /** ffmpeg is a separate program and does not understand Android content:// URIs: it is copied to the app cache. */
    fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val dir = File(ctx.cacheDir, "inputs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }                       // only the current video is kept
        val ext = name.substringAfterLast('.', "mp4").take(5)
        val f = File(dir, "in_${System.currentTimeMillis()}.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            ?: error(tx("Could not open the video"))
        return f
    }

    fun probe(ctx: Context, file: File): Pair<InfoVideo, List<Track>>? = runCatching {
        val ffprobe = File(ctx.applicationInfo.nativeLibraryDir, "libffprobe.so").path
        val p = ProcessBuilder(
            ffprobe, "-v", "error",
            "-show_entries",
            "format=duration,size,bit_rate:stream=index,codec_type,codec_name,channels,bit_rate,width,height,avg_frame_rate,r_frame_rate" +
                ":stream_tags=title,language,rotate:stream_side_data=rotation",
            "-of", "json", file.path,
        ).redirectErrorStream(false).start()
        val txt = p.inputStream.bufferedReader().readText()
        p.waitFor()
        parse(txt, file.length())
    }.getOrNull()

    /** Reads an audio file: duration, bitrate, format, whether it has cover art and its tags (title, artist, album). */
    fun probeAudio(ctx: Context, file: File): InfoAudio? = runCatching {
        val ffprobe = File(ctx.applicationInfo.nativeLibraryDir, "libffprobe.so").path
        val p = ProcessBuilder(
            ffprobe, "-v", "error",
            "-show_entries",
            "format=duration,bit_rate:format_tags:stream=index,codec_type,codec_name,channels,sample_rate,bit_rate:stream_disposition=attached_pic:stream_tags",
            "-of", "json", file.path,
        ).redirectErrorStream(false).start()
        val txt = p.inputStream.bufferedReader().readText()
        p.waitFor()
        parseAudio(txt, file.length())
    }.getOrNull()

    private fun tag(o: JSONObject?, name: String): String {
        if (o == null) return ""
        val k = o.keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) } ?: return ""
        return o.optString(k).trim()
    }

    fun parseAudio(json: String, sizeBytes: Long): InfoAudio? {
        val root = JSONObject(json)
        val streams = root.optJSONArray("streams") ?: return null
        var codec = ""; var channels = 2; var hz = 0; var trackKbps = 0; var hasAudio = false; var cover = false
        var title = ""; var artist = ""; var album = ""
        val fmt = root.optJSONObject("format")
        val tagsF = fmt?.optJSONObject("tags")
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            when (s.optString("codec_type")) {
                "audio" -> if (!hasAudio) {
                    hasAudio = true
                    codec = s.optString("codec_name").lowercase()
                    channels = s.optInt("channels", 2)
                    hz = s.optString("sample_rate").toIntOrNull() ?: 0
                    trackKbps = ((s.optString("bit_rate").toLongOrNull() ?: 0L) / 1000).toInt()
                    val tg = s.optJSONObject("tags")
                    title = tag(tg, "title"); artist = tag(tg, "artist"); album = tag(tg, "album")
                }
                "video" -> if (s.optJSONObject("disposition")?.optInt("attached_pic") == 1) cover = true
            }
        }
        if (!hasAudio) return null
        if (title.isEmpty()) title = tag(tagsF, "title")
        if (artist.isEmpty()) artist = tag(tagsF, "artist")
        if (album.isEmpty()) album = tag(tagsF, "album")
        val dur = fmt?.optString("duration")?.toDoubleOrNull() ?: 0.0
        val kbps = if (trackKbps > 0) trackKbps else ((fmt?.optString("bit_rate")?.toLongOrNull() ?: 0L) / 1000).toInt()
        return InfoAudio(dur, kbps, codec, channels, hz, sizeBytes, cover, title, artist, album)
    }

    /** Separate from probe() so it can be tested without Android if needed. */
    fun parse(json: String, sizeBytes: Long): Pair<InfoVideo, List<Track>>? {
        val root = JSONObject(json)
        val streams = root.optJSONArray("streams") ?: return null
        var w = 0; var h = 0; var fps = 0.0; var codec = ""; var rot = 0
        var seen = false
        val tracks = mutableListOf<Track>()
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            when (s.optString("codec_type")) {
                "video" -> if (!seen) {
                    seen = true
                    w = s.optInt("width"); h = s.optInt("height"); codec = s.optString("codec_name")
                    fps = fraction(s.optString("avg_frame_rate")).takeIf { it > 0 } ?: fraction(s.optString("r_frame_rate"))
                    rot = s.optJSONObject("tags")?.optString("rotate")?.toIntOrNull() ?: 0
                    s.optJSONArray("side_data_list")?.let { l ->
                        for (j in 0 until l.length()) if (l.getJSONObject(j).has("rotation")) rot = l.getJSONObject(j).optInt("rotation")
                    }
                }
                "audio" -> {
                    val tags = s.optJSONObject("tags")
                    tracks += Track(
                        codec = s.optString("codec_name").lowercase(),
                        kbps = (s.optString("bit_rate").toLongOrNull() ?: 0L).div(1000).toInt(),
                        channels = s.optInt("channels"),
                        title = tags?.optString("title").orEmpty(),
                        language = tags?.optString("language").orEmpty(),
                    )
                }
            }
        }
        if (!seen || w <= 0 || h <= 0) return null
        if (abs(rot) % 180 == 90) { val t = w; w = h; h = t }        // ffmpeg rotates by itself: the final sizes are the rotated ones
        val fmt = root.optJSONObject("format")
        val dur = fmt?.optString("duration")?.toDoubleOrNull() ?: 0.0
        val kbps = ((fmt?.optString("bit_rate")?.toLongOrNull() ?: 0L) / 1000).toInt()
        return InfoVideo(w, h, if (fps > 0) fps else 30.0, dur, kbps, codec, sizeBytes) to tracks
    }

    private fun fraction(s: String): Double {
        val p = s.split("/")
        val a = p.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
        val b = p.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (b == 0.0) 0.0 else a / b
    }
}
