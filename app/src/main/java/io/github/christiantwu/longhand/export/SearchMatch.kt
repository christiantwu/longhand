package io.github.christiantwu.longhand.export

import java.text.BreakIterator

/** Where search text occurs in a transcript line, for showing and highlighting it. */
object SearchMatch {

    /** Text to show with the [matches] in it highlighted (ranges into [text]). */
    data class Excerpt(val text: String, val matches: List<IntRange>)

    /**
     * Every occurrence of [query] (trimmed) in [text], ignoring case, from left to right without overlaps. Each one is
     * widened to whole characters as they're drawn (grapheme clusters): search text that ends inside one, like "क"
     * inside "कि", would otherwise split it between two styles, and Android then draws the vowel sign on its own,
     * on a dotted circle. The same goes for a letter and its accent.
     */
    fun occurrences(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val clusters by lazy { clusterBreaks(text) } // most lines have no match
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) return out
            val start = if (clusters.isBoundary(i)) i else clusters.preceding(i)
            val end = if (clusters.isBoundary(i + q.length)) i + q.length else clusters.following(i + q.length)
            out += start until end
            from = end
        }
    }

    private fun clusterBreaks(text: String): BreakIterator = BreakIterator.getCharacterInstance().apply { setText(text) }

    /**
     * [line] from about [context] columns before the first occurrence of [query] (Chinese, Japanese
     * and Korean characters count two, being twice as wide), with "…" in front when the start is cut;
     * the end is left to the text's own ellipsis. Kept short so the match shows within two lines on a
     * narrow phone. The cut falls at the start of a word unless one word runs up to the match, and never
     * inside a character as drawn. A line without an occurrence is shown whole.
     */
    fun excerpt(line: String, query: String, context: Int = 24): Excerpt {
        val all = occurrences(line, query)
        val first = all.firstOrNull()?.first ?: return Excerpt(line, emptyList())
        var start = first
        var columns = 0
        while (start > 0) {
            val width = if (wide(line[start - 1])) 2 else 1
            if (columns + width > context) break
            columns += width
            start--
        }
        if (start > 0) {
            (start - 1 until first).firstOrNull { line[it].isWhitespace() }?.let { start = it + 1 }
            while (start < first && line[start].isWhitespace()) start++
            // Not inside a character as drawn, such as an emoji or a syllable with its vowel sign.
            val clusters = clusterBreaks(line)
            if (!clusters.isBoundary(start)) start = clusters.following(start).coerceAtMost(first)
        }
        if (start == 0) return Excerpt(line, all)
        val shift = 1 - start // for the "…"
        return Excerpt("…" + line.substring(start), all.map { it.first + shift..it.last + shift })
    }

    private fun wide(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        else -> c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFF60'
    }
}
