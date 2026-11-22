package app.compresor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val info = InfoVideo(1920, 1080, 60.0, 60.0, kbps = 8000)
    private val singleTrack = listOf(Track("aac", 128, 2))

    @Test fun sampleSize() {
        // 10 MB in 60 s: 10*8192/60*0.95 = 1297.07; minus audio 64 = 1233
        assertEquals(1233, bitrateForSize(10.0, 60.0, 64))
    }

    @Test fun impossibleSize() {
        assertNull(bitrateForSize(1.0, 3600.0, 64))
        assertNull(bitrateForSize(10.0, 0.0, 64))
    }

    @Test fun webmForcesAv1() {
        val a = consistency(VideoSettings(codec = Codec.X264, container = Container.WEBM))
        assertEquals(Codec.AV1, a.codec)
    }

    @Test fun neverEnlarges() {
        val a = crop(VideoSettings(width = 4000, height = 3000, fps = 120.0), info, singleTrack)
        assertEquals(1920, a.width)
        assertEquals(1080, a.height)
        assertEquals(0.0, a.fps, 0.0)
    }

    @Test fun evenDimensions() {
        val a = crop(VideoSettings(width = 1281, height = 721), info, singleTrack)
        assertEquals(1280, a.width)
        assertEquals(720, a.height)
    }

    @Test fun audioNeverAboveOriginal() {
        val a = crop(VideoSettings(audioKbps = 96), info, listOf(Track("aac", 48, 2)))
        assertEquals(48, a.audioKbps)
    }

    @Test fun crfByDefault() {
        val x = command("ff", "in.mkv", "out.mp4", VideoSettings(codec = Codec.X264), info, singleTrack)
        assertEquals("20", x[x.indexOf("-crf") + 1])
        val v = command("ff", "in.mkv", "out.webm", VideoSettings(codec = Codec.AV1, container = Container.WEBM), info, singleTrack)
        assertEquals("44", v[v.indexOf("-crf") + 1])
        assertTrue(v.contains("libsvtav1"))
    }

    @Test fun sizeModeUsesBitrateNotCrf() {
        val c = command("ff", "in.mkv", "out.mp4", VideoSettings(mode = Mode.SIZE, sizeMb = 10.0), info, singleTrack)
        assertTrue(c.contains("-b:v"))
        assertFalse(c.contains("-crf"))
    }

    @Test fun twoTracksAreMixed() {
        val c = command("ff", "in.mkv", "out.mp4", VideoSettings(), info,
            listOf(Track("aac", 64, 2), Track("ac3", 128, 6)))
        assertTrue(c[c.indexOf("-filter_complex") + 1].contains("amix=inputs=2:normalize=0"))
    }

    @Test fun noAudio() {
        val c = command("ff", "in.mkv", "out.mp4", VideoSettings(), info, emptyList())
        assertTrue(c.contains("-an"))
    }

    @Test fun minPlanUsesSingleThread() {
        val a = VideoSettings(codec = Codec.AV1, container = Container.WEBM, presetAv1 = 4)
        val c = command("ff", "in.mkv", "out.webm", a, info, singleTrack, Plan.MIN)
        assertEquals("8", c[c.indexOf("-preset") + 1])
        assertEquals("1", c[c.indexOf("-threads") + 1])
    }

    @Test fun aomOn32Bit() {
        val a = VideoSettings(codec = Codec.AV1, container = Container.WEBM)
        val c = command("ff", "in.mkv", "out.webm", a, info, singleTrack, encoderAv1 = "libaom-av1")
        assertTrue(c.contains("libaom-av1"))
        assertTrue(c.contains("-cpu-used"))
        assertFalse(c.contains("libsvtav1"))
    }

    @Test fun ownMark() {
        val c = command("ff", "in.mkv", "out.mp4", VideoSettings(), info, singleTrack)
        assertTrue(c.contains("comment=Compresor_B2"))
    }
}

class EnginePassesTest {
    private val info = InfoVideo(1920, 1080, 30.0, 60.0, kbps = 8000)
    private val mp4 = VideoSettings(mode = Mode.SIZE, codec = Codec.X264, sizeMb = 10.0, twoPasses = true)

