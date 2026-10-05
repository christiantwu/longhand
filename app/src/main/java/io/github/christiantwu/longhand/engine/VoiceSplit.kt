package io.github.christiantwu.longhand.engine

import kotlin.math.ceil
import kotlin.math.round

/**
 * Splits a long turn where another speaker's voice takes over. Diarization sometimes runs two people's speech into one
 * turn; listened to in shorter windows, the part that's someone else's sounds more like their voice than the turn's
 * speaker's.
 *
 * Each turn of at least [MIN_TURN_SEC] by a speaker with a voice is heard in windows of [WINDOW_SEC], one every
 * [HOP_SEC] from its start (and one more ending at its end, if the last stops short of it), each fingerprinted on its
 * own and scored against every speaker's voice. The turn is decided in cells of [CELL_SEC] from its start: a cell scores
 * the mean of the windows that cover it, and goes to the best other speaker when they score at least [MARGIN] more than
 * the turn's own. Less than [MIN_RUN_SEC] of another voice in a row goes back to the turn's speaker, and the turn is cut
 * where the voice changes.
 *
 * On sample calls, this put 1.4–2.4% more of the labelled speech on the right speaker in three of six and changed none
 * of the others. Each window takes about 10 ms to fingerprint on a desktop CPU; all of them, a sixth or so of the time
 * diarization takes.
 * tools/diarization_eval.py replays this, and VoiceSplitParityTest checks that the two decide alike.
 */
object VoiceSplit {

    /** Turns shorter than this (seconds) stay as they are. */
    const val MIN_TURN_SEC = 3f

    /** Each window fingerprinted is this long (seconds)... */
    const val WINDOW_SEC = 1.5f

    /** ...and starts this long (seconds) after the one before. */
    const val HOP_SEC = 0.75f

    /** A turn is decided in cells this long (seconds). */
    const val CELL_SEC = 0.25f

    /** A cell goes to another speaker who scores at least this much more than the turn's own. */
    const val MARGIN = 0.10

    /** Less than this (seconds) of another voice in a turn goes back to the turn's speaker. */
    const val MIN_RUN_SEC = 1f
    /** The shortest piece the recogniser takes (TranscriptionEngine.MIN_PIECE_SAMPLES). */
    const val MIN_PIECE_SEC = TranscriptionEngine.MIN_PIECE_SAMPLES.toFloat() / MODEL_SAMPLE_RATE

    /**
     * [turns] (in order, not overlapping) with each long one split by voice, then joined up again as [SegmentLogic.merge]
     * joins them; they still don't overlap.
     * @param speakers the speakers with a voice; with fewer than two, nothing changes.
     * @param onWindow after each window is scored: how many have been, of how many in all.
     * @param score a window's (start, end in seconds) scores against the voices of [speakers] in ascending order, or null
     * if it has no fingerprint. Called once for each window, in order.
     */
    fun resplit(
        turns: List<Span>,
        speakers: Collection<Int>,
        onWindow: (Int, Int) -> Unit = { _, _ -> },
        score: (Float, Float) -> FloatArray?,
    ): List<Span> {
        val ordered = speakers.distinct().sorted()
        if (ordered.size < 2) return turns
        val starts = turns.map { if (it.duration >= MIN_TURN_SEC && it.speaker in ordered) windows(it) else emptyList() }
        val total = starts.sumOf { it.size }
        var done = 0
        val out = ArrayList<Span>()
        turns.forEachIndexed { i, turn ->
            if (starts[i].isEmpty()) {
                out += turn
            } else {
                val scores = starts[i].map { a -> score(a, a + WINDOW_SEC).also { onWindow(++done, total) } }
                out += split(turn, ordered, starts[i], scores)
            }
        }
        return SegmentLogic.merge(out)
    }

