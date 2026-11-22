package app.compresor.ui

import app.compresor.tx

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.compresor.engine.VideoSettings
import app.compresor.engine.Channels
import app.compresor.engine.Codec
import app.compresor.engine.Container
import app.compresor.engine.Control
import app.compresor.engine.Mode
import app.compresor.engine.ResolutionOption
import app.compresor.engine.Track
import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import app.compresor.engine.AUDIO_OPUS_DEF
import app.compresor.engine.AudioMode
import app.compresor.engine.audioForSize
import app.compresor.engine.bitrateForSize
import app.compresor.engine.passesCommand
import app.compresor.engine.resolutionOptions
import app.compresor.engine.optionByDefault
import app.compresor.ui.theme.Spacing
import app.compresor.ui.theme.ThemeMode
import app.compresor.ui.theme.Dimens
import app.compresor.ui.theme.Radius
import app.compresor.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

// ───────────── Home ─────────────

@Composable
private fun ToolCard(title: String, text: String, onTouch: () -> Unit) {
    val c = AppTheme.palette
    Column(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = Dimens.row)
            .background(c.surface, RoundedCornerShape(Radius.lg))
            .border(1.dp, c.border, RoundedCornerShape(Radius.lg))
            .clickable(onClickLabel = title, onClick = onTouch)
            .padding(Spacing.e4),
    ) {
        Text(title, color = c.accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(text, color = c.textMuted)
    }
}

@Composable
fun HomeScreen(onCompress: () -> Unit, onCut: () -> Unit, onCrop: () -> Unit, onAudio: () -> Unit, onOptions: () -> Unit) {
    val c = AppTheme.palette
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().padding(Spacing.e4).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOptions, modifier = Modifier.defaultMinSize(minWidth = Dimens.touchMin, minHeight = Dimens.touchMin)) {
                Icon(Icons.Filled.Settings, contentDescription = tx("Options"), tint = c.accent)
            }
            Text("Compresor", color = c.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = Spacing.e2))
        }
        Text(tx("Your videos and audio, offline."), color = c.textMuted)
        VerticalGap(Spacing.e5)
        ToolCard(tx("Compress"), tx("Pick a video and decide how small to make it."), onCompress)
        VerticalGap(Spacing.e3)
        ToolCard(tx("Cut video"), tx("Keep only one section of the video. You will see the length of the section."), onCut)
        VerticalGap(Spacing.e3)
        ToolCard(tx("Crop video"), tx("Crop part of the picture and rotate the video. You will see the size in pixels of what is left."), onCrop)
        VerticalGap(Spacing.e3)
        ToolCard(tx("Compress or convert audio"), tx("From almost any format to MP3, Opus, OGG or AAC, at the size you want."), onAudio)
    }
}

@Composable
private fun CheckboxRow(text: String, help: String, marked: Boolean, onChangeSettings: (Boolean) -> Unit) {
    val c = AppTheme.palette
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Dimens.row).clickable { onChangeSettings(!marked) }.padding(vertical = Spacing.e2),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(marked, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
        Column(Modifier.padding(start = Spacing.e2)) {
            Text(text, color = c.text)
            Text(help, color = c.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = AppTheme.palette.textMuted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Spacing.e4, bottom = Spacing.e1))
}

@Composable
private fun FolderRow(title: String, default: String, selected: String?, onPick: () -> Unit, onReset: () -> Unit) {
    val c = AppTheme.palette
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.e1)) {
        Text(title, color = c.text)
        Text(if (selected != null) app.compresor.execution.Compression.folderText(selected) else tx("{0} (default)", default),
            color = c.data, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = onPick, modifier = Modifier.defaultMinSize(minHeight = Dimens.touchMin)) { Text(tx("Choose folder"), color = c.accent) }
            if (selected != null) TextButton(onClick = onReset, modifier = Modifier.defaultMinSize(minHeight = Dimens.touchMin)) { Text(tx("Back to the default one"), color = c.textMuted) }
        }
    }
}

