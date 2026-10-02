package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.StereoAnalysis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class StereoAnalysisTest {
    private val n = 16_000
    private val a = FloatArray(n) { sin(it * 0.05f) }
    private val b = FloatArray(n) { sin(it * 0.173f + 1f) }

    @Test fun duplicatedChannelsAreNotDistinct() = assertFalse(StereoAnalysis.channelsAreDistinct(a, a.copyOf()))
    @Test fun silentChannelIsNotDistinct() = assertFalse(StereoAnalysis.channelsAreDistinct(a, FloatArray(n)))
    @Test fun differentSignalsAreDistinct() = assertTrue(StereoAnalysis.channelsAreDistinct(a, b))
}
