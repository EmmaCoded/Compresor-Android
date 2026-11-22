package app.compresor.engine

import app.compresor.tx

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Crop rectangle in normalized coordinates (0 to 1) of the ALREADY rotated image. Pure, so it can be tested on the PC.

data class RectN(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width get() = r - l
    val height get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
}

val FULL_RECT = RectN(0f, 0f, 1f, 1f)

/** The rectangle follows the content when the image rotates 90 degrees to the right: (x, y) becomes (1 - y, x). */
fun RectN.clockwise() = RectN(1 - b, l, 1 - t, r)

/** Rotation of 90 degrees to the left: (x, y) becomes (y, 1 - x). */
fun RectN.counterclockwise() = RectN(t, 1 - r, b, 1 - l)

/** Moves the rectangle without changing its size and without leaving the image. */
fun RectN.move(dx: Float, dy: Float): RectN {
    val nx = (l + dx).coerceIn(0f, 1f - width)
    val ny = (t + dy).coerceIn(0f, 1f - height)
    return RectN(nx, ny, nx + width, ny + height)
}

/** Corners: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left. The opposite one stays fixed. */
fun RectN.withCorner(corner: Int, x: Float, y: Float, minW: Float, minH: Float): RectN {
    val left = corner == 0 || corner == 3
    val arr = corner == 0 || corner == 1
    val newLeft = if (left) x.coerceIn(0f, r - minW) else l
    val newRight = if (!left) x.coerceIn(l + minW, 1f) else r
    val newTop = if (arr) y.coerceIn(0f, b - minH) else t
    val newBottom = if (!arr) y.coerceIn(t + minH, 1f) else b
    return RectN(newLeft, newTop, newRight, newBottom)
}

/** Edges: 0 left, 1 top, 2 right, 3 bottom. */
fun RectN.withEdge(side: Int, v: Float, minW: Float, minH: Float): RectN = when (side) {
    0 -> copy(l = v.coerceIn(0f, r - minW))
    1 -> copy(t = v.coerceIn(0f, b - minH))
    2 -> copy(r = v.coerceIn(l + minW, 1f))
    else -> copy(b = v.coerceIn(t + minH, 1f))
}

/**
 * Corner dragged with the aspect ratio locked. `aspect` = width/height in PIXELS; `w` and `h` are the sizes of the rotated
 * image. The opposite corner stays fixed; the size comes from the horizontal movement and is limited to what fits.
 */
fun RectN.withCornerLocked(corner: Int, x: Float, y: Float, aspect: Float, w: Int, h: Int, minW: Float): RectN {
    val left = corner == 0 || corner == 3
    val arr = corner == 0 || corner == 1
    val ox = if (left) r else l
    val oy = if (arr) b else t
    val slackX = if (left) ox else 1f - ox
    val slackY = if (arr) oy else 1f - oy
    val k = w / (aspect * h)                       // normalized height = normalized width * k
    var newW = abs(x - ox).coerceAtLeast(minW)
    var newH = newW * k
    if (newH > slackY) { newH = slackY; newW = newH / k }
    if (newW > slackX) { newW = slackX; newH = newW * k }
    if (newW < minW) { newW = minW; newH = newW * k }
    return RectN(
        if (left) ox - newW else ox, if (arr) oy - newH else oy,
        if (left) ox else ox + newW, if (arr) oy else oy + newH,
    )
}

/** The largest rectangle with that aspect ratio (width/height in pixels) that fits in the image, centered as close as possible to (cx, cy). */
fun maxWithAspect(aspect: Float, w: Int, h: Int, cx: Float, cy: Float): RectN {
    val k = w / (aspect * h)
    var newW = 1f
    var newH = newW * k
    if (newH > 1f) { newH = 1f; newW = newH / k }
    val l = (cx - newW / 2).coerceIn(0f, 1f - newW)
    val t = (cy - newH / 2).coerceIn(0f, 1f - newH)
    return RectN(l, t, l + newW, t + newH)
}

/** To pixels of the rotated image; the engine makes it even and inside the image. */
fun RectN.toCrop(w: Int, h: Int, rotation: Rotation, noAudio: Boolean): CropRegion {
    val x = (l * w).roundToInt().coerceIn(0, w)
    val y = (t * h).roundToInt().coerceIn(0, h)
    val rw = (width * w).roundToInt().coerceIn(1, w)
    val rh = (height * h).roundToInt().coerceIn(1, h)
    return CropRegion(x, y, rw, rh, rotation, noAudio)
}

/** List of aspect ratios on offer (null = free). */
enum class Aspect(private val textIn: String, val width: Int, val height: Int) {
    FREE("Free", 0, 0), ORIGINAL("Original", 0, 0),
    SQUARE("1:1", 1, 1), FOUR_THREE("4:3", 4, 3), THREE_FOUR("3:4", 3, 4),
    SIXTEEN_NINE("16:9", 16, 9), NINE_SIXTEEN("9:16", 9, 16);

    val text: String get() = tx(textIn)

    /** width/height in pixels; for ORIGINAL the image one is used; null if free. */
    fun aspect(w: Int, h: Int): Float? = when (this) {
        FREE -> null
        ORIGINAL -> w.toFloat() / h
        else -> width.toFloat() / height
    }
}
