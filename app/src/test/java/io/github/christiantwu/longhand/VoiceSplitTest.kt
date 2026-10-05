package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.Span
import io.github.christiantwu.longhand.engine.VoiceSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSplitTest {

    // Scores of a window against the voices of speakers 0 and 1, in that order.
    private val sounds0 = floatArrayOf(0.8f, 0.3f)
    private val sounds1 = floatArrayOf(0.3f, 0.8f)

    /** [turn] split with the given scores for its windows, against speakers 0 and 1. */
    private fun split(turn: Span, vararg scores: FloatArray?): List<Span> {
        val starts = VoiceSplit.windows(turn)
        assertEquals(starts.size, scores.size)
        return VoiceSplit.split(turn, listOf(0, 1), starts, scores.toList())
    }

    @Test fun windowsStartEveryThreeQuartersOfASecondAndTheLastEndsWithTheTurn() {
        assertEquals(listOf(10f, 10.75f, 11.5f), VoiceSplit.windows(Span(10f, 13f, 0)))
        // 2.25 s + 1.5 s stops short of the end: one more window ends exactly there.
        assertEquals(listOf(0f, 0.75f, 1.5f, 2.25f, 2.5f), VoiceSplit.windows(Span(0f, 4f, 0)))
        assertEquals(listOf(5f), VoiceSplit.windows(Span(5f, 6.5f, 0)))
        assertEquals(emptyList<Float>(), VoiceSplit.windows(Span(5f, 6f, 0)))
    }

    @Test fun whereAnotherVoiceTakesOverTheTurnIsSplit() {
        // A 6 s turn heard in seven windows; the last three sound like speaker 1. Cells covered by windows of both say
        // nothing either way (equal means), so the split comes where only speaker 1's windows cover a cell.
        val turn = Span(0f, 6f, 0)
        assertEquals(listOf(Span(0f, 3.75f, 0), Span(3.75f, 6f, 1)),
            split(turn, sounds0, sounds0, sounds0, sounds0, sounds1, sounds1, sounds1))
        // Speaker 1 started the turn instead.
        assertEquals(listOf(Span(0f, 2.25f, 1), Span(2.25f, 6f, 0)),
            split(turn, sounds1, sounds1, sounds1, sounds0, sounds0, sounds0, sounds0))
    }

    @Test fun lessThanASecondOfAnotherVoiceStays() {
        // One window sounds like speaker 1, and only where it overlaps the one before (2.25-3 s) do the two average
        // enough to move: 0.75 s, too little to go by.
        val turn = Span(0f, 6f, 0)
        assertEquals(listOf(turn),
            split(turn, sounds0, sounds0, floatArrayOf(0.6f, 0.3f), floatArrayOf(0f, 1f), floatArrayOf(0.9f, 0f), sounds0, sounds0))
    }

    @Test fun anotherVoiceMustSoundClearlyCloser() {
        val turn = Span(0f, 3f, 0)
        val slightly = floatArrayOf(0.5f, 0.55f)
        assertEquals(listOf(turn), split(turn, slightly, slightly, slightly))
        // Clearly closer throughout: the whole turn is theirs.
        val clearly = floatArrayOf(0.5f, 0.65f)
        assertEquals(listOf(Span(0f, 3f, 1)), split(turn, clearly, clearly, clearly))
    }

    @Test fun cellsNoWindowCoversGoWithTheNearestThatIs() {
        // 4.1 s: the last window (2.6-4.1 s) covers the cells it mostly overlaps, so the last cell (4-4.1 s) has no
        // window and goes with the one before it, to the end of the turn.
        val turn = Span(0f, 4.1f, 0)
        assertEquals(listOf(Span(0f, 2.5f, 0), Span(2.5f, 4.1f, 1)),
            split(turn, sounds0, sounds0, floatArrayOf(0.9f, 0.2f), floatArrayOf(0.2f, 0.9f), floatArrayOf(0.2f, 0.9f)))
        // A window with no fingerprint covers nothing: the start of the turn goes with what follows.
        val short = Span(0f, 3f, 0)
        assertEquals(listOf(Span(0f, 3f, 1)), split(short, null, sounds1, sounds1))
        assertEquals(listOf(short), split(short, null, null, null))
    }

    @Test fun onlyLongTurnsOfSpeakersWithAVoiceAreHeardAgain() {
        val turns = listOf(Span(0f, 2.9f, 0), Span(3f, 9f, 2), Span(9.5f, 15.5f, 0))
        val heard = ArrayList<Pair<Float, Float>>()
        val out = VoiceSplit.resplit(turns, setOf(1, 0)) { start, end -> sounds0.also { heard += start to end } }
        assertEquals(turns, out)
        // Speaker 0's 6 s turn only, in seven windows of 1.5 s; speaker 2 has no voice.
        assertEquals(List(7) { 9.5f + it * 0.75f }, heard.map { it.first })
        assertTrue(heard.all { (start, end) -> end - start == VoiceSplit.WINDOW_SEC })

        // With one voice, there's no one else it could be.
        VoiceSplit.resplit(turns, setOf(0)) { _, _ -> throw AssertionError("scored with one voice") }
    }

    @Test fun aSplitOffPieceJoinsTheSameSpeakersTurnBeforeIt() {
        // Speaker 1's answer runs on into speaker 0's turn, which diarization started too early.
        val turns = listOf(Span(0f, 4f, 1), Span(4.5f, 10.5f, 0))
        val progress = ArrayList<Pair<Int, Int>>()
        val out = VoiceSplit.resplit(turns, listOf(0, 1), onWindow = { done, total -> progress += done to total }) { start, _ ->
            if (start < 6.5f) sounds1 else sounds0
        }
        assertEquals(listOf(Span(0f, 6.75f, 1), Span(6.75f, 10.5f, 0)), out)
        // Five windows for the first turn, seven for the second.
        assertEquals(List(12) { it + 1 to 12 }, progress)
    }

    @Test fun turnsNeverOverlapAndNoneIsLost() {
        val random = kotlin.random.Random(11)
        repeat(200) {
            var t = 0f
            val turns = List(10) {
                t += random.nextFloat() * 2f
                val turn = Span(t, t + 0.5f + random.nextFloat() * 9f, random.nextInt(3))
                t = turn.end
                turn
            }
            val out = VoiceSplit.resplit(turns, listOf(0, 1, 2)) { _, _ -> FloatArray(3) { random.nextFloat() } }
            out.zipWithNext().forEach { (a, b) -> assertTrue("overlap: $a $b in $turns", a.end <= b.start) }
            for (turn in turns) {
                val covered = out.filter { it.start < turn.end && turn.start < it.end }
                    .sumOf { (minOf(it.end, turn.end) - maxOf(it.start, turn.start)).toDouble() }
                assertEquals("$turn in $out", turn.duration.toDouble(), covered, 1e-4)
            }
        }
    }
}
