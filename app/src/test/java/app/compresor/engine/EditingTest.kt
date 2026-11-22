package app.compresor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {
    // ----- rules of the points
    @Test fun startCannotGoAfterEnd() {
        assertTrue(canSetStart(1000, 5000))
        assertFalse(canSetStart(5000, 5000))
        assertFalse(canSetStart(6000, 5000))
        assertTrue(canSetStart(4900, 5000))        // exactly the minimum (0.1 s)
    }

    @Test fun endCannotGoBeforeStart() {
        assertTrue(canSetEnd(6000, 5000))
        assertFalse(canSetEnd(5000, 5000))
        assertFalse(canSetEnd(1000, 5000))
    }

    @Test fun pointDoesNotMoveTheLineUntilItCollides() {
        // the line is at 50 s; the end point goes down from 90 to 60: it does not touch it
        assertFalse(pushesLine(90_000, 60_000, 50_000))
        // it keeps going down from 60 to 40: it passes through the line -> pushes it
        assertTrue(pushesLine(60_000, 40_000, 50_000))
        // the start point goes up from 10 to 30 with the line at 50: it does not touch it
        assertFalse(pushesLine(10_000, 30_000, 50_000))
        // and from 30 to 70: it pushes it forward
        assertTrue(pushesLine(30_000, 70_000, 50_000))
    }

    @Test fun lineStuckToPointDragsBothWays() {
        assertTrue(pushesLine(50_000, 51_000, 50_000))      // starts dragging it forward
        assertTrue(pushesLine(50_000, 49_000, 50_000))      // or backward
        assertFalse(pushesLine(50_000, 50_000, 50_000))     // without moving nothing happens
    }

    @Test fun pointFarFromLineNeverMovesIt() {
        assertFalse(pushesLine(20_000, 25_000, 80_000))
        assertFalse(pushesLine(95_000, 85_000, 80_000))
    }

    @Test fun timeText() {
        assertEquals("00:00.0", timeText(0))
        assertEquals("00:01.1", timeText(1100))
        assertEquals("00:06.4", timeText(6450))
        assertEquals("01:05.3", timeText(65300))
    }

    // ----- cut
    @Test fun fastCutCopiesWithoutReencoding() {
        val c = cutCommand("ff", "in.mp4", "out.mp4", CutRange(1500, 6500))
        assertEquals("1.500", c[c.indexOf("-ss") + 1])
        assertEquals("5.000", c[c.indexOf("-t") + 1])
        assertTrue(c.indexOf("-ss") < c.indexOf("-i"))                // seek BEFORE opening: fast
        assertEquals("copy", c[c.indexOf("-c") + 1])
        assertFalse(c.contains("libx264"))
        assertEquals("out.mp4", c.last())
    }

    @Test fun exactCutReencodes() {
        val c = cutCommand("ff", "in.mkv", "out.mp4", CutRange(0, 3000, precise = true))
        assertTrue(c.contains("libx264")); assertTrue(c.contains("aac")); assertFalse(c.contains("copy"))
    }

    @Test fun cutNoAudio() {
        val c = cutCommand("ff", "in.mp4", "out.mp4", CutRange(0, 3000, noAudio = true))
        assertFalse(c.contains("0:a?"))
        val p = cutCommand("ff", "in.mp4", "out.mp4", CutRange(0, 3000, precise = true, noAudio = true))
        assertFalse(p.contains("aac"))
    }

    @Test fun cutAlwaysHasPositiveDuration() {
        val c = cutCommand("ff", "in.mp4", "out.mp4", CutRange(5000, 5000))
        assertEquals("0.100", c[c.indexOf("-t") + 1])
    }

    @Test fun outputExtension() {
        assertEquals(".mp4", cutOutputExtension("a.mp4", false))
        assertEquals(".webm", cutOutputExtension("a.webm", false))     // fast: same container
        assertEquals(".mkv", cutOutputExtension("a.avi", false))       // odd: mkv accepts everything
        assertEquals(".mp4", cutOutputExtension("a.avi", true))
        assertEquals(".mkv", cutOutputExtension("a.webm", true))
    }

    // ----- rotation and crop
    @Test fun rotations() {
        assertEquals(Rotation.RIGHT, Rotation.NONE.clockwise())
        assertEquals(Rotation.NONE, Rotation.RIGHT.counterclockwise())
        assertEquals(Rotation.LEFT, Rotation.NONE.counterclockwise())
        assertEquals(Rotation.NONE, Rotation.LEFT.clockwise())
        assertEquals("transpose=1", filterRotation(Rotation.RIGHT))
        assertEquals("transpose=2", filterRotation(Rotation.LEFT))
    }

    @Test fun rotationSwapsTheSizes() {
        assertEquals(1080 to 1920, rotatedDimensions(1920, 1080, Rotation.RIGHT))
        assertEquals(1920 to 1080, rotatedDimensions(1920, 1080, Rotation.DOWN))
    }

    @Test fun cropAdjustsToEven() {
        val r = adjustCrop(CropRegion(11, 13, 301, 201), 1920, 1080)
        assertEquals(10, r.x); assertEquals(12, r.y); assertEquals(300, r.width); assertEquals(200, r.height)
    }

    @Test fun cropStaysInsideImage() {
        val r = adjustCrop(CropRegion(1800, 1000, 500, 500), 1920, 1080)
        assertTrue(r.x + r.width <= 1920); assertTrue(r.y + r.height <= 1080)
    }

    @Test fun filterRotatesFirstThenCrops() {
        // 1920x1080 image rotated to the right = 1080x1920; the rectangle is measured in that image
        val f = filterCrop(CropRegion(0, 100, 1080, 800, Rotation.RIGHT), 1920, 1080)
        assertEquals("transpose=1,crop=1080:800:0:100", f)
    }

    @Test fun noCropNorRotationMeansNoFilter() {
        assertEquals("", filterCrop(CropRegion(0, 0, 1920, 1080), 1920, 1080))
        assertEquals("transpose=2", filterCrop(CropRegion(0, 0, 1080, 1920, Rotation.LEFT), 1920, 1080))
    }

    @Test fun cropCommandCopiesAudioWhenItFitsInMp4() {
        val info = listOf(Track("aac", 128, 2))
        val c = cropCommand("ff", "in.mp4", "out.mp4", CropRegion(0, 0, 800, 600), 1920, 1080, info)
        assertEquals("crop=800:600:0:0", c[c.indexOf("-vf") + 1])
        assertEquals("copy", c[c.indexOf("-c:a") + 1])
        val dts = cropCommand("ff", "in.mkv", "out.mp4", CropRegion(0, 0, 800, 600), 1920, 1080, listOf(Track("dts", 768, 6)))
        assertEquals("aac", dts[dts.indexOf("-c:a") + 1])
    }
}

