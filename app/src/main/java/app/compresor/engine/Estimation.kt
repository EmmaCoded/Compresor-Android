package app.compresor.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// List of output resolutions with approximate size ("Size" tab).
// The formula is the same idea the reference app uses: the size grows with the pixels,
//   size = bits_per_pixel_per_frame x width x height x fps x duration / 8  (+ audio).
// Measured against its listing: 1080p -> 5.9 MB, 720p -> 2.6 MB (ratio 0.44 = pixel ratio).

/** Bits per pixel per frame of the Size list.
 *  x264 = 0.047: comes from the reference app's listing (1080p at 60 fps -> 5.9 MB in 8 s = 0.047 bits/px/frame).
 *  AV1  = 0.026: my ASSUMPTION (~45 % less than x264); not measured. It is also capped to the real size of the video. */
const val BPP_X264 = 0.047
const val BPP_AV1 = 0.026

/** Short output sides (maximum 1080p, minimum 144p): the 6 of YouTube and 16 intermediate ones. */
val OUTPUT_SIDES = listOf(
    1080, 1020, 960, 900, 840, 780, 720, 680, 640, 600, 576,
    540, 504, 480, 432, 400, 360, 320, 288, 240, 192, 144,
)

data class ResolutionOption(
    val side: Int,          // short side: this is what is shown as "720p"
    val width: Int,
    val height: Int,
    val original: Boolean,  // true = same resolution as the video
    val approxMb: Double,     // approximate
    val changePct: Int,     // negative = smaller than the original
)

fun bppOf(c: Codec): Double = if (c == Codec.AV1) BPP_AV1 else BPP_X264

fun estimateSizeMb(info: InfoVideo, width: Int, height: Int, codec: Codec, kbpsAudio: Int): Double {
    val fps = if (info.fps > 0) min(info.fps, 60.0) else 30.0
    val bitsVideo = bppOf(codec) * width * height * fps * info.duration
    val bitsAudio = kbpsAudio * 1000.0 * info.duration
    return (bitsVideo + bitsAudio) / 8.0 / 1048576.0
}

private fun even(n: Int) = max(2, n - n % 2)

/** Options for THIS video: "Original" (if it is not above 1080p) and each side of the list that is smaller. */
fun resolutionOptions(info: InfoVideo, codec: Codec, kbpsAudio: Int = AUDIO_OPUS_DEF): List<ResolutionOption> {
    val short = min(info.width, info.height)
    val long = max(info.width, info.height)
    if (short <= 0 || info.duration <= 0.0) return emptyList()
    val vertical = info.height > info.width
    val originalMb = info.sizeBytes / 1048576.0
    val audio = min(kbpsAudio, AUDIO_OPUS_MAX)

    fun option(side: Int, w: Int, h: Int, isOriginal: Boolean): ResolutionOption {
        var estimate = estimateSizeMb(info, w, h, codec, audio)
        // Never more than the original at that same scale: an already compressed video is not inflated.
        if (originalMb > 0) estimate = min(estimate, originalMb * (w.toDouble() * h) / (info.width.toDouble() * info.height))
        val change = if (originalMb > 0) ((estimate / originalMb - 1.0) * 100).roundToInt() else 0
        return ResolutionOption(side, w, h, isOriginal, estimate, change)
    }

    val res = mutableListOf<ResolutionOption>()
    if (short <= 1080) res += option(short, even(info.width), even(info.height), true)
    for (side in OUTPUT_SIDES) {
        if (side >= short) continue
        val other = even((long.toDouble() * side / short).roundToInt())
        res += if (vertical) option(side, side, other, false) else option(side, other, side, false)
    }
    return res
}

/** Option that is selected automatically in the Size list: the highest resolution that weighs less than 10 MB (not counting "original"); if none reaches it, the lightest. */
fun optionByDefault(options: List<ResolutionOption>, limitMb: Double = 10.0): ResolutionOption? =
    options.filter { !it.original && it.approxMb < limitMb }.maxByOrNull { it.side }
        ?: options.filter { !it.original }.minByOrNull { it.approxMb }
        ?: options.firstOrNull()
