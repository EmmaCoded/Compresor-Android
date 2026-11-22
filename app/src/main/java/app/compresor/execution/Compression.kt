package app.compresor.execution

import app.compresor.tx

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.compresor.engine.VideoSettings
import app.compresor.engine.flacPictureBlockTest
import app.compresor.engine.audioCommand
import app.compresor.engine.extractCoverCommand
import app.compresor.engine.ffmetadataCover
import app.compresor.engine.coverAsTag
import app.compresor.engine.cutCommand
import app.compresor.engine.cropCommand
import app.compresor.engine.cutOutputExtension
import app.compresor.engine.InfoVideo
import app.compresor.engine.Mode
import app.compresor.engine.Track
import app.compresor.engine.Plan
import app.compresor.engine.passesCommand
import java.io.File
import kotlin.math.floor
import kotlin.math.max

/** Pauses (SIGSTOP/SIGCONT) and stops (q, then terminate) the running ffmpeg. */
class ProcessControl {
    @Volatile var process: Process? = null
    @Volatile var stopped = false
    @Volatile var paused = false

    /** The pid comes from the process `pid` field, or from the text "Process[pid=1234, ...]" if the field is not there. */
    private fun pid(p: Process): Int? =
        runCatching { p.javaClass.getDeclaredField("pid").let { it.isAccessible = true; it.getInt(p) } }.getOrNull()
            ?: Regex("pid=(\\d+)").find(p.toString())?.groupValues?.get(1)?.toIntOrNull()

    fun pauseJob() {
        val p = process ?: return
        pid(p)?.let { android.os.Process.sendSignal(it, 19); paused = true }     // SIGSTOP
    }

    fun resume() {
        val p = process ?: return
        pid(p)?.let { android.os.Process.sendSignal(it, 18); paused = false }    // SIGCONT
    }

    fun stop() {
        stopped = true
        val p = process ?: return
        if (paused) resume()
        runCatching { p.outputStream.write("q".toByteArray()); p.outputStream.flush() }
        Thread {
            runCatching {
                Thread.sleep(4000); if (p.isAlive) p.destroy()
                Thread.sleep(3000); if (p.isAlive) p.destroyForcibly()
            }
        }.start()
    }
}

data class Progress(
    val pct: Float = 0f, val sec: Double = 0.0, val bytes: Long = 0, val speed: Double = 0.0,
    val pass: Int = 1, val passes: Int = 1, val sizeRetry: Int = 0, val plan: Plan = Plan.NORMAL,
)

enum class Ending { OK, STOPPED, FAILURE }

data class Outcome(
    val end: Ending, val output: File? = null, val originalSize: Long = 0, val outputSize: Long = 0,
    val plan: Plan = Plan.NORMAL, val sizeRetries: Int = 0, val exceedsTarget: Boolean = false,
    val outputInfo: InfoVideo? = null, val error: String = "", val seconds: Double = 0.0,
)

/** What was saved: text to show, address (to be able to replace it) and name without extension. */
data class Saved(val text: String, val uri: Uri?, val name: String)

object Compression {

    /** Deletes a file that THIS APP saved (to replace it with a new one). Never called with original videos. */
    fun deleteSavedFile(ctx: Context, g: Saved) {
        val u = g.uri ?: return
        runCatching {
            when {
                u.scheme == "file" -> File(u.path ?: return).delete()
                DocumentsContract.isDocumentUri(ctx, u) -> DocumentsContract.deleteDocument(ctx.contentResolver, u)
                else -> ctx.contentResolver.delete(u, null, null)
            }
        }
    }

    private fun withProgress(c: List<String>) =
        listOf(c[0], "-hide_banner", "-nostats", "-loglevel", "error", "-progress", "pipe:1") + c.drop(1)

