package app.compresor.ui

import app.compresor.tx

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.compresor.execution.Ending
import app.compresor.execution.Outcome
import app.compresor.engine.AudioSettings
import app.compresor.engine.InfoAudio
import app.compresor.engine.AudioOutput
import app.compresor.engine.suggestedKbps
import app.compresor.engine.audioSizeBytes
import app.compresor.ui.theme.Spacing
import app.compresor.ui.theme.Dimens
import app.compresor.ui.theme.Radius
import app.compresor.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ───────────── Data: the audio files on the phone (MediaStore) ─────────────

data class AudioItem(
    val id: Long, val name: String, val title: String, val artist: String,
    val durationMs: Long, val bytes: Long, val added: Long, val path: String?,
) {
    /** What is shown: the tag title, or the file name without extension. */
    val displayName: String get() = title.ifBlank { name.substringBeforeLast('.') }
}

enum class AudioSortOrder { APPEARANCE, NAME, DURATION, SIZE }

fun sortAudios(l: List<AudioItem>, o: AudioSortOrder, ascending: Boolean): List<AudioItem> {
    val cmp: Comparator<AudioItem> = when (o) {
        AudioSortOrder.APPEARANCE -> compareBy<AudioItem>({ it.added }, { it.id })
        AudioSortOrder.NAME -> compareBy<AudioItem, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }.thenBy { it.id }
        AudioSortOrder.DURATION -> compareBy<AudioItem>({ it.durationMs }, { it.id })
        AudioSortOrder.SIZE -> compareBy<AudioItem>({ it.bytes }, { it.id })
    }
    val s = l.sortedWith(cmp)
    return if (ascending) s else s.reversed()
}

object AudioRepo {
    fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    fun uriOf(id: Long): Uri = ContentUris.withAppendedId(collection(), id)

    @Suppress("DEPRECATION")
    fun listAll(ctx: Context): List<AudioItem> {
        val project = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATA,
        )
        val res = ArrayList<AudioItem>()
        runCatching {
            ctx.contentResolver.query(collection(), project, "${MediaStore.Audio.Media.SIZE}>0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val artist = c.getString(3).orEmpty().let { if (it == "<unknown>") "" else it }
                    res += AudioItem(
                        id = c.getLong(0), name = c.getString(1) ?: "audio", title = c.getString(2).orEmpty(), artist = artist,
                        durationMs = c.getLong(4), bytes = c.getLong(5), added = c.getLong(6) * 1000, path = c.getString(7),
                    )
                }
            }
        }
        return res
    }

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyArray()
    }

    fun hasPermission(ctx: Context): Boolean {
        val p = permissions()
        return p.isEmpty() || p.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }
}

// ───────────── Cover art ─────────────

/**
 * Cover art of an audio file, by three paths (the first one that works):
 *  1. the image embedded in the file (MediaMetadataRetriever: MP3, M4A, FLAC and many OGG),
 *  2. the system thumbnail (Android 10+: also reads folder.jpg and album covers),
 *  3. ffmpeg (OGG/Opus/WMA/APE... that the system does not understand), at most 2 at a time.
 * It is kept in memory (small); audio files without cover art are also remembered so the work is not repeated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Covers {
    private val memory = object : LruCache<String, Bitmap>(6 * 1024) {      // KB
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val noCover = HashSet<String>()
    private val threads: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)
    private val quotaFfmpeg = Semaphore(2)

    fun emptyMemory() = memory.evictAll()
    private fun keyName(id: Long, side: Int) = "${id}_$side"
    fun inMemory(id: Long, side: Int): Bitmap? = memory.get(keyName(id, side))

    private fun reduce(bytes: ByteArray, side: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= side && o.outHeight / (s * 2) >= side) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Cover art of a file (no cache). Null if it has none. */
    suspend fun fromFile(ctx: Context, f: File, side: Int): Bitmap? = withContext(threads) {
        val r = MediaMetadataRetriever()
        val embedded = runCatching {
            r.setDataSource(f.path)
            r.embeddedPicture?.let { reduce(it, side) }
        }.getOrNull()
        runCatching { r.release() }
        embedded ?: withFfmpeg(ctx, f, side)
    }

    private suspend fun withFfmpeg(ctx: Context, f: File, side: Int): Bitmap? = quotaFfmpeg.withPermit {
        runCatching {
            val ffmpeg = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so").path
            val out = File(ctx.cacheDir, "pl_cv_${System.nanoTime()}.jpg")
            try {
                val p = ProcessBuilder(
                    ffmpeg, "-y", "-v", "error", "-i", f.path, "-an", "-map", "0:v:0", "-frames:v", "1",
                    "-vf", "scale='min($side,iw)':-2", "-q:v", "5", out.path,
                ).redirectErrorStream(true).start()
                p.inputStream.readBytes()
                p.waitFor()
                if (out.length() > 0) BitmapFactory.decodeFile(out.path) else null
            } finally { out.delete() }
        }.getOrNull()
    }

    suspend fun load(ctx: Context, a: AudioItem, side: Int): Bitmap? {
        val k = keyName(a.id, side)
        memory.get(k)?.let { return it }
        if (synchronized(noCover) { k in noCover }) return null
        val path = a.path?.let { File(it) }?.takeIf { it.canRead() }
        var b: Bitmap? = null
        if (path != null) {
            // 1) what is embedded (no ffmpeg yet: the cheap thing first)
            b = withContext(threads) {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(path.path); r.embeddedPicture?.let { reduce(it, side) } } catch (e: Exception) { null }
                finally { runCatching { r.release() } }
            }
        }
        if (b == null && Build.VERSION.SDK_INT >= 29) {
            b = withContext(threads) { runCatching { ctx.contentResolver.loadThumbnail(AudioRepo.uriOf(a.id), Size(side, side), null) }.getOrNull() }
        }
        if (b == null && path != null) b = withContext(threads) { withFfmpeg(ctx, path, side) }
        if (b != null) memory.put(k, b) else synchronized(noCover) { noCover += k }
        return b
    }
}

