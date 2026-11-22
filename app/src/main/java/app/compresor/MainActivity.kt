package app.compresor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.compresor.execution.JobQueue
import app.compresor.execution.Compression
import app.compresor.execution.Outcome
import app.compresor.execution.Probe
import app.compresor.execution.Task
import app.compresor.execution.RunningJob
import app.compresor.execution.mark
import app.compresor.engine.sanitizeName
import app.compresor.engine.availableName
import app.compresor.ui.Config
import app.compresor.ui.OptionsScreen
import app.compresor.execution.verb
import app.compresor.ui.CutScreen
import app.compresor.ui.CropScreen
import app.compresor.engine.VideoSettings
import app.compresor.engine.Codec
import app.compresor.engine.InfoAudio
import app.compresor.engine.InfoVideo
import app.compresor.engine.Track
import app.compresor.ui.AudioItem
import app.compresor.ui.ConvertAudioScreen
import app.compresor.ui.AudioListScreen
import app.compresor.ui.AudioResultScreen
import app.compresor.ui.GalleryRepo
import app.compresor.ui.SettingsScreen
import app.compresor.ui.GalleryScreen
import app.compresor.ui.VideoItem
import app.compresor.ui.deleteCopy
import app.compresor.ui.HomeScreen
import app.compresor.ui.ProgressScreen
import app.compresor.ui.ResultScreen
import app.compresor.ui.ChosenVideo
import app.compresor.ui.readVideo
import app.compresor.ui.theme.AppTheme
import app.compresor.ui.theme.CompressorTheme
import app.compresor.ui.videoDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_LOW) app.compresor.ui.ThumbnailLoader.emptyMemory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.compresor.ui.Config.begin(this)
        // Leftovers from interrupted runs (half-written outputs and stats files): deleted when no job is running.
        if (JobQueue.current == null) cacheDir.listFiles { f -> f.name.startsWith("output_") || f.name.startsWith("pl_") }?.forEach { it.delete() }
        val demo = intent.getBooleanExtra("demo", false)
        val path = intent.getStringExtra("path")      // tests only: opens a video from the cache without going through the picker
        val tool = intent.getStringExtra("tool")   // tests only: compress | cut | crop
        setContent {
            // The app ignores the system font size (everything adjusts itself to fit on screen).
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1f)) {
                CompressorTheme(Config.theme) { Root(demo, path, tool) }
            }
        }
    }
}

/** Screen phases before compressing. During and after compressing, `JobQueue` (which lives in the service) is in charge. */
private enum class Tool { COMPRESS, CROP, CUT }

private sealed interface Phase {
    data object Home : Phase
    data object Preparing : Phase
    data object Gallery : Phase
    data object Options : Phase
    data object AudioList : Phase
    data class AudioAdjust(val video: ChosenVideo, val info: InfoAudio) : Phase
    data class Adjust(val video: ChosenVideo) : Phase
    data class Cut(val video: ChosenVideo) : Phase
    data class Crop(val video: ChosenVideo) : Phase
}

/** Copies the video to the cache and reads it with ffprobe (MediaMetadataRetriever as a fallback). */
private fun prepareVideo(ctx: Context, uri: android.net.Uri): ChosenVideo? {
    val name = Probe.nameOf(ctx, uri)
    val cachedFile = runCatching { Probe.copyToCache(ctx, uri, name) }.getOrNull() ?: return null
    Probe.probe(ctx, cachedFile)?.let { (info, tracks) -> return ChosenVideo(uri, name, info, cachedFile, tracks, isCopy = true) }
    return readVideo(ctx, uri)?.copy(path = cachedFile, isCopy = true)
}

/**
 * Video from the gallery: read directly from its folder (no copy) if the path can be opened.
 * Otherwise it is copied to the cache as before. In direct mode the app NEVER deletes the file.
 */
private fun prepareDirect(ctx: Context, item: VideoItem): ChosenVideo? {
    val f = item.path?.let { java.io.File(it) }
    if (f != null && f.canRead()) {
        Probe.probe(ctx, f)?.let { (info, tracks) -> return ChosenVideo(GalleryRepo.uriOf(item.id), item.name, info, f, tracks, isCopy = false) }
    }
    return prepareVideo(ctx, GalleryRepo.uriOf(item.id))
}