    @Test fun x264SizeModeDoesTwoPasses() {
        val l = passesCommand("ff", "in.mkv", "out.mp4", mp4, info, listOf(Track("aac", 128, 2)), "pl")
        assertEquals(2, l.size)
        val (p1, p2) = l
        assertEquals("1", p1[p1.indexOf("-pass") + 1])
        assertTrue(p1.contains("-an")); assertTrue(p1.contains("null"))
        assertEquals("2", p2[p2.indexOf("-pass") + 1])
        assertEquals("out.mp4", p2.last())
        assertTrue(p2.indexOf("-pass") < p2.size - 1)
    }

    @Test fun av1AlwaysOnePass() {
        val a = mp4.copy(codec = Codec.AV1, container = Container.WEBM)
        assertEquals(1, passesCommand("ff", "in.mkv", "out.webm", a, info, listOf(Track("aac", 128, 2)), "pl").size)
    }

    @Test fun crfModeDoesNotDoTwoPasses() {
        val a = VideoSettings(mode = Mode.QUALITY, twoPasses = true)
        assertEquals(1, passesCommand("ff", "in.mkv", "out.mp4", a, info, listOf(Track("aac", 128, 2)), "pl").size)
    }

    @Test fun audioOriginalCopiedWhenItFits() {
        val a = VideoSettings(audio = AudioMode.ORIGINAL)
        val c = command("ff", "in.mkv", "out.mp4", a, info, listOf(Track("aac", 128, 2)))
        assertEquals("copy", c[c.indexOf("-c:a") + 1])
    }

    @Test fun audioOriginalNotInWebmFallsBackToOpus() {
        val a = VideoSettings(codec = Codec.AV1, container = Container.WEBM, audio = AudioMode.ORIGINAL)
        val c = command("ff", "in.mkv", "out.webm", a, info, listOf(Track("dts", 768, 6)))
        assertEquals("libopus", c[c.indexOf("-c:a") + 1])
    }

    @Test fun bitrateCountsOriginalAudio() {
        val a = mp4.copy(audio = AudioMode.ORIGINAL)
        val withOriginal = command("ff", "in.mkv", "out.mp4", a, info, listOf(Track("aac", 256, 2)))
        val withOpus = command("ff", "in.mkv", "out.mp4", mp4, info, listOf(Track("aac", 256, 2)))
        val bitrateWithOriginalAudio = withOriginal[withOriginal.indexOf("-b:v") + 1].removeSuffix("k").toInt()
        val bitrateWithOpusAudio = withOpus[withOpus.indexOf("-b:v") + 1].removeSuffix("k").toInt()
        assertTrue(bitrateWithOriginalAudio < bitrateWithOpusAudio)   // the original audio costs more, less is left for the video
    }
}

class EngineSmallSizeTest {
    private val info = InfoVideo(1280, 960, 30.0, 20.0)

    @Test fun audioDoesNotEatTheBudget() {
        // 0.1 MB in 20 s = 38 kbps in total: the 64k audio is lowered to ~9 -> minimum 16
        assertEquals(16, audioForSize(0.1, 20.0, 64))
        assertEquals(64, audioForSize(10.0, 20.0, 64))      // with plenty of budget it is not touched
    }

    @Test fun decimalTargetGivesVideoBitrate() {
        val a = VideoSettings(mode = Mode.SIZE, sizeMb = 0.5)
        val c = command("ff", "in.mp4", "out.mp4", a, info, listOf(Track("aac", 128, 2)))
        val kbps = c[c.indexOf("-b:v") + 1].removeSuffix("k").toInt()
        // 0.5 MB / 20 s = 194 kbps * 0.95 = 184 - audio 48 (25 % of 194) = ~146
        assertTrue(kbps in 100..200)
        assertEquals("48k", c[c.indexOf("-b:a") + 1])
    }

    @Test fun decimalRequestedSizeIsRespected() {
        assertEquals(1233, bitrateForSize(10.0, 60.0, 64))
        assertEquals(115, bitrateForSize(1.0, 60.0, 14))   // 1*8192/60*0.95 = 129.7 - 14
    }
}
