package app.compresor.ui

import app.compresor.tx

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.compresor.execution.JobQueue
import app.compresor.execution.hms
import app.compresor.execution.ProcessControl
import app.compresor.execution.JobState
import app.compresor.execution.Ending
import app.compresor.execution.Progress
import app.compresor.execution.Outcome
import app.compresor.engine.VideoSettings
import app.compresor.engine.AudioMode
import app.compresor.engine.Codec
import app.compresor.engine.Plan
import app.compresor.ui.theme.Spacing
import app.compresor.ui.theme.Dimens
import app.compresor.ui.theme.Radius
import app.compresor.ui.theme.AppTheme
import kotlinx.coroutines.delay
import java.util.Locale

private fun mb(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)

private fun planName(p: Plan, av1: Boolean) = when (p) {
    Plan.NORMAL -> tx("Normal")
    Plan.SAFE -> tx("Safe (the encoder had crashed)")
    Plan.MIN -> tx("Low usage (out of memory)")
}

@Composable
fun ProgressScreen(
    video: ChosenVideo, settings: VideoSettings, state: JobState, ctrl: ProcessControl, title: String = tx("Compressing"),
    onBackgroundMode: () -> Unit = {},
) {
    val c = AppTheme.palette
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf(false) }

    // The compression runs in a service: you can leave the app and follow the progress in the notification.
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    // Keep the screen on: only while this screen is in view and the checkbox (also in the gear options) is ticked.
    val viewMode = LocalView.current
    DisposableEffect(Config.screenOnEnabled) { viewMode.keepScreenOn = Config.screenOnEnabled; onDispose { viewMode.keepScreenOn = false } }

    val p = state.progress
    val total = p.passes
    val global = ((p.pass - 1) + p.pct) / total
    val elapsed = state.secondsActive(now)
    val missing = if (global > 0.02f) (elapsed / global * (1 - global)).toLong() else -1L

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().padding(Spacing.e4)) {
        Text(title, color = c.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
        Text(video.name, color = c.textMuted, maxLines = 2)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            VerticalGap(Spacing.e5)
            Text("${(global * 100).toInt()} %", color = c.accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displaySmall)
            VerticalGap(Spacing.e2)
            Box(Modifier.fillMaxWidth().height(10.dp).background(c.surface2, RoundedCornerShape(Radius.pill))) {
                Box(Modifier.fillMaxWidth(global.coerceIn(0f, 1f)).height(10.dp)
                    .background(if (state.paused) c.secondary else c.accent, RoundedCornerShape(Radius.pill)))
            }
            VerticalGap(Spacing.e4)
            if (total > 1) Text(tx("Pass {0} of {1}", p.pass, total), color = c.text, fontWeight = FontWeight.SemiBold)
            Text(tx("Time: {0}", hms(elapsed)) + if (missing >= 0) tx(" · about {0} left", hms(missing)) else "", color = c.text)
            Text(tx("Size so far: {0}", mb(p.bytes)), color = c.data, fontFamily = FontFamily.Monospace)
            if (p.speed > 0) Text(tx("Speed: {0}x", String.format(Locale.getDefault(), "%.2f", p.speed)), color = c.textMuted)
            if (settings.codec != Codec.AV1 && p.speed in 0.001..0.2 && elapsed > 60)
                Text(tx("It is very slow: the system may be throttling the app. In phone Settings > Apps > Compresor > Battery, choose \"Unrestricted\"."), color = c.warning)
            if (p.sizeRetry > 0) Text(tx("Over the requested size: retry {0} of 3 with a lower bitrate.", p.sizeRetry), color = c.warning)
            if (p.plan != Plan.NORMAL) Text(tx("Plan: {0}", planName(p.plan, settings.codec == Codec.AV1)), color = c.warning)
            if (state.paused) Text(tx("Paused"), color = c.secondary, fontWeight = FontWeight.Bold)
            VerticalGap(Spacing.e3)
            Text(tx("With \"Leave in background\" it keeps working and you see the progress in the notification."), color = c.textMuted,
                style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onBackgroundMode,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.e3).height(Dimens.touchMin), shape = RoundedCornerShape(Radius.pill),
            colors = ButtonDefaults.buttonColors(containerColor = c.surface2, contentColor = c.text),
        ) { Text(tx("Leave in background"), fontWeight = FontWeight.SemiBold) }
        Row(Modifier.fillMaxWidth().clickable { Config.updateScreenOnEnabled(!Config.screenOnEnabled) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(Config.screenOnEnabled, null,
                colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
            Text(tx("Keep screen on"), color = c.text, modifier = Modifier.padding(start = Spacing.e2))
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = Spacing.e3), horizontalArrangement = Arrangement.spacedBy(Spacing.e3)) {
            Button(
                onClick = { if (state.paused) JobQueue.resume() else JobQueue.pauseJob() },
                modifier = Modifier.weight(1f).height(Dimens.button), shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = c.secondary, contentColor = c.onSecondary),
            ) { Text(if (state.paused) tx("Resume") else tx("Pause"), fontWeight = FontWeight.Bold) }
            Button(
                onClick = { confirm = true },
                modifier = Modifier.weight(1f).height(Dimens.button), shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onDanger),
            ) { Text(tx("Stop"), fontWeight = FontWeight.Bold) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(tx("Stop the compression?")) },
            text = { Text(tx("What is done so far is deleted. Your original video is not touched.")) },
            confirmButton = { TextButton(onClick = { confirm = false; JobQueue.stop() }) { Text(tx("Stop"), color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tx("Continue"), color = c.accent) } },
        )
    }
}