class TimelineTest {
    @Test fun zoomMaxByDuration() {
        assertEquals(2f, zoomMax(1000), 0f)         // very short video: minimum 2x
        assertEquals(30f, zoomMax(60_000), 0f)      // 1 min: ~2 s are visible
        assertEquals(64f, zoomMax(10_000_000), 0f)  // very long video: cap
    }

    @Test fun conversionMsX() {
        val total = 1000f
        assertEquals(500f, msAX(30_000, 60_000, total), 0.01f)
        assertEquals(30_000L, xMs(500f, 60_000, total))
        assertEquals(60_000L, xMs(5000f, 60_000, total))            // does not go past the end
        assertEquals(0L, xMs(-10f, 60_000, total))
    }

    @Test fun scrollNeverLeavesTheVideo() {
        assertEquals(0f, clampScroll(-50f, 700f, 4f), 0f)
        assertEquals(2100f, clampScroll(99999f, 700f, 4f), 0f)      // 700*4 - 700
        assertEquals(0f, clampScroll(10f, 700f, 1f), 0f)            // without zoom there is no scroll
    }

    @Test fun zoomKeepsWhatWasUnderTheFinger() {
        // The anchor (x=350) points to the middle of the video at zoom 2; after going up to 4 it still points to the middle.
        val s = scrollAfterZoom(350f, 700f, 2f, 4f, 350f)
        assertEquals(0.5f, (s + 350f) / totalWidth(700f, 4f), 0.001f)
    }

    @Test fun centerForAMoment() {
        val s = centeredScroll(30_000, 60_000, 700f, 4f)
        assertEquals(0.5f, (s + 350f) / totalWidth(700f, 4f), 0.001f)
    }

