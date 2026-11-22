package app.compresor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {
    private val info = InfoAudio(duration = 200.0, kbps = 256, codec = "mp3", channels = 2, cover = true)

    @Test fun approximateSize() {
        // 128 kbps x 200 s = 3.2 MB (+1 %)
        assertEquals(3_232_000L, audioSizeBytes(200.0, 128))
        assertEquals("3.1 MB", audioSizeLabel(3_232_000L))
        assertEquals("500 KB", audioSizeLabel(512_000L))
    }

    @Test fun defaultKeepsEverything() {
        val c = audioCommand("ff", "in.flac", "out.mp3", AudioSettings(), info)
        assertTrue(c.containsAll(listOf("-map_metadata", "0", "-c:a", "libmp3lame", "-b:a", "128k")))
        assertTrue(c.windowed(2).contains(listOf("-map", "0:v:0")))               // the cover art is included
        assertTrue(c.windowed(2).contains(listOf("-disposition:v:0", "attached_pic")))
        assertFalse(c.contains("-map_chapters"))
    }

    @Test fun removeMetadataRemovesCoverAndTags() {
        val c = audioCommand("ff", "in.mp3", "out.opus", AudioSettings(AudioOutput.OPUS, 96, removeMetadata = true), info)
        assertTrue(c.windowed(2).contains(listOf("-map_metadata", "-1")))
        assertFalse(c.windowed(2).contains(listOf("-map", "0:v:0")))
        assertTrue(c.contains("-vn"))
        assertTrue(c.containsAll(listOf("libopus", "96k", "-vbr")))
    }

    @Test fun noCoverDoesNotMapVideo() {
        val c = audioCommand("ff", "in.mp3", "out.m4a", AudioSettings(AudioOutput.AAC, 128), info.copy(cover = false))
        assertFalse(c.windowed(2).contains(listOf("-map", "0:v:0")))
        assertTrue(c.containsAll(listOf("aac", "+faststart")))
    }

    @Test fun surroundDownmixedToStereo() {
        val c = audioCommand("ff", "in.ac3", "out.mp3", AudioSettings(), info.copy(channels = 6))
        assertTrue(c.windowed(2).contains(listOf("-ac", "2")))
    }

    @Test fun kbpsAdaptsToFormat() {
        assertEquals(192, nearestKbps(AudioOutput.MP3, 200))
        assertEquals(192, nearestKbps(AudioOutput.OPUS, 200))
        assertEquals(192, suggestedKbps(256, AudioOutput.OPUS))     // 256 / 1.35 = 189 -> 192
        assertEquals(128, suggestedKbps(0, AudioOutput.MP3))
        assertEquals(320, suggestedKbps(900, AudioOutput.MP3))      // never exceeds the maximum
    }

    @Test fun opusCarriesCoverAsTag() {
        val c = audioCommand("ff", "in.mp3", "out.opus", AudioSettings(AudioOutput.OPUS, 96), info, metaCover = "meta.txt")
        assertFalse(c.windowed(2).contains(listOf("-map", "0:v:0")))               // it does not go as video
        assertTrue(c.windowed(2).contains(listOf("-i", "meta.txt")))
        assertTrue(c.windowed(2).contains(listOf("-map_metadata", "1")))
        assertTrue(c.contains("-vn"))
        // without metadata there is no extra file
        val q = audioCommand("ff", "in.mp3", "out.opus", AudioSettings(AudioOutput.OPUS, 96, true), info, metaCover = "meta.txt")
        assertFalse(q.contains("meta.txt"))
    }

    @Test fun flacPictureBlock() {
        val b = flacPictureBlockTest(byteArrayOf(1, 2, 3), 600, 400)
        // type 3, 10-letter mime, empty description, 600x400, 24 bits, 0 colors, 3 bytes of image
        assertEquals(4 + 4 + 10 + 4 + 16 + 4 + 3, b.size)
        assertEquals(3, b[3].toInt())
        assertEquals(10, b[7].toInt())
        assertEquals(1, b[b.size - 3].toInt())
        assertEquals(3, b[b.size - 4].toInt())    // last byte of the image length
    }

    @Test fun ffmetadataEscapesSpecialChars() {
        assertEquals(";FFMETADATA1\nMETADATA_BLOCK_PICTURE=AB\\=\\=\n", ffmetadataCover("AB=="))
    }
}