/** Options of the gear icon. */
@Composable
fun OptionsScreen(onBack: () -> Unit) {
    val c = AppTheme.palette
    val ctx = LocalContext.current
    val tight = isTightScreen()
    fun selected(uri: android.net.Uri?, put: (String?) -> Unit) {
        if (uri == null) return
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        put(uri.toString())
    }
    val pickVideo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { selected(it) { s -> Config.updateVideoFolder(s) } }
    val pickAudio = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { selected(it) { s -> Config.updateAudioFolder(s) } }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(Spacing.e4).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(minWidth = Dimens.touchMin, minHeight = Dimens.touchMin)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back"), tint = c.text)
            }
            Text(tx("Options"), color = c.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = Spacing.e2))
        }
        SectionTitle(tx("General"))
        CheckboxRow(tx("Keep screen on"), tx("While compressing, cutting or converting."),
            Config.screenOnEnabled) { Config.updateScreenOnEnabled(it) }
        CheckboxRow(tx("Save without asking"), tx("When compression or conversion ends, it saves right away without asking for the name."),
            Config.skipName) { Config.updateSkipName(it) }

        SectionTitle(tx("When compressing"))
        Text(tx("Video codec"), color = c.text)
        Row(Modifier.fillMaxWidth()) {
            RadioOption("x264 (H.264)", Config.videoCodec == Codec.X264, modifier = Modifier.weight(1f), compact = tight) { Config.updateVideoCodec(Codec.X264) }
            RadioOption("AV1", Config.videoCodec == Codec.AV1, modifier = Modifier.weight(1f), compact = tight) { Config.updateVideoCodec(Codec.AV1) }
        }
        Text(tx("Audio codec"), color = c.text, modifier = Modifier.padding(top = Spacing.e1))
        Row(Modifier.fillMaxWidth()) {
            AUDIO_NAMES.forEachIndexed { i, n -> RadioOption(n, Config.defaultAudio == i, modifier = Modifier.weight(1f), compact = tight) { Config.updateDefaultAudio(i) } }
        }
        Text(tx("This is the one that comes pre-selected when compressing a video."), color = c.textMuted, style = MaterialTheme.typography.bodySmall)

        SectionTitle(tx("Theme"))
        Row(Modifier.fillMaxWidth()) {
            RadioOption(tx("System"), Config.theme == ThemeMode.SYSTEM, modifier = Modifier.weight(1f), compact = tight) { Config.updateTheme(ThemeMode.SYSTEM) }
            RadioOption(tx("Light"), Config.theme == ThemeMode.LIGHT, modifier = Modifier.weight(1f), compact = tight) { Config.updateTheme(ThemeMode.LIGHT) }
            RadioOption(tx("Dark"), Config.theme == ThemeMode.DARK, modifier = Modifier.weight(1f), compact = tight) { Config.updateTheme(ThemeMode.DARK) }
        }

        SectionTitle(tx("Language"))
        Row(Modifier.fillMaxWidth()) {
            RadioOption(tx("System"), app.compresor.Language.mode == app.compresor.Language.SYSTEM, modifier = Modifier.weight(1f), compact = tight) { Config.updateLanguage(app.compresor.Language.SYSTEM) }
            RadioOption("Español", app.compresor.Language.mode == app.compresor.Language.SPANISH_CODE, modifier = Modifier.weight(1f), compact = tight) { Config.updateLanguage(app.compresor.Language.SPANISH_CODE) }
            RadioOption("English", app.compresor.Language.mode == app.compresor.Language.ENGLISH_CODE, modifier = Modifier.weight(1f), compact = tight) { Config.updateLanguage(app.compresor.Language.ENGLISH_CODE) }
        }

        SectionTitle(tx("Output folders"))
        FolderRow(tx("Videos"), "DCIM/Compresor_CC", Config.videoFolder, { pickVideo.launch(null) }, { Config.updateVideoFolder(null) })
        FolderRow(tx("Audio files"), "Music/Compresor_CC", Config.audioFolder, { pickAudio.launch(null) }, { Config.updateAudioFolder(null) })

        SectionTitle(tx("About"))
        Column(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(Radius.md)).border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(Spacing.e3)) {
            Text("Compresor", color = c.text, fontWeight = FontWeight.Bold)
            Text(tx("Version {0}", version), color = c.textMuted)
            Text(tx("Creator: Emma_Cc"), color = c.textMuted)
        }
    }
}

