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

    @Test fun spansAreCutOffAtTheEndOfTheCall() {
        // Diarization hears silence after the call: what it finds there isn't part of the call.
        val spans = listOf(Span(0f, 4f, 0), Span(3f, 10.4f, 1), Span(10f, 10.2f, 0), Span(10.2f, 10.5f, 2), Span(10.3f, 10.6f, 1))
        assertEquals(listOf(Span(0f, 4f, 0), Span(3f, 10.2f, 1), Span(10f, 10.2f, 0)), SegmentLogic.upTo(spans, 10.2f))
    }

    private fun withoutOverlaps(vararg turns: Span) = SegmentLogic.withoutOverlaps(turns.toList(), minLength = 0.2f)

    @Test fun anMmHmmSaidOverSomeoneGoesAndTheirTurnKeepsTheAudio() {
        assertEquals(listOf(Span(0f, 10f, 0)), withoutOverlaps(Span(0f, 10f, 0), Span(4f, 5.5f, 1)))
        // Two parts of a sentence kept apart only by the "mm-hmm" join up again.
        assertEquals(listOf(Span(0f, 9f, 0)), withoutOverlaps(Span(0f, 6f, 0), Span(4f, 5f, 1), Span(6.5f, 9f, 0)))
    }

    @Test fun aLongerTurnWithinAnotherCutsItInTwo() {
        assertEquals(listOf(Span(0f, 5f, 0), Span(5f, 10f, 1), Span(10f, 20f, 0)), withoutOverlaps(Span(0f, 20f, 0), Span(5f, 10f, 1)))
    }

    @Test fun turnsThatPartlyOverlapMeetInTheMiddle() {
        assertEquals(listOf(Span(0f, 9f, 0), Span(9f, 15f, 1)), withoutOverlaps(Span(0f, 10f, 0), Span(8f, 15f, 1)))
        // The same span twice: each has half.
        assertEquals(listOf(Span(0f, 2f, 0), Span(2f, 4f, 1)), withoutOverlaps(Span(0f, 4f, 1), Span(0f, 4f, 0)))
    }

    @Test fun aTurnWithinTwoThatOverlapSitsBetweenThem() {
        // 0 and 1 overlap from 10 to 20 and would meet at 15, where 2 starts.
        assertEquals(listOf(Span(0f, 15f, 0), Span(15f, 18f, 2), Span(18f, 30f, 1)),
            withoutOverlaps(Span(0f, 20f, 0), Span(10f, 30f, 1), Span(15f, 18f, 2)))
    }

    @Test fun piecesTooShortToRecogniseGo() {
        // 0's part before 1's turn would be 0.1 s.
        assertEquals(listOf(Span(0.1f, 5f, 1), Span(5f, 10f, 0)), withoutOverlaps(Span(0f, 10f, 0), Span(0.1f, 5f, 1)))
        // 1's turn within 0's meets 2's at 9.625 and 0's meets 2's at 9.75: the sliver of 0's between goes.
        assertEquals(listOf(Span(0f, 7.5f, 0), Span(7.5f, 9.625f, 1), Span(9.75f, 14f, 2)),
            withoutOverlaps(Span(0f, 10f, 0), Span(9.5f, 14f, 2), Span(7.5f, 9.75f, 1)))
    }

    @Test fun aSpeakersOwnTurnsThatOverlapAreOne() {
        // Speaker 0 heard twice at 8-10 (two of their clusters at once) and an "mm-hmm" from 1 inside.
        assertEquals(listOf(Span(0f, 12f, 0)), withoutOverlaps(Span(0f, 10f, 0), Span(5f, 6f, 1), Span(8f, 12f, 0)))
    }

    @Test fun aSpeakerMayBeLeftWithNoTurns() {
        assertEquals(listOf(Span(0f, 10f, 0), Span(12f, 15f, 2)),
            withoutOverlaps(Span(0f, 10f, 0), Span(2f, 3f, 1), Span(6f, 7f, 1), Span(12f, 15f, 2)))
    }

    @Test fun everyMomentIsHeardOnceAndNoneIsLost() {
        val random = kotlin.random.Random(7)
        repeat(200) {
            val turns = List(12) {
                val start = random.nextFloat() * 60f
                Span(start, start + 0.3f + random.nextFloat() * 8f, random.nextInt(3))
            }
            val out = SegmentLogic.withoutOverlaps(SegmentLogic.merge(turns), minLength = 0.2f)
            out.zipWithNext().forEach { (a, b) -> assert(a.end <= b.start) { "overlap: $a $b in $turns" } }
            out.forEach { assert(it.duration >= 0.2f) { "too short: $it in $turns" } }
            // Only pieces too short to recognise are left out (two side by side at most).
            fun covered(spans: List<Span>, t: Float) = spans.any { t >= it.start && t < it.end }
            var lost = 0f
            var t = 0f
            while (t < 70f) {
                lost = if (covered(turns, t) && !covered(out, t)) lost + 0.05f else 0f
                assert(lost <= 0.45f) { "lost up to $t of $turns: $out" }
                t += 0.05f
            }
        }
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
