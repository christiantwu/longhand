package io.github.christiantwu.longhand.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/** Growable float buffer; avoids boxing when decoding hours of audio. */
class FloatBuilder(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(16))
    var size = 0
        private set

    fun add(v: Float) {
        // Grow gently: the buffer is presized from the file's duration, so this is rare.
        if (size == data.size) data = data.copyOf(data.size + maxOf(data.size / 4, 16))
        data[size++] = v
    }

    /**
     * The samples collected. When at most [maxPadding] slots are unused, the buffer itself is
     * returned with a silent (zero) tail instead of copying; for an hour-long call that
     * avoids briefly holding a second ~230 MB array.
     */
    fun toArray(maxPadding: Int = 0): FloatArray = if (data.size - size <= maxPadding) data else data.copyOf(size)
}

/**
 * Streaming band-limited sample-rate converter to [outRate], fed one sample at a time.
 *
 * Polyphase Kaiser-windowed sinc: output sample n is the input at time n·inRate/outRate,
 * interpolated with a filter cut off just below the lower of the two Nyquist rates. That
 * removes the images linear interpolation used to leave above 4 kHz when 8 kHz call audio is
 * raised to 16 kHz; they made the speaker segmentation miss most changes of speaker.
 *
 * The output has no delay (an impulse stays at the same time), and after [finish] it is exactly
 * ceil(inputs · outRate / inRate) samples long.
 */
class StreamResampler(inRate: Int, outRate: Int, private val out: FloatBuilder) {
    private val passthrough = inRate == outRate
    // Every [down] input samples become [up] output samples (the rate ratio in lowest terms).
    private val up: Int
    private val down: Int
    private val half: Int // filter half-width, in input samples
    private val taps: Int
    private val rows: Array<FloatArray> // one filter per output phase
    private val history: FloatArray // the last [taps] inputs, stored twice so every window is contiguous
    private var pos = 0
    private var newest = -1L // index of the latest input
    private var base = 0L // whole part of the next output's input time
    private var phase = 0 // fractional part, in 1/up steps
    private var inputs = 0L
    private var outputs = 0L

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        val ratio = outRate.toDouble() / inRate
        val cutoff = 0.475 * minOf(1.0, ratio) // cycles per input sample
        half = ceil(16 * maxOf(1.0, 1 / ratio)).toInt()
        taps = 2 * half
        history = FloatArray(2 * taps)
        val phases = minOf(up, MAX_PHASES)
        rows = Array(if (passthrough) 0 else phases) { p ->
            val frac = p.toDouble() / phases
            val row = DoubleArray(taps) { i ->
                val u = frac + half - 1 - i // distance from the output time to input sample i
                2 * cutoff * sinc(2 * cutoff * u) * kaiser(u / half)
            }
            val sum = row.sum()
            FloatArray(taps) { (row[it] / sum).toFloat() } // gain of exactly 1 for a steady input
        }
    }

    fun push(x: Float) {
        if (passthrough) {
            out.add(x)
            return
        }
        inputs++
        add(x)
    }

    /** Emits the last outputs, which need input beyond the end; call once after the final [push]. */
    fun finish() {
        if (passthrough) return
        val total = (inputs * up + down - 1) / down
        while (outputs < total) add(0f)
    }

    private fun add(x: Float) {
        history[pos] = x
        history[pos + taps] = x
        pos = (pos + 1) % taps
        newest++
        while (newest == base + half) emit()
    }

    private fun emit() {
        val row = rows[if (up <= MAX_PHASES) phase else ((phase.toLong() * MAX_PHASES) / up).toInt()]
        var acc = 0f
        for (i in 0 until taps) acc += history[pos + i] * row[i]
        out.add(acc)
        outputs++
        phase += down
        base += phase / up
        phase %= up
    }

    private companion object {
        /** Rates with more phases than this (unusual ones) round the phase; standard rates stay exact. */
        const val MAX_PHASES = 4096
        const val BETA = 8.0

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        fun kaiser(r: Double): Double = if (abs(r) > 1) 0.0 else besselI0(BETA * sqrt(1 - r * r)) / besselI0(BETA)

        /** Modified Bessel function of the first kind, order 0, by its power series. */
        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                k++
            }
            return sum
        }
    }
}
