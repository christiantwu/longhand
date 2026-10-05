package io.github.christiantwu.longhand.engine

/** A stretch of speech attributed to one speaker, in seconds from the start of the recording. */
data class Span(val start: Float, val end: Float, val speaker: Int) {
    val duration: Float get() = end - start
}

/** One transcribed line, as stored and displayed, with when each of its words was said if the recogniser told. */
data class TranscriptLine(val startMs: Long, val endMs: Long, val speaker: Int, val text: String, val words: List<WordTime>? = null)

object SegmentLogic {

    /**
     * Sorts spans by start time and joins neighbours from the same speaker when the pause
     * between them is at most [maxGap] seconds. Spans from different speakers are never
     * merged, so an interjection by the other person keeps its own line.
     */
    fun merge(spans: List<Span>, maxGap: Float = 1.0f): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == s.speaker && s.start - last.end <= maxGap) {
                out[out.lastIndex] = last.copy(end = maxOf(last.end, s.end))
            } else {
                out += s
            }
        }
        return out
    }

    /** [spans] cut off at [end] (seconds), those starting there or after it gone. */
    fun upTo(spans: List<Span>, end: Float): List<Span> =
        spans.filter { it.start < end }.map { if (it.end > end) it.copy(end = end) else it }

    /**
     * Speaker turns made not to overlap, so that each moment is recognised once: turns heard on the mono mix that
     * overlap would have the overlap transcribed twice, the same words under both speakers.
     * - A turn of at most [backchannel] seconds lying within another speaker's (an "mm-hmm" said over them) goes, and
     *   the surrounding turn keeps its audio.
     * - A longer turn lying within another speaker's keeps its span, and the outer turn is cut into the parts before
     *   and after it.
     * - Two turns that partly overlap meet in the middle of the overlap.
     *
     * A speaker's own turns that overlap are joined first. Pieces left shorter than [minLength] seconds go (too short
     * to recognise), and what's left of a speaker's speech joins up again as [merge] joins it, where nobody else now
     * speaks in between: two parts of a sentence an "mm-hmm" kept apart. A speaker may be left with no turns at all.
     */
    fun withoutOverlaps(turns: List<Span>, minLength: Float, backchannel: Float = BACKCHANNEL_SEC): List<Span> {
        val joined = turns.groupBy { it.speaker }.values.flatMap { merge(it, maxGap = 0f) }
            .filter { it.end > it.start }
            .sortedWith(compareBy<Span>({ it.start }, { it.end }, { it.speaker }))
        // Shorter, and inside the other's span.
        fun Span.within(o: Span) = speaker != o.speaker && o.start <= start && end <= o.end && duration < o.duration
        val kept = joined.filter { t -> t.duration > backchannel || joined.none { t.within(it) } }

        // Partly overlapping turns (or two with the same span) meet in the middle of the overlap: the one that starts
        // first ends there, the other starts there. A turn's middles with turns starting before it all come before its
        // middles with turns starting after it, so none ends before it starts.
        val starts = FloatArray(kept.size) { kept[it].start }
        val ends = FloatArray(kept.size) { kept[it].end }
        for (i in kept.indices) {
            for (j in i + 1 until kept.size) {
                val a = kept[i]
                val b = kept[j]
                if (b.start >= a.end) break
                if (a.within(b) || b.within(a)) continue
                val middle = (b.start + minOf(a.end, b.end)) / 2
                ends[i] = minOf(ends[i], middle)
                starts[j] = maxOf(starts[j], middle)
            }
        }
        // A turn lying within another keeps its span, which the outer turn loses.
        val pieces = kept.indices.flatMap { i ->
            var parts = listOf(starts[i] to ends[i])
            for (j in kept.indices) {
                if (!kept[j].within(kept[i])) continue
                parts = parts.flatMap { (a, b) ->
                    listOf(a to minOf(b, starts[j]), maxOf(a, ends[j]) to b).filter { it.second > it.first }
                }
            }
            parts.map { (a, b) -> Span(a, b, kept[i].speaker) }
        }
        return merge(pieces.filter { it.duration >= minLength })
    }

    /** At most this long (seconds) and said within someone else's turn, a turn is an "mm-hmm": [withoutOverlaps]. */
    const val BACKCHANNEL_SEC = 1.5f

    /**
     * Renumbers speakers in order of first appearance, so whoever talks first is
     * speaker 0 ("Speaker 1"). The diarizer's cluster ids are otherwise arbitrary.
     */
    fun relabelByFirstAppearance(spans: List<Span>): List<Span> {
        val mapping = LinkedHashMap<Int, Int>()
        for (s in spans.sortedBy { it.start }) mapping.getOrPut(s.speaker) { mapping.size }
        return spans.map { it.copy(speaker = mapping.getValue(it.speaker)) }
    }

    /**
     * Splits [start, end] into contiguous pieces of at most [maxLen] seconds, cutting in the
     * middle of a pause so no word is cut and no audio is dropped. Each cut goes at the latest
     * pause that keeps the piece within [maxLen] (and longer than [minLen], to avoid tiny
     * pieces the recognizer handles poorly); with no usable pause it cuts at exactly [maxLen].
     *
     * @param pauses silent intervals (start to end, seconds) inside the range.
     */
    fun splitAtPauses(
        start: Float,
        end: Float,
        pauses: List<Pair<Float, Float>>,
        maxLen: Float,
        minLen: Float = maxLen / 3,
    ): List<Pair<Float, Float>> {
        val cuts = pauses.map { (it.first + it.second) / 2 }.filter { it > start && it < end }.sorted()
        val out = ArrayList<Pair<Float, Float>>()
        var s = start
        while (end - s > maxLen) {
            val limit = s + maxLen
            val cut = cuts.lastOrNull { it > s + minLen && it <= limit } ?: limit
            out += s to cut
            s = cut
        }
        out += s to end
        return out
    }

    private val dollarAfterWord = Regex("""(?<=[A-Za-z0-9,.])\$(?=\d)""")
    private val spaces = Regex("""\s+""")

    /** Small fixes for the recognizer's detokenization, e.g. "around$2,000" -> "around $2,000". */
    fun cleanText(text: String): String =
        text.replace(dollarAfterWord, " \\$").replace(spaces, " ").trim()

    /** Formats a position as HH:MM:SS, which sorts and reads well for calls of any length. */
    fun formatTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    }

    /** Compact clock for summary prompts: "04:05" or "1:02:03". */
    fun formatClock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, (total / 60) % 60, total % 60) else "%02d:%02d".format(total / 60, total % 60)
    }

    /** Human duration for list rows: "4:05" or "1:02:03". */
    fun formatDuration(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
