package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.SpeakerResolver
import io.github.christiantwu.longhand.engine.Span
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks that [SpeakerResolver] decides exactly as the tuning harness's copy of it
 * (tools/diarization_eval.py) on real calls. Runs only where `tools/diarization_eval.py --fixtures`
 * has written fixtures from local sample recordings, which never leave the machine.
 */
class SpeakerResolverParityTest {

    private val dir = File("../sample_recordings/.cache/fixtures")

    @Test fun matchesTheHarnessOnSampleCalls() {
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no fixtures in $dir", files.isNotEmpty())
        for (file in files) {
            val spans = ArrayList<Span>()
            val plan = HashMap<Int, List<Pair<Float, Float>>>()
            val prints = HashMap<Int, FloatArray>()
            var owner: FloatArray? = null
            var expected = emptyList<Int>()
            file.forEachLine { line ->
                val f = line.split(' ')
                when (f[0]) {
                    "span" -> spans += Span(f[1].toFloat(), f[2].toFloat(), f[3].toInt())
                    "plan" -> plan[f[1].toInt()] = f.drop(2).chunked(2) { (a, b) -> a.toFloat() to b.toFloat() }
                    "fp" -> prints[f[1].toInt()] = FloatArray(f.size - 2) { f[it + 2].toFloat() }
                    "owner" -> owner = FloatArray(f.size - 1) { f[it + 1].toFloat() }
                    "expect" -> expected = f.drop(1).map { it.toInt() }
                }
            }
            val ourPlan = SpeakerResolver.fingerprintPlan(spans)
            assertEquals("${file.name}: clusters fingerprinted", plan.keys, ourPlan.keys)
            for ((cluster, parts) in plan) {
                val ours = ourPlan.getValue(cluster)
                assertEquals("${file.name}: parts of cluster $cluster", parts.size, ours.size)
                parts.zip(ours).forEach { (p, o) ->
                    assertEquals(p.first, o.first, 1e-3f)
                    assertEquals(p.second, o.second, 1e-3f)
                }
            }
            assertEquals(file.name, expected, SpeakerResolver.resolve(spans, prints, owner).spans.map { it.speaker })
        }
    }
}
