package app.compresor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** App language. "system" follows the phone (Spanish if the phone is Spanish, English otherwise). */
object Language {
    const val SYSTEM = "system"
    const val SPANISH_CODE = "es"
    const val ENGLISH_CODE = "en"

    var mode by mutableStateOf(SYSTEM)

    fun isSpanish(): Boolean = when (mode) {
        SPANISH_CODE -> true
        ENGLISH_CODE -> false
        else -> Locale.getDefault().language == "es"
    }
}

/**
 * Text for the screen. The English text in the code is the key; Spanish comes from [SPANISH].
 * Placeholders are {0}, {1}... A text with printf-style codes (%.1f) is returned as is (call .format on it).
 */
fun tx(english: String, vararg args: Any?): String {
    var s = if (Language.isSpanish()) SPANISH[english] ?: english else english
    for (i in args.indices) s = s.replace("{$i}", args[i].toString())
    return s
}
