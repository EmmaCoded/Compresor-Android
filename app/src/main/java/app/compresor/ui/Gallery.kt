package app.compresor.ui

import app.compresor.tx

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.compresor.ui.theme.Spacing
import app.compresor.ui.theme.Dimens
import app.compresor.ui.theme.Radius
import app.compresor.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────── Data: the videos on the phone (MediaStore) ─────────────

object GalleryRepo {
    fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun uriOf(id: Long): Uri = ContentUris.withAppendedId(collection(), id)

    @Suppress("DEPRECATION")
    fun listAll(ctx: Context): List<VideoItem> {
        val project = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATA,
        )
        val res = ArrayList<VideoItem>()
        runCatching {
            ctx.contentResolver.query(collection(), project, null, null, null)?.use { c ->
                val colId = c.getColumnIndexOrThrow(project[0]); val colName = c.getColumnIndexOrThrow(project[1])
                val colSize = c.getColumnIndexOrThrow(project[2]); val colDuration = c.getColumnIndexOrThrow(project[3])
                val colAdded = c.getColumnIndexOrThrow(project[4]); val colModified = c.getColumnIndexOrThrow(project[5])
                val colTaken = c.getColumnIndexOrThrow(project[6]); val colPath = c.getColumnIndexOrThrow(project[7])
                while (c.moveToNext()) {
                    val mod = c.getLong(colModified) * 1000
                    val taken = c.getLong(colTaken)
                    res += VideoItem(
                        id = c.getLong(colId), name = c.getString(colName) ?: "video", bytes = c.getLong(colSize),
                        durationMs = c.getLong(colDuration), added = c.getLong(colAdded) * 1000, modified = mod,
                        created = if (taken > 0) taken else mod, path = c.getString(colPath),
                    )
                }
            }
        }
        return res
    }

    /** Android 14+: besides the full permission there is "limited access" (only the videos the user picks). */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyArray()                           // Android 5: granted at install
    }

    fun hasPermission(ctx: Context): Boolean {
        val p = permissions()
        return p.isEmpty() || p.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }
}

/**
 * Fast and light thumbnails.
 *  - ONE already-reduced keyframe is taken (without decoding the whole frame): fast and low memory.
 *  - Small memory cache (it empties itself when memory is short) + a small JPEG per video on disk:
 *    a discarded thumbnail reloads in milliseconds.
 *  - At most 3 decodings at a time; the ones no longer in view are cancelled before they start.
 */
