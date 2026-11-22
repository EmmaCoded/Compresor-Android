package app.compresor.ui

import app.compresor.tx

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.compresor.ui.theme.Spacing
import app.compresor.ui.theme.Dimens
import app.compresor.ui.theme.Radius
import app.compresor.ui.theme.AppTheme

// Pieces of the components spec. Only colors and sizes from the tokens.

@Composable
fun ViewPreview(uri: Uri?, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color.Black).semantics { contentDescription = tx("Video preview") },
        contentAlignment = Alignment.Center,
    ) {
        if (uri == null) {
            Text(tx("Video preview"), color = Color.White)
        } else {
            var isPlaying by remember { mutableStateOf(false) }
            var viewMode by remember { mutableStateOf<VideoView?>(null) }
            var previewFailed by remember(uri) { mutableStateOf(false) }
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(uri)
                        setOnPreparedListener { it.isLooping = true; seekTo(100) }
                        setOnErrorListener { _, _, _ -> previewFailed = true; true }
                        viewMode = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (previewFailed) {
                Text(
                    tx("This phone cannot show the preview (for example, it cannot play AV1). The file is fine: open it in another player."),
                    color = Color.White, modifier = Modifier.padding(Spacing.e4),
                )
            }
            if (!previewFailed) IconButton(
                onClick = {
                    viewMode?.let { v -> if (v.isPlaying) { v.pause(); isPlaying = false } else { v.start(); isPlaying = true } }
                },
                modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) tx("Pause") else tx("Play"), tint = Color.White,
                )
            }
        }
    }
}

@Composable
fun Heading(text: String) {
    Text(
        text, color = AppTheme.palette.textMuted, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = Spacing.e4, bottom = Spacing.e2),
    )
}

/** Row of an option (resolution): circle, title, detail and approximate size on the right. */
@Composable
fun OptionRow(
    picked: Boolean, title: String, detail: String, right: String, right2: String,
    onPick: () -> Unit,
) {
    val c = AppTheme.palette
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = Dimens.row).clickable(role = Role.RadioButton, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = picked, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.borderInput),
        )
        Column(Modifier.weight(1f).padding(start = Spacing.e2)) {
            Text(title, color = if (picked) c.accent else c.text, fontWeight = FontWeight.SemiBold)
            Text(detail, color = c.textMuted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Spacing.e2)) {
            Text(right, color = c.data, fontWeight = FontWeight.SemiBold)
            Text(right2, color = c.textMuted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}

/** Selector of 2 or 3 options side by side. Pressing and holding an option notifies through `onKeep`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Segmented(
    options: List<String>, selected: Int, onPick: (Int) -> Unit,
    onKeep: ((Int) -> Unit)? = null, enabledList: List<Boolean>? = null,
) {
    val c = AppTheme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.e2)) {
        options.forEachIndexed { i, text ->
            val ok = enabledList?.getOrNull(i) ?: true
            val sel = i == selected
            Box(
                Modifier.weight(1f).defaultMinSize(minHeight = Dimens.input)
                    .background(if (sel) c.accent else c.surface2, RoundedCornerShape(Radius.md))
                    .border(1.dp, if (sel) c.accent else c.borderInput, RoundedCornerShape(Radius.md))
                    .combinedClickable(
                        enabled = ok, role = Role.RadioButton,
                        onClick = { onPick(i) },
                        onLongClick = { onKeep?.invoke(i) },
                    )
                    .padding(horizontal = Spacing.e2, vertical = Spacing.e2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text, color = if (sel) c.onAccent else if (ok) c.text else c.textMuted,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Dropdown list: label on top, current value and arrow. */
@Composable
fun <T> Dropdown(
    tag: String, value: String, options: List<Pair<String, T>>, onPick: (T) -> Unit,
) {
    val c = AppTheme.palette
    var opened by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.e2)) {
        Text(tag, color = c.textMuted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Box {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = Dimens.input)
                    .background(c.surface2, RoundedCornerShape(Radius.sm))
                    .border(1.dp, c.borderInput, RoundedCornerShape(Radius.sm))
                    .clickable(role = Role.DropdownList) { opened = true }
                    .padding(horizontal = Spacing.e3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, color = c.text, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = c.text)
            }
            DropdownMenu(expanded = opened, onDismissRequest = { opened = false }) {
                options.forEach { (text, v) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { opened = false; onPick(v) })
                }
            }
        }
    }
}

@Composable
fun VerticalGap(height: androidx.compose.ui.unit.Dp = Spacing.e4) = Spacer(Modifier.height(height))

/**
 * Notifies when the files on the phone change (one is added or deleted, also from another app) and when returning to the app.
 * Waits 0.6 s to gather several consecutive changes into a single reload.
 */
@Composable
fun MediaRefresh(uri: Uri, onChangeSettings: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val current by androidx.compose.runtime.rememberUpdatedState(onChangeSettings)
    androidx.compose.runtime.DisposableEffect(uri, owner) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val task = Runnable { current() }
        val observer = object : android.database.ContentObserver(h) {
            override fun onChange(selfChange: Boolean) { h.removeCallbacks(task); h.postDelayed(task, 600) }
        }
        ctx.contentResolver.registerContentObserver(uri, true, observer)
        var first = true
        val life = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (first) first = false else current() }
        }
        owner.lifecycle.addObserver(life)
        onDispose { ctx.contentResolver.unregisterContentObserver(observer); owner.lifecycle.removeObserver(life); h.removeCallbacks(task) }
    }
}
