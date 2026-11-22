package app.compresor.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Compression engine: pure Kotlin, no Android. A port of the .py compressors (mostly Compresor_Manual.py).
// Nothing here touches files or launches processes.

enum class Mode { SIZE, QUALITY, ADVANCED }
enum class Codec { X264, AV1 }
enum class Container(val ext: String) { MP4(".mp4"), WEBM(".webm") }
enum class Control { CRF, BITRATE }
enum class AudioMode { OPUS, ORIGINAL }
enum class Channels { MONO, STEREO, ORIGINAL }

/** How to step down to a lower plan when the encoder crashes or runs out of memory. */
enum class Plan { NORMAL, SAFE, MIN }

data class InfoVideo(
    val width: Int, val height: Int, val fps: Double, val duration: Double,
    val kbps: Int = 0, val codec: String = "", val sizeBytes: Long = 0,
)

data class Track(
    val codec: String, val kbps: Int = 0, val channels: Int = 0,
    val title: String = "", val language: String = "",
)

data class VideoSettings(
    val mode: Mode = Mode.QUALITY,
    val codec: Codec = Codec.X264,
    val container: Container = Container.MP4,
    // by size
    val sizeMb: Double = 10.0,
    // quality
    val control: Control = Control.CRF,
    val crfX264: Int = CRF_X264_DEF,
    val crfAv1: Int = CRF_AV1_DEF,
    val bitrateKbps: Int = 0,
    val width: Int = 0,          // 0 = same as the original
    val height: Int = 0,
    val fps: Double = 0.0,       // 0 = same as the original
    // audio
    val audio: AudioMode = AudioMode.OPUS,
    val audioKbps: Int = AUDIO_OPUS_DEF,
    val channels: Channels = Channels.MONO,
    // advanced
    val presetX264: String = "fast",
    val presetAv1: Int = 4,
    val twoPasses: Boolean = false,
    val gop: Int = 240,
    val extraParams: String = "",   // x264-params or svtav1-params; empty = the defaults
    val threads: Int = 0,             // 0 = whatever the encoder decides
)

const val CRF_X264_DEF = 20
const val CRF_AV1_DEF = 44
const val AUDIO_OPUS_DEF = 64
const val AUDIO_OPUS_MAX = 96
const val AUDIO_OPUS_MIN = 16
const val AV1_PRESET_MAX = 13
const val X264_PARAMS_CUSTOM =
    "ref=6:bframes=8:b-adapt=2:direct=auto:me=umh:subme=9:trellis=2:deblock=-1,-1:psy-rd=1.0:aq-mode=2:aq-strength=0.8"
const val AV1_PARAMS_CUSTOM = "tune=1"
const val MARGIN_SIZE = 0.95
const val PROCESSED_MARK = "Compresor_B2"

/** WebM only supports AV1: the container decides (same as container_consistency in the .py). */
fun consistency(a: VideoSettings): VideoSettings =
    if (a.container == Container.WEBM && a.codec != Codec.AV1) a.copy(codec = Codec.AV1) else a

/** Audio ceiling: 96k, and never more than the original had. */
fun capAudio(tracks: List<Track>): Int {
    val src = tracks.maxOfOrNull { it.kbps } ?: 0
    return if (src <= 0) AUDIO_OPUS_MAX else max(AUDIO_OPUS_MIN, min(AUDIO_OPUS_MAX, src))
}

/** Hard limits: do not enlarge, do not raise fps, do not inflate audio, even dimensions. */
fun crop(a: VideoSettings, info: InfoVideo, tracks: List<Track>): VideoSettings {
    var w = if (a.width <= 0) info.width else min(a.width, info.width)
    var h = if (a.height <= 0) info.height else min(a.height, info.height)
    w = max(16, w - w % 2)
    h = max(16, h - h % 2)
    val fps = if (info.fps > 0 && a.fps > info.fps + 0.01) 0.0 else a.fps
    var kbps = max(AUDIO_OPUS_MIN, min(a.audioKbps, capAudio(tracks)))
    if (a.mode == Mode.SIZE) kbps = audioForSize(a.sizeMb, info.duration, kbps)
    return consistency(
        a.copy(
            width = w, height = h, fps = fps, audioKbps = kbps,
            crfX264 = a.crfX264.coerceIn(0, 51), crfAv1 = a.crfAv1.coerceIn(0, 63),
            presetAv1 = a.presetAv1.coerceIn(0, AV1_PRESET_MAX),
        )
    )
}

/**
 * Video bitrate (kbps) so that the file weighs `mb`. null if impossible
 * (unknown duration, or the audio alone already eats the whole budget).
 * 1 MB = 8192 kilobits; 5 % is left as margin for the container.
 */
fun bitrateForSize(mb: Double, duration: Double, kbpsAudio: Int): Int? {
    if (mb <= 0 || duration <= 0.0) return null
    val total = mb * 8192.0 / duration * MARGIN_SIZE
    val video = floor(total - kbpsAudio).toInt()
    return if (video >= 1) video else null
}