// ───────────── Video settings (3 tabs) ─────────────

private val PRESETS_X264 = listOf("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow")
private val FPS_OPTIONS = listOf(10.0, 12.0, 15.0, 20.0, 23.976, 24.0, 25.0, 29.97, 30.0, 48.0, 50.0, 59.94, 60.0)

private fun containerOf(c: Codec) = if (c == Codec.AV1) Container.WEBM else Container.MP4

/** Audio type of the list: 0 = Opus mono, 1 = Opus stereo, 2 = the original. */
private fun audioOf(i: Int): Pair<AudioMode, Channels> = when (i) {
    1 -> AudioMode.OPUS to Channels.STEREO
    2 -> AudioMode.ORIGINAL to Channels.ORIGINAL
    else -> AudioMode.OPUS to Channels.MONO
}
private val AUDIO_NAMES get() = listOf(tx("Opus mono"), tx("Opus stereo"), tx("Original"))
private fun mb(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
/** Goes up to 0.1 MB and never below 0.1: the default target of the list (allows decimals). */
private fun roundedMb(v: Double) = max(0.1, ceil(v * 10) / 10)
private fun textMb(v: Double) = if (v == kotlin.math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
private fun readMb(s: String): Double? = s.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

private fun explanation(c: Codec, is32: Boolean) = when (c) {
    Codec.X264 -> tx("x264 (H.264): the classic one. It plays on almost any phone, TV or app and compresses fast, but the files come out larger.")
    Codec.AV1 -> tx("AV1: maximum compression. Much smaller files at the same quality, but it can take a very long time, the phone gets warm and uses battery. Best with the charger plugged in.") +
        (if (is32) tx(" On a 32-bit phone it is even slower.") else "") + tx(" It is saved as WebM.")
}

@Composable
private fun NumberField(tag: String, value: String, onChangeSettings: (String) -> Unit, enabled: Boolean = true, decimal: Boolean = false) {
    val c = AppTheme.palette
    OutlinedTextField(
        value = value, onValueChange = { onChangeSettings(it.filter { ch -> ch.isDigit() || (decimal && (ch == '.' || ch == ',')) }.take(7)) },
        label = { Text(tag) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.e2),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text, unfocusedTextColor = c.text,
            focusedBorderColor = c.accent, unfocusedBorderColor = c.borderInput,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.textMuted, cursorColor = c.accent,
        ),
    )
}

@Composable
private fun NumberSlider(tag: String, value: Int, min: Int, max: Int, help: String, onChangeSettings: (Int) -> Unit) {
    val c = AppTheme.palette
    Column(Modifier.fillMaxWidth().padding(top = Spacing.e2)) {
        Row {
            Text(tag, color = c.textMuted, modifier = Modifier.weight(1f))
            Text("$value", color = c.data, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChangeSettings(it.toInt()) }, valueRange = min.toFloat()..max.toFloat(),
            steps = 0, modifier = Modifier.defaultMinSize(minHeight = Dimens.touchMin),
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface2),
        )
        Text(help, color = c.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CodecPicker(codec: Codec, is32: Boolean, onChangeSettings: (Codec) -> Unit, onHelp: (Codec) -> Unit) {
    val c = AppTheme.palette
    Heading(tx("Codec"))
    Segmented(
        listOf("x264", "AV1"), if (codec == Codec.X264) 0 else 1,
        onPick = { onChangeSettings(if (it == 0) Codec.X264 else Codec.AV1) },
        onKeep = { onHelp(if (it == 0) Codec.X264 else Codec.AV1) },
    )
    VerticalGap(Spacing.e2)
    Text(explanation(codec, is32), color = if (codec == Codec.AV1) c.warning else c.textMuted, style = MaterialTheme.typography.bodySmall)
    Text(tx("Press and hold a button to read what each one is."), color = c.textMuted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = Spacing.e1))
    VerticalGap(Spacing.e4)
}

@Composable
fun SettingsScreen(
    video: ChosenVideo, initialCodec: Codec, onChangeCodec: (Codec) -> Unit, onBack: () -> Unit,
    onCompress: (VideoSettings) -> Unit = {},
) {
    val c = AppTheme.palette
    val ctx = LocalContext.current
    val info = video.info
    val is32 = remember { ctx.applicationInfo.nativeLibraryDir.contains("64").not() }
    var tab by remember { mutableIntStateOf(0) }
    val prefs = remember { ctx.getSharedPreferences("compresor", Context.MODE_PRIVATE) }
    val savedBefore = remember { prefs.getBoolean("t_guardar", false) }
    val baseCodec = if (savedBefore && prefs.getString("t_codec", "") == "av1") Codec.AV1
        else if (savedBefore) Codec.X264 else initialCodec
    var limited by remember {
        val (am, ca) = audioOf(if (savedBefore) prefs.getInt("t_audio", 0) else Config.defaultAudio)
        mutableStateOf(
            VideoSettings(
                codec = baseCodec, container = containerOf(baseCodec), twoPasses = true,
                fps = if (savedBefore) prefs.getFloat("t_fps", 0f).toDouble() else 0.0, audio = am, channels = ca,
            )
        )
    }
    // "Custom" panel of the Size tab
    var custom by remember { mutableStateOf(false) }
    var customShortSide by remember { mutableIntStateOf(-1) }
    var mbCustom by remember { mutableStateOf<String?>(null) }
    var customFps by remember { mutableStateOf(if (savedBefore) prefs.getFloat("t_fps", 0f).toDouble() else 0.0) }
    var customCodec by remember { mutableStateOf(baseCodec) }
    var customAudio by remember { mutableIntStateOf(if (savedBefore) prefs.getInt("t_audio", 0) else Config.defaultAudio) }
    var customTwoPass by remember { mutableStateOf(true) }
    var save by remember { mutableStateOf(savedBefore) }
    var sideSel by remember { mutableIntStateOf(-1) }
    var qualitySide by remember { mutableIntStateOf(-1) }  // Quality and Advanced: the ORIGINAL resolution by default
    var editedMb by remember { mutableStateOf<String?>(null) }
    var bitrateText by remember { mutableStateOf("1500") }
    var gopText by remember { mutableStateOf("240") }
    var threadsText by remember { mutableStateOf("0") }
    var help by remember { mutableStateOf<Codec?>(null) }
    var commandPreview by remember { mutableStateOf<String?>(null) }

    val options = remember(info, limited.codec) { resolutionOptions(info, limited.codec) }
    val sel: ResolutionOption? = options.firstOrNull { it.side == sideSel } ?: optionByDefault(options)
    val qualitySelection: ResolutionOption? = options.firstOrNull { it.side == qualitySide } ?: options.firstOrNull { it.original } ?: sel
    val selUse = if (tab == 0) sel else qualitySelection
    val mbAuto = sel?.let { roundedMb(it.approxMb) } ?: 10.0
    val mbLabel = editedMb ?: textMb(mbAuto)

    val customOptions = remember(info, customCodec) { resolutionOptions(info, customCodec) }
    val customSelection = customOptions.firstOrNull { it.side == customShortSide } ?: optionByDefault(customOptions)
    val mbCustomAuto = customSelection?.let { roundedMb(it.approxMb) } ?: 10.0
    val customMbText = mbCustom ?: textMb(mbCustomAuto)
    val customMb = readMb(customMbText) ?: mbCustomAuto
    val customKbps = bitrateForSize(customMb, info.duration, if (customAudio == 2) 128 else audioForSize(customMb, info.duration, AUDIO_OPUS_DEF))
    val customBitrateTooLow = customKbps != null && customSelection != null &&
        customKbps * 1000.0 / (customSelection.width * customSelection.height * (if (info.fps > 0) kotlin.math.min(info.fps, 60.0) else 30.0)) <
        (if (customCodec == Codec.AV1) 0.006 else 0.02)

    // "Save FPS, audio and codec for all Size presets"
    LaunchedEffect(save, customFps, customCodec, customAudio) {
        prefs.edit().putBoolean("t_guardar", save).apply()
        if (save) {
            prefs.edit().putFloat("t_fps", customFps.toFloat()).putInt("t_audio", customAudio)
                .putString("t_codec", if (customCodec == Codec.AV1) "av1" else "x264").apply()
            val (am, ca) = audioOf(customAudio)
            limited = limited.copy(codec = customCodec, container = containerOf(customCodec), fps = customFps, audio = am, channels = ca)
            editedMb = null
            onChangeCodec(customCodec)
        }
    }

    fun changeCodec(new: Codec) {
        limited = limited.copy(codec = new, container = containerOf(new))
        editedMb = null
        onChangeCodec(new)
    }

    fun finalSettings(): VideoSettings {
        if (tab == 0 && custom) {
            val (am, ca) = audioOf(customAudio)
            return limited.copy(
                mode = Mode.SIZE, codec = customCodec, container = containerOf(customCodec),
                width = customSelection?.width ?: 0, height = customSelection?.height ?: 0, sizeMb = customMb,
                fps = customFps, audio = am, channels = ca, twoPasses = customTwoPass,
            )
        }
        val base = limited.copy(
            twoPasses = if (tab == 0) true else limited.twoPasses,
            mode = Mode.entries[tab],
            width = selUse?.width ?: 0, height = selUse?.height ?: 0,
            sizeMb = readMb(mbLabel) ?: mbAuto,
            bitrateKbps = bitrateText.toIntOrNull() ?: 0,
            gop = gopText.toIntOrNull()?.coerceAtLeast(1) ?: 240,
            threads = threadsText.toIntOrNull() ?: 0,
        )
        return base
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(Dimens.topBar), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(Dimens.touchMin, Dimens.touchMin)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back"), tint = c.text)
            }
            Text(tx("Compress"), color = c.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
        }
        // Everything (video, data, tabs and options) scrolls together: on small screens the list is not left without space.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        // The video takes ~25 % of the screen height (between 110 and 220): on small screens it leaves room for the options.
        val videoHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.25f).dp
            .coerceIn(110.dp, 220.dp)
        ViewPreview(video.uri, Modifier.fillMaxWidth().height(videoHeight))
        Text(
            "${video.name} · ${info.width}x${info.height} · ${info.fps.toInt()} fps · ${info.duration.toInt()} s" +
                (if (info.sizeBytes > 0) " · ${mb(info.sizeBytes / 1048576.0)} MB" else ""),
            color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2,
            modifier = Modifier.padding(horizontal = Spacing.e4, vertical = Spacing.e2),
        )
        TabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.accent) {
            listOf(tx("Size"), tx("Quality"), tx("Advanced")).forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, selectedContentColor = c.accent, unselectedContentColor = c.textMuted,
                    text = { Text(t, maxLines = 1, fontWeight = FontWeight.SemiBold) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.e4)) {
            when (tab) {
                0 -> {
                    Heading(tx("Output resolution"))
                    if (options.isEmpty()) Text(tx("Could not read the video length, so there is no estimate."), color = c.warning)
                    OptionRow(
                        picked = custom, title = tx("Custom"), detail = tx("You set the size in MB"),
                        right = if (custom && customKbps != null) "≈ $customKbps kbps" else "", right2 = "",
                        onPick = { custom = true },
                    )
                    if (custom) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = Spacing.e2)
                                .background(c.surface, RoundedCornerShape(Radius.md))
                                .border(1.dp, c.border, RoundedCornerShape(Radius.md)).padding(Spacing.e3),
                        ) {
                            Dropdown(
                                tx("Resolution"),
                                customSelection?.let { (if (it.original) tx("Original · ") else "") + "${it.side}p · ${it.width}x${it.height}" } ?: "-",
                                customOptions.map { (if (it.original) tx("Original · ") else "") + "${it.side}p" to it.side },
                                { customShortSide = it; mbCustom = null },
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.e3)) {
                                Box(Modifier.weight(1f)) { NumberField(tx("Size (MB)"), customMbText, { mbCustom = it }, decimal = true) }
                                Box(Modifier.weight(1f)) {
                                    Dropdown(
                                        tx("FPS"), if (customFps <= 0) tx("Same") else "${customFps.toInt()}",
                                        listOf(tx("Same") to 0.0) + FPS_OPTIONS.filter { it < info.fps - 0.01 }.map { "${it.toInt()}" to it },
                                        { customFps = it },
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.e3)) {
                                Box(Modifier.weight(1f)) {
                                    Dropdown(
                                        tx("Codec"), if (customCodec == Codec.AV1) "AV1" else "x264",
                                        listOf("x264" to Codec.X264, "AV1" to Codec.AV1), { customCodec = it; mbCustom = null },
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    Dropdown(tx("Audio"), AUDIO_NAMES[customAudio], AUDIO_NAMES.mapIndexed { i, n -> n to i }, { customAudio = it })
                                }
                            }
                            VerticalGap(Spacing.e3)
                            Text(
                                if (customKbps == null) tx("Impossible: the audio alone no longer fits in that size.") else tx("≈ {0} kbps of video", customKbps),
                                color = if (customKbps == null) c.danger else c.data, fontWeight = FontWeight.SemiBold,
                            )
                            if (customBitrateTooLow) Text(tx("Bitrate too low for that resolution: lower the resolution or raise the MB."), color = c.warning,
                                style = MaterialTheme.typography.bodySmall)
                            if (customCodec == Codec.AV1) Text(tx("AV1 uses 1 pass, so the final size may vary a little."), color = c.textMuted,
                                style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth().clickable(enabled = customCodec == Codec.X264) { customTwoPass = !customTwoPass },
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(customTwoPass && customCodec == Codec.X264, null, enabled = customCodec == Codec.X264,
                                    colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
                                Text(tx("2 passes (x264 only)"), color = c.text, modifier = Modifier.padding(start = Spacing.e2))
                            }
                            Row(Modifier.fillMaxWidth().clickable { save = !save }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(save, null,
                                    colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
                                Text(tx("Save FPS, audio and codec for all Size presets"), color = c.text,
                                    modifier = Modifier.padding(start = Spacing.e2))
                            }
                        }
                    }
                    options.forEach { o ->
                        OptionRow(
                            picked = !custom && o.side == sel?.side,
                            title = if (o.original) tx("Original · {0}p", o.side) else "${o.side}p",
                            detail = "${o.width}x${o.height}",
                            right = "≈ ${mb(o.approxMb)} MB",
                            right2 = if (info.sizeBytes > 0) (if (o.changePct > 0) "+" else "") + "${o.changePct}%" else "",
                            onPick = { custom = false; sideSel = o.side; editedMb = null },
                        )
                    }
                    VerticalGap(Spacing.e4)
                    CodecPicker(limited.codec, is32, ::changeCodec) { help = it }
                }
                1 -> {
                    Heading(tx("Quality"))
                    Dropdown(
                        tx("Resolution"), qualitySelection?.let { (if (it.original) tx("Original · ") else "") + "${it.side}p · ${it.width}x${it.height}" } ?: "-",
                        options.map { (if (it.original) tx("Original · ") else "") + "${it.side}p" to it.side }, { qualitySide = it },
                    )
                    Dropdown(
                        tx("Frames per second"), if (limited.fps <= 0) tx("Same as the video") else "${limited.fps} fps",
                        listOf(tx("Same as the video") to 0.0) + FPS_OPTIONS.filter { it < info.fps - 0.01 }.map { "$it fps" to it },
                        { limited = limited.copy(fps = it) },
                    )
                    VerticalGap(Spacing.e3)
                    Segmented(listOf(tx("Quality (CRF)"), tx("Bitrate")), if (limited.control == Control.CRF) 0 else 1,
                        { limited = limited.copy(control = if (it == 0) Control.CRF else Control.BITRATE) })
                    if (limited.control == Control.CRF) {
                        if (limited.codec == Codec.AV1)
                            NumberSlider("CRF (AV1)", limited.crfAv1, 0, 63, tx("Lower = better quality and bigger file. Default 44.")) { limited = limited.copy(crfAv1 = it) }
                        else
                            NumberSlider("CRF (x264)", limited.crfX264, 0, 51, tx("Lower = better quality and bigger file. Default 20.")) { limited = limited.copy(crfX264 = it) }
                    } else {
                        NumberField(tx("Video bitrate (kbps)"), bitrateText, { bitrateText = it })
                    }
                    VerticalGap(Spacing.e4)
                    CodecPicker(limited.codec, is32, ::changeCodec) { help = it }
                }
                else -> {
                    CodecPicker(limited.codec, is32, ::changeCodec) { help = it }
                    Heading(tx("Container"))
                    Segmented(listOf("MP4", "WebM"), if (limited.container == Container.MP4) 0 else 1, {
                        limited = if (it == 1) limited.copy(container = Container.WEBM, codec = Codec.AV1) else limited.copy(container = Container.MP4)
                        if (it == 1) onChangeCodec(Codec.AV1)
                    })
                    Text(tx("WebM only supports AV1: picking it switches the codec to AV1."), color = c.textMuted, style = MaterialTheme.typography.bodySmall)
                    Heading(tx("Encoder speed"))
                    if (limited.codec == Codec.AV1)
                        NumberSlider(tx("AV1 preset"), limited.presetAv1, 0, 13, tx("0 = slowest and best, 13 = fastest and worst. Yours: 4.")) { limited = limited.copy(presetAv1 = it) }
                    else
                        Dropdown(tx("x264 preset"), limited.presetX264, PRESETS_X264.map { it to it }, { limited = limited.copy(presetX264 = it) })
                    Heading(tx("Quality or bitrate"))
                    Segmented(listOf("CRF", tx("Bitrate")), if (limited.control == Control.CRF) 0 else 1,
                        { limited = limited.copy(control = if (it == 0) Control.CRF else Control.BITRATE) })
                    if (limited.control == Control.CRF) {
                        if (limited.codec == Codec.AV1)
                            NumberSlider("CRF (AV1)", limited.crfAv1, 0, 63, tx("Default 44.")) { limited = limited.copy(crfAv1 = it) }
                        else
                            NumberSlider("CRF (x264)", limited.crfX264, 0, 51, tx("Default 20.")) { limited = limited.copy(crfX264 = it) }
                    } else {
                        NumberField(tx("Video bitrate (kbps)"), bitrateText, { bitrateText = it })
                        Row(Modifier.fillMaxWidth().clickable(enabled = limited.codec == Codec.X264) { limited = limited.copy(twoPasses = !limited.twoPasses) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(limited.twoPasses && limited.codec == Codec.X264, null, enabled = limited.codec == Codec.X264,
                                colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
                            Text(tx("2 passes (x264 only)"), color = c.text, modifier = Modifier.padding(start = Spacing.e2))
                        }
                    }
                    Heading(tx("Picture"))
                    Dropdown(
                        tx("Resolution"), qualitySelection?.let { (if (it.original) tx("Original · ") else "") + "${it.side}p · ${it.width}x${it.height}" } ?: "-",
                        options.map { (if (it.original) tx("Original · ") else "") + "${it.side}p" to it.side }, { qualitySide = it },
                    )
                    Dropdown(
                        tx("Frames per second"), if (limited.fps <= 0) tx("Same as the video") else "${limited.fps} fps",
                        listOf(tx("Same as the video") to 0.0) + FPS_OPTIONS.filter { it < info.fps - 0.01 }.map { "$it fps" to it },
                        { limited = limited.copy(fps = it) },
                    )
                    Heading(tx("Audio"))
                    NumberSlider(tx("Opus (kbps)"), limited.audioKbps, 16, 96, tx("This is a ceiling: it never goes above the original audio.")) { limited = limited.copy(audioKbps = it) }
                    VerticalGap(Spacing.e2)
                    Segmented(listOf(tx("Mono"), tx("Stereo")), if (limited.channels == Channels.MONO) 0 else 1,
                        { limited = limited.copy(channels = if (it == 0) Channels.MONO else Channels.STEREO) })
                    Heading(tx("Other"))
                    NumberField(tx("GOP (frames between keyframes)"), gopText, { gopText = it })
                    NumberField(tx("Threads (0 = automatic)"), threadsText, { threadsText = it })
                    OutlinedTextField(
                        value = limited.extraParams, onValueChange = { limited = limited.copy(extraParams = it) },
                        label = { Text(tx("Extra encoder parameters (empty = yours)")) },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.e2),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accent,
                            unfocusedBorderColor = c.borderInput, focusedLabelColor = c.accent, unfocusedLabelColor = c.textMuted, cursorColor = c.accent),
                    )
                    VerticalGap(Spacing.e5)
                }
            }
        }
        }
        Box(Modifier.fillMaxWidth().background(c.bg).padding(Spacing.e4).navigationBarsPadding()) {
            Button(
                onClick = {
                    val a = finalSettings()
                    val ext = if (a.container == Container.WEBM) ".webm" else ".mp4"
                    if (video.path != null) { onCompress(a); return@Button }
                    commandPreview = passesCommand(
                        "libffmpeg.so", "input", "output$ext", a, info.copy(), video.tracks, "passlog",
                        encoderAv1 = if (is32) "libaom-av1" else "libsvtav1",
                    ).mapIndexed { i, cmd ->
                        "Pass ${i + 1}:\n" + cmd.joinToString(" ") { if (it.contains(' ') || it.contains('[')) "\"$it\"" else it }
                    }.joinToString("\n\n")
                },
                enabled = selUse != null && !(tab == 0 && custom && customKbps == null),
                modifier = Modifier.fillMaxWidth().height(Dimens.button),
                shape = RoundedCornerShape(Radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent,
                    disabledContainerColor = c.surface2, disabledContentColor = c.textMuted),
            ) { Text(tx("Compress"), fontWeight = FontWeight.Bold) }
        }
    }

    help?.let { codecChoice ->
        AlertDialog(
            onDismissRequest = { help = null },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(if (codecChoice == Codec.X264) "x264 (H.264)" else "AV1") },
            text = { Text(explanation(codecChoice, is32)) },
            confirmButton = { TextButton(onClick = { help = null }) { Text(tx("Got it"), color = c.accent) } },
        )
    }
    commandPreview?.let { t ->
        AlertDialog(
            onDismissRequest = { commandPreview = null },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
            title = { Text(tx("It does not compress yet")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(tx("The engine is not connected to ffmpeg yet. This is the command that will run:"))
                    VerticalGap(Spacing.e2)
                    Text(t, fontFamily = FontFamily.Monospace, color = c.data, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { commandPreview = null }) { Text(tx("Close"), color = c.accent) } },
        )
    }
}
