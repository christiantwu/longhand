package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.VoiceMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class VoiceMathTest {
    private val me = floatArrayOf(1f, 0f, 0f)

    @Test fun picksTheClearlyClosestVoice() {
        val voices = mapOf(0 to floatArrayOf(0.2f, 1f, 0f), 1 to floatArrayOf(0.95f, 0.1f, 0.05f))
        assertEquals(1, VoiceMath.pickOwner(me, voices))
    }

    @Test fun noOwnerWhenNobodySoundsLikeYou() {
        assertNull(VoiceMath.pickOwner(me, mapOf(0 to floatArrayOf(0.3f, 1f, 0f), 1 to floatArrayOf(0f, 0f, 1f))))
    }

    @Test fun noOwnerWhenTwoVoicesAreEquallyClose() {
        assertNull(VoiceMath.pickOwner(me, mapOf(0 to floatArrayOf(0.9f, 0.3f, 0f), 1 to floatArrayOf(0.9f, 0f, 0.3f))))
    }

    @Test fun bytesRoundTrip() {
        val v = floatArrayOf(0.25f, -1.5f, 3f)
        assertArrayEquals(v, VoiceMath.fromBytes(VoiceMath.toBytes(v)), 0f)
        assertEquals(1f, VoiceMath.cosine(v, v), 1e-6f)
    }

    // "Recognise voices". Fictional unit voices: voice(c, k) scores c against v, and a·b against voice(b, j) for j ≠ k.
    private val v = floatArrayOf(1f, 0f, 0f, 0f)
    private fun voice(cos: Float, axis: Int = 1) = FloatArray(4).also { it[0] = cos; it[axis] = sqrt(1 - cos * cos) }

    @Test fun suggestsTheKnownVoiceThatClearlyMatches() {
        assertEquals(7L, VoiceMath.suggest(v, mapOf(7L to voice(0.7f, 1), 8L to voice(0.4f, 2))))
    }

    @Test fun suggestsOnlyAboveTheThreshold() {
        assertNull(VoiceMath.suggest(v, mapOf(7L to voice(0.55f))))
        assertEquals(7L, VoiceMath.suggest(v, mapOf(7L to voice(0.57f))))
    }

    @Test fun suggestsOnlyWithAClearLeadOverTheNextKnownVoice() {
        assertNull(VoiceMath.suggest(v, mapOf(7L to voice(0.7f, 1), 8L to voice(0.62f, 2))))
        assertEquals(7L, VoiceMath.suggest(v, mapOf(7L to voice(0.7f, 1), 8L to voice(0.58f, 2))))
    }

    @Test fun anExcludedBestMatchIsNotSwappedForTheNext() {
        val known = mapOf(7L to voice(0.8f, 1), 8L to voice(0.6f, 2))
        assertEquals(7L, VoiceMath.suggest(v, known))
        // Rejected for this speaker, or someone else's name in this call: no suggestion, not the runner-up.
        assertNull(VoiceMath.suggest(v, known, excluded = setOf(7L)))
        assertEquals(7L, VoiceMath.suggest(v, known, excluded = setOf(8L)))
    }

    @Test fun noSuggestionWithoutKnownVoices() {
        assertNull(VoiceMath.suggest(v, emptyMap()))
    }

    @Test fun centroidIsTheNormalisedMeanOfUnitSamples() {
        val c = VoiceMath.centroid(listOf(floatArrayOf(3f, 0f), floatArrayOf(0f, 0.5f), floatArrayOf(1f, 2f, 3f)))!!
        // Each sample counts the same whatever its length; one of another size is left out.
        assertArrayEquals(floatArrayOf(0.70710677f, 0.70710677f), c, 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 1f), VoiceMath.centroid(listOf(floatArrayOf(0f, 2f)))!!, 1e-6f)
        assertNull(VoiceMath.centroid(emptyList()))
    }
}
