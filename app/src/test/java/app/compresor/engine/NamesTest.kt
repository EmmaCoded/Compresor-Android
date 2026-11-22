package app.compresor.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test fun withoutCollisionStaysCut() {
        assertEquals("fiesta_cut", availableName("fiesta.mp4", "cut", "mp4") { false })
    }

    @Test fun ifExistsContinuesWithCut2AndCut3() {
        val has = setOf("fiesta_cut.mp4", "fiesta_cut2.mp4")
        assertEquals("fiesta_cut3", availableName("fiesta.mp4", "cut", "mp4") { it in has })
        assertEquals("fiesta_cut2", availableName("fiesta.mp4", "cut", "mp4") { it == "fiesta_cut.mp4" })
    }

    @Test fun cuttingACutDoesNotRepeatTheMark() {
        assertEquals("fiesta_cut2", availableName("fiesta_cut.mp4", "cut", "mp4") { it == "fiesta_cut.mp4" })
        assertEquals("fiesta_cut4", availableName("fiesta_cut3.mp4", "cut", "mp4") { it in setOf("fiesta_cut.mp4", "fiesta_cut2.mp4", "fiesta_cut3.mp4") })
    }

    @Test fun removesForbiddenChars() {
        assertEquals("ab", sanitizeName("a/b:", "x").replace("\\", ""))
        assertEquals("x", sanitizeName("  ... ", "x"))
        assertEquals("mi video", sanitizeName(" mi video ", "x"))
    }
}
