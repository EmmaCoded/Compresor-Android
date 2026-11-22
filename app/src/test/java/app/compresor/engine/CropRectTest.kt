package app.compresor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRectTest {
    private val eps = 0.0001f
    private fun same(a: RectN, b: RectN) {
        assertEquals(a.l, b.l, eps); assertEquals(a.t, b.t, eps); assertEquals(a.r, b.r, eps); assertEquals(a.b, b.b, eps)
    }

    @Test fun rotatingFourTimesKeepsTheRect() {
        val r = RectN(0.1f, 0.2f, 0.6f, 0.5f)
        same(r, r.clockwise().clockwise().clockwise().clockwise())
        same(r, r.counterclockwise().counterclockwise().counterclockwise().counterclockwise())
    }

    @Test fun clockwiseAndCounterclockwiseCancel() {
        val r = RectN(0.1f, 0.2f, 0.6f, 0.5f)
        same(r, r.clockwise().counterclockwise())
        same(r, r.counterclockwise().clockwise())
    }

    @Test fun rotateRightMovesTopLeftCornerToTopRight() {
        // a rectangle stuck to the top-left ends up at the top-right after rotating 90 degrees to the right
        val r = RectN(0f, 0f, 0.4f, 0.2f).clockwise()
        same(RectN(0.8f, 0f, 1f, 0.4f), r)
    }

    @Test fun moveStaysInsideImage() {
        val r = RectN(0.5f, 0.5f, 0.9f, 0.9f).move(0.5f, 0.5f)
        assertEquals(0.6f, r.l, eps); assertEquals(1f, r.r, eps); assertEquals(1f, r.b, eps)
        assertEquals(0.4f, r.width, eps)                       // the size does not change
    }

    @Test fun cornerWithOppositeFixedAndMinSize() {
        val r = FULL_RECT.withCorner(0, 0.3f, 0.4f, 0.1f, 0.1f)
        same(RectN(0.3f, 0.4f, 1f, 1f), r)
        val verySmall = FULL_RECT.withCorner(2, 0.01f, 0.01f, 0.1f, 0.1f)   // it cannot collapse
        assertEquals(0.1f, verySmall.width, eps); assertEquals(0.1f, verySmall.height, eps)
    }

    @Test fun edgeMovesOnlyOneSide() {
        val r = FULL_RECT.withEdge(2, 0.7f, 0.1f, 0.1f)
        same(RectN(0f, 0f, 0.7f, 1f), r)
    }

    @Test fun lockedCornerKeepsAspectInPixels() {
        // image 1920x1080, aspect 1:1; the bottom-right corner is dragged from (0,0) to x=0.3
        val r = RectN(0f, 0f, 0.5f, 0.5f).withCornerLocked(2, 0.3f, 0.9f, 1f, 1920, 1080, 0.05f)
        assertEquals(1f, (r.width * 1920) / (r.height * 1080), 0.01f)
        assertEquals(0f, r.l, eps); assertEquals(0f, r.t, eps)       // the opposite corner did not move
    }

    @Test fun lockedCornerStaysInsideImage() {
        val r = RectN(0.2f, 0.2f, 0.4f, 0.4f).withCornerLocked(2, 5f, 5f, 16f / 9f, 1920, 1080, 0.05f)
        assertTrue(r.r <= 1.0001f && r.b <= 1.0001f)
        assertEquals(16f / 9f, (r.width * 1920) / (r.height * 1080), 0.02f)
    }

    @Test fun maxKeepingAspect() {
        // in a 1920x1080 image the largest 1:1 is 1080x1080 = 0.5625 of the width and the full height
        val r = maxWithAspect(1f, 1920, 1080, 0.5f, 0.5f)
        assertEquals(0.5625f, r.width, 0.001f); assertEquals(1f, r.height, eps)
        // and the largest 9:16 in that same image
        val v = maxWithAspect(9f / 16f, 1920, 1080, 0.5f, 0.5f)
        assertEquals(1f, v.height, eps)
        assertEquals(9f / 16f, (v.width * 1920) / (v.height * 1080), 0.01f)
    }

    @Test fun toPixelsAndEngine() {
        val rec = RectN(0.25f, 0.25f, 0.75f, 0.75f).toCrop(1920, 1080, Rotation.NONE, false)
        assertEquals(480, rec.x); assertEquals(270, rec.y); assertEquals(960, rec.width); assertEquals(540, rec.height)
        assertEquals("crop=960:540:480:270", filterCrop(rec, 1920, 1080))
    }

    @Test fun fullFrameWithoutRotationMakesNoFilter() {
        val rec = FULL_RECT.toCrop(1920, 1080, Rotation.NONE, false)
        assertEquals("", filterCrop(rec, 1920, 1080))
    }

    @Test fun originalAndFreeAspects() {
        assertEquals(null, Aspect.FREE.aspect(1920, 1080))
        assertEquals(1920f / 1080f, Aspect.ORIGINAL.aspect(1920, 1080)!!, eps)
        assertEquals(1f, Aspect.SQUARE.aspect(1920, 1080)!!, eps)
    }
}
