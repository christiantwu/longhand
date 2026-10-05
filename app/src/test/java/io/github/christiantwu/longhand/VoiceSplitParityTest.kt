package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.Span
import io.github.christiantwu.longhand.engine.VoiceSplit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks that [VoiceSplit] splits turns by voice exactly as the tuning harness's copy of it (tools/diarization_eval.py)
 * does on real calls, given the scores the harness's fingerprints gave each window. Runs only where
 * `tools/diarization_eval.py --fixtures` has written fixtures from local sample recordings, which never leave the machine.
 */
class VoiceSplitParityTest {

    private val dir = File("../sample_recordings/.cache/fixtures")

    @Test fun matchesTheHarnessOnSampleCalls() {
        val files = dir.listFiles { f -> f.name.startsWith("split") && f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no fixtures in $dir", files.isNotEmpty())
        for (file in files) {
            var speakers = emptyList<Int>()
            val turns = ArrayList<Span>()
            val windows = ArrayList<Pair<Float, FloatArray?>>()
            val expected = ArrayList<Span>()
            file.forEachLine { line ->
                val f = line.split(' ')
                when (f[0]) {
                    "speakers" -> speakers = f.drop(1).filter { it.isNotEmpty() }.map { it.toInt() }
                    "turn" -> turns += Span(f[1].toFloat(), f[2].toFloat(), f[3].toInt())
                    "window" -> windows += f[1].toFloat() to (if (f[2] == "none") null else FloatArray(f.size - 2) { f[it + 2].toFloat() })
                    "expect" -> expected += Span(f[1].toFloat(), f[2].toFloat(), f[3].toInt())
                }
            }
            var next = 0
            val split = VoiceSplit.resplit(turns, speakers) { start, end ->
                val (theirs, scores) = windows[next++]
                assertEquals("${file.name}: window $next", theirs, start, 1e-3f)
                assertEquals("${file.name}: window $next", VoiceSplit.WINDOW_SEC, end - start, 1e-3f)
                scores
            }
            assertEquals("${file.name}: windows scored", windows.size, next)
            assertEquals("${file.name}: speakers", expected.map { it.speaker }, split.map { it.speaker })
            expected.zip(split).forEach { (e, s) ->
                assertEquals("${file.name}: $e", e.start, s.start, 1e-3f)
                assertEquals("${file.name}: $e", e.end, s.end, 1e-3f)
            }
        }
    }
}
