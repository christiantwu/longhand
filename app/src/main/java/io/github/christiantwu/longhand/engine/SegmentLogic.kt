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
