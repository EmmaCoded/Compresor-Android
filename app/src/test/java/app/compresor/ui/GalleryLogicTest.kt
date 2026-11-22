package app.compresor.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class GalleryLogicTest {
    private fun v(id: Long, name: String, added: Long = 0, modified: Long = 0, created: Long = 0) =
        VideoItem(id, name, 1000, 1000, added, modified, created, null)

    private val a = v(1, "b.mp4", added = 300, modified = 100, created = 200)
    private val b = v(2, "A.mp4", added = 100, modified = 300, created = 100)
    private val c = v(3, "c.mp4", added = 200, modified = 200, created = 300)
    private val all = listOf(a, b, c)

    @Test fun appearanceDescendingNewestFirst() {
        assertEquals(listOf(1L, 3L, 2L), sort(all, SortOrder.APPEARANCE, false).map { it.id })
    }

    @Test fun movingAVideoToTheFolderPutsItFirst() {
        val moved = b.copy(added = 999)            // when moved, its "appearance" date changes
        assertEquals(2L, sort(listOf(a, moved, c), SortOrder.APPEARANCE, false).first().id)
        // and the creation and modification dates do not matter for that order
        assertEquals(2L, sort(listOf(a, moved.copy(created = 0, modified = 0), c), SortOrder.APPEARANCE, false).first().id)
    }

    @Test fun creationAndModificationAreIndependent() {
        assertEquals(listOf(3L, 1L, 2L), sort(all, SortOrder.CREATION, false).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), sort(all, SortOrder.MODIFICATION, false).map { it.id })
    }

    @Test fun ascendingIsReverse() {
        assertEquals(sort(all, SortOrder.CREATION, false).reversed(), sort(all, SortOrder.CREATION, true))
    }

    @Test fun nameIgnoringCase() {
        assertEquals(listOf("A.mp4", "b.mp4", "c.mp4"), sort(all, SortOrder.NAME, true).map { it.name })
        assertEquals(listOf("c.mp4", "b.mp4", "A.mp4"), sort(all, SortOrder.NAME, false).map { it.name })
    }

    @Test fun tieStable() {
        val x = v(5, "x", added = 10); val y = v(4, "y", added = 10)
        assertEquals(listOf(4L, 5L), sort(listOf(x, y), SortOrder.APPEARANCE, true).map { it.id })
    }

    @Test fun sizeInKbBelowOneMb() {
        val l = Locale.US
        assertEquals("1 KB", formatSize(10, l))
        assertEquals("850 KB", formatSize(850L * 1024, l))
        assertEquals("1023 KB", formatSize(1023L * 1024, l))
        assertEquals("1.0 MB", formatSize(1L shl 20, l))
        assertEquals("5.8 MB", formatSize((5.8 * 1048576).toLong(), l))
        assertEquals("1.5 GB", formatSize((1.5 * 1073741824).toLong(), l))
    }

    @Test fun duration() {
        assertEquals("0:09", durationText(9_000))
        assertEquals("1:32", durationText(92_000))
        assertEquals("1:05:03", durationText(3_903_000))
    }
}
