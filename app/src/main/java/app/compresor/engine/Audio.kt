package app.compresor.engine

import app.compresor.tx

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

// Compress or convert audio. Pure Kotlin, no Android.

/** Output formats. MP3 is the classic one (default); Opus/OGG are the most efficient; AAC (M4A) the most compatible with phones and watches. */
enum class AudioOutput(val tag: String, val ext: String, val codec: String, val kbps: List<Int>, val defaultKbps: Int, private val noteIn: String) {
    MP3("MP3", "mp3", "libmp3lame", listOf(64, 96, 112, 128, 160, 192, 224, 256, 320), 128, "Opens everywhere."),
    OPUS("Opus", "opus", "libopus", listOf(16, 24, 32, 48, 64, 96, 128, 160, 192, 256), 96, "The most efficient: sounds good at few kbps."),
    OGG("OGG (Opus)", "ogg", "libopus", listOf(16, 24, 32, 48, 64, 96, 128, 160, 192, 256), 96, "Opus inside OGG: more players can read it."),
    AAC("AAC (M4A)", "m4a", "aac", listOf(48, 64, 96, 128, 160, 192, 256), 128, "Very compatible, especially with iPhone and watches.");

    /** Short description shown under the format buttons (translated). */
    val note: String get() = tx(noteIn)
}

data class AudioSettings(
    val output: AudioOutput = AudioOutput.MP3,
    val kbps: Int = AudioOutput.MP3.defaultKbps,
    val removeMetadata: Boolean = false,    // by default title, artist, cover art, etc. are kept
)

/** What is known about the input audio. */
data class InfoAudio(
    val duration: Double, val kbps: Int = 0, val codec: String = "", val channels: Int = 2, val hz: Int = 0,
    val sizeBytes: Long = 0, val cover: Boolean = false,
    val title: String = "", val artist: String = "", val album: String = "",
)

/** Approximate audio size (without cover art or tags): kbps x seconds / 8, plus ~1 % container. */
fun audioSizeBytes(durationSec: Double, kbps: Int): Long = (durationSec * kbps * 1000.0 / 8.0 * 1.01).roundToLong()

fun audioSizeLabel(bytes: Long): String =
    if (bytes >= 1_048_576) String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0) else String.format(Locale.US, "%d KB", max(1L, bytes / 1024))

/** The kbps in the list closest to `kbps` (to switch from one format to another without losing the choice). */
fun nearestKbps(s: AudioOutput, kbps: Int): Int = s.kbps.minByOrNull { kotlin.math.abs(it - kbps) } ?: s.defaultKbps

/** Opus needs less than MP3: the bitrate that is equivalent in quality (about 1 kbps of Opus ~ 1.35 of MP3). */
fun suggestedKbps(sourceKbps: Int, s: AudioOutput): Int {
    if (sourceKbps <= 0) return s.defaultKbps
    val adjusted = if (s == AudioOutput.OPUS || s == AudioOutput.OGG) (sourceKbps / 1.35).toInt() else sourceKbps
    return nearestKbps(s, minOf(adjusted, s.kbps.last()))
}

/** Opus and OGG (ffmpeg 7.1) do not accept cover art as a "video": it goes as a METADATA_BLOCK_PICTURE tag, from an ffmetadata file. */
fun coverAsTag(s: AudioOutput) = s == AudioOutput.OPUS || s == AudioOutput.OGG

/** Extracts the cover art to a JPEG of up to 600 px (for Opus/OGG the size matters: it ends up inside a tag). */
fun extractCoverCommand(ffmpeg: String, inputFile: String, outputJpg: String): List<String> =
    listOf(ffmpeg, "-y", "-i", inputFile, "-map", "0:v:0", "-frames:v", "1", "-vf", "scale='min(600,iw)':-2", "-q:v", "4", outputJpg)

private fun u32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

/** FLAC/Vorbis "picture" block (type 3 = front cover) with a JPEG. */
fun flacPictureBlockTest(jpeg: ByteArray, width: Int, height: Int): ByteArray {
    val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
    return u32(3) + u32(mime.size) + mime + u32(0) + u32(width) + u32(height) + u32(24) + u32(0) + u32(jpeg.size) + jpeg
}

/** ffmetadata file with the cover art already in base64 (the padding `=` is preceded by `\`, as that format requires). */
fun ffmetadataCover(base64: String): String =
    ";FFMETADATA1\nMETADATA_BLOCK_PICTURE=" + base64.replace("\\", "\\\\").replace("=", "\\=") + "\n"

/**
 * ffmpeg command. MP3 and M4A carry the cover art as a one-frame "video" (JPEG of up to 800 px).
 * Opus/OGG carry it as a tag: `metaCover` is the path of the ffmetadata file (if there is cover art and metadata is not removed).
 * If metadata removal is requested, neither cover art nor tags nor chapters are included.
 */
fun audioCommand(ffmpeg: String, inputFile: String, output: String, a: AudioSettings, info: InfoAudio, metaCover: String? = null): List<String> {
    val asVideo = info.cover && !a.removeMetadata && !coverAsTag(a.output)
    val cmd = mutableListOf(ffmpeg, "-y", "-i", inputFile)
    val withMeta = metaCover != null && !a.removeMetadata && coverAsTag(a.output)
    if (withMeta) cmd += listOf("-i", metaCover!!)
    cmd += listOf("-map", "0:a:0")
    if (asVideo) cmd += listOf("-map", "0:v:0")
    if (a.removeMetadata) cmd += listOf("-map_metadata", "-1", "-map_chapters", "-1")
    else {
        cmd += listOf("-map_metadata", "0")
        if (withMeta) cmd += listOf("-map_metadata", "1")
    }
    cmd += listOf("-c:a", a.output.codec, "-b:a", "${a.kbps}k")
    if (info.channels > 2) cmd += listOf("-ac", "2")
    when (a.output) {
        AudioOutput.OPUS, AudioOutput.OGG -> cmd += listOf("-vbr", "on", "-application", "audio")
        AudioOutput.MP3 -> cmd += listOf("-id3v2_version", "3")
        AudioOutput.AAC -> cmd += listOf("-movflags", "+faststart")
    }
    if (asVideo) cmd += listOf("-c:v", "mjpeg", "-q:v", "3", "-vf", "scale='min(800,iw)':-2", "-disposition:v:0", "attached_pic")
    cmd += listOf(if (asVideo) "-sn" else "-vn", "-dn", output)
    return cmd
}
