package app.compresor.engine

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Math of the cutter's time bar (zoom, scrolling, thumbnails). Pure, so it can be tested on the PC.

/** Maximum zoom: at least ~2 seconds must fit on screen (and never more than 64x). */
fun zoomMax(durationMs: Long): Float = min(64f, max(2f, durationMs / 2000f))

fun clampZoom(z: Float, durationMs: Long): Float = z.coerceIn(1f, zoomMax(durationMs))

fun totalWidth(viewWidth: Float, zoom: Float): Float = viewWidth * zoom

fun clampScroll(scroll: Float, viewWidth: Float, zoom: Float): Float =
    scroll.coerceIn(0f, max(0f, totalWidth(viewWidth, zoom) - viewWidth))

fun msAX(ms: Long, durationMs: Long, total: Float): Float = if (durationMs <= 0) 0f else ms.toFloat() / durationMs * total

fun xMs(x: Float, durationMs: Long, total: Float): Long =
    if (total <= 0f) 0L else (x / total * durationMs).toLong().coerceIn(0L, durationMs)

/** New scroll when the zoom changes, so that what was under `anchor` (px of the view) stays there. */
fun scrollAfterZoom(scroll: Float, viewWidth: Float, zoomOld: Float, zoomNew: Float, anchor: Float): Float {
    val relative = (scroll + anchor) / totalWidth(viewWidth, zoomOld)       // 0..1 over the video
    return clampScroll(relative * totalWidth(viewWidth, zoomNew) - anchor, viewWidth, zoomNew)
}

/** Scroll that leaves `ms` in the center of the view. */
fun centeredScroll(ms: Long, durationMs: Long, viewWidth: Float, zoom: Float): Float =
    clampScroll(msAX(ms, durationMs, totalWidth(viewWidth, zoom)) - viewWidth / 2, viewWidth, zoom)

/** Thumbnail level: 0 = 1 per base tile, 1 = double, etc. It changes in steps so it does not reload on every pinch. */
fun thumbnailLevel(zoom: Float): Int = if (zoom <= 1f) 0 else ceil(ln(zoom.toDouble()) / ln(2.0)).toInt()

fun tilesInLevel(base: Int, level: Int): Int = base shl level

/** Range of tiles (inclusive) that touch the visible part. */
fun visibleTiles(scroll: Float, viewWidth: Float, total: Float, n: Int): IntRange {
    if (n <= 0 || total <= 0f) return IntRange.EMPTY
    val a = (scroll / total * n).toInt().coerceIn(0, n - 1)
    val b = (ceil((scroll + viewWidth) / total * n).toInt() - 1).coerceIn(a, n - 1)   // the exact edge does not count the next tile
    return a..b
}

/** Millisecond of the center of each tile (where its frame is taken from). */
fun tileCenterMs(i: Int, n: Int, durationMs: Long): Long = ((i + 0.5) / n * durationMs).toLong().coerceIn(0L, durationMs)

/**
 * Speed (px per second) of the automatic scrolling when dragging a point near the edge.
 * 0 outside the zone; increases smoothly (quadratic) up to `max` right at the edge: slow, to watch the video in slow motion.
 */
fun edgeSpeed(distanceOnEdge: Float, zone: Float, maxValue: Float): Float {
    if (zone <= 0f || distanceOnEdge >= zone) return 0f
    val f = ((zone - max(0f, distanceOnEdge)) / zone).coerceIn(0f, 1f)
    return f * f * maxValue
}

/** Pinch ratio: only the HORIZONTAL separation of the fingers counts (vertical does not zoom). */
fun factorPinchHorizontal(xBefore: Pair<Float, Float>, xNow: Pair<Float, Float>): Float {
    val before = kotlin.math.abs(xBefore.first - xBefore.second)
    val now = kotlin.math.abs(xNow.first - xNow.second)
    return if (before < 8f) 1f else now / before
}

/** Scroll the bar with the finger: the content follows the finger (finger to the right = you see what came before). */
fun scrollAfterDrag(scroll: Float, dx: Float, viewWidth: Float, zoom: Float): Float =
    clampScroll(scroll - dx, viewWidth, zoom)

/**
 * Instant that a point dragged with the finger at `x` (px of the bar) points to. The point NEVER leaves the visible
 * part: if the finger goes past the edge, it stays stuck to the edge (if it were behind the screen it could not be grabbed again).
 */
fun msFinger(x: Float, shift: Float, viewWidth: Float, scroll: Float, zoom: Float, durationMs: Long): Long =
    xMs((x - shift).coerceIn(0f, viewWidth) + scroll, durationMs, totalWidth(viewWidth, zoom))

/**
 * Minimum scroll so that `x` (px of the view) ends up inside [margin, viewWidth - margin]. If it is already visible, nothing changes.
 * Unlike centering, it does not move what is already in view (a point stuck to the edge stays stuck to the edge).
 */
fun scrollToShow(scroll: Float, x: Float, viewWidth: Float, zoom: Float, margin: Float): Float {
    val new = when {
        x < 0f -> scroll + x - margin
        x > viewWidth -> scroll + x - (viewWidth - margin)
        else -> scroll
    }
    return clampScroll(new, viewWidth, zoom)
}
