package io.github.christiantwu.longhand.engine

import kotlin.math.sqrt

/**
 * Some recorders put each side of the call on its own channel. When that's the case the
 * channel already tells us who is speaking, which is more reliable than voice clustering.
 */
object StereoAnalysis {

    /**
     * True when the two channels carry different signals. Near-identical channels
     * (correlation above [maxCorrelation]) are just mono saved as stereo, and a channel
     * that is essentially silent carries no second speaker.
     */
    fun channelsAreDistinct(left: FloatArray, right: FloatArray, maxCorrelation: Double = 0.8): Boolean {
        val n = minOf(left.size, right.size)
        if (n == 0) return false
        var sl = 0.0
        var sr = 0.0
        var slr = 0.0
        // Sampling every 4th value is plenty for a correlation estimate on long calls.
        var i = 0
        while (i < n) {
            val l = left[i].toDouble()
            val r = right[i].toDouble()
            sl += l * l
            sr += r * r
            slr += l * r
            i += 4
        }
        if (sl == 0.0 || sr == 0.0) return false
        val energyRatio = maxOf(sl, sr) / minOf(sl, sr)
        if (energyRatio > 1000) return false
        val corr = slr / sqrt(sl * sr)
        return corr < maxCorrelation
    }

    fun rms(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, x.size)
        val b = to.coerceIn(a, x.size)
        if (b == a) return 0.0
        var s = 0.0
        for (i in a until b) s += x[i] * x[i]
        return sqrt(s / (b - a))
    }
}
