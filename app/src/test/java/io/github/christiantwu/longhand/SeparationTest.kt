package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.engine.DecodedAudio
import io.github.christiantwu.longhand.engine.Separation
import io.github.christiantwu.longhand.engine.Span
import io.github.christiantwu.longhand.work.SeparationCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who spoke when in a call, found while its language was detected and kept for transcribing it. */
class SeparationTest {

    private fun rec(id: Long, size: Long = 1_000, modified: Long = 5) = Recording(id = id, documentUri = "u$id",
        displayName = "c$id", sizeBytes = size, lastModified = modified, status = RecordingStatus.PENDING)

    private fun separation() = Separation(listOf(Separation.Turn(Span(0f, 1f, 0))), emptyMap(), listOf(0 to 16_000))

    private val voice = floatArrayOf(0.6f, 0.8f)

    @Test fun turnsAreHeardOnTheirChannelOrTheMonoMix() {
        val left = floatArrayOf(0.2f, 0.4f)
        val right = floatArrayOf(0.6f, 0.0f)
        val stereo = DecodedAudio(listOf(left, right))
        val turns = Separation(listOf(Separation.Turn(Span(0f, 1f, 1), channel = 1), Separation.Turn(Span(1f, 2f, 0), channel = 0)),
            emptyMap(), emptyList()).turnsIn(stereo)
        assertEquals(listOf(Span(0f, 1f, 1), Span(1f, 2f, 0)), turns.map { it.first })
        // The arrays themselves: voices are fingerprinted from the turns on the same audio.
        assertSame(right, turns[0].second)
        assertSame(left, turns[1].second)
        val mixed = Separation(listOf(Separation.Turn(Span(0f, 1f, 0)), Separation.Turn(Span(1f, 2f, 1))), emptyMap(), emptyList())
            .turnsIn(stereo)
        assertSame(stereo.mono, mixed[0].second)
        assertSame(stereo.mono, mixed[1].second)
        assertEquals(listOf(0.4f, 0.2f), stereo.mono.toList())
    }

    @Test fun usedOnceForTheSameCallUnchangedWithTheSameVoiceprint() {
        val cache = SeparationCache(8)
        val kept = separation()
        cache.put(rec(1), voice, kept)
        // The voiceprint is read afresh for each call: equal is enough.
        assertSame(kept, cache.take(rec(1), voice.copyOf()))
        assertNull(cache.take(rec(1), voice))
        // Without a voiceprint then and now.
        cache.put(rec(2), null, kept)
        assertSame(kept, cache.take(rec(2), null))
    }

    @Test fun notForAnotherCallAChangedFileOrAnotherVoiceprint() {
        val cache = SeparationCache(8)
        cache.put(rec(1), voice, separation())
        assertNull(cache.take(rec(2), voice))
        assertNull(cache.take(rec(1, size = 1_001), voice))
        // Dropped once it didn't hold.
        assertNull(cache.take(rec(1), voice))

        cache.put(rec(1), voice, separation())
        assertNull(cache.take(rec(1, modified = 6), voice))
        // The owner confirmed their voice meanwhile, or set it for the first time: they may be told apart differently.
        cache.put(rec(1), voice, separation())
        assertNull(cache.take(rec(1), floatArrayOf(0.8f, 0.6f)))
        cache.put(rec(1), null, separation())
        assertNull(cache.take(rec(1), voice))
    }

    @Test fun holdsAtMostItsCapacityTheOldestGoingFirst() {
        val cache = SeparationCache(2)
        for (id in 1L..3L) cache.put(rec(id), voice, separation())
        assertNull(cache.take(rec(1), voice))
        assertNotNull(cache.take(rec(2), voice))
        assertNotNull(cache.take(rec(3), voice))
        // Kept again, a call is the newest.
        for (id in listOf(1L, 2L, 1L, 3L)) cache.put(rec(id), voice, separation())
        assertNull(cache.take(rec(2), voice))
        assertNotNull(cache.take(rec(1), voice))
        assertNotNull(cache.take(rec(3), voice))
    }

    @Test fun speakersFromDetectionAreUsedOnlyForTheSameAudio() {
        val found = Separation(listOf(Separation.Turn(Span(0f, 1f, 0))), emptyMap(), listOf(0 to 16_000), sampleCount = 32_000, channels = 1)
        assertTrue(found.matches(DecodedAudio(listOf(FloatArray(32_000)))))
        // The file rewritten in place since: trimmed, or now in stereo.
        assertFalse(found.matches(DecodedAudio(listOf(FloatArray(30_000)))))
        assertFalse(found.matches(DecodedAudio(listOf(FloatArray(32_000), FloatArray(32_000)))))
    }
}
