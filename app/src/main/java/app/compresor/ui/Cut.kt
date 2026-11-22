package app.compresor.ui

import app.compresor.tx

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.compresor.engine.CUT_MIN_MS
import app.compresor.engine.CutRange
import app.compresor.engine.MESSAGE_END_BEFORE_START
import app.compresor.engine.MESSAGE_START_AFTER_END
import app.compresor.engine.totalWidth
import app.compresor.engine.pushesLine
import app.compresor.engine.tilesInLevel
import app.compresor.engine.visibleTiles
import app.compresor.engine.factorPinchHorizontal
import app.compresor.engine.clampScroll
import app.compresor.engine.clampZoom
import app.compresor.engine.msFinger
import app.compresor.engine.scrollToShow
import app.compresor.engine.msAX
import app.compresor.engine.thumbnailLevel
import app.compresor.engine.canSetEnd
import app.compresor.engine.canSetStart
import app.compresor.engine.centeredScroll
import app.compresor.engine.scrollAfterDrag
import app.compresor.engine.scrollAfterZoom
import app.compresor.engine.timeText
import app.compresor.engine.edgeSpeed
import app.compresor.engine.xMs
import app.compresor.engine.zoomMax
import app.compresor.ui.theme.Spacing
import app.compresor.ui.theme.Dimens
import app.compresor.ui.theme.Radius
import app.compresor.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** BAR = the white frame line; BACKGROUND = any other place of the strip (dragging scrolls the bar). */
private enum class DragTarget { NONE, BAR, START, END, BACKGROUND }

/** Everything that changes in the cutter: the section, the zoom and the scrolling of the bar. */
private class CutState(val durationMs: Long) {
    var start by mutableLongStateOf(0L)
    var end by mutableLongStateOf(durationMs)
    var zoom by mutableFloatStateOf(1f)
    var scroll by mutableFloatStateOf(0f)
    var viewWidth by mutableFloatStateOf(0f)
    var loop by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var fingerX by mutableFloatStateOf(0f)
    var target by mutableStateOf(DragTarget.NONE)
    var shift by mutableFloatStateOf(0f)         // how far to one side of the point you touched: so the point does not jump to the finger

    fun total() = totalWidth(viewWidth, zoom)
    fun xOf(ms: Long) = msAX(ms, durationMs, total()) - scroll
    fun msOf(x: Float) = xMs(x + scroll, durationMs, total())

    fun pinch(factor: Float, anchor: Float) {
        val new = clampZoom(zoom * factor, durationMs)
        scroll = scrollAfterZoom(scroll, viewWidth, zoom, new, anchor)
        zoom = new
    }

    fun zoomTo(value: Float, centerMs: Long) {
        zoom = clampZoom(value, durationMs)
        scroll = centeredScroll(centerMs, durationMs, viewWidth, zoom)
    }
}