/** In By-size mode the audio cannot eat the budget: cap at 25 % of the total (minimum 16 kbps). */
fun audioForSize(mb: Double, duration: Double, kbpsAudio: Int): Int {
    if (mb <= 0.0 || duration <= 0.0) return kbpsAudio
    val cap = mb * 8192.0 / duration * MARGIN_SIZE * 0.25
    return if (kbpsAudio > cap) max(AUDIO_OPUS_MIN, cap.toInt()) else kbpsAudio
}

/** Video bitrate to request, depending on the mode. null = CRF is used. */
fun effectiveBitrate(a: VideoSettings, info: InfoVideo, kbpsAudio: Int): Int? = when {
    a.mode == Mode.SIZE -> bitrateForSize(a.sizeMb, info.duration, kbpsAudio)
    a.control == Control.BITRATE -> a.bitrateKbps.takeIf { it > 0 }
    else -> null
}

fun fpsText(f: Double): String = when {
    abs(f - 23.976) < 0.01 -> "24000/1001"
    abs(f - 29.97) < 0.01 -> "30000/1001"
    abs(f - 59.94) < 0.01 -> "60000/1001"
    f == floor(f) -> f.toInt().toString()
    else -> f.toString()
}

/** Video filter: lanczos scaling and/or fps reduction. Empty if there is nothing to do. */
fun filterChain(a: VideoSettings, info: InfoVideo): String {
    val f = mutableListOf<String>()
    if (a.width != info.width || a.height != info.height) f += "scale=${a.width}:${a.height}:flags=lanczos"
    if (a.fps > 0) f += "fps=${fpsText(a.fps)}"
    return f.joinToString(",")
}

/**
 * Video arguments. `encoderAv1`: "libsvtav1" (64 bit) or "libaom-av1" (32 bit).
 * SAFE/MINIMUM plan = the retries of the .py (faster preset, fewer threads).
 */
fun videoArgs(
    a: VideoSettings, bitrate: Int?, plan: Plan = Plan.NORMAL, encoderAv1: String = "libsvtav1",
): List<String> {
    val jump = when (plan) { Plan.MIN -> 4; Plan.SAFE -> 2; Plan.NORMAL -> 0 }
    val v = mutableListOf<String>()
    if (a.codec == Codec.AV1) {
        val preset = min(AV1_PRESET_MAX, a.presetAv1 + jump)
        if (encoderAv1 == "libaom-av1") {
            v += listOf("-c:v", "libaom-av1", "-pix_fmt", "yuv420p", "-g", a.gop.toString(),
                "-cpu-used", min(8, preset).toString(), "-row-mt", "1")
            if (bitrate == null) v += listOf("-crf", a.crfAv1.toString(), "-b:v", "0")
            else v += listOf("-b:v", "${bitrate}k")
        } else {
            v += listOf("-c:v", "libsvtav1", "-pix_fmt", "yuv420p", "-g", a.gop.toString(),
                "-preset", preset.toString())
            if (bitrate == null) v += listOf("-crf", a.crfAv1.toString()) else v += listOf("-b:v", "${bitrate}k")
            when (plan) {
                Plan.MIN -> v += listOf("-threads", "1")
                Plan.SAFE -> v += listOf("-threads", "2")
                Plan.NORMAL -> v += listOf("-svtav1-params", a.extraParams.ifBlank { AV1_PARAMS_CUSTOM })
            }
        }
        if (a.threads > 0 && plan == Plan.NORMAL) v += listOf("-threads", a.threads.toString())
        return v
    }
    var preset = a.presetX264
    if (plan == Plan.MIN) preset = "veryfast"
    else if (plan == Plan.SAFE && preset in setOf("slower", "veryslow", "placebo")) preset = "medium"
    v += listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", preset,
        "-profile:v", "high", "-level", "4.2", "-g", a.gop.toString())
    if (bitrate == null) v += listOf("-crf", a.crfX264.toString())
    else v += listOf("-b:v", "${bitrate}k", "-maxrate", "${(bitrate * 1.5).toInt()}k", "-bufsize", "${bitrate * 2}k")
    when (plan) {
        Plan.MIN -> v += listOf("-threads", "1")
        Plan.NORMAL -> {
            v += listOf("-x264-params", a.extraParams.ifBlank { X264_PARAMS_CUSTOM })
            if (a.threads > 0) v += listOf("-threads", a.threads.toString())
        }
        Plan.SAFE -> {}
    }
    return v
}

/** Audio in Opus mode: all tracks are mixed into ONE (amix without normalizing). */
class AudioArgs(val pre: List<String>, val maps: List<String>, val codec: List<String>)