    /** One ffmpeg run reading `-progress pipe:1`. Returns the exit code. */
    private fun run(
        cmd: List<String>, dur: Double, ctrl: ProcessControl, pass: Int, passes: Int, retry: Int, plan: Plan,
        errors: MutableList<String>, onProgress: (Progress) -> Unit,
    ): Int {
        val p = ProcessBuilder(withProgress(cmd)).start()
        ctrl.process = p
        val reader = Thread {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { l ->
                    synchronized(errors) { errors.add(l); if (errors.size > 40) errors.removeAt(0) }
                }
            }
        }.also { it.start() }
        var sec = 0.0; var bytes = 0L; var speed = 0.0
        runCatching {
            p.inputStream.bufferedReader().forEachLine { l ->
                val i = l.indexOf('=')
                if (i > 0) {
                    val v = l.substring(i + 1).trim()
                    when (l.substring(0, i)) {
                        "out_time_us", "out_time_ms" -> v.toLongOrNull()?.let { sec = it / 1_000_000.0 }
                        "total_size" -> v.toLongOrNull()?.let { bytes = it }
                        "speed" -> speed = v.removeSuffix("x").trim().toDoubleOrNull() ?: speed
                        "progress" -> onProgress(
                            Progress(
                                if (dur > 0) (sec / dur).toFloat().coerceIn(0f, 1f) else 0f, sec, bytes, speed,
                                pass, passes, retry, plan,
                            )
                        )
                    }
                }
            }
        }
        val rc = p.waitFor()
        reader.join(1000)
        ctrl.process = null
        return rc
    }

    /**
     * Compresses `input` with `settings`. Retry ladder of the .py: normal -> safe -> minimum
     * only if the encoder crashes (signal) or runs out of memory; a parameter error is not retried.
     * In By size: if the file weighs more than requested, it is redone with 10 % less bitrate (up to 3 times).
     */
    fun compressVideo(
        ctx: Context, inputFile: File, settings: VideoSettings, info: InfoVideo, tracks: List<Track>,
        ctrl: ProcessControl, onProgress: (Progress) -> Unit,
    ): Outcome {
        val t0 = System.currentTimeMillis()
        val libDir = ctx.applicationInfo.nativeLibraryDir
        val ffmpeg = File(libDir, "libffmpeg.so").path
        val encAv1 = if (libDir.contains("64")) "libsvtav1" else "libaom-av1"
        val stamp = System.currentTimeMillis()
        val output = File(ctx.cacheDir, "output_$stamp${settings.container.ext}")
        val passlog = File(ctx.cacheDir, "pl_$stamp").path
        val errors = mutableListOf<String>()
        val target = (settings.sizeMb * 1_048_576.0).toLong()
        var plan = Plan.NORMAL
        try {
            var factor = 1.0
            var retry = 0
            while (true) {
                val limited = if (settings.mode == Mode.SIZE) settings.copy(sizeMb = max(0.05, settings.sizeMb * factor)) else settings
                var ok = false
                for (p in Plan.entries) {
                    plan = p
                    if (output.exists()) output.delete()
                    val cmds = passesCommand(ffmpeg, inputFile.path, output.path, limited, info, tracks, passlog, p, encAv1)
                    var rc = 0
                    for ((i, c) in cmds.withIndex()) {
                        if (ctrl.stopped) break                 // if a stop was requested during the previous pass, another one is not started
                        rc = run(c, info.duration, ctrl, i + 1, cmds.size, retry, p, errors, onProgress)
                        if (rc != 0 || ctrl.stopped) break
                    }
                    if (ctrl.stopped) {
                        output.delete()
                        return Outcome(Ending.STOPPED, originalSize = inputFile.length(), seconds = sec(t0))
                    }
                    if (rc == 0 && output.exists() && output.length() > 0) { ok = true; break }
                    val signal = rc >= 128 || rc < 0          // the process died from a signal (crash or out of memory)
                    if (!signal) break                        // parameter error: retrying does not fix it
                }
                if (!ok) {
                    output.delete()
                    val last = synchronized(errors) { errors.takeLast(4).joinToString("\n") }
                    return Outcome(Ending.FAILURE, originalSize = inputFile.length(), plan = plan, error = last, seconds = sec(t0))
                }
                val weighs = output.length()
                if (settings.mode != Mode.SIZE || weighs <= target || retry >= 3) {
                    return Outcome(
                        Ending.OK, output, inputFile.length(), weighs, plan, retry,
                        exceedsTarget = settings.mode == Mode.SIZE && weighs > target,
                        outputInfo = Probe.probe(ctx, output)?.first, seconds = sec(t0),
                    )
                }
                factor *= 0.9
                retry++
            }
        } finally {
            File(ctx.cacheDir.path).listFiles { f -> f.name.startsWith("pl_$stamp") }?.forEach { it.delete() }
        }
    }

    private fun sec(t0: Long) = (System.currentTimeMillis() - t0) / 1000.0

    /** Cut or crop: a single ffmpeg run (there are no parameters to lower, so there is no retry ladder). */
    fun editFile(
        ctx: Context, inputFile: File, name: String, task: Task, info: InfoVideo, tracks: List<Track>,
        ctrl: ProcessControl, onProgress: (Progress) -> Unit,
    ): Outcome {
        val t0 = System.currentTimeMillis()
        val ffmpeg = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so").path
        val ext: String
        val duration: Double
        when (task) {
            is Task.Cut -> { ext = cutOutputExtension(name, task.cutRange.precise); duration = (task.cutRange.endMs - task.cutRange.startMs) / 1000.0 }
            is Task.Crop -> { ext = ".mp4"; duration = info.duration }
            is Task.Audio -> { ext = "." + task.settings.output.ext; duration = task.info.duration }
            is Task.Compress -> return Outcome(Ending.FAILURE, error = "Invalid task")
        }
        val output = File(ctx.cacheDir, "output_${System.currentTimeMillis()}$ext")
        val tempFiles = mutableListOf<File>()
        val cmd = when (task) {
            is Task.Cut -> cutCommand(ffmpeg, inputFile.path, output.path, task.cutRange)
            is Task.Crop -> cropCommand(ffmpeg, inputFile.path, output.path, task.cropArea, info.width, info.height, tracks)
            is Task.Audio -> audioCommandOf(ctx, ffmpeg, inputFile, output, task, tempFiles)
            is Task.Compress -> emptyList()
        }
        val errors = mutableListOf<String>()
        val rc = run(cmd, duration, ctrl, 1, 1, 0, Plan.NORMAL, errors, onProgress)
        tempFiles.forEach { it.delete() }
        if (ctrl.stopped) {
            output.delete()
            return Outcome(Ending.STOPPED, originalSize = inputFile.length(), seconds = sec(t0))
        }
        if (rc == 0 && output.exists() && output.length() > 0) {
            return Outcome(
                Ending.OK, output, inputFile.length(), output.length(),
                outputInfo = Probe.probe(ctx, output)?.first, seconds = sec(t0),
            )
        }
        output.delete()
        val last = synchronized(errors) { errors.takeLast(4).joinToString("\n") }
        return Outcome(Ending.FAILURE, originalSize = inputFile.length(), error = last, seconds = sec(t0))
    }

    /** Audio command. For Opus/OGG the cover art is extracted to a JPEG and put in as a tag (temporary "pl_*" files, deleted when done). */
    private fun audioCommandOf(ctx: Context, ffmpeg: String, inputFile: File, output: File, t: Task.Audio, temp: MutableList<File>): List<String> {
        val a = t.settings
        var meta: String? = null
        if (t.info.cover && !a.removeMetadata && coverAsTag(a.output)) runCatching {
            val jpg = File(ctx.cacheDir, "pl_portada_${System.currentTimeMillis()}.jpg").also { temp += it }
            val extractor = ProcessBuilder(extractCoverCommand(ffmpeg, inputFile.path, jpg.path)).redirectErrorStream(true).start()
            extractor.inputStream.readBytes(); extractor.waitFor()
            if (jpg.length() > 0) {
                val bytes = jpg.readBytes()
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                val b64 = android.util.Base64.encodeToString(flacPictureBlockTest(bytes, maxOf(0, o.outWidth), maxOf(0, o.outHeight)), android.util.Base64.NO_WRAP)
                val metaFile = File(ctx.cacheDir, "pl_meta_${System.currentTimeMillis()}.txt").also { temp += it }
                metaFile.writeText(ffmetadataCover(b64))
                meta = metaFile.path
            }
        }
        return audioCommand(ffmpeg, inputFile.path, output.path, a, t.info, meta)
    }

    private const val FOLDER = "Compresor_CC"

    private fun mimeOf(ext: String) = when (ext) {
        "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "mp4", "m4v" -> "video/mp4"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; else -> "application/octet-stream"
    }

    /** Is there already a file with that name (with extension) in the app folder? Videos: DCIM/Compresor_CC. Audio: Music/Compresor_CC. */
    /** Readable name of a chosen folder (for example "Movies/My cuts"). */
    fun folderText(uri: String): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
        id.substringAfter(':', id).ifEmpty { tx("Internal storage") }
    }.getOrDefault(uri)

    private fun childrenOf(ctx: Context, tree: Uri): List<String> {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val res = ArrayList<String>()
        ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) res += c.getString(0).orEmpty()
        }
        return res
    }

    fun exists(ctx: Context, name: String, audio: Boolean, folder: String? = null): Boolean {
        if (folder != null) { val r = runCatching { name in childrenOf(ctx, Uri.parse(folder)) }; if (r.isSuccess) return r.getOrThrow() }
        return existsInDefaultFolder(ctx, name, audio)
    }

    private fun existsInDefaultFolder(ctx: Context, name: String, audio: Boolean): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val col = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val rel = (if (audio) "Music/" else "DCIM/") + FOLDER + "/"
            ctx.contentResolver.query(col, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(name, rel), null)
                ?.use { it.count > 0 } ?: false
        } else {
            File(File(Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DCIM), FOLDER), name).exists()
        }
    }.getOrDefault(false)

    /** Copies the result to the gallery: videos to DCIM/Compresor_CC, audio to Music/Compresor_CC. `name` has no extension. Returns where it ended up. */
    fun saveInGallery(ctx: Context, file: File, name: String, audio: Boolean = false, folder: String? = null): Saved? {
        if (folder != null) {
            val ok = runCatching {
                val ext = file.extension.lowercase().ifEmpty { if (audio) "mp3" else "mp4" }
                val tree = Uri.parse(folder)
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(ctx.contentResolver, root, mimeOf(ext), "$name.$ext") ?: error("no document")
                ctx.contentResolver.openOutputStream(doc)!!.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
                Saved(folderText(folder) + "/$name.$ext", doc, name)
            }
            if (ok.isSuccess) return ok.getOrThrow()
            // the chosen folder is no longer usable (permission lost or deleted): it is saved in the default one
        }
        return saveInDefaultFolder(ctx, file, name, audio)
    }

    private fun saveInDefaultFolder(ctx: Context, file: File, name: String, audio: Boolean): Saved? = runCatching {
        val ext = file.extension.lowercase().ifEmpty { if (audio) "mp3" else "mp4" }
        val full = "$name.$ext"
        val mime = mimeOf(ext)
        val folder = (if (audio) "Music/" else "DCIM/") + FOLDER
        if (Build.VERSION.SDK_INT >= 29) {
            val col = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, full)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = ctx.contentResolver.insert(col, v) ?: error("no uri")
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Saved("$folder/$full", uri, name)
        } else {
            // Android 9 or lower: public folder (needs the write permission, which is asked when saving).
            val dir = File(Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DCIM), FOLDER)
            dir.mkdirs()
            val destination = File(dir, full)
            file.copyTo(destination, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(destination.path), arrayOf(mime), null)
            Saved("$folder/$full", Uri.fromFile(destination), name)
        }
    }.getOrNull()
}