@Composable
fun CutScreen(video: ChosenVideo, onBack: () -> Unit, onSave: (CutRange) -> Unit) {
    val c = AppTheme.palette
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val dur = (video.info.duration * 1000).toLong().coerceAtLeast(1000L)
    val state = remember(video) { CutState(dur) }
    val ctrl = remember(video) { PlayerControl().also { it.limitMs = maxOf(0L, dur - 150L) } }
    val barHeightDp = 64
    val barHeightPx = (barHeightDp * density).toInt()
    val photos = remember(video) { Frames(ctx, video, barHeightPx, dur) }
    DisposableEffect(photos) { onDispose { photos.releaseResources() } }

    var menuX by remember { mutableStateOf<Float?>(null) }       // where the "set point" menu was opened
    var menuZoom by remember { mutableStateOf(false) }
    var dialogSave by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }       // message inside the screen (Toasts are not always seen)
    LaunchedEffect(notice) { if (notice != null) { delay(3500); notice = null } }

    fun applyChange(obj: DragTarget, x: Float) {
        // A dragged point never leaves the visible part: if the finger goes past the edge it stays stuck to the edge.
        val ms = if (obj == DragTarget.START || obj == DragTarget.END)
            msFinger(x, state.shift, state.viewWidth, state.scroll, state.zoom, dur)
        else state.msOf(x - state.shift)
        when (obj) {
            DragTarget.BAR -> ctrl.goTo(ms)
            // Points do NOT move the frame line (they only show their frame while dragged), unless they collide with it and keep going: then they push it.
            DragTarget.START -> {
                val previous = state.start
                val v = ms.coerceIn(0L, max(0L, state.end - CUT_MIN_MS)); state.start = v
                if (pushesLine(previous, v, ctrl.posMs)) ctrl.goTo(v) else ctrl.see(v)
            }
            DragTarget.END -> {
                val previous = state.end
                val v = ms.coerceIn(minOf(dur, state.start + CUT_MIN_MS), dur); state.end = v
                if (pushesLine(previous, v, ctrl.posMs)) ctrl.goTo(v) else ctrl.see(v)
            }
            DragTarget.NONE, DragTarget.BACKGROUND -> {}
        }
    }

    // Playback: the bar follows the video; on reaching the end of the section, it either goes back to the start (loop) or stops.
    LaunchedEffect(ctrl.playing) {
        while (ctrl.playing) {
            val p = ctrl.readPos()
            ctrl.posMs = p
            if (p >= state.end) {
                if (state.loop) ctrl.goTo(state.start) else { ctrl.pauseJob(); ctrl.goTo(state.end) }
            }
            val x = state.xOf(ctrl.posMs)
            if (state.zoom > 1f && (x < 0f || x > state.viewWidth)) state.scroll = centeredScroll(ctrl.posMs, dur, state.viewWidth, state.zoom)
            delay(33)
        }
    }

    // If the position falls outside the view (for example when jumping with << or >>), the bar scrolls to show it.
    LaunchedEffect(ctrl.posMs, state.zoom) {
        val x = state.xOf(ctrl.posMs)
        if (state.zoom > 1f && state.viewWidth > 0f && !state.dragging && state.target == DragTarget.NONE && (x < -1f || x > state.viewWidth + 1f))
            state.scroll = scrollToShow(state.scroll, x, state.viewWidth, state.zoom, 16 * density)
    }

    // Dragging a point near the edge: the bar advances slowly and the preview runs in slow motion.
    LaunchedEffect(state.dragging) {
        var t0 = SystemClock.elapsedRealtime()
        while (isActive && state.dragging) {
            delay(16)
            val t1 = SystemClock.elapsedRealtime(); val dt = (t1 - t0) / 1000f; t0 = t1
            val x = state.fingerX
            val v = edgeSpeed(state.viewWidth - x, 56 * density, 150 * density) - edgeSpeed(x, 56 * density, 150 * density)
            if (v != 0f && state.zoom > 1f) {
                val new = clampScroll(state.scroll + v * dt, state.viewWidth, state.zoom)
                if (new != state.scroll) { state.scroll = new; applyChange(state.target, x) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        // ── top bar: back, title and, at the top right, the loop
        Row(Modifier.fillMaxWidth().height(Dimens.topBar), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back to menu"), tint = c.text)
            }
            Text(tx("Cut video"), color = c.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.loop = !state.loop }, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.Filled.Repeat, contentDescription = if (state.loop) tx("Loop on") else tx("Loop off"),
                    tint = if (state.loop) c.accent else c.textMuted)
            }
        }

        // ── preview
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val videoAspect = (video.info.width.toFloat() / video.info.height).coerceIn(0.3f, 3f)
            Box(Modifier.aspectRatio(videoAspect, matchHeightConstraintsFirst = true)) { ViewVideo(ctrl, video) }
            if (ctrl.error) {
                Text(tx("This phone cannot show this video (for example, it cannot play AV1). You can still cut it, but without a preview."),
                    color = Color.White, modifier = Modifier.padding(Spacing.e4))
            }
        }

        notice?.let { msg ->
            Text(msg, color = c.warning, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(c.surface).padding(horizontal = Spacing.e4, vertical = Spacing.e2))
        }

        // ── times and zoom
        // start, length of the cut and end: their own row so they never split into two lines
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.e4, vertical = Spacing.e1), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timeText(state.start), color = c.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(tx("Length ") + timeText(state.end - state.start), color = c.text, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(timeText(state.end), color = c.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.e4), verticalAlignment = Alignment.CenterVertically) {
            Text(tx("Now: {0}", timeText(ctrl.posMs)), color = c.textMuted, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menuZoom = true }, modifier = Modifier.defaultMinSize(minHeight = Dimens.touchMin)) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = null, tint = c.accent)
                    Text(" " + (if (state.zoom < 10f) "%.1f".format(state.zoom).removeSuffix(".0") else state.zoom.roundToInt().toString()) + "x", color = c.accent)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = tx("Choose zoom"), tint = c.accent)
                }
                DropdownMenu(expanded = menuZoom, onDismissRequest = { menuZoom = false }) {
                    listOf(1f, 2f, 4f, 8f, 16f, 32f, 64f).filter { it <= zoomMax(dur) || it == 1f }.forEach { z ->
                        DropdownMenuItem(text = { Text("${z.roundToInt()}x") }, onClick = { menuZoom = false; state.zoomTo(z, ctrl.posMs) })
                    }
                }
            }
        }

        // ── frame strip (time bar)
        val tileAspect = (video.info.width.toFloat() / video.info.height).coerceIn(0.6f, 1.8f)
        val width = state.viewWidth
        val base = if (width > 0) max(3, (width / (barHeightPx * tileAspect)).roundToInt()) else 5
        val level = thumbnailLevel(state.zoom)
        val n = tilesInLevel(base, level)
        val visible = visibleTiles(state.scroll, width, state.total(), n)
        LaunchedEffect(level, visible.first, visible.last, n) {
            if (!visible.isEmpty()) { delay(60); photos.load(level, visible, n) }
        }
        val vc = LocalViewConfiguration.current

        // The canvas takes the whole width and the bar keeps a margin on each side: this way the outer half of the points also receives the touch.
        val marginPx = 24 * density
        Box(Modifier.fillMaxWidth().height(barHeightDp.dp)) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { state.viewWidth = it.width - 2 * marginPx; state.scroll = clampScroll(state.scroll, state.viewWidth, state.zoom) }
                    .pointerInput(state) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val hw = 28 * density
                            val xI = state.xOf(state.start)
                            val xF = state.xOf(state.end)
                            val xP = state.xOf(ctrl.posMs)
                            val x0 = down.position.x - marginPx
                            val atStart = x0 in (xI - hw * 1.2f)..(xI + hw * 0.3f)
                            val atEnd = x0 in (xF - hw * 0.3f)..(xF + hw * 1.2f)
                            val onLine = abs(x0 - xP) < 24 * density
                            // Points have priority; then the white line; any other place is the background (scroll the bar).
                            val obj = when {
                                atStart && atEnd -> if (abs(x0 - xI) <= abs(x0 - xF)) DragTarget.START else DragTarget.END
                                atStart -> DragTarget.START
                                atEnd -> DragTarget.END
                                onLine -> DragTarget.BAR
                                else -> DragTarget.BACKGROUND
                            }
                            state.target = obj
                            state.shift = when (obj) { DragTarget.START -> x0 - xI; DragTarget.END -> x0 - xF; DragTarget.BAR -> x0 - xP; else -> 0f }
                            if (obj != DragTarget.BACKGROUND) { ctrl.pauseJob(); applyChange(obj, x0) }
                            down.consume()

                            // is it held without moving?
                            val response: String? = withTimeoutOrNull(vc.longPressTimeoutMillis) {
                                var r: String? = null
                                while (r == null) {
                                    val e = awaitPointerEvent()
                                    r = when {
                                        e.changes.count { it.pressed } >= 2 -> "dos"
                                        e.changes.firstOrNull()?.pressed != true -> "arriba"
                                        abs(e.changes[0].position.x - marginPx - x0) > vc.touchSlop -> "mueve"
                                        else -> null
                                    }
                                }
                                r
                            }
                            // press and hold (on the background or on the line): the line goes there and "Set start/end point" opens
                            if (response == null && (obj == DragTarget.BACKGROUND || obj == DragTarget.BAR)) {
                                if (obj == DragTarget.BACKGROUND) { ctrl.pauseJob(); state.shift = 0f; applyChange(DragTarget.BAR, x0) }
                                menuX = state.xOf(ctrl.posMs)
                                do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                                state.target = DragTarget.NONE; state.shift = 0f
                                return@awaitEachGesture
                            }
                            // a single tap on the background puts the frame line there
                            if (response == "arriba") {
                                if (obj == DragTarget.BACKGROUND) { ctrl.pauseJob(); state.shift = 0f; applyChange(DragTarget.BAR, x0) }
                                state.target = DragTarget.NONE; state.shift = 0f
                                return@awaitEachGesture
                            }

                            // drag (one finger) or pinch (two fingers)
                            var prev: Pair<Float, Float>? = null
                            var lastX = x0
                            while (true) {
                                val e = awaitPointerEvent()
                                val fingers = e.changes.filter { it.pressed }
                                if (fingers.isEmpty()) break
                                if (fingers.size >= 2) {
                                    state.dragging = false
                                    val xs = (fingers[0].position.x - marginPx) to (fingers[1].position.x - marginPx)
                                    prev?.let { p -> state.pinch(factorPinchHorizontal(p, xs), (xs.first + xs.second) / 2f) }
                                    prev = xs
                                } else {
                                    prev = null
                                    val x = fingers[0].position.x - marginPx
                                    state.fingerX = x
                                    // only the start and end points take the bar to the edge; the background scrolls it and the line only moves
                                    state.dragging = obj == DragTarget.START || obj == DragTarget.END
                                    if (obj == DragTarget.BACKGROUND) state.scroll = scrollAfterDrag(state.scroll, x - lastX, state.viewWidth, state.zoom)
                                    else applyChange(obj, x)
                                    lastX = x
                                }
                                e.changes.forEach { it.consume() }
                            }
                            state.dragging = false
                            state.target = DragTarget.NONE
                            state.shift = 0f
                            // when releasing a point, the preview goes back to the frame of the white line
                            if (obj == DragTarget.START || obj == DragTarget.END) ctrl.goTo(ctrl.posMs)
                        }
                    }
            ) {
                @Suppress("UNUSED_VARIABLE") val arrivedFrames = photos.version      // redraws when a new frame arrives
                val barWidth = state.viewWidth
                val total = state.total()
                // the margin where the points go is also dark (otherwise a light stripe is left next to the point)
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(0f, 0f), Size(marginPx, size.height))
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(marginPx + barWidth, 0f), Size(size.width - marginPx - barWidth, size.height))
                translate(left = marginPx) {
                val tileW = total / n
                for (i in visible) {
                    val x0 = i * tileW - state.scroll
                    val img = photos.fetch(level, i)
                    if (img != null) {
                        // centered crop so the frame fills the tile without stretching
                        val relTile = tileW / size.height
                        val relImg = img.width.toFloat() / img.height
                        var sw = img.width; var sh = img.height; var sx = 0; var sy = 0
                        if (relImg > relTile) { sw = (img.height * relTile).toInt().coerceAtLeast(1); sx = (img.width - sw) / 2 }
                        else { sh = (img.width / relTile).toInt().coerceAtLeast(1); sy = (img.height - sh) / 2 }
                        drawImage(img, IntOffset(sx, sy), IntSize(sw, sh), IntOffset(x0.roundToInt(), 0), IntSize(tileW.roundToInt() + 1, size.height.roundToInt()))
                    } else {
                        drawRect(c.surface2, Offset(x0, 0f), Size(tileW + 1f, size.height))
                    }
                }
                val xI = state.xOf(state.start)
                val xF = state.xOf(state.end)
                // what is left outside the section is darkened
                if (xI > 0f) drawRect(Color.Black.copy(alpha = 0.6f), Offset(0f, 0f), Size(xI.coerceAtMost(barWidth), size.height))
                if (xF < barWidth) drawRect(Color.Black.copy(alpha = 0.6f), Offset(xF.coerceAtLeast(0f), 0f), Size(barWidth - xF.coerceAtLeast(0f), size.height))
                // edges of the section
                val thickness = 3 * density
                drawRect(c.accent, Offset(xI, 0f), Size(xF - xI, thickness))
                drawRect(c.accent, Offset(xI, size.height - thickness), Size(xF - xI, thickness))
                // start and end points: half a rounded square, flattened, with a black dot in the center
                val halfWidth = 16 * density
                val cornerRadius = 8 * density
                clipRect(xI - halfWidth, 0f, xI, size.height) { drawRoundRect(Color.White, Offset(xI - halfWidth, 0f), Size(halfWidth * 2, size.height), CornerRadius(cornerRadius, cornerRadius)) }
                drawCircle(Color.Black, 3 * density, Offset(xI - halfWidth / 2, size.height / 2))
                clipRect(xF, 0f, xF + halfWidth, size.height) { drawRoundRect(Color.White, Offset(xF - halfWidth, 0f), Size(halfWidth * 2, size.height), CornerRadius(cornerRadius, cornerRadius)) }
                drawCircle(Color.Black, 3 * density, Offset(xF + halfWidth / 2, size.height / 2))
                // the white bar: says which frame is seen above
                val xP = state.xOf(ctrl.posMs)
                if (xP in -2f..(barWidth + 2f)) {
                    drawRect(Color.White, Offset(xP - 1.5f * density, -2f), Size(3 * density, size.height + 4f))
                    drawCircle(Color.White, 5 * density, Offset(xP, 0f))
                }
                }
            }

            // menu when holding the bar
            menuX?.let { mx ->
                DropdownMenu(expanded = true, onDismissRequest = { menuX = null }, offset = DpOffset(((mx + marginPx) / density).dp, 0.dp)) {
                    val pos = ctrl.posMs
                    val okStart = canSetStart(pos, state.end)
                    val okEnd = canSetEnd(pos, state.start)
                    // Both can be tapped: the one that is not valid appears dimmed and, if you tap it, explains why.
                    DropdownMenuItem(
                        text = { Text(tx("Set start point"), color = if (okStart) c.text else c.textMuted) },
                        onClick = {
                            menuX = null
                            if (okStart) state.start = pos else notice = MESSAGE_START_AFTER_END
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(tx("Set end point"), color = if (okEnd) c.text else c.textMuted) },
                        onClick = {
                            menuX = null
                            if (okEnd) state.end = pos else notice = MESSAGE_END_BEFORE_START
                        },
                    )
                }
            }
        }

        // ── controls: << (start of the cut), > (play or pause), >> (end of the cut)
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.e2), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ctrl.goTo(state.start) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.FastRewind, contentDescription = tx("Go to start of the cut"), tint = c.text, modifier = Modifier.size(32.dp))
            }
            IconButton(
                onClick = {
                    if (!ctrl.playing && (ctrl.posMs < state.start || ctrl.posMs >= state.end)) ctrl.goTo(state.start)
                    ctrl.toggle()
                },
                modifier = Modifier.size(64.dp).background(c.accent, RoundedCornerShape(Radius.pill)),
            ) {
                Icon(if (ctrl.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (ctrl.playing) tx("Pause") else tx("Play"), tint = c.onAccent, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { ctrl.goTo(state.end) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.FastForward, contentDescription = tx("Go to end of the cut"), tint = c.text, modifier = Modifier.size(32.dp))
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.e4, vertical = Spacing.e2).navigationBarsPadding()) {
            Button(
                onClick = { ctrl.pauseJob(); dialogSave = true },
                modifier = Modifier.fillMaxWidth().height(Dimens.button), shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text(tx("Cut"), fontWeight = FontWeight.Bold) }
        }
    }

    if (dialogSave) {
        var precise by remember { mutableStateOf(false) }
        var noAudio by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { dialogSave = false },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(tx("Cut {0}", timeText(state.end - state.start))) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { precise = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!precise, null, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.borderInput))
                        Column(Modifier.padding(start = Spacing.e2)) {
                            Text(tx("Fast"), fontWeight = FontWeight.SemiBold)
                            Text(tx("No re-encoding: almost instant and no quality loss. It starts at the previous keyframe, so it may include a little extra at the start."),
                                color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.e2).clickable { precise = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(precise, null, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.borderInput))
                        Column(Modifier.padding(start = Spacing.e2)) {
                            Text(tx("Precise"), fontWeight = FontWeight.SemiBold)
                            Text(tx("Re-encodes: cuts exactly where you put the points, but takes longer."), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.e3).clickable { noAudio = !noAudio }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(noAudio, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
                        Text(tx("Remove the audio"), modifier = Modifier.padding(start = Spacing.e2))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialogSave = false
                    onSave(CutRange(state.start, state.end, precise, noAudio))
                }) { Icon(Icons.Filled.Check, contentDescription = null, tint = c.accent); Text(tx(" Cut"), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { dialogSave = false }) { Text(tx("Cancel"), color = c.textMuted) } },
        )
    }
}
