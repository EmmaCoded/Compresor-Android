package app.compresor.ui

import app.compresor.tx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.compresor.engine.Rotation
import app.compresor.engine.Aspect
import app.compresor.engine.FULL_RECT
import app.compresor.engine.RectN
import app.compresor.engine.CropRegion
import app.compresor.engine.toCrop
import app.compresor.engine.adjustCrop
import app.compresor.engine.counterclockwise
import app.compresor.engine.withEdge
import app.compresor.engine.withCorner
import app.compresor.engine.withCornerLocked
import app.compresor.engine.rotatedDimensions
import app.compresor.engine.clockwise
import app.compresor.engine.maxWithAspect
import app.compresor.engine.move
import app.compresor.ui.theme.Spacing
import app.compresor.ui.theme.Dimens
import app.compresor.ui.theme.Radius
import app.compresor.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** A frame of the video to draw the crop on top of. Android first; if it cannot (e.g. AV1), ffmpeg. */
private object PhotoPreview {
    private const val SIDE_MAX = 1280

    fun load(ctx: Context, video: ChosenVideo): Bitmap? = withAndroid(ctx, video) ?: withFfmpeg(ctx, video)

    private fun withAndroid(ctx: Context, video: ChosenVideo): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            val f = video.path?.takeIf { it.canRead() }
            if (f != null) r.setDataSource(f.path) else r.setDataSource(ctx, video.uri ?: return null)
            val t = minOf(1_000_000L, (video.info.duration * 1000 / 2).toLong() * 1000)
            val e = minOf(1.0, SIDE_MAX.toDouble() / maxOf(video.info.width, video.info.height))
            val w = maxOf(2, (video.info.width * e).toInt()); val h = maxOf(2, (video.info.height * e).toInt())
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
            else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun withFfmpeg(ctx: Context, video: ChosenVideo): Bitmap? {
        val inputFile = video.path?.takeIf { it.canRead() } ?: return null
        val ffmpeg = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpeg.exists()) return null
        val output = File(ctx.cacheDir, "recorte_cuadro.jpg")
        return try {
            val p = ProcessBuilder(
                ffmpeg.path, "-v", "error", "-nostdin", "-y", "-i", inputFile.path, "-frames:v", "1",
                "-vf", "scale=$SIDE_MAX:$SIDE_MAX:force_original_aspect_ratio=decrease", "-q:v", "3", output.path,
            ).redirectErrorStream(true).start()
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); null }
            else if (output.exists()) BitmapFactory.decodeFile(output.path) else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { output.delete() }
        }
    }
}

private fun rotateBitmap(b: Bitmap, g: Rotation): Bitmap =
    if (g == Rotation.NONE) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(g.degrees.toFloat()) }, true)

private enum class Grab { NOTHING, MOVE, CORNER_0, CORNER_1, CORNER_2, CORNER_3, EDGE_0, EDGE_1, EDGE_2, EDGE_3 }