    /**
     * The starts of the windows [turn] is heard in: one every [HOP_SEC] from its start while they fit, and one more ending
     * at its end if the last stops short of it. None if the turn is shorter than a window.
     */
    fun windows(turn: Span): List<Float> {
        val s = turn.start.toDouble()
        val last = turn.end.toDouble() - WINDOW_SEC
        val count = ceil((last + 1e-9 - s) / HOP_SEC).toInt()
        if (count <= 0) return emptyList()
        val out = MutableList(count) { (s + it * HOP_SEC.toDouble()).toFloat() }
        if (last - (s + (count - 1) * HOP_SEC.toDouble()) > 1e-6) out += last.toFloat()
        return out
    }

    /**
     * [turn] cut where another speaker's voice takes over, from its windows' [starts] ([windows]) and their [scores]
     * against the voices of [speakers] (ascending, the turn's own among them), null for a window with no fingerprint.
     * Cells no window covers at either end go with the nearest one that is covered.
     */
    fun split(turn: Span, speakers: List<Int>, starts: List<Float>, scores: List<FloatArray?>): List<Span> {
        val own = speakers.indexOf(turn.speaker)
        require(own >= 0) { "speaker ${turn.speaker} has no voice" }
        val s = turn.start.toDouble()
        val e = turn.end.toDouble()
        val cell = CELL_SEC.toDouble()
        val cells = ceil((e - s) / cell).toInt()
        if (cells <= 0) return listOf(turn)
        val sums = Array(cells) { DoubleArray(speakers.size) }
        val covering = IntArray(cells)
        for ((start, row) in starts.zip(scores)) {
            if (row == null) continue
            val a = start.toDouble()
            val from = round((a - s) / cell).toInt().coerceAtLeast(0)
            val to = minOf(cells, round((a + WINDOW_SEC - s) / cell).toInt())
            for (c in from until to) {
                for (j in speakers.indices) sums[c][j] += row[j].toDouble()
                covering[c]++
            }
        }
        val first = covering.indexOfFirst { it > 0 }
        if (first < 0) return listOf(turn)
        val last = covering.indexOfLast { it > 0 }

        val label = IntArray(cells) { c ->
            if (covering[c] == 0) return@IntArray own
            val mean = DoubleArray(speakers.size) { sums[c][it] / covering[c] }
            var best = -1
            for (j in speakers.indices) if (j != own && (best < 0 || mean[j] > mean[best])) best = j
            if (mean[best] - mean[own] >= MARGIN) best else own
        }
        for (c in 0 until first) label[c] = label[first]
        for (c in last + 1 until cells) label[c] = label[last]

        // Runs of cells with one speaker: first cell, last cell, speaker (as an index into speakers).
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < cells) {
            var j = i
            while (j + 1 < cells && label[j + 1] == label[i]) j++
            runs += intArrayOf(i, j, label[i])
            i = j + 1
        }
        fun startOf(c: Int) = s + c * cell
        fun endOf(c: Int) = minOf(e, s + (c + 1) * cell)
        for (run in runs) if (run[2] != own && endOf(run[1]) - startOf(run[0]) < MIN_RUN_SEC) run[2] = own

        val out = ArrayList<Span>()
        for ((from, to, who) in runs.map { Triple(it[0], it[1], speakers[it[2]]) }) {
            val prev = out.lastOrNull()
            if (prev != null && prev.speaker == who) out[out.lastIndex] = prev.copy(end = endOf(to).toFloat())
            else out += Span(startOf(from).toFloat(), endOf(to).toFloat(), who)
        }
        // A piece too short to recognise (the turn's last cell, cut short at its end) joins the piece next to it.
        var k = 0
        while (out.size > 1 && k < out.size) {
            val piece = out[k]
            if (piece.end - piece.start >= MIN_PIECE_SEC) { k++; continue }
            if (k > 0) out[k - 1] = out[k - 1].copy(end = piece.end) else out[1] = out[1].copy(start = piece.start)
            out.removeAt(k)
        }
        return out
    }
}
