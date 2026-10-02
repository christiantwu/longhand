package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.VoiceMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