object ThumbnailLoader {
    private const val QUALITY_JPEG = 82
    private const val DISK_MAX = 24L shl 20      // 24 MB
    private const val DISK_TARGET = 16L shl 20 // when pruning, it goes down to 16 MB

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val threads = Dispatchers.IO.limitedParallelism(3)

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16 / 1024).toInt().coerceIn(8 * 1024, 24 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // Debug counters: only in debug apps, in files/mini_stats.txt.
    private val gen = java.util.concurrent.atomic.AtomicInteger()
    private val disk = java.util.concurrent.atomic.AtomicInteger()
    private val memoryHits = java.util.concurrent.atomic.AtomicInteger()
    private val nulls = java.util.concurrent.atomic.AtomicInteger()
    private val requested = java.util.concurrent.atomic.AtomicInteger()
    private val msGen = java.util.concurrent.atomic.AtomicLong()
    private val msDisk = java.util.concurrent.atomic.AtomicLong()
    private var lastShape = ""
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()   // they are not retried on every screen
    private val reasons = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun reason(m: String) { reasons.merge(m, 1, Int::plus) }

    private fun dump(ctx: Context) {
        if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            java.io.File(ctx.filesDir, "mini_stats.txt").writeText(
                "generated=${gen.get()} (ms=${msGen.get()}) from_disk=${disk.get()} (ms=${msDisk.get()}) from_memory=${memoryHits.get()} nulls=${nulls.get()} reasons=$reasons requested=${requested.get()} last=$lastShape"
            )
        }
    }

    private fun keyName(v: VideoItem, side: Int) = "${v.id}_${v.modified}_$side"
    private fun folder(ctx: Context) = java.io.File(ctx.cacheDir, "thumbnails").apply { mkdirs() }

    fun inMemory(v: VideoItem, side: Int): Bitmap? = memory.get(keyName(v, side))
    fun emptyMemory() = memory.evictAll()

    /** Keeps the thumbnails folder under ~24 MB by deleting the least used. */
    fun prune(ctx: Context) {
        val fs = folder(ctx).listFiles() ?: return
        var total = fs.sumOf { it.length() }
        if (total <= DISK_MAX) return
        for (f in fs.sortedBy { it.lastModified() }) {
            if (total <= DISK_TARGET) break
            total -= f.length(); f.delete()
        }
    }

    suspend fun load(ctx: Context, v: VideoItem, side: Int): Bitmap? {
        val k = keyName(v, side)
        memory.get(k)?.let { memoryHits.incrementAndGet(); return it }
        if (k in failed) return null
        return withContext(threads) {
            requested.incrementAndGet()
            ensureActive()                                    // if it already left the screen, nothing is done
            val file = java.io.File(folder(ctx), "$k.jpg")
            val t0 = android.os.SystemClock.elapsedRealtime()
            if (file.exists()) {
                BitmapFactory.decodeFile(file.path)?.let { b ->
                    memory.put(k, b); file.setLastModified(System.currentTimeMillis())
                    disk.incrementAndGet(); msDisk.addAndGet(android.os.SystemClock.elapsedRealtime() - t0); dump(ctx)
                    return@withContext b
                }
            }
            ensureActive()
            val b = generate(ctx, v, side) ?: withFfmpeg(ctx, v, side)
                ?: run { nulls.incrementAndGet(); failed.add(k); dump(ctx); return@withContext null }
            memory.put(k, b)
            runCatching { java.io.FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.JPEG, QUALITY_JPEG, it) } }
            gen.incrementAndGet(); msGen.addAndGet(android.os.SystemClock.elapsedRealtime() - t0)
            lastShape = "${b.width}x${b.height}"; dump(ctx)
            b
        }
    }

    /** A keyframe near second 1, reduced so that the SHORT side measures `side` px (the cell crops it square). */
    @Suppress("DEPRECATION")
    private fun generate(ctx: Context, v: VideoItem, side: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            try { r.setDataSource(ctx, GalleryRepo.uriOf(v.id)) } catch (e: Exception) { reason("setDataSource:" + e.javaClass.simpleName); return system(ctx, v, side) }
            val tUs = minOf(1_000_000L, v.durationMs * 1000 / 2)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
            if (Build.VERSION.SDK_INT >= 27 && w > 0 && h > 0) {
                val e = side.toDouble() / minOf(w, h)
                if (e < 1.0) {
                    val dw = maxOf(2, (w * e).toInt()); val dh = maxOf(2, (h * e).toInt())
                    r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)?.let { return it }
                }
            }
            val full = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: run { reason("frame_nulo"); return system(ctx, v, side) }
            val e = side.toDouble() / minOf(full.width, full.height)
            return if (e < 1.0) Bitmap.createScaledBitmap(full, maxOf(2, (full.width * e).toInt()), maxOf(2, (full.height * e).toInt()), true)
                .also { if (it !== full) full.recycle() } else full
        } catch (e: Exception) {
            reason("excepcion:" + e.javaClass.simpleName)
            return system(ctx, v, side)
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Last resort: ffmpeg (the app's own) takes ONE small frame. It works for what the phone cannot decode
     * (for example AV1 on a phone without a decoder). Only if the video can be opened by its path.
     */
    private val quotaFfmpeg = java.util.concurrent.Semaphore(2)    // at most 2 ffmpeg processes at a time (they are heavy)

    private fun withFfmpeg(ctx: Context, v: VideoItem, side: Int): Bitmap? {
        quotaFfmpeg.acquire()
        try { return withFfmpegNoQuota(ctx, v, side) } finally { quotaFfmpeg.release() }
    }

    private fun withFfmpegNoQuota(ctx: Context, v: VideoItem, side: Int): Bitmap? {
        val path = v.path?.takeIf { java.io.File(it).canRead() } ?: return null
        val ffmpeg = java.io.File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpeg.exists()) return null
        val output = java.io.File(ctx.cacheDir, "mini_tmp_${v.id}.jpg")
        return try {
            val filter = "scale='if(gt(iw,ih),-2,$side)':'if(gt(iw,ih),$side,-2)':flags=fast_bilinear"
            val p = ProcessBuilder(
                ffmpeg.path, "-v", "error", "-nostdin", "-y", "-threads", "2",
                "-i", path, "-frames:v", "1", "-vf", filter, "-q:v", "4", output.path,      // the first frame is a keyframe: only that one is decoded
            ).redirectErrorStream(true).start()
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); reason("ffmpeg_tiempo"); null }
            else if (output.exists()) BitmapFactory.decodeFile(output.path).also { if (it == null) reason("ffmpeg_jpg_malo") }
            else { reason("ffmpeg_no_output"); null }
        } catch (e: Exception) {
            reason("ffmpeg:" + e.javaClass.simpleName); null
        } finally {
            runCatching { output.delete() }
        }
    }

    /** Fallback: the system thumbnail (slower). */
    @Suppress("DEPRECATION")
    private fun system(ctx: Context, v: VideoItem, side: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(GalleryRepo.uriOf(v.id), Size(side, side), null)
        else MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    }.onFailure { reason("system:" + it.javaClass.simpleName) }.getOrNull().also { if (it == null) reason("system_null") }
}