@Composable
private fun TableRow(tag: String, original: String, new: String, newColor: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.TextUnit) {
    val c = AppTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(tag, color = c.textMuted, fontSize = size, modifier = Modifier.weight(1.1f))
        Text(original, color = c.text, fontFamily = FontFamily.Monospace, fontSize = size, modifier = Modifier.weight(1f))
        Text(new, color = newColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = size, modifier = Modifier.weight(1f))
    }
}

/**
 * Buttons at the end. Confirmation mode: Save, Change settings and, side by side, Discard and Main menu (4).
 * "Save without asking" mode (or if there is nothing to save): only Change settings and Main menu, side by side (2).
 */
@Composable
fun ResultButtons(
    hasUnsavedResult: Boolean, textSave: String,
    onSave: () -> Unit, onChangeSettings: () -> Unit, onDiscard: () -> Unit, onMenu: () -> Unit,
) {
    val c = AppTheme.palette
    val height = if (isTightScreen()) 44.dp else Dimens.button
    val shapeKind = RoundedCornerShape(Radius.pill)
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.e4, vertical = Spacing.e2), verticalArrangement = Arrangement.spacedBy(Spacing.e2)) {
        if (hasUnsavedResult) {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(height), shape = shapeKind,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text(textSave, fontWeight = FontWeight.Bold) }
            Button(onClick = onChangeSettings, modifier = Modifier.fillMaxWidth().height(height), shape = shapeKind,
                colors = ButtonDefaults.buttonColors(containerColor = c.secondary, contentColor = c.onSecondary)) { Text(tx("Change settings"), fontWeight = FontWeight.Bold) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.e2)) {
                Button(onClick = onDiscard, modifier = Modifier.weight(1f).height(height), shape = shapeKind,
                    colors = ButtonDefaults.buttonColors(containerColor = c.surface2, contentColor = c.danger)) { Text(tx("Discard"), fontWeight = FontWeight.SemiBold) }
                Button(onClick = onMenu, modifier = Modifier.weight(1f).height(height), shape = shapeKind,
                    colors = ButtonDefaults.buttonColors(containerColor = c.surface2, contentColor = c.text)) { Text(tx("Main menu"), fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.e2)) {
                Button(onClick = onChangeSettings, modifier = Modifier.weight(1f).height(height), shape = shapeKind,
                    colors = ButtonDefaults.buttonColors(containerColor = c.secondary, contentColor = c.onSecondary)) { Text(tx("Change settings"), fontWeight = FontWeight.Bold, maxLines = 1) }
                Button(onClick = onMenu, modifier = Modifier.weight(1f).height(height), shape = shapeKind,
                    colors = ButtonDefaults.buttonColors(containerColor = c.surface2, contentColor = c.text)) { Text(tx("Main menu"), fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

@Composable
fun ResultScreen(
    video: ChosenVideo, settings: VideoSettings, res: Outcome,
    onSave: () -> Unit, onChangeSettings: () -> Unit, onDiscard: () -> Unit, onMenu: () -> Unit,
    task: app.compresor.execution.Task? = null,
    savedIn: String? = null,
) {
    val compressing = task == null || task is app.compresor.execution.Task.Compress
    val c = AppTheme.palette
    val tight = isTightScreen()
    val size = if (tight) 12.sp else 14.sp
    val auto = Config.skipName
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        if (res.end == Ending.OK) {
            // Everything fits without scrolling: the preview takes the space that is left.
            Column(Modifier.weight(1f).padding(horizontal = Spacing.e4, vertical = Spacing.e2)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(tx("Done"), color = c.success, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    Text(tx("  Took {0}", hms(res.seconds.toLong())), color = c.textMuted, fontSize = size, modifier = Modifier.padding(bottom = 3.dp))
                }
                if (auto) Text(if (savedIn != null) tx("Saved in {0}", savedIn) else tx("Saving…"), color = c.accent, fontSize = size, maxLines = 2)
                VerticalGap(Spacing.e1)
                val uri: Uri? = res.output?.let { Uri.fromFile(it) }
                ViewPreview(uri, Modifier.fillMaxWidth().weight(1f).defaultMinSize(minHeight = 64.dp))
                VerticalGap(Spacing.e2)
                val i = video.info
                val o = res.outputInfo
                val change = if (res.originalSize > 0) ((res.outputSize.toDouble() / res.originalSize - 1) * 100).toInt() else 0
                Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Radius.md))
                    .border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(horizontal = Spacing.e3, vertical = Spacing.e2)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("", Modifier.weight(1.1f))
                        Text(tx("Original"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = size, modifier = Modifier.weight(1f))
                        Text(tx("Compressed"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = size, modifier = Modifier.weight(1f))
                    }
                    TableRow(tx("Size"), mb(res.originalSize), mb(res.outputSize) + " (" + (if (change > 0) "+" else "") + "$change%)",
                        if (change > 0) c.warning else c.success, size)
                    TableRow(tx("Resolution"), "${i.width}x${i.height}", o?.let { "${it.width}x${it.height}" } ?: "?", c.data, size)
                    TableRow(tx("Frames/s"), "${i.fps.toInt()}", o?.let { "${it.fps.toInt()}" } ?: "?", c.data, size)
                    TableRow(tx("Codec"), i.codec.ifBlank { "?" }, o?.codec?.ifBlank { "?" } ?: "?", c.data, size)
                    if (task is app.compresor.execution.Task.Cut) {
                        TableRow(tx("Duration"), hms(i.duration.toLong()), o?.let { hms(it.duration.toLong()) } ?: "?", c.data, size)
                    }
                    if (compressing) TableRow(tx("Audio"), video.tracks.joinToString { it.codec }.ifBlank { tx("no audio") },
                        if (settings.audio == AudioMode.ORIGINAL) "original" else "opus", c.data, size)
                }
                val notices = buildList {
                    if (res.exceedsTarget) add(tx("It is over the requested size ({0} MB) even after 3 retries. Try fewer MB or a lower resolution.", settings.sizeMb) to c.warning)
                    else if (res.sizeRetries > 0) add(tx("It went over the requested size and was redone {0} time(s) with a lower bitrate.", res.sizeRetries) to c.textMuted)
                    if (res.plan != Plan.NORMAL) add(tx("It used the fallback plan: {0}.", planName(res.plan, settings.codec == Codec.AV1)) to c.warning)
                    if (change > 0) add(tx("Heads up: the compressed file is bigger than the original.") to c.warning)
                }
                notices.forEach { (m, col) -> Text(m, color = col, fontSize = size, modifier = Modifier.padding(top = 2.dp)) }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.e4)) {
                if (res.end == Ending.STOPPED) {
                    Text(tx("Stopped"), color = c.warning, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                    Text(tx("Nothing was saved. Your original video is untouched."), color = c.textMuted)
                } else {
                    Text(tx("Could not compress"), color = c.danger, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                    Text(tx("Your original video is untouched. This is what ffmpeg said:"), color = c.textMuted)
                    VerticalGap(Spacing.e2)
                    Text(res.error.ifBlank { tx("(no message)") }, color = c.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ResultButtons(res.end == Ending.OK && !auto, tx("Save to gallery"), onSave, onChangeSettings, onDiscard, onMenu)
    }
}