    @Test fun thumbnailLevels() {
        assertEquals(0, thumbnailLevel(1f))
        assertEquals(1, thumbnailLevel(2f))
        assertEquals(2, thumbnailLevel(3f))
        assertEquals(2, thumbnailLevel(4f))
        assertEquals(3, thumbnailLevel(4.1f))
        assertEquals(16, tilesInLevel(4, 2))
    }

    @Test fun visibleTiles() {
        // 8 tiles in 800 px total; 200 to 500 px visible -> tiles 2..4
        assertEquals(2..4, visibleTiles(200f, 300f, 800f, 8))
        assertEquals(0..7, visibleTiles(0f, 800f, 800f, 8))
        assertEquals(22_500L, tileCenterMs(1, 4, 60_000))
        assertEquals(7_500L, tileCenterMs(0, 4, 60_000))
    }

    @Test fun edgeSpeedIsSlowAndSmooth() {
        assertEquals(0f, edgeSpeed(60f, 48f, 120f), 0f)          // outside the zone: still
        assertEquals(0f, edgeSpeed(48f, 48f, 120f), 0f)
        val media = edgeSpeed(24f, 48f, 120f)
        val stuck = edgeSpeed(0f, 48f, 120f)
        assertEquals(120f, stuck, 0.01f)                              // cap
        assertTrue(media > 0f && media < stuck / 2)                   // quadratic: at half the zone, a quarter of the speed
    }

    @Test fun pointNeverLeavesTheView() {
        // view of 700 px, zoom 4, 60 s video: the finger goes past the right edge (x = 760) and the point stays at the visible edge
        val atEdge = msFinger(700f, 0f, 700f, 1000f, 4f, 60_000)
        assertEquals(atEdge, msFinger(760f, 0f, 700f, 1000f, 4f, 60_000))
        assertEquals(atEdge, msFinger(5000f, 0f, 700f, 1000f, 4f, 60_000))
        // and the same on the left
        assertEquals(msFinger(0f, 0f, 700f, 1000f, 4f, 60_000), msFinger(-90f, 0f, 700f, 1000f, 4f, 60_000))
        // stuck to the right edge with scroll 1000 out of 2800 possible: (1000 + 700) / 2800 * 60 s
        assertEquals(36_428L, atEdge)
    }

    @Test fun releasingPointStuckToEdgeKeepsItStuck() {
        // the point is at the right edge (x = 700): it is already visible, so the scroll does NOT change (it used to recenter)
        assertEquals(1000f, scrollToShow(1000f, 700f, 700f, 4f, 24f), 0f)
        assertEquals(1000f, scrollToShow(1000f, 0f, 700f, 4f, 24f), 0f)
    }

    @Test fun scrollToShowMovesTheMinimum() {
        // something was left 100 px to the right of the view: it scrolls just enough to see it with a 24 px margin
        assertEquals(1000f + 100f + 24f, scrollToShow(1000f, 800f, 700f, 4f, 24f), 0.01f)
        // 50 px to the left
        assertEquals(1000f - 50f - 24f, scrollToShow(1000f, -50f, 700f, 4f, 24f), 0.01f)
        // never goes past the limits of the video
        assertEquals(0f, scrollToShow(10f, -500f, 700f, 4f, 24f), 0f)
    }

    @Test fun scrollBarWithFinger() {
        // zoom 4 in a 700 px view: finger 100 px to the left = you see what comes next (scroll +100)
        assertEquals(600f, scrollAfterDrag(500f, -100f, 700f, 4f), 0f)
        assertEquals(400f, scrollAfterDrag(500f, 100f, 700f, 4f), 0f)
        assertEquals(0f, scrollAfterDrag(50f, 500f, 700f, 4f), 0f)          // does not go past the start
        assertEquals(2100f, scrollAfterDrag(2000f, -500f, 700f, 4f), 0f)    // nor the end
        assertEquals(0f, scrollAfterDrag(0f, -80f, 700f, 1f), 0f)           // without zoom there is nowhere to scroll
    }

    @Test fun pinchCountsOnlyHorizontal() {
        // the fingers move apart 100 -> 200 horizontally: factor 2
        assertEquals(2f, factorPinchHorizontal(100f to 200f, 50f to 250f), 0.001f)
        // if they only move apart vertically (same x) there is no zoom
        assertEquals(1f, factorPinchHorizontal(100f to 100f, 100f to 100f), 0f)
    }
}
