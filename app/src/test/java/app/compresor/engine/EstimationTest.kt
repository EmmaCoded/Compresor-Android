package app.compresor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimationTest {
    private val v1080 = InfoVideo(1920, 1080, 30.0, 60.0, kbps = 8000, sizeBytes = 60L * 1048576)
    private val v4k = InfoVideo(3840, 2160, 30.0, 60.0, sizeBytes = 300L * 1048576)
    private val vertical = InfoVideo(1080, 1920, 30.0, 60.0, sizeBytes = 60L * 1048576)

    @Test fun max1080YMin144() {
        val o = resolutionOptions(v4k, Codec.X264)
        assertEquals(1080, o.first().side)
        assertEquals(144, o.last().side)
        assertFalse(o.any { it.original })        // 4K: there is no "Original" because it is above 1080p
    }

    @Test fun hasManyIntermediate() {
        assertTrue(resolutionOptions(v4k, Codec.X264).size >= 20)
    }

    @Test fun originalOnlyIfNotAbove1080() {
        val o = resolutionOptions(v1080, Codec.X264)
        assertTrue(o.first().original)
        assertEquals(1080, o.first().side)
        assertTrue(o.drop(1).none { it.side >= 1080 })
    }

    @Test fun neverLargerThanOriginal() {
        val small = InfoVideo(640, 360, 30.0, 10.0, sizeBytes = 1048576)
        val o = resolutionOptions(small, Codec.X264)
        assertTrue(o.all { it.side <= 360 })
    }

    @Test fun evenDimensionsAndAspect() {
        for (v in listOf(v1080, v4k, vertical)) {
            for (o in resolutionOptions(v, Codec.X264)) {
                assertEquals(0, o.width % 2); assertEquals(0, o.height % 2)
            }
        }
        val v = resolutionOptions(vertical, Codec.X264).first { it.side == 720 }
        assertEquals(720, v.width)                // in portrait the short side is the width
        assertEquals(1280, v.height)
    }

    @Test fun sizeGrowsWithPixels() {
        val o = resolutionOptions(v1080, Codec.X264)
        for (i in 1 until o.size) assertTrue(o[i].approxMb < o[i - 1].approxMb)
    }

    @Test fun pixelRatioLikeTheApp() {
        // 720p / 1080p = 0.444 of the pixels; without audio the size follows that ratio.
        val a = estimateSizeMb(v1080, 1920, 1080, Codec.X264, 0)
        val b = estimateSizeMb(v1080, 1280, 720, Codec.X264, 0)
        assertEquals(0.444, b / a, 0.001)
    }

    @Test fun av1WeighsLessThanX264() {
        val x = estimateSizeMb(v1080, 1280, 720, Codec.X264, 64)
        val a = estimateSizeMb(v1080, 1280, 720, Codec.AV1, 64)
        assertTrue(a < x)
    }

    @Test fun withoutDurationThereIsNoList() {
        assertTrue(resolutionOptions(InfoVideo(1920, 1080, 30.0, 0.0), Codec.X264).isEmpty())
    }

    @Test fun neverExceedsOriginalSize() {
        val light = InfoVideo(1920, 1080, 60.0, 92.0, sizeBytes = 27_091_148L)   // 25.8 MB
        val o = resolutionOptions(light, Codec.X264)
        assertTrue(o.all { it.approxMb <= 25.9 })
        assertTrue(o.first().changePct <= 0)
    }

    @Test fun defaultIsLargestUnderTenMb() {
        val o = listOf(
            ResolutionOption(1080, 1920, 1080, true, 40.0, 0), ResolutionOption(720, 1280, 720, false, 18.0, -55),
            ResolutionOption(480, 854, 480, false, 9.5, -76), ResolutionOption(360, 640, 360, false, 5.0, -87), ResolutionOption(144, 256, 144, false, 1.0, -97),
        )
        assertEquals(480, optionByDefault(o)?.side)
        // if none is under 10 MB, the lightest
        assertEquals(144, optionByDefault(o, 0.5)?.side)
        // a video that already weighs little does not leave "original" selected
        val small = listOf(ResolutionOption(720, 1280, 720, true, 4.0, 0), ResolutionOption(480, 854, 480, false, 2.0, -50))
        assertEquals(480, optionByDefault(small)?.side)
    }
}
