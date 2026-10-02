package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.FloatBuilder
import io.github.christiantwu.longhand.engine.StreamResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun run(inRate: Int, input: FloatArray): FloatArray {
        val out = FloatBuilder(4)
        val r = StreamResampler(inRate, 16_000, out)
        input.forEach(r::push)
        r.finish()
        return out.toArray()
    }

    @Test fun builderReusesBufferWhenTailIsSmall() {
        val b = FloatBuilder(20)
        repeat(18) { b.add(1f) }
        val reused = b.toArray(maxPadding = 2)
        assertEquals(20, reused.size) // same buffer, zero tail
        assertEquals(0f, reused[19])
        assertEquals(18, b.toArray(maxPadding = 1).size) // too much slack: exact copy
    }

    @Test fun builderGrowsPastInitialCapacity() {
        val b = FloatBuilder(16)
        repeat(1000) { b.add(it.toFloat()) }
        val out = b.toArray()
        assertEquals(1000, out.size)
        assertEquals(999f, out[999])
    }

    @Test fun passthroughAt16k() {
        val x = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(x.toList(), run(16_000, x).toList())
    }

    @Test fun outputLengthFollowsTheRateRatio() {
        for ((rate, n) in listOf(8_000 to 8_001, 11_025 to 11_025, 22_050 to 22_051, 44_100 to 44_100, 48_000 to 48_001)) {
            val expected = ((n.toLong() * 16_000 + rate - 1) / rate).toInt()
            assertEquals("at $rate Hz", expected, run(rate, FloatArray(n)).size)
        }
    }

    @Test fun anImpulseKeepsItsTime() {
        val at8k = run(8_000, FloatArray(4_000).also { it[1_000] = 1f })
        assertEquals(2_000, at8k.indices.maxBy { at8k[it] })
        val at48k = run(48_000, FloatArray(12_000).also { it[3_000] = 1f })
        assertEquals(1_000, at48k.indices.maxBy { at48k[it] })
    }

    @Test fun aSteadyInputKeepsItsLevel() {
        for (rate in listOf(8_000, 44_100, 48_000)) {
            val y = run(rate, FloatArray(rate) { 0.5f })
            for (i in 200 until y.size - 200) assertEquals("at $rate Hz", 0.5f, y[i], 1e-4f) // edges ramp in and out
        }
    }

    @Test fun speechFrequenciesPassUnchanged() {
        // 8,001 Hz needs more filter phases than are kept, so it runs the rounded-phase path.
        for (rate in listOf(8_000, 8_001, 44_100, 48_000)) {
            val y = run(rate, tone(1_000.0, rate, amplitude = 0.5))
            assertEquals("at $rate Hz", 0.0, db(amplitude(y, 1_000.0) / 0.5), 0.05)
        }
        // The top of telephone speech, just below the 3.8 kHz cutoff for 8 kHz audio.
        assertTrue(db(amplitude(run(8_000, tone(3_400.0, 8_000, amplitude = 0.5)), 3_400.0) / 0.5) > -0.5)
        // Downsampled audio keeps everything the 16 kHz models hear, not just the middle of speech.
        for (rate in listOf(44_100, 48_000)) for (f in listOf(3_400.0, 6_000.0)) {
            val y = run(rate, tone(f, rate, amplitude = 0.5))
            assertEquals("$f Hz at $rate Hz", 0.0, db(amplitude(y, f) / 0.5), 0.05)
        }
    }

    @Test fun upsamplingLeavesNoImages() {
        // Raising 8 kHz audio to 16 kHz mirrors each tone f to 8 kHz - f unless it's filtered out.
        for ((f, minDb) in listOf(3_400.0 to 70.0, 2_000.0 to 80.0)) {
            val y = run(8_000, tone(f, 8_000))
            val rejection = db(amplitude(y, f) / amplitude(y, 8_000 - f))
            assertTrue("image of $f Hz only ${"%.1f".format(rejection)} dB down", rejection >= minDb)
        }
    }

    @Test fun downsamplingLeavesNoAliases() {
        // Above the 8 kHz limit of 16 kHz audio: 10 kHz would fold to 6 kHz, and 12 kHz to 4 kHz.
        for ((rate, f, alias) in listOf(Triple(48_000, 10_000.0, 6_000.0), Triple(44_100, 12_000.0, 4_000.0))) {
            val rejection = db(1.0 / amplitude(run(rate, tone(f, rate)), alias))
            assertTrue("$f Hz at $rate Hz aliases only ${"%.1f".format(rejection)} dB down", rejection >= 70)
        }
    }

    private fun tone(f: Double, rate: Int, amplitude: Double = 1.0) =
        FloatArray(rate) { (amplitude * sin(2 * PI * f * it / rate)).toFloat() }

    /** Amplitude of frequency [f] in 16 kHz output, measured away from the edges over a whole number of periods. */
    private fun amplitude(y: FloatArray, f: Double, from: Int = 1_000, to: Int = 15_000): Double {
        var s = 0.0
        var c = 0.0
        for (i in from until to) {
            val w = 2 * PI * f * i / 16_000
            s += y[i] * sin(w)
            c += y[i] * cos(w)
        }
        return 2 * sqrt(s * s + c * c) / (to - from)
    }

    private fun db(ratio: Double) = 20 * log10(ratio)
}
