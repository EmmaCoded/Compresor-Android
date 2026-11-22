package app.compresor.engine

import app.compresor.tx

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Cut (a section in time) and Crop (frame rectangle + rotation). Pure Kotlin, no Android.

/** A section of the video. Times are in milliseconds. */
data class CutRange(
    val startMs: Long,
    val endMs: Long,
    val precise: Boolean = false,   // false = no re-encoding (fast, starts at the previous keyframe); true = frame-exact
    val noAudio: Boolean = false,
)

/** Clockwise rotation. */
enum class Rotation(val degrees: Int) {
    NONE(0), RIGHT(90), DOWN(180), LEFT(270);

    fun clockwise() = entries[(ordinal + 1) % 4]
    fun counterclockwise() = entries[(ordinal + 3) % 4]
    val swapsSides get() = this == RIGHT || this == LEFT
}

/** Rectangle in pixels of the ALREADY rotated image (the one seen in the preview). */
data class CropRegion(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val rotation: Rotation = Rotation.NONE,
    val noAudio: Boolean = false,
)

private val EXT_CONTAINERS = setOf("mp4", "mkv", "webm", "mov", "m4v")

fun secondsText(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

/** "01:05.3" (minutes:seconds.tenths) for the bar labels. */
fun timeText(ms: Long): String {
    val d = max(0L, ms) / 100
    return String.format(Locale.US, "%02d:%02d.%d", d / 600, d / 10 % 60, d % 10)
}

// ───────────── rules for the start and end points ─────────────

const val CUT_MIN_MS = 100L

fun canSetStart(posMs: Long, endMs: Long) = posMs + CUT_MIN_MS <= endMs
fun canSetEnd(posMs: Long, startMs: Long) = posMs >= startMs + CUT_MIN_MS

val MESSAGE_START_AFTER_END: String get() = tx("You cannot put the start point after the end point.")
val MESSAGE_END_BEFORE_START: String get() = tx("You cannot put the end point before the start point.")

/**
 * A start or end point that is dragged does NOT move the frame line... unless it collides with it and keeps going:
 * then it pushes it (forward or backward). `previous` and `new` are the positions of the point before and after
 * this movement; `pos` is the line. If the line is already stuck to the point, any movement drags it along.
 */
fun pushesLine(previous: Long, new: Long, pos: Long): Boolean =
    (previous <= pos && new > pos) || (previous >= pos && new < pos)

// ───────────── cut ─────────────

/** Output extension: fast = the same container if it works (otherwise mkv, which accepts everything); precise = mp4 (mkv if it came from mkv/webm). */
fun cutOutputExtension(enteredName: String, precise: Boolean): String {
    val ext = enteredName.substringAfterLast('.', "").lowercase()
    return if (precise) (if (ext == "mkv" || ext == "webm") ".mkv" else ".mp4")
    else if (ext in EXT_CONTAINERS) ".$ext" else ".mkv"
}

fun cutCommand(ffmpeg: String, inputFile: String, output: String, c: CutRange): List<String> {
    val dur = max(CUT_MIN_MS, c.endMs - c.startMs)
    val cmd = mutableListOf(ffmpeg, "-y", "-ss", secondsText(c.startMs), "-i", inputFile, "-t", secondsText(dur))
    cmd += listOf("-map", "0:v:0")
    if (!c.noAudio) cmd += listOf("-map", "0:a?")
    if (c.precise) {
        cmd += listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p")
        if (!c.noAudio) cmd += listOf("-c:a", "aac", "-b:a", "160k")
    } else {
        cmd += listOf("-c", "copy", "-avoid_negative_ts", "make_zero")
    }
    cmd += listOf("-sn", "-dn")
    if (output.endsWith(".mp4") || output.endsWith(".mov") || output.endsWith(".m4v")) cmd += listOf("-movflags", "+faststart")
    cmd += output
    return cmd
}

// ───────────── crop and rotate ─────────────

fun filterRotation(g: Rotation): String = when (g) {
    Rotation.NONE -> ""
    Rotation.RIGHT -> "transpose=1"
    Rotation.LEFT -> "transpose=2"
    Rotation.DOWN -> "transpose=1,transpose=1"
}

/** Image size after the rotation. */
fun rotatedDimensions(width: Int, height: Int, g: Rotation): Pair<Int, Int> = if (g.swapsSides) height to width else width to height

/** Keeps the rectangle inside the rotated image and with even sizes (x264 and yuv420p require it). */
fun adjustCrop(r: CropRegion, width: Int, height: Int): CropRegion {
    val (w, h) = rotatedDimensions(width, height, r.rotation)
    val rw = max(16, min(r.width, w)).let { it - it % 2 }
    val rh = max(16, min(r.height, h)).let { it - it % 2 }
    val x = r.x.coerceIn(0, max(0, w - rw)).let { it - it % 2 }
    val y = r.y.coerceIn(0, max(0, h - rh)).let { it - it % 2 }
    return r.copy(x = x, y = y, width = rw, height = rh)
}

fun hasCrop(r: CropRegion, width: Int, height: Int): Boolean {
    val (w, h) = rotatedDimensions(width, height, r.rotation)
    return !(r.x == 0 && r.y == 0 && r.width >= w && r.height >= h)
}

/** Full video filter: first it rotates and then it crops (the rectangle is the one of the already rotated image). */
fun filterCrop(r0: CropRegion, width: Int, height: Int): String {
    val r = adjustCrop(r0, width, height)
    val f = mutableListOf<String>()
    filterRotation(r.rotation).takeIf { it.isNotEmpty() }?.let { f += it }
    if (hasCrop(r, width, height)) f += "crop=${r.width}:${r.height}:${r.x}:${r.y}"
    return f.joinToString(",")
}

/** The audio is copied if it fits in MP4; otherwise it is converted to AAC. */
fun cropCommand(ffmpeg: String, inputFile: String, output: String, r: CropRegion, width: Int, height: Int, tracks: List<Track>): List<String> {
    val vf = filterCrop(r, width, height)
    val cmd = mutableListOf(ffmpeg, "-y", "-i", inputFile)
    if (vf.isNotEmpty()) cmd += listOf("-vf", vf)
    cmd += listOf("-map", "0:v:0")
    if (!r.noAudio) cmd += listOf("-map", "0:a?")
    cmd += listOf("-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p")
    if (!r.noAudio) {
        val copyable = AUDIO_COPYABLE[Container.MP4] ?: emptySet()
        if (tracks.isNotEmpty() && tracks.all { it.codec in copyable }) cmd += listOf("-c:a", "copy")
        else cmd += listOf("-c:a", "aac", "-b:a", "160k")
    }
    cmd += listOf("-sn", "-dn", "-movflags", "+faststart", output)
    return cmd
}