@Composable
private fun Cover(a: AudioItem, side: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val c = AppTheme.palette
    val bmp by produceState<ImageBitmap?>(Covers.inMemory(a.id, side)?.asImageBitmap(), a.id, side) {
        if (value == null) {
            delay(60)      // if it leaves the screen within 60 ms (fast scrolling), the coroutine is cancelled and nothing is loaded
            value = Covers.load(ctx, a, side)?.asImageBitmap()
        }
    }
    CoverBox(bmp, modifier)
}

@Composable
private fun CoverBox(bmp: ImageBitmap?, modifier: Modifier) {
    val c = AppTheme.palette
    Box(modifier.clip(RoundedCornerShape(Radius.md)).background(c.surface2), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, contentDescription = tx("Cover art"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.MusicNote, contentDescription = tx("No cover art"), tint = c.textMuted)
    }
}

// ───────────── Audio list (list only, no grid) ─────────────

@Composable
fun AudioListScreen(onPick: (AudioItem) -> Unit, onBack: () -> Unit, onPicker: () -> Unit) {
    val c = AppTheme.palette
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("compresor", Context.MODE_PRIVATE) }
    var contains by remember { mutableStateOf(AudioRepo.hasPermission(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        contains = AudioRepo.hasPermission(ctx); denied = !contains
    }
    var sortOrder by remember { mutableStateOf(runCatching { AudioSortOrder.valueOf(prefs.getString("a_orden", "") ?: "") }.getOrDefault(AudioSortOrder.APPEARANCE)) }
    var ascending by remember { mutableStateOf(prefs.getBoolean("a_asc", false)) }
    var menu by remember { mutableStateOf(false) }
    var audios by remember { mutableStateOf<List<AudioItem>?>(null) }
    val side = (52 * LocalDensity.current.density).toInt().coerceIn(96, 200)

    var reload by remember { mutableIntStateOf(0) }
    MediaRefresh(AudioRepo.collection()) { reload++ }
    LaunchedEffect(contains, reload) { if (contains) audios = withContext(Dispatchers.IO) { AudioRepo.listAll(ctx) } }
    fun save() { prefs.edit().putString("a_orden", sortOrder.name).putBoolean("a_asc", ascending).apply() }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(Dimens.topBar), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back to menu"), tint = c.text)
            }
            Text(tx("Audio files"), color = c.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onPicker, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = tx("Choose a file with the system picker"), tint = c.text)
            }
            if (contains) IconButton(onClick = { menu = true }, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.Filled.FilterList, contentDescription = tx("Order"), tint = c.text)
            }
        }
        val list = audios
        when {
            !contains -> Column(Modifier.fillMaxSize().padding(Spacing.e4), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tx("To show your audio files I need access to them."), color = c.text, style = MaterialTheme.typography.titleMedium)
                VerticalGap(Spacing.e2)
                Text(tx("I only read the audio files: no internet, nothing is uploaded."), color = c.textMuted)
                VerticalGap(Spacing.e4)
                Button(onClick = { requestPermission.launch(AudioRepo.permissions()) }, modifier = Modifier.fillMaxWidth().height(Dimens.button),
                    shape = RoundedCornerShape(Radius.pill), colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                    Text(tx("Allow access to audio"), fontWeight = FontWeight.Bold)
                }
                if (denied) {
                    VerticalGap(Spacing.e2)
                    TextButton(onClick = onPicker, modifier = Modifier.defaultMinSize(minHeight = Dimens.touchMin)) {
                        Text(tx("Better to choose an audio file with the system picker"), color = c.accent)
                    }
                }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tx("Loading audio files…"), color = c.textMuted) }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(Spacing.e4), contentAlignment = Alignment.Center) {
                Text(tx("There are no audio files on this phone."), color = c.textMuted)
            }
            else -> {
                val sorted = remember(list, sortOrder, ascending) { sortAudios(list, sortOrder, ascending) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sorted, key = { it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable(onClickLabel = tx("Choose {0}", a.displayName)) { onPick(a) }
                                .padding(horizontal = Spacing.e4, vertical = Spacing.e2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(a, side, Modifier.size(52.dp))
                            Column(Modifier.padding(start = Spacing.e3).weight(1f)) {
                                Text(a.displayName, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(a.artist, durationText(a.durationMs), formatSize(a.bytes)).filter { it.isNotBlank() }.joinToString(" · "),
                                    color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (menu) {
        val tight = isTightScreen()
        val sub = if (tight) 13.sp else 14.sp
        AlertDialog(
            onDismissRequest = { menu = false },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(tx("Order"), fontSize = if (tight) 18.sp else 22.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(tx("Sort by"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = sub)
                    RadioOption(tx("Last seen"), sortOrder == AudioSortOrder.APPEARANCE, compact = tight) { sortOrder = AudioSortOrder.APPEARANCE; save() }
                    RadioOption(tx("Name"), sortOrder == AudioSortOrder.NAME, compact = tight) { sortOrder = AudioSortOrder.NAME; save() }
                    RadioOption(tx("Duration"), sortOrder == AudioSortOrder.DURATION, compact = tight) { sortOrder = AudioSortOrder.DURATION; save() }
                    RadioOption(tx("Size"), sortOrder == AudioSortOrder.SIZE, compact = tight) { sortOrder = AudioSortOrder.SIZE; save() }
                    Text(tx("Direction"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = sub, modifier = Modifier.padding(top = if (tight) Spacing.e1 else Spacing.e3))
                    Row(Modifier.fillMaxWidth()) {
                        RadioOption(tx("Descending"), !ascending, modifier = Modifier.weight(1f), compact = tight) { ascending = false; save() }
                        RadioOption(tx("Ascending"), ascending, modifier = Modifier.weight(1f), compact = tight) { ascending = true; save() }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menu = false }) { Text(tx("Done"), color = c.accent) } },
        )
    }
}

// ───────────── Convert (a single window) ─────────────

private fun dataText(i: InfoAudio): String =
    listOfNotNull(
        i.codec.uppercase().ifBlank { null },
        if (i.kbps > 0) "${i.kbps} kbps" else null,
        if (i.hz > 0) String.format(Locale.US, "%.1f kHz", i.hz / 1000.0) else null,
        durationText((i.duration * 1000).toLong()),
        formatSize(i.sizeBytes),
    ).joinToString(" · ")

/** The smaller of what the original suggests and the format's factory value: by default the file comes out smaller, not bigger. */
private fun initialKbps(i: InfoAudio, s: AudioOutput) = s.defaultKbps

@Composable
fun ConvertAudioScreen(video: ChosenVideo, info: InfoAudio, onBack: () -> Unit, onConvert: (AudioSettings) -> Unit) {
    val c = AppTheme.palette
    val ctx = LocalContext.current
    val tight = isTightScreen()
    var output by remember { mutableStateOf(AudioOutput.MP3) }
    var kbps by remember { mutableIntStateOf(initialKbps(info, AudioOutput.MP3)) }
    var remove by remember { mutableStateOf(false) }
    val side = (if (tight) 80 else 110) * LocalDensity.current.density.toInt().coerceAtLeast(1)
    val cover by produceState<ImageBitmap?>(null, video.path) {
        val f = video.path
        if (f != null && info.cover) value = Covers.fromFile(ctx, f, side)?.asImageBitmap()
    }
    val size = audioSizeBytes(info.duration, kbps)
    val change = if (info.sizeBytes > 0) ((size.toDouble() / info.sizeBytes - 1) * 100).toInt() else 0
    val list = output.kbps
    val idx = list.indexOf(kbps).coerceAtLeast(0)
    val space = if (tight) Spacing.e2 else Spacing.e3

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(if (tight) 48.dp else Dimens.topBar), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back"), tint = c.text)
            }
            Text(tx("Compress or convert audio"), color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
        }
        // If the screen is very short or the font very large, the content scrolls (never cut off); on normal screens it fits entirely.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.e4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverBox(cover, Modifier.size(if (tight) 72.dp else 96.dp))
                Column(Modifier.padding(start = Spacing.e3)) {
                    Text(info.title.ifBlank { video.name.substringBeforeLast('.') }, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (info.artist.isNotBlank()) Text(info.artist, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dataText(info), color = c.data, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2)
                }
            }
            Text(tx("Format"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = if (tight) 13.sp else 14.sp, modifier = Modifier.padding(top = space))
            Row(Modifier.fillMaxWidth()) {
                RadioOption(AudioOutput.MP3.tag, output == AudioOutput.MP3, modifier = Modifier.weight(1f), compact = tight) { output = AudioOutput.MP3; kbps = initialKbps(info, output) }
                RadioOption(AudioOutput.OPUS.tag, output == AudioOutput.OPUS, modifier = Modifier.weight(1f), compact = tight) { output = AudioOutput.OPUS; kbps = initialKbps(info, output) }
            }
            Row(Modifier.fillMaxWidth()) {
                RadioOption(AudioOutput.OGG.tag, output == AudioOutput.OGG, modifier = Modifier.weight(1f), compact = tight) { output = AudioOutput.OGG; kbps = initialKbps(info, output) }
                RadioOption(AudioOutput.AAC.tag, output == AudioOutput.AAC, modifier = Modifier.weight(1f), compact = tight) { output = AudioOutput.AAC; kbps = initialKbps(info, output) }
            }
            Text(output.note, color = c.textMuted, style = MaterialTheme.typography.bodySmall)

            Row(Modifier.fillMaxWidth().padding(top = space), verticalAlignment = Alignment.CenterVertically) {
                Text(tx("Bitrate"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = if (tight) 13.sp else 14.sp, modifier = Modifier.weight(1f))
                Text("$kbps kbps", color = c.accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = idx.toFloat(), onValueChange = { kbps = list[it.toInt().coerceIn(0, list.lastIndex)] },
                valueRange = 0f..(list.lastIndex).toFloat(), steps = (list.size - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface2),
            )
            Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Radius.md)).border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(Spacing.e3)) {
                Text(
                    tx("Will weigh about {0}", formatSize(size)) + if (info.sizeBytes > 0) " (${if (change > 0) "+" else ""}$change %)" else "",
                    color = if (change > 0) c.warning else c.success, fontWeight = FontWeight.SemiBold,
                )
                if (info.kbps in 1 until kbps) Text(tx("The original is {0} kbps: raising the bitrate does not improve the sound.", info.kbps), color = c.warning, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.e1).clickable { remove = !remove }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remove, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
                Column(Modifier.padding(start = Spacing.e2)) {
                    Text(tx("Remove metadata and cover art"), color = c.text)
                    Text(if (remove) tx("Internal title, artist and cover art are not saved.") else tx("Internal title, artist, cover art and other data are kept."),
                        color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(
            onClick = { onConvert(AudioSettings(output, kbps, remove)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.e4, vertical = Spacing.e2).height(if (tight) 48.dp else Dimens.button),
            shape = RoundedCornerShape(Radius.pill), colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        ) { Text(tx("Convert"), fontWeight = FontWeight.Bold) }
    }
}

// ───────────── Result with player ─────────────

private fun msText(ms: Int) = durationText(ms.toLong())

@Composable
fun AudioResultScreen(
    video: ChosenVideo, info: InfoAudio, settings: AudioSettings, res: Outcome,
    onSave: () -> Unit, onChangeSettings: () -> Unit, onDiscard: () -> Unit, onMenu: () -> Unit, savedIn: String? = null,
) {
    val c = AppTheme.palette
    val auto = Config.skipName
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.e4)) {
            when (res.end) {
                Ending.OK -> {
                    Text(tx("Done"), color = c.success, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                    Text(tx("Took {0}.", app.compresor.execution.hms(res.seconds.toLong())), color = c.textMuted)
                    if (auto) Text(if (savedIn != null) tx("Saved in {0}", savedIn) else tx("Saving…"), color = c.accent)
                    VerticalGap(Spacing.e3)
                    res.output?.let { AudioPlayer(it) }
                    VerticalGap(Spacing.e3)
                    val change = if (res.originalSize > 0) ((res.outputSize.toDouble() / res.originalSize - 1) * 100).toInt() else 0
                    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Radius.md)).border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(Spacing.e3)) {
                        Text(tx("Size: {0} → {1} ({2} %)", formatSize(res.originalSize), formatSize(res.outputSize), (if (change > 0) "+" else "") + change),
                            color = if (change > 0) c.warning else c.success, fontWeight = FontWeight.SemiBold)
                        Text(tx("Format: {0} · {1} kbps", settings.output.tag, settings.kbps), color = c.text)
                        Text(if (settings.removeMetadata) tx("No metadata or cover art.") else tx("With metadata") + if (info.cover) tx(" and cover art.") else ".", color = c.textMuted)
                    }
                    if (change > 0) { VerticalGap(Spacing.e2); Text(tx("Heads up: the result is bigger than the original."), color = c.warning) }
                }
                Ending.STOPPED -> {
                    Text(tx("Stopped"), color = c.warning, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                    Text(tx("Nothing was saved. Your original audio is untouched."), color = c.textMuted)
                }
                Ending.FAILURE -> {
                    Text(tx("Could not convert"), color = c.danger, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                    Text(tx("Your original audio is untouched. This is what ffmpeg said:"), color = c.textMuted)
                    VerticalGap(Spacing.e2)
                    Text(res.error.ifBlank { tx("(no message)") }, color = c.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ResultButtons(res.end == Ending.OK && !auto, tx("Save to Music"), onSave, onChangeSettings, onDiscard, onMenu)
    }
}

/** Simple player (MediaPlayer): play/pause and a seek bar. It releases itself when leaving the screen. */
@Composable
private fun AudioPlayer(file: File) {
    val c = AppTheme.palette
    val mp = remember(file) { MediaPlayer() }
    var ready by remember(file) { mutableStateOf(false) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    var pos by remember(file) { mutableIntStateOf(0) }
    var dur by remember(file) { mutableIntStateOf(0) }
    var dragging by remember(file) { mutableStateOf(false) }
    var errorRep by remember(file) { mutableStateOf(false) }
    var value by remember(file) { mutableFloatStateOf(0f) }

    DisposableEffect(file) {
        runCatching {
            mp.setDataSource(file.path)
            mp.setOnPreparedListener { dur = it.duration; ready = true }
            mp.setOnCompletionListener { isPlaying = false; pos = 0; mp.seekTo(0) }
            mp.setOnErrorListener { _, _, _ -> errorRep = true; isPlaying = false; true }
            mp.prepareAsync()
        }.onFailure { errorRep = true }
        onDispose { runCatching { mp.release() } }
    }
    LaunchedEffect(isPlaying, dragging) {
        while (isPlaying && !dragging) { pos = runCatching { mp.currentPosition }.getOrDefault(pos); delay(250) }
    }

    Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Radius.md)).border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(Spacing.e3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (isPlaying) { mp.pause(); isPlaying = false } else { mp.start(); isPlaying = true } },
                enabled = ready && !errorRep,
                modifier = Modifier.size(Dimens.touchMin).clip(RoundedCornerShape(Radius.pill)).background(if (ready) c.accent else c.surface2),
            ) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (isPlaying) tx("Pause") else tx("Play"), tint = c.onAccent)
            }
            Column(Modifier.padding(start = Spacing.e3).weight(1f)) {
                Slider(
                    value = if (dragging) value else if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = { dragging = true; value = it; pos = (it * dur).toInt() },
                    onValueChangeFinished = { runCatching { mp.seekTo(pos) }; dragging = false },
                    enabled = ready && !errorRep,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface2),
                )
                Text(if (errorRep) tx("Could not play it here.") else "${msText(pos)} / ${msText(dur)}", color = if (errorRep) c.warning else c.textMuted,
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