fun opusAudioArgs(a: VideoSettings, nTracks: Int, vf: String): AudioArgs {
    val channelCount = if (a.channels == Channels.MONO) "1" else "2"
    if (nTracks == 0) {
        return AudioArgs(
            if (vf.isNotEmpty()) listOf("-vf", vf) else emptyList(),
            listOf("-map", "0:v:0", "-an"), emptyList(),
        )
    }
    val parts = mutableListOf<String>()
    if (vf.isNotEmpty()) parts += "[0:v]$vf[vout]"
    parts += if (nTracks > 1) "[0:a]amix=inputs=$nTracks:normalize=0[aout]" else "[0:a:0]anull[aout]"
    return AudioArgs(
        listOf("-filter_complex", parts.joinToString(";")),
        listOf("-map", if (vf.isNotEmpty()) "[vout]" else "0:v:0", "-map", "[aout]"),
        listOf("-c:a", "libopus", "-b:a", "${a.audioKbps}k", "-ac", channelCount),
    )
}

/** Full ffmpeg command (a single pass). Arguments go as a list, never as console text. */
fun command(
    ffmpeg: String, inputFile: String, output: String, a: VideoSettings, info: InfoVideo, tracks: List<Track>,
    plan: Plan = Plan.NORMAL, encoderAv1: String = "libsvtav1",
): List<String> {
    val limited = crop(a, info, tracks)
    val bitrate = effectiveBitrate(limited, info, actualAudioKbps(limited, tracks))
    val vf = filterChain(limited, info)
    val audio = if (usesOriginalAudio(limited, tracks)) originalAudioArgs(vf) else opusAudioArgs(limited, tracks.size, vf)
    val cmd = mutableListOf(ffmpeg, "-y", "-i", inputFile)
    cmd += audio.pre
    cmd += videoArgs(limited, bitrate, plan, encoderAv1)
    cmd += audio.codec
    cmd += audio.maps
    cmd += listOf("-sn", "-dn", "-metadata", "comment=$PROCESSED_MARK")
    if (limited.container == Container.MP4) cmd += listOf("-movflags", "+faststart")
    cmd += output
    return cmd
}

// ───────────── original audio and two passes ─────────────

/** Which audio codecs can be copied as they are in each container (from Compresor_Manual.py). */
val AUDIO_COPYABLE: Map<Container, Set<String>> = mapOf(
    Container.MP4 to setOf("aac", "mp3", "alac", "ac3", "eac3", "opus", "flac"),
    Container.WEBM to setOf("opus", "vorbis"),
)

/** The original audio is kept only if it was requested and ALL tracks fit in the container. */
fun usesOriginalAudio(a: VideoSettings, tracks: List<Track>): Boolean {
    if (a.audio != AudioMode.ORIGINAL || tracks.isEmpty()) return false
    val ok = AUDIO_COPYABLE[a.container] ?: emptySet()
    return tracks.all { it.codec in ok }
}

/** Original audio: all tracks are copied without re-encoding. */
fun originalAudioArgs(vf: String): AudioArgs = AudioArgs(
    if (vf.isNotEmpty()) listOf("-vf", vf) else emptyList(),
    listOf("-map", "0:v:0", "-map", "0:a?"),
    listOf("-c:a", "copy"),
)

/** Audio kbps that are really spent (for the by-size calculation). */
fun actualAudioKbps(a: VideoSettings, tracks: List<Track>): Int =
    if (usesOriginalAudio(a, tracks)) tracks.sumOf { if (it.kbps > 0) it.kbps else 128 } else a.audioKbps

/**
 * List of commands to run in order. Two passes only with x264 and bitrate (by size or fixed bitrate):
 * the 1st analyzes and writes the stats file; the 2nd encodes. SVT-AV1 does not do 2 passes through ffmpeg.
 */
fun passesCommand(
    ffmpeg: String, inputFile: String, output: String, a: VideoSettings, info: InfoVideo, tracks: List<Track>,
    passlog: String, plan: Plan = Plan.NORMAL, encoderAv1: String = "libsvtav1", nullValue: String = "/dev/null",
): List<List<String>> {
    val only = command(ffmpeg, inputFile, output, a, info, tracks, plan, encoderAv1)
    val limited = crop(a, info, tracks)
    val bitrate = effectiveBitrate(limited, info, actualAudioKbps(limited, tracks))
    val two = limited.twoPasses && limited.codec == Codec.X264 && bitrate != null && plan != Plan.MIN
    if (!two) return listOf(only)
    val vf = filterChain(limited, info)
    val p1 = mutableListOf(ffmpeg, "-y", "-i", inputFile)
    if (vf.isNotEmpty()) p1 += listOf("-vf", vf)
    p1 += videoArgs(limited, bitrate, plan, encoderAv1)
    p1 += listOf("-pass", "1", "-passlogfile", passlog, "-an", "-map", "0:v:0", "-f", "null", nullValue)
    val p2 = only.toMutableList()
    p2.addAll(p2.size - 1, listOf("-pass", "2", "-passlogfile", passlog))   // before the output file
    return listOf(p1, p2)
}
