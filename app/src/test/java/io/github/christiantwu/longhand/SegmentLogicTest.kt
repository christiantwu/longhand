package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.SegmentLogic
import io.github.christiantwu.longhand.engine.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentLogicTest {

    @Test fun mergesSameSpeakerAcrossShortGap() {
        val out = SegmentLogic.merge(listOf(Span(0f, 2f, 0), Span(2.5f, 4f, 0), Span(4.2f, 6f, 1)))
        assertEquals(listOf(Span(0f, 4f, 0), Span(4.2f, 6f, 1)), out)
    }

    @Test fun keepsSameSpeakerSeparateAcrossLongGap() {
        val out = SegmentLogic.merge(listOf(Span(0f, 2f, 0), Span(5f, 6f, 0)))
        assertEquals(2, out.size)
    }

    @Test fun doesNotMergeAcrossInterjection() {
        val out = SegmentLogic.merge(listOf(Span(0f, 5f, 0), Span(4f, 6f, 1), Span(6.5f, 9f, 0)))
        assertEquals(3, out.size)
    }

    @Test fun sortsBeforeMerging() {
        val out = SegmentLogic.merge(listOf(Span(3f, 4f, 0), Span(0f, 2f, 0)))
        assertEquals(listOf(Span(0f, 4f, 0)), out)
    }

    @Test fun relabelsByFirstAppearance() {
        val out = SegmentLogic.relabelByFirstAppearance(listOf(Span(5f, 6f, 0), Span(0f, 1f, 1), Span(2f, 3f, 2)))
        // First heard: old 1 (t=0) -> 0, old 2 (t=2) -> 1, old 0 (t=5) -> 2.
        assertEquals(listOf(2, 0, 1), out.map { it.speaker })
    }

    private fun pausesAt(vararg t: Float) = t.map { (it - 0.2f) to (it + 0.2f) }

    @Test fun shortTurnIsNotSplit() {
        assertEquals(listOf(5f to 20f), SegmentLogic.splitAtPauses(5f, 20f, pausesAt(10f), 25f))
    }

    @Test fun cutsAtLatestPauseWithinLimit() {
        val pieces = SegmentLogic.splitAtPauses(0f, 60f, pausesAt(10f, 20f, 30f, 40f, 50f), 25f)
        assertEquals(listOf(0f to 20f, 20f to 40f, 40f to 60f), pieces)
    }

    @Test fun hardCutsWithoutPauses() {
        assertEquals(listOf(0f to 25f, 25f to 50f, 50f to 60f), SegmentLogic.splitAtPauses(0f, 60f, emptyList(), 25f))
    }

    @Test fun ignoresPausesThatWouldLeaveTinyPieces() {
        // A pause 2 s in would leave a 2 s piece; better to cut hard at the limit.
        assertEquals(listOf(0f to 25f, 25f to 30f), SegmentLogic.splitAtPauses(0f, 30f, pausesAt(2f), 25f))
    }

    @Test fun piecesAreContiguousAndWithinLimit() {
        val pieces = SegmentLogic.splitAtPauses(3f, 100f, pausesAt(9f, 17f, 31f, 33f, 58f, 71f, 90f), 25f)
        assertEquals(3f, pieces.first().first)
        assertEquals(100f, pieces.last().second)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first) }
        pieces.forEach { assert(it.second - it.first <= 25f) { "too long: $it" } }
    }

    @Test fun cleansDollarSpacing() {
        assertEquals("around \$2,000, but", SegmentLogic.cleanText("around\$2,000, but"))
        assertEquals("costs \$5", SegmentLogic.cleanText("costs \$5"))
        assertEquals("a b", SegmentLogic.cleanText("  a   b "))
    }

    @Test fun formatsTimestamps() {
        assertEquals("00:00:00", SegmentLogic.formatTimestamp(0))
        assertEquals("00:01:23", SegmentLogic.formatTimestamp(83_900))
        assertEquals("01:02:03", SegmentLogic.formatTimestamp(3_723_000))
        assertEquals("4:05", SegmentLogic.formatDuration(245_000))
        assertEquals("04:05", SegmentLogic.formatClock(245_000))
        assertEquals("1:02:03", SegmentLogic.formatClock(3_723_000))
        assertEquals("1:02:03", SegmentLogic.formatDuration(3_723_000))
    }
}