@Composable
fun CropScreen(video: ChosenVideo, onBack: () -> Unit, onSave: (CropRegion) -> Unit) {
    val c = AppTheme.palette
    val ctx = LocalContext.current
    val density = LocalDensity.current.density

    var base by remember(video) { mutableStateOf<Bitmap?>(null) }          // unrotated frame
    var loading by remember(video) { mutableStateOf(true) }
    LaunchedEffect(video) {
        base = withContext(Dispatchers.IO) { PhotoPreview.load(ctx, video) }
        loading = false
    }
    var rotation by remember { mutableStateOf(Rotation.NONE) }
    var rect by remember { mutableStateOf(FULL_RECT) }
    var prop by remember { mutableStateOf(Aspect.FREE) }
    var dialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val (widthG, heightG) = rotatedDimensions(video.info.width, video.info.height, rotation)   // sizes in pixels after the rotation
    val image: ImageBitmap? = remember(base, rotation) { base?.let { rotateBitmap(it, rotation).asImageBitmap() } }
    var box by remember { mutableStateOf(Size.Zero) }
    val imageAspect = widthG.toFloat() / heightG
    val imgW = min(box.width, box.height * imageAspect)
    val imgH = if (imageAspect > 0) imgW / imageAspect else 0f
    val imgL = (box.width - imgW) / 2
    val imgT = (box.height - imgH) / 2

    fun rotate(clockwise: Boolean) {
        rotation = if (clockwise) rotation.clockwise() else rotation.counterclockwise()
        rect = if (clockwise) rect.clockwise() else rect.counterclockwise()
        if (prop != Aspect.FREE && prop != Aspect.ORIGINAL) prop = Aspect.FREE    // the chosen aspect ratio no longer matches
    }

    fun pickAspect(p: Aspect) {
        prop = p
        p.aspect(widthG, heightG)?.let { rect = maxWithAspect(it, widthG, heightG, rect.cx, rect.cy) }
    }

    val cropCurrent = rect.toCrop(widthG, heightG, rotation, false).let { adjustCrop(it, video.info.width, video.info.height) }
    val hasChange = rotation != Rotation.NONE || rect != FULL_RECT

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(Dimens.topBar), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back to menu"), tint = c.text)
            }
            Text(tx("Crop video"), color = c.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { rotation = Rotation.NONE; rect = FULL_RECT; prop = Aspect.FREE }, enabled = hasChange) {
                Text(tx("Reset"), color = if (hasChange) c.accent else c.textMuted)
            }
        }

        // ── the frame with the rectangle
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }) {
            if (image == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (loading) tx("Loading the frame…") else tx("Could not show this video, so the crop cannot be drawn."),
                        color = Color.White, modifier = Modifier.padding(Spacing.e4))
                }
            } else {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(rotation, prop, box) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (imgW <= 0f || imgH <= 0f) return@awaitEachGesture
                            val hit = 32 * density
                            val px = { n: Float -> imgL + n * imgW }
                            val py = { n: Float -> imgT + n * imgH }
                            val x0 = down.position.x; val y0 = down.position.y
                            val corners = listOf(rect.l to rect.t, rect.r to rect.t, rect.r to rect.b, rect.l to rect.b)
                            val locked = prop.aspect(widthG, heightG) != null
                            var grab = Grab.NOTHING
                            var best = hit
                            corners.forEachIndexed { i, (nx, ny) ->
                                val d = hypot(x0 - px(nx), y0 - py(ny))
                                if (d < best) { best = d; grab = Grab.entries[Grab.CORNER_0.ordinal + i] }
                            }
                            if (grab == Grab.NOTHING && !locked) {
                                val insideX = x0 in px(rect.l)..px(rect.r); val insideY = y0 in py(rect.t)..py(rect.b)
                                val touchTolerance = 22 * density
                                when {
                                    insideY && abs(x0 - px(rect.l)) < touchTolerance -> grab = Grab.EDGE_0
                                    insideX && abs(y0 - py(rect.t)) < touchTolerance -> grab = Grab.EDGE_1
                                    insideY && abs(x0 - px(rect.r)) < touchTolerance -> grab = Grab.EDGE_2
                                    insideX && abs(y0 - py(rect.b)) < touchTolerance -> grab = Grab.EDGE_3
                                }
                            }
                            if (grab == Grab.NOTHING && x0 in px(rect.l)..px(rect.r) && y0 in py(rect.t)..py(rect.b)) grab = Grab.MOVE
                            if (grab == Grab.NOTHING) return@awaitEachGesture
                            down.consume()
                            var last = down.position
                            val minW = 40 * density / imgW; val minH = 40 * density / imgH
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.pressed } ?: break
                                val nx = ((ch.position.x - imgL) / imgW).coerceIn(0f, 1f)
                                val ny = ((ch.position.y - imgT) / imgH).coerceIn(0f, 1f)
                                val lockedAspect = prop.aspect(widthG, heightG)
                                rect = when (grab) {
                                    Grab.MOVE -> rect.move((ch.position.x - last.x) / imgW, (ch.position.y - last.y) / imgH)
                                    Grab.CORNER_0, Grab.CORNER_1, Grab.CORNER_2, Grab.CORNER_3 -> {
                                        val i = grab.ordinal - Grab.CORNER_0.ordinal
                                        if (lockedAspect != null) rect.withCornerLocked(i, nx, ny, lockedAspect, widthG, heightG, minW)
                                        else rect.withCorner(i, nx, ny, minW, minH)
                                    }
                                    Grab.EDGE_0 -> rect.withEdge(0, nx, minW, minH)
                                    Grab.EDGE_1 -> rect.withEdge(1, ny, minW, minH)
                                    Grab.EDGE_2 -> rect.withEdge(2, nx, minW, minH)
                                    Grab.EDGE_3 -> rect.withEdge(3, ny, minW, minH)
                                    Grab.NOTHING -> rect
                                }
                                last = ch.position
                                e.changes.forEach { it.consume() }
                            }
                        }
                    }
                ) {
                    drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset(imgL.roundToInt(), imgT.roundToInt()), IntSize(imgW.roundToInt(), imgH.roundToInt()))
                    val l = imgL + rect.l * imgW; val t = imgT + rect.t * imgH
                    val r = imgL + rect.r * imgW; val b = imgT + rect.b * imgH
                    val velocity = Color.Black.copy(alpha = 0.55f)
                    drawRect(velocity, Offset(imgL, imgT), Size(imgW, t - imgT))
                    drawRect(velocity, Offset(imgL, b), Size(imgW, imgT + imgH - b))
                    drawRect(velocity, Offset(imgL, t), Size(l - imgL, b - t))
                    drawRect(velocity, Offset(r, t), Size(imgL + imgW - r, b - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2 * density))
                    // rule of thirds
                    val fine = Color.White.copy(alpha = 0.35f)
                    for (k in 1..2) {
                        drawLine(fine, Offset(l + (r - l) * k / 3, t), Offset(l + (r - l) * k / 3, b), 1 * density)
                        drawLine(fine, Offset(l, t + (b - t) * k / 3), Offset(r, t + (b - t) * k / 3), 1 * density)
                    }
                    // handles on the 4 corners
                    val rad = 7 * density
                    for ((ex, ey) in listOf(l to t, r to t, r to b, l to b)) {
                        drawCircle(Color.White, rad, Offset(ex, ey))
                        drawCircle(Color.Black, rad * 0.35f, Offset(ex, ey))
                    }
                }
            }
        }

        // ── crop sizes
        Text(
            tx("Crop: {0} × {1} px  (original {2} × {3})", cropCurrent.width, cropCurrent.height, widthG, heightG),
            color = c.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = Spacing.e4, vertical = Spacing.e1),
        )
        notice?.let { Text(it, color = c.warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.e4)) }

        // ── aspect ratios
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.e4, vertical = Spacing.e1),
            horizontalArrangement = Arrangement.spacedBy(Spacing.e2)) {
            Aspect.entries.forEach { p ->
                val sel = p == prop
                Box(
                    Modifier.defaultMinSize(minHeight = Dimens.touchMin)
                        .background(if (sel) c.accent else c.surface2, RoundedCornerShape(Radius.pill))
                        .border(1.dp, if (sel) c.accent else c.borderInput, RoundedCornerShape(Radius.pill))
                        .clickable(enabled = image != null) { pickAspect(p) }
                        .padding(horizontal = Spacing.e4, vertical = Spacing.e2),
                    contentAlignment = Alignment.Center,
                ) { Text(p.text, color = if (sel) c.onAccent else c.text, fontWeight = FontWeight.SemiBold) }
            }
        }

        // ── rotate
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.e1), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { rotate(false) }, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.Filled.RotateLeft, contentDescription = tx("Rotate left"), tint = c.text)
            }
            Text(
                tx("Rotation: ") + when (rotation) { Rotation.NONE -> tx("none"); Rotation.RIGHT -> tx("90° right"); Rotation.DOWN -> "180°"; Rotation.LEFT -> tx("90° left") },
                color = c.textMuted, modifier = Modifier.padding(horizontal = Spacing.e4),
            )
            IconButton(onClick = { rotate(true) }, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.Filled.RotateRight, contentDescription = tx("Rotate right"), tint = c.text)
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.e4, vertical = Spacing.e2).navigationBarsPadding()) {
            Button(
                onClick = { if (hasChange) dialog = true else notice = tx("There is nothing to crop or rotate yet.") },
                modifier = Modifier.fillMaxWidth().height(Dimens.button), shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text(tx("Crop"), fontWeight = FontWeight.Bold) }
        }
    }

    if (dialog) {
        var noAudio by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { dialog = false },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(tx("Crop and rotate")) },
            text = {
                Column {
                    Text(tx("It is re-encoded to MP4 with good quality, so it takes longer than cutting a section."), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.e3).clickable { noAudio = !noAudio }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(noAudio, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
                        Text(tx("Remove the audio"), modifier = Modifier.padding(start = Spacing.e2))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = false
                    onSave(rect.toCrop(widthG, heightG, rotation, noAudio))
                }) { Text(tx("Crop"), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text(tx("Cancel"), color = c.textMuted) } },
        )
    }
}