// ───────────── Screen ─────────────

private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

@Composable
private fun Thumbnail(v: VideoItem, side: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val c = AppTheme.palette
    var failure by remember(v.id, side) { mutableStateOf(false) }
    val bmp by produceState<ImageBitmap?>(ThumbnailLoader.inMemory(v, side)?.asImageBitmap(), v.id, side) {
        if (value == null) {
            delay(60)    // if within 60 ms it already left the screen (fast scrolling), this coroutine is cancelled and nothing is decoded
            val b = ThumbnailLoader.load(ctx, v, side)
            value = b?.asImageBitmap()
            failure = b == null
        }
    }
    Box(modifier.background(c.surface2), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (failure && bmp == null) Icon(Icons.Filled.Movie, contentDescription = tx("No preview"), tint = c.textMuted)
    }
}

@Composable
fun RadioOption(
    text: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier.fillMaxWidth(), compact: Boolean = false, onPick: () -> Unit,
) {
    val c = AppTheme.palette
    Row(
        modifier.defaultMinSize(minHeight = if (compact) 34.dp else Dimens.touchMin).clickable(role = Role.RadioButton, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null,
            modifier = if (compact) Modifier.size(32.dp) else Modifier,
            colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.borderInput))
        Text(text, color = c.text, fontSize = if (compact) 14.sp else 16.sp, maxLines = 2,
            modifier = Modifier.padding(start = if (compact) Spacing.e1 else Spacing.e2).weight(1f))
        if (icon != null) Icon(icon, contentDescription = null, tint = if (selected) c.accent else c.textMuted,
            modifier = if (compact) Modifier.size(18.dp) else Modifier)
    }
}

/** Is there little room? Short screen (in dp, so the DPI counts): menus are tightened so they fit entirely. The app ignores the system font size. */
@Composable
fun isTightScreen(): Boolean {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    return cfg.screenHeightDp < 700
}

