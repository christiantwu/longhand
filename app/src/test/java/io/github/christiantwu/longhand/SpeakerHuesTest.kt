package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.ui.SpeakerHues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/** Speaker names stay apart in colour whatever the wallpaper. */
class SpeakerHuesTest {

    private fun apart(a: Float, b: Float): Float = abs(a - b).let { min(it, 360f - it) }

    @Test fun sixVoicesGetHuesAtLeastSixtyDegreesApartForEveryWallpaper() {
        for (primary in 0 until 360 step 5) {
            val hues = (0..5).map { SpeakerHues.hue(primary.toFloat(), it) }
            for (i in hues.indices) for (j in i + 1 until hues.size) {
                assertTrue("wallpaper hue $primary, voices $i and $j", apart(hues[i], hues[j]) >= 60f - 1e-3f)
            }
        }
    }

    @Test fun theOtherPersonIsAThirdOfTheWayRoundFromYou() {
        assertEquals(200f, SpeakerHues.hue(200f, 0), 1e-3f)
        assertEquals(320f, SpeakerHues.hue(200f, 1), 1e-3f)
        assertEquals(80f, SpeakerHues.hue(200f, 2), 1e-3f)
    }

    @Test fun pastSixVoicesNobodyElseGetsYourHue() {
        for (slot in 1..20) assertTrue(apart(SpeakerHues.hue(30f, slot), 30f) >= 60f - 1e-3f)
    }

    @Test fun youComeFirstThenTheOthersInOrder() {
        // You are speaker 2 on a conference call.
        assertEquals(listOf(1, 2, 0, 3, 4), (0..4).map { SpeakerHues.slot(it, owner = 2) })
        assertEquals(listOf(0, 1, 2), (0..2).map { SpeakerHues.slot(it, owner = 0) })
        // Your voice not known yet: speaker 1 takes your place, as before.
        assertEquals(listOf(1, 0, 2, 3), (0..3).map { SpeakerHues.slot(it, owner = null) })
    }
}