/** An audio file: read directly from its folder when possible (never deleted), otherwise copied to the cache. Returns the "video" (only to carry name, path and size) and what ffprobe says. */
private fun prepareAudio(ctx: Context, uri: android.net.Uri, name: String, path: String?): Pair<ChosenVideo, InfoAudio>? {
    val direct = path?.let { java.io.File(it) }?.takeIf { it.canRead() }
    val f = direct ?: runCatching { Probe.copyToCache(ctx, uri, name) }.getOrNull() ?: return null
    val info = Probe.probeAudio(ctx, f) ?: run { if (direct == null) f.delete(); return null }
    val v = ChosenVideo(
        uri, name, InfoVideo(0, 0, 0.0, info.duration, info.kbps, info.codec, f.length()), f,
        listOf(Track(info.codec, info.kbps, info.channels)), isCopy = direct == null,
    )
    return v to info
}

@Composable
private fun Root(demo: Boolean, pathTest: String? = null, toolTest: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("compresor", Context.MODE_PRIVATE) }
    var phase by remember { mutableStateOf<Phase>(if (demo) Phase.Adjust(videoDemo()) else Phase.Home) }
    var tool by remember { mutableStateOf(Tool.COMPRESS) }
    fun destination(v: ChosenVideo): Phase = when (tool) {
        Tool.COMPRESS -> Phase.Adjust(v)
        Tool.CUT -> Phase.Cut(v)
        Tool.CROP -> Phase.Crop(v)
    }

    LaunchedEffect(pathTest) {
        if (pathTest != null && toolTest == "audio") {      // tests only: opens an audio file from the cache
            phase = Phase.Preparing
            val r = withContext(Dispatchers.IO) {
                val f = java.io.File(pathTest)
                Probe.probeAudio(ctx, f)?.let { i ->
                    ChosenVideo(null, f.name, InfoVideo(0, 0, 0.0, i.duration, i.kbps, i.codec, f.length()), f,
                        listOf(Track(i.codec, i.kbps, i.channels)), isCopy = f.path.startsWith(ctx.cacheDir.path)) to i
                }
            }
            phase = if (r != null) Phase.AudioAdjust(r.first, r.second) else Phase.Home
        } else if (pathTest != null) {
            phase = Phase.Preparing
            val v = withContext(Dispatchers.IO) {
                val f = java.io.File(pathTest)
                val isCacheCopy = f.path.startsWith(ctx.cacheDir.path)       // only what is in the app cache is deleted
                Probe.probe(ctx, f)?.let { (info, tracks) -> ChosenVideo(null, f.name, info, f, tracks, isCopy = isCacheCopy) }
                    ?: readVideo(ctx, android.net.Uri.fromFile(f))?.copy(path = f, isCopy = isCacheCopy)      // no ffprobe (emulator)
            }
            tool = when (toolTest) { "cut" -> Tool.CUT; "crop" -> Tool.CROP; else -> Tool.COMPRESS }
            phase = if (v != null) destination(v) else Phase.Home
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            phase = Phase.Preparing
            scope.launch {
                val v = withContext(Dispatchers.IO) { prepareVideo(ctx, uri) }
                if (v == null) {
                    Toast.makeText(ctx, tx("Could not read that video."), Toast.LENGTH_LONG).show()
                    phase = Phase.Home
                } else phase = destination(v)
            }
        }
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            phase = Phase.Preparing
            scope.launch {
                val r = withContext(Dispatchers.IO) { prepareAudio(ctx, uri, Probe.nameOf(ctx, uri), null) }
                if (r == null) {
                    Toast.makeText(ctx, tx("Could not read that audio."), Toast.LENGTH_LONG).show()
                    phase = Phase.AudioList
                } else phase = Phase.AudioAdjust(r.first, r.second)
            }
        }
    }

    fun begin(v: ChosenVideo, ta: Task) = JobQueue.begin(ctx, v, ta)

    // "You must grant permission" notices: a dialog with OK (and a shortcut to the phone settings in case Android no longer lets us ask).
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    var permissionsVersion by remember { mutableIntStateOf(0) }
    permissionNotice?.let { msg ->
        val c = AppTheme.palette
        AlertDialog(
            onDismissRequest = { permissionNotice = null },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(tx("A permission is missing")) }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { permissionNotice = null }) { Text(tx("OK"), color = c.accent) } },
            dismissButton = {
                TextButton(onClick = {
                    permissionNotice = null
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))) }
                }) { Text(tx("Open settings"), color = c.textMuted) }
            },
        )
    }

    // Storage (videos, audio and, on Android 6 to 9, writing): ALWAYS asked for when missing, on opening the app and when entering each thing that uses it.
    fun storagePermissions(): Array<String> =
        (GalleryRepo.permissions().toList() + app.compresor.ui.AudioRepo.permissions().toList() +
            (if (Build.VERSION.SDK_INT in 23..28) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList())).distinct().toTypedArray()
    fun storageMissing() = !GalleryRepo.hasPermission(ctx) || !app.compresor.ui.AudioRepo.hasPermission(ctx) ||
        (Build.VERSION.SDK_INT in 23..28 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
    var storageReason by remember { mutableStateOf(tx("see and save your videos and audio")) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        permissionsVersion++
        if (storageMissing()) permissionNotice = tx("You must grant storage permission to {0}.", storageReason)
    }
    fun ensureStorage(reason: String) {
        if (storageMissing() && storagePermissions().isNotEmpty()) { storageReason = reason; askStorage.launch(storagePermissions()) }
    }
    LaunchedEffect(Unit) { ensureStorage(tx("see and save your videos and audio")) }
    LaunchedEffect(phase::class) {
        when (phase) {
            Phase.Gallery -> ensureStorage(tx("see your videos"))
            Phase.AudioList -> ensureStorage(tx("see your audio files"))
            else -> {}
        }
    }

    // Notifications (Android 13+): only when tapping "Leave in background". If you do not grant them, it does not go to the background and you are told.
    fun goToBackgroundMode() { (ctx as? android.app.Activity)?.moveTaskToBack(true) }
    // Battery "unrestricted": on Tecno/Infinix/Xiaomi the system freezes background work otherwise (asked once).
    val askBattery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { goToBackgroundMode() }
    fun withBattery() {
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            runCatching {
                askBattery.launch(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + ctx.packageName)))
            }.onFailure { goToBackgroundMode() }
        } else goToBackgroundMode()
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) withBattery() else permissionNotice = tx("You must grant notification permission to leave the work in the background.")
    }
    fun backgroundMode() {
        val missing = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (missing) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else withBattery()
    }

    // Write permission (Android 6 to 9 only): asked when saving.
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = pendingSave
        pendingSave = null
        if (ok && f != null) f() else if (!ok) permissionNotice = tx("You must grant storage permission to save on your phone.")
    }
    fun withWrite(f: () -> Unit) {
        val missing = Build.VERSION.SDK_INT in 23..28 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (missing) { pendingSave = f; askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) } else f()
    }

    // What was saved last (to REPLACE it if the settings change in "save without asking" mode) and the text that is shown.
    var saved by remember { mutableStateOf<app.compresor.execution.Saved?>(null) }
    var savedIn by remember { mutableStateOf<String?>(null) }

    // when going back home there is nothing left to replace
    LaunchedEffect(phase) { if (phase == Phase.Home) { saved = null; savedIn = null } }

    fun clear(v: ChosenVideo, res: Outcome? = null) {
        res?.output?.delete()
        v.deleteCopy()
        JobQueue.clear()
        saved = null; savedIn = null
    }

    var proposedName by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val work = JobQueue.current
    val outcome = JobQueue.outcome

    fun folderOf(tr: RunningJob) = if (tr.task is Task.Audio) Config.audioFolder else Config.videoFolder

    /** Saves the result. In automatic mode it stays on the result screen; with confirmation it goes back home. */
    fun saveResult(tr: RunningJob, r: Outcome, name: String, auto: Boolean) {
        withWrite {
            scope.launch {
                val g = withContext(Dispatchers.IO) { r.output?.let { Compression.saveInGallery(ctx, it, name, tr.task is Task.Audio, folderOf(tr)) } }
                if (g == null) {
                    savedIn = null
                    Toast.makeText(ctx, tx("Could not save."), Toast.LENGTH_LONG).show()
                } else {
                    saved = g; savedIn = g.text
                    if (!auto) { clear(tr.video, r); Toast.makeText(ctx, tx("Saved in {0}", g.text), Toast.LENGTH_LONG).show(); phase = Phase.Home }
                }
            }
        }
    }

    // "Save without asking" mode: saves by itself when done. If it came from "Change settings", the new video REPLACES the previous one.
    LaunchedEffect(outcome) {
        val r = outcome
        val tr = JobQueue.current
        if (r != null && tr != null && r.end == app.compresor.execution.Ending.OK && Config.skipName) {
            savedIn = null
            val name = withContext(Dispatchers.IO) {
                val previous = saved
                if (previous != null) { Compression.deleteSavedFile(ctx, previous); saved = null; previous.name }
                else availableName(tr.video.name, tr.task.mark, r.output?.extension ?: "mp4") { Compression.exists(ctx, it, tr.task is Task.Audio, folderOf(tr)) }
            }
            saveResult(tr, r, name, auto = true)
        }
    }

    if (work != null) {
        if (outcome == null) {
            ProgressScreen(work.video, work.settings, work.state, work.ctrl, work.task.verb, onBackgroundMode = { backgroundMode() })
        } else {
            val auto = Config.skipName
            BackHandler { clear(work.video, outcome); phase = Phase.Home }
            val onSaveCommon: () -> Unit = {
                scope.launch {
                    val proposed = withContext(Dispatchers.IO) {
                        availableName(work.video.name, work.task.mark, outcome.output?.extension ?: "mp4") { Compression.exists(ctx, it, work.task is Task.Audio, folderOf(work)) }
                    }
                    proposedName = proposed
                }
            }
            val onMenu: () -> Unit = {
                // with confirmation and not saved yet: warn before losing the result
                if (!auto && outcome.end == app.compresor.execution.Ending.OK) confirmLeave = true
                else { clear(work.video, outcome); phase = Phase.Home }
            }
            val ta = work.task
            if (ta is Task.Audio) AudioResultScreen(
                video = work.video, info = ta.info, settings = ta.settings, res = outcome,
                onSave = onSaveCommon,
                onChangeSettings = { outcome.output?.delete(); savedIn = null; JobQueue.clear(); phase = Phase.AudioAdjust(work.video, ta.info) },
                onDiscard = { clear(work.video, outcome); phase = Phase.Home },
                onMenu = onMenu, savedIn = savedIn,
            ) else ResultScreen(
                video = work.video, settings = work.settings, res = outcome, task = work.task,
                onSave = onSaveCommon,
                onChangeSettings = {
                    outcome.output?.delete(); savedIn = null; JobQueue.clear()
                    phase = when (work.task) { is Task.Cut -> Phase.Cut(work.video); is Task.Crop -> Phase.Crop(work.video); else -> Phase.Adjust(work.video) }
                },
                onDiscard = { clear(work.video, outcome); phase = Phase.Home },
                onMenu = onMenu, savedIn = savedIn,
            )
            proposedName?.let { initial ->
                val c = AppTheme.palette
                var text by remember(initial) { mutableStateOf(initial) }
                AlertDialog(
                    onDismissRequest = { proposedName = null },
                    containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
                    title = { Text(tx("File name")) },
                    text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                    confirmButton = {
                        TextButton(onClick = { proposedName = null; saveResult(work, outcome, sanitizeName(text, initial), auto = false) }) { Text(tx("Save"), color = c.accent) }
                    },
                    dismissButton = { TextButton(onClick = { proposedName = null }) { Text(tx("Cancel"), color = c.textMuted) } },
                )
            }
            if (confirmLeave) {
                val c = AppTheme.palette
                AlertDialog(
                    onDismissRequest = { confirmLeave = false },
                    containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
                    title = { Text(tx("Leave without saving?")) }, text = { Text(tx("You have not saved this result yet.")) },
                    confirmButton = { TextButton(onClick = { confirmLeave = false; clear(work.video, outcome); phase = Phase.Home }) { Text(tx("Leave"), color = c.danger) } },
                    dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(tx("Stay"), color = c.accent) } },
                )
            }
        }
        return
    }

    when (val f = phase) {
        Phase.Home -> HomeScreen(
            onCompress = { tool = Tool.COMPRESS; phase = Phase.Gallery },
            onCut = { tool = Tool.CUT; phase = Phase.Gallery },
            onCrop = { tool = Tool.CROP; phase = Phase.Gallery },
            onAudio = { phase = Phase.AudioList },
            onOptions = { phase = Phase.Options },
        )
        Phase.AudioList -> {
            BackHandler { phase = Phase.Home }
            androidx.compose.runtime.key(permissionsVersion) { AudioListScreen(
                onPick = { item: AudioItem ->
                    phase = Phase.Preparing
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { prepareAudio(ctx, app.compresor.ui.AudioRepo.uriOf(item.id), item.name, item.path) }
                        if (r == null) {
                            Toast.makeText(ctx, tx("Could not read that audio."), Toast.LENGTH_LONG).show()
                            phase = Phase.AudioList
                        } else phase = Phase.AudioAdjust(r.first, r.second)
                    }
                },
                onBack = { phase = Phase.Home },
                onPicker = { pickAudio.launch("audio/*") },
            ) }
        }
        is Phase.AudioAdjust -> {
            BackHandler { f.video.deleteCopy(); phase = Phase.AudioList }
            ConvertAudioScreen(f.video, f.info, onBack = { f.video.deleteCopy(); phase = Phase.AudioList },
                onConvert = { a -> begin(f.video, Task.Audio(a, f.info)) })
        }
        Phase.Options -> {
            BackHandler { phase = Phase.Home }
            OptionsScreen(onBack = { phase = Phase.Home })
        }
        Phase.Gallery -> {
            BackHandler { phase = Phase.Home }
            androidx.compose.runtime.key(permissionsVersion) { GalleryScreen(
                onPick = { item ->
                    phase = Phase.Preparing
                    scope.launch {
                        val v = withContext(Dispatchers.IO) { prepareDirect(ctx, item) }
                        if (v == null) {
                            Toast.makeText(ctx, tx("Could not read that video."), Toast.LENGTH_LONG).show()
                            phase = Phase.Gallery
                        } else phase = destination(v)
                    }
                },
                onBack = { phase = Phase.Home },
                onPicker = { pick.launch("video/*") },
            ) }
        }
        Phase.Preparing -> {
            val c = AppTheme.palette
            Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
                Text(tx("Preparing the video…"), color = c.text)
            }
        }
        is Phase.Adjust -> {
            BackHandler { f.video.deleteCopy(); phase = Phase.Home }
            SettingsScreen(
                video = f.video, initialCodec = Config.videoCodec,
                onChangeCodec = { },
                onBack = { f.video.deleteCopy(); phase = Phase.Home },
                onCompress = { a -> begin(f.video, Task.Compress(a)) },
            )
        }
        is Phase.Cut -> {
            BackHandler { f.video.deleteCopy(); phase = Phase.Home }
            CutScreen(f.video, onBack = { f.video.deleteCopy(); phase = Phase.Home }, onSave = { c -> begin(f.video, Task.Cut(c)) })
        }
        is Phase.Crop -> {
            BackHandler { f.video.deleteCopy(); phase = Phase.Home }
            CropScreen(f.video, onBack = { f.video.deleteCopy(); phase = Phase.Home }, onSave = { r -> begin(f.video, Task.Crop(r)) })
        }
    }
}