@Composable
fun GalleryScreen(onPick: (VideoItem) -> Unit, onBack: () -> Unit, onPicker: () -> Unit) {
    val c = AppTheme.palette
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("compresor", Context.MODE_PRIVATE) }
    var contains by remember { mutableStateOf(GalleryRepo.hasPermission(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        contains = GalleryRepo.hasPermission(ctx); denied = !contains
    }

    var viewMode by remember { mutableStateOf(runCatching { ViewMode.valueOf(prefs.getString("g_vista", "") ?: "") }.getOrDefault(ViewMode.GRID)) }
    var sortOrder by remember { mutableStateOf(runCatching { SortOrder.valueOf(prefs.getString("g_orden", "") ?: "") }.getOrDefault(SortOrder.APPEARANCE)) }
    var ascending by remember { mutableStateOf(prefs.getBoolean("g_asc", false)) }
    var menu by remember { mutableStateOf(false) }
    var videos by remember { mutableStateOf<List<VideoItem>?>(null) }
    // Size of each thumbnail = that of its cell in pixels (low resolution, but without looking bad).
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val sideGrid = (screen.screenWidthDp * density / 3).toInt().coerceIn(120, 360)
    val sideList = (52 * density).toInt().coerceIn(96, 200)

    var reload by remember { mutableIntStateOf(0) }
    MediaRefresh(GalleryRepo.collection()) { reload++ }
    LaunchedEffect(contains, reload) {
        if (contains) {
            videos = withContext(Dispatchers.IO) { GalleryRepo.listAll(ctx) }
            withContext(Dispatchers.IO) { ThumbnailLoader.prune(ctx) }
        }
    }
    fun save() {
        prefs.edit().putString("g_vista", viewMode.name).putString("g_orden", sortOrder.name).putBoolean("g_asc", ascending).apply()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(Dimens.topBar), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back to menu"), tint = c.text)
            }
            Text(tx("Videos"), color = c.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onPicker, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = tx("Search in the file explorer"), tint = c.text)
            }
            if (contains) IconButton(onClick = { menu = true }, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.Filled.FilterList, contentDescription = tx("View and order"), tint = c.text)
            }
        }

        val list = videos
        when {
            !contains -> Column(Modifier.fillMaxSize().padding(Spacing.e4), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tx("To show your videos I need access to them."), color = c.text, style = MaterialTheme.typography.titleMedium)
                VerticalGap(Spacing.e2)
                Text(tx("I only read the videos: no internet, nothing is uploaded."), color = c.textMuted)
                VerticalGap(Spacing.e4)
                Button(onClick = { requestPermission.launch(GalleryRepo.permissions()) },
                    modifier = Modifier.fillMaxWidth().height(Dimens.button), shape = RoundedCornerShape(Radius.pill),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                    Text(tx("Allow access to videos"), fontWeight = FontWeight.Bold)
                }
                if (denied) {
                    VerticalGap(Spacing.e2)
                    TextButton(onClick = onPicker, modifier = Modifier.defaultMinSize(minHeight = Dimens.touchMin)) {
                        Text(tx("Better to choose a video with the system picker"), color = c.accent)
                    }
                }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tx("Loading videos…"), color = c.textMuted) }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(Spacing.e4), contentAlignment = Alignment.Center) {
                Text(tx("There are no videos on this phone."), color = c.textMuted)
            }
            else -> {
                val sorted = remember(list, sortOrder, ascending) { sort(list, sortOrder, ascending) }
                if (viewMode == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(sorted, key = { it.id }) { v ->
                            Box(Modifier.aspectRatio(1f).clickable(onClickLabel = tx("Choose {0}", v.name)) { onPick(v) }) {
                                Thumbnail(v, sideGrid, Modifier.fillMaxSize())
                                Text(
                                    durationText(v.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(sorted, key = { it.id }) { v ->
                            Row(
                                Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
                                    .clickable(onClickLabel = tx("Choose {0}", v.name)) { onPick(v) }
                                    .padding(horizontal = Spacing.e4, vertical = Spacing.e2),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Thumbnail(v, sideList, Modifier.size(52.dp).clip(RoundedCornerShape(Radius.sm)))
                                Column(Modifier.weight(1f).padding(start = Spacing.e3)) {
                                    Text(v.name, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(DATE_FORMAT.format(Date(if (sortOrder == SortOrder.CREATION) v.created else if (sortOrder == SortOrder.APPEARANCE) v.added else v.modified)),
                                            color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                                        Text(formatSize(v.bytes), color = c.data, style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = Spacing.e3))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (menu) {
        val tight = isTightScreen()
        val gap = if (tight) Spacing.e1 else Spacing.e3
        val sub = if (tight) 13.sp else 14.sp
        AlertDialog(
            onDismissRequest = { menu = false },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(tx("View and order"), fontSize = if (tight) 18.sp else 22.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(tx("View"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = sub)
                    Row(Modifier.fillMaxWidth()) {
                        RadioOption(tx("Grid"), viewMode == ViewMode.GRID, modifier = Modifier.weight(1f), compact = tight) { viewMode = ViewMode.GRID; save() }
                        RadioOption(tx("List"), viewMode == ViewMode.LIST, modifier = Modifier.weight(1f), compact = tight) { viewMode = ViewMode.LIST; save() }
                    }
                    Text(tx("Sort by"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = sub, modifier = Modifier.padding(top = gap))
                    RadioOption(tx("Last seen"), sortOrder == SortOrder.APPEARANCE, compact = tight) { sortOrder = SortOrder.APPEARANCE; save() }
                    RadioOption(tx("Created"), sortOrder == SortOrder.CREATION, compact = tight) { sortOrder = SortOrder.CREATION; save() }
                    RadioOption(tx("Modified"), sortOrder == SortOrder.MODIFICATION, compact = tight) { sortOrder = SortOrder.MODIFICATION; save() }
                    RadioOption(tx("Name"), sortOrder == SortOrder.NAME, compact = tight) { sortOrder = SortOrder.NAME; save() }
                    Text(tx("Direction"), color = c.textMuted, fontWeight = FontWeight.SemiBold, fontSize = sub, modifier = Modifier.padding(top = gap))
                    Row(Modifier.fillMaxWidth()) {
                        RadioOption(tx("Descending"), !ascending, Icons.Filled.ArrowDownward, Modifier.weight(1f), tight) { ascending = false; save() }
                        RadioOption(tx("Ascending"), ascending, Icons.Filled.ArrowUpward, Modifier.weight(1f), tight) { ascending = true; save() }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menu = false }) { Text(tx("Done"), color = c.accent) } },
        )
    }
}
