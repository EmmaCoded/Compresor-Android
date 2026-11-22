package app.compresor.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.compresor.engine.Codec
import app.compresor.ui.theme.ThemeMode

/** Options of the gear icon. Saved in the "compresor" preferences and read only once when the app opens. */
object Config {
    private var prefs: android.content.SharedPreferences? = null
    var screenOnEnabled by mutableStateOf(false)   // keep the screen on while compressing or cutting
        private set
    var skipName by mutableStateOf(true)         // save right away, without asking for the name (on by default)
        private set
    var theme by mutableStateOf(ThemeMode.DARK)      // dark by default
        private set
    var videoCodec by mutableStateOf(Codec.X264)     // the one that comes pre-selected when compressing
        private set
    var defaultAudio by mutableStateOf(1)         // 0 = Opus mono, 1 = Opus stereo, 2 = the original
        private set
    var videoFolder by mutableStateOf<String?>(null)   // null = DCIM/Compresor_CC; otherwise the URI of the chosen folder
        private set
    var audioFolder by mutableStateOf<String?>(null)   // null = Music/Compresor_CC
        private set

    fun begin(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("compresor", Context.MODE_PRIVATE)
        prefs = p
        screenOnEnabled = p.getBoolean("screen_on", false)
        skipName = p.getBoolean("skip_name", true)
        theme = runCatching { ThemeMode.valueOf(p.getString("theme", "") ?: "") }.getOrDefault(ThemeMode.DARK)
        videoFolder = p.getString("video_folder", null)
        videoCodec = if (p.getString("codec", "x264") == "av1") Codec.AV1 else Codec.X264
        defaultAudio = p.getInt("default_audio", 1).coerceIn(0, 2)
        audioFolder = p.getString("audio_folder", null)
        app.compresor.Language.mode = p.getString("idioma", app.compresor.Language.SYSTEM) ?: app.compresor.Language.SYSTEM
    }

    fun updateLanguage(v: String) { app.compresor.Language.mode = v; prefs?.edit()?.putString("idioma", v)?.apply() }
    fun updateScreenOnEnabled(v: Boolean) { screenOnEnabled = v; prefs?.edit()?.putBoolean("screen_on", v)?.apply() }
    fun updateSkipName(v: Boolean) { skipName = v; prefs?.edit()?.putBoolean("skip_name", v)?.apply() }
    fun updateTheme(v: ThemeMode) { theme = v; prefs?.edit()?.putString("theme", v.name)?.apply() }
    fun updateVideoCodec(v: Codec) { videoCodec = v; prefs?.edit()?.putString("codec", if (v == Codec.AV1) "av1" else "x264")?.apply() }
    fun updateDefaultAudio(v: Int) { defaultAudio = v; prefs?.edit()?.putInt("default_audio", v)?.apply() }
    fun updateVideoFolder(v: String?) { videoFolder = v; prefs?.edit()?.putString("video_folder", v)?.apply() }
    fun updateAudioFolder(v: String?) { audioFolder = v; prefs?.edit()?.putString("audio_folder", v)?.apply() }
}
