package app.compresor.ui

import app.compresor.execution.hms
import java.util.Locale

// Gallery logic without Android (so it can be tested on the PC).

/** A video on the phone. Dates are in milliseconds. */
data class VideoItem(
    val id: Long,
    val name: String,
    val bytes: Long,
    val durationMs: Long,
    val added: Long,     // when it appeared on the phone (if you move it to another folder, it changes): DATE_ADDED
    val modified: Long,   // DATE_MODIFIED
    val created: Long,       // DATE_TAKEN (when it was recorded); if there is none, the modification is used
    val path: String?,
)

enum class ViewMode { GRID, LIST }
enum class SortOrder { APPEARANCE, CREATION, MODIFICATION, NAME }

/** Sorts the list. `ascending = false` is from largest to smallest (newest / Z to A first). */
fun sort(videos: List<VideoItem>, sortOrder: SortOrder, ascending: Boolean): List<VideoItem> {
    val cmp: Comparator<VideoItem> = when (sortOrder) {
        SortOrder.APPEARANCE -> compareBy<VideoItem>({ it.added }, { it.id })
        SortOrder.CREATION -> compareBy<VideoItem>({ it.created }, { it.id })
        SortOrder.MODIFICATION -> compareBy<VideoItem>({ it.modified }, { it.id })
        SortOrder.NAME -> compareBy<VideoItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    }
    val s = videos.sortedWith(cmp)
    return if (ascending) s else s.reversed()
}

/** Below 1 MB it is shown in KB; from 1 GB, in GB. */
fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
    bytes >= 1L shl 30 -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(locale, "%d KB", maxOf(1L, bytes / 1024))
}

fun durationText(ms: Long): String = hms(ms / 1000)
