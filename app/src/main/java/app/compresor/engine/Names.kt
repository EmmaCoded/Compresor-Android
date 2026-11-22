package app.compresor.engine

// Names of the saved files. Pure Kotlin, no Android.

/** Removes what cannot go in a file name. If nothing is left, returns `default`. */
fun sanitizeName(s: String, default: String): String {
    val t = s.filter { it >= ' ' && it !in "\\/:*?\"<>|" }.trim().trim('.').trim()
    return t.ifEmpty { default }
}

/**
 * Name proposed when saving: `video_cut`, and if `video_cut.mp4` already exists, `video_cut2`, `video_cut3`...
 * If the original already ends with the mark (`video_cut`), it is not repeated: counting continues from there (`video_cut2`).
 * `exists` receives the full name with extension (`video_cut.mp4`).
 */
fun availableName(base: String, mark: String, ext: String, exists: (String) -> Boolean): String {
    val cleaned = sanitizeName(base.substringBeforeLast('.'), "video")
    val root = Regex("_" + Regex.escape(mark) + """\d*$""").replace(cleaned, "").ifEmpty { cleaned }
    var n = 1
    while (n < 10_000) {
        val c = root + "_" + mark + (if (n == 1) "" else n.toString())
        if (!exists("$c.$ext")) return c
        n++
    }
    return root + "_" + mark + System.currentTimeMillis()
}
