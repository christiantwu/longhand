package io.github.christiantwu.longhand.engine

import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.export.TranscriptFormatter
import java.lang.Character.UnicodeScript
import java.util.Locale
import kotlin.math.roundToLong

/** When a recognised word was said: its start and end, in ms from the start of its line. */
data class WordTime(val startMs: Long, val endMs: Long) {
    /** Moved by [ms], for a line that now starts elsewhere; never before the line's start. */
    fun shift(ms: Long) = WordTime((startMs + ms).coerceAtLeast(0), (endMs + ms).coerceAtLeast(0))
}

/**
 * One piece's text as the recogniser wrote it (cleaned up, and in English with its numbers in digits: [SpokenNumbers]),
 * with when each of its [Words] was said, if known.
 */
class Recognized(val text: String, val words: List<WordTime>?)

/**
 * The words of a line, as editing sees them: what lies between spaces, except that Chinese and Japanese, written
 * without spaces, have a word for each character (Korean has spaces). Punctuation stays with the word before it.
 * Word timings are stored one per word, so the same text must always divide the same way.
 */
object Words {

    /** Where each word of [text] is. */
    fun ranges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        var prev = -1 // the code point before, or -1 after a space
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (start >= 0) out += start until i
                start = -1
                prev = -1
            } else {
                // A Chinese or Japanese character is a word of its own, and so is a run of letters or digits after one.
                val begins = start < 0 || isCjk(cp) ||
                    (prev >= 0 && (isCjk(prev) || isCjkPunctuation(prev)) && Character.isLetterOrDigit(cp))
                if (begins) {
                    if (start >= 0) out += start until i
                    start = i
                }
                prev = cp
            }
            i += Character.charCount(cp)
        }
        if (start >= 0) out += start until text.length
        return out
    }

    fun of(text: String): List<String> = ranges(text).map { text.substring(it) }

    /** Chinese characters and kana, which Corrections also treats as written without spaces. */
    fun isCjk(cp: Int): Boolean {
        val script = UnicodeScript.of(cp)
        return script == UnicodeScript.HAN || script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA ||
            cp == 0x30FC || cp == 0xFF70
    }

    /** Chinese and Japanese punctuation (。、「」) and full-width forms (，！？). */
    private fun isCjkPunctuation(cp: Int) = cp in 0x3000..0x303F || (cp in 0xFF00..0xFFEF && !Character.isLetterOrDigit(cp))
}

/**
 * Word timings: when each word of a line was said, kept so a line can later be split between two words at the right
 * moment. They come from sherpa-onnx's tokens and their times, and belong to the words of what the recogniser wrote
 * (Segment.recognized, or the text when that's the same), in order.
 */
object WordTimings {

    /** After the last token, when nothing says how long it lasts. */
    private const val LAST_TOKEN_SEC = 0.3f

    /** As stored: each word's "start,end" in centiseconds from the line's start, separated by spaces. */
    fun encode(words: List<WordTime>): String = words.joinToString(" ") { "${cs(it.startMs)},${cs(it.endMs)}" }

    private fun cs(ms: Long) = (ms.coerceAtLeast(0) + 5) / 10

    /** The stored timings, or null when there are none (or they can't be read). */
    fun decode(stored: String?): List<WordTime>? {
        if (stored == null) return null
        if (stored.isEmpty()) return emptyList()
        return stored.split(' ').map { pair ->
            val comma = pair.indexOf(',')
            if (comma < 0) return null
            val start = pair.substring(0, comma).toLongOrNull() ?: return null
            val end = pair.substring(comma + 1).toLongOrNull() ?: return null
            WordTime(start * 10, end * 10)
        }
    }

    /**
     * When each word of [line]'s text was said, as far as its timings tell: the words of its text are matched to those
     * of what the recogniser wrote, and a word that isn't there (corrected, or typed by hand) has none. Null when the
     * line has no timings.
     */
    fun of(line: Segment): List<WordTime?>? {
        val times = decode(line.words) ?: return null
        val source = line.recognized ?: line.text
        if (Words.ranges(source).size != times.size) return null
        if (line.recognized == null) return times
        return WordAlignment.align(Words.of(line.text), Words.of(source)).map { j -> if (j >= 0) times[j] else null }
    }

    /**
     * Word timings from a recogniser's result: [tokens] (a word starts with a space, as sherpa-onnx writes BPE pieces;
     * SenseVoice writes Chinese and Japanese a character at a time) with their [starts] in seconds and, for TDT models
     * (Parakeet), their [durations]; otherwise a token lasts until the next starts. A token's time is shared out among
     * its characters, and each word of [text] (the result's text after SegmentLogic.cleanText) runs from its first
     * letter or digit to its last. [lead] is silence added before the piece, whose time is taken off; [length] is the
     * piece's length in seconds. Tags such as `<|en|>` aren't text. Null when the tokens don't spell out [text].
     */
    fun fromTokens(
        text: String, tokens: Array<String>, starts: FloatArray, durations: FloatArray?, lead: Float, length: Float,
    ): List<WordTime>? {
        if (text.isEmpty() || tokens.size != starts.size) return null
        val kept = tokens.indices.filter { !isTag(tokens[it]) }
        if (kept.isEmpty()) return null
        val from = FloatArray(kept.size)
        var last = 0f
        kept.forEachIndexed { k, i ->
            last = maxOf(last, (starts[i] - lead).coerceIn(0f, length))
            from[k] = last
        }
        // TDT models give each token a duration, which may be 0 (the next token starts at once).
        val timed = durations != null && durations.size == tokens.size && durations.any { it > 0f }
        val raw = StringBuilder()
        val charFrom = ArrayList<Float>()
        val charTo = ArrayList<Float>()
        kept.forEachIndexed { k, i ->
            val to = when {
                timed -> from[k] + durations[i]
                k + 1 < kept.size -> from[k + 1]
                else -> from[k] + LAST_TOKEN_SEC
            }.coerceIn(from[k], maxOf(from[k], length))
            val token = tokens[i]
            val letters = token.count { !it.isWhitespace() }
            var j = 0
            for (c in token) {
                raw.append(c)
                if (c.isWhitespace()) {
                    charFrom += Float.NaN
                    charTo += Float.NaN
                } else {
                    charFrom += from[k] + (to - from[k]) * j / letters
                    charTo += from[k] + (to - from[k]) * (j + 1) / letters
                    j++
                }
            }
        }
        if (SegmentLogic.cleanText(raw.toString()) != text) return null
        // Cleaning only adds and removes spaces, so the text's other characters are the tokens', in order.
        val at = IntArray(text.length) { -1 }
        var r = 0
        for (i in text.indices) {
            if (text[i].isWhitespace()) continue
            while (r < raw.length && raw[r] != text[i]) {
                if (!raw[r].isWhitespace()) return null
                r++
            }
            if (r == raw.length) return null
            at[i] = r++
        }
        // A word's time is its letters' and digits': punctuation after it can take a while ("Hello," then a pause).
        return Words.ranges(text).map { w ->
            val letters = w.filter { isWordChar(text[it]) }
            val a = at[letters.firstOrNull() ?: w.first]
            val b = at[letters.lastOrNull() ?: w.last]
            if (a < 0 || b < 0) return null
            WordTime(ms(charFrom[a]), ms(charTo[b]))
        }
    }

    private fun ms(sec: Float): Long = (sec * 1000).roundToLong()

    /** Letters and digits, and the marks written with them (Devanagari vowel signs, accents). */
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isTag(token: String) = token.length > 2 && token.startsWith("<") && token.endsWith(">")
}

/** Which words of one text are which words of another, for texts that differ by corrections or a hand edit. */
object WordAlignment {

    /** Beyond this (words × words), the changed middle of two long texts isn't matched up. */
    private const val MAX_CELLS = 4_000_000L

    /**
     * For each word of [a], the index of the same word in [b], or -1: the longest common sequence of words, ignoring
     * case and punctuation.
     */
    fun align(a: List<String>, b: List<String>): IntArray {
        val ka = a.map(::key)
        val kb = b.map(::key)
        val out = IntArray(a.size) { -1 }
        var p = 0
        while (p < a.size && p < b.size && ka[p] == kb[p]) {
            out[p] = p
            p++
        }
        var s = 0
        while (s < a.size - p && s < b.size - p && ka[a.size - 1 - s] == kb[b.size - 1 - s]) {
            out[a.size - 1 - s] = b.size - 1 - s
            s++
        }
        val n = a.size - p - s
        val m = b.size - p - s
        if (n == 0 || m == 0 || n.toLong() * m > MAX_CELLS) return out
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (ka[p + i] == kb[p + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                ka[p + i] == kb[p + j] -> {
                    out[p + i] = p + j
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    /** What two words are compared by: their letters and digits in lower case (a word of punctuation alone, as it is). */
    fun key(word: String): String = word.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.ifEmpty { word }
}

/**
 * Changes made by hand to a turn: the lines (segments) shown together as one paragraph. Pure, so the rules can be
 * tested; RecordingDao stores the result.
 */
object TranscriptEdits {

    /** A word of a turn: its line (the index among the turn's lines), its place in that line's text, and the word. */
    data class TurnWord(val line: Int, val index: Int, val text: String)

    /** The turn's words, line by line: what Split shows to choose from. */
    fun words(lines: List<Segment>): List<TurnWord> =
        lines.flatMapIndexed { k, line -> Words.of(line.text).mapIndexed { i, w -> TurnWord(k, i, w) } }

    /** The turn's text, as shown. */
    fun text(lines: List<Segment>): String = lines.map { it.text }.reduce(TranscriptFormatter::join)

    /** The turn's text as the recogniser wrote it, before common corrections and edits by hand. */
    fun recognized(lines: List<Segment>): String = lines.map { it.recognized ?: it.text }.reduce(TranscriptFormatter::join)

    /** Typed text as stored: trimmed, with single spaces (a line break typed in the box included). */
    fun clean(typed: String): String = typed.trim().replace(Regex("\\s+"), " ")

    /**
     * The turn's [lines] (in order) as one line saying [typed], typed by hand: it covers the whole turn, keeps the first
     * line's id, and what the recogniser wrote and the word timings of all of them, joined. A turn's lines follow one
     * another with nobody else starting in between, so nothing else moves.
     */
    fun edit(lines: List<Segment>, typed: String): Segment {
        val start = lines.minOf { it.startMs }
        val first = lines.first { it.startMs == start }
        val text = clean(typed)
        val source = recognized(lines)
        return first.copy(
            startMs = start, endMs = lines.maxOf { it.endMs }, text = text,
            recognized = source.takeIf { it != text }, words = joinedWords(lines, start, source), edited = true,
        )
    }

    /** The lines' word timings as one line's, from [start]; null unless every line has them. */
    private fun joinedWords(lines: List<Segment>, start: Long, source: String): String? {
        val all = ArrayList<WordTime>()
        for (line in lines) {
            val times = WordTimings.decode(line.words) ?: return null
            if (times.size != Words.ranges(line.recognized ?: line.text).size) return null
            times.mapTo(all) { it.shift(line.startMs - start) }
        }
        return if (all.size == Words.ranges(source).size) WordTimings.encode(all) else null
    }

    /** The turn's [lines] given to [speaker]. */
    fun reassign(lines: List<Segment>, speaker: Int): List<Segment> = lines.map { it.copy(speaker = speaker) }

    /**
     * The turn's [lines] (in order) split before word [at] of [words]: what comes before it goes to speaker [before],
     * the rest to [after]. When the word starts a line, the lines only change speaker; otherwise its line is cut in two
     * ([cutLine]), and the second part is a new line (id 0).
     */
    fun split(lines: List<Segment>, at: Int, before: Int, after: Int): List<Segment> {
        val word = words(lines)[at]
        require(at > 0) { "nothing comes before the first word" }
        return lines.flatMapIndexed { k, line ->
            when {
                k < word.line -> listOf(line.copy(speaker = before))
                k > word.line || word.index == 0 -> listOf(line.copy(speaker = after))
                else -> cutLine(line, word.index).let { (head, tail) -> listOf(head.copy(speaker = before), tail.copy(speaker = after)) }
            }
        }
    }

    /**
     * [line] cut before its word [word] at [cutTime]: its text, what the recogniser wrote and the word timings divided
     * the same way. Where what the recogniser wrote can't be divided there (one word of it became several), both parts
     * keep only their text, as typed by hand.
     */
    fun cutLine(line: Segment, word: Int): Pair<Segment, Segment> {
        val ranges = Words.ranges(line.text)
        require(word in 1 until ranges.size) { "word $word of ${ranges.size}" }
        val cut = cutTime(line, word)
        val at = ranges[word].first
        val headText = line.text.substring(0, at).trimEnd()
        val tailText = line.text.substring(at).trimStart()
        val source = line.recognized ?: line.text
        val sourceRanges = Words.ranges(source)
        val times = WordTimings.decode(line.words)?.takeIf { it.size == sourceRanges.size }
        val k = if (line.recognized == null) word else sourceWord(WordAlignment.align(Words.of(line.text), Words.of(source)), sourceRanges.size, word)
        val tail = Segment(recordingId = line.recordingId, startMs = cut, endMs = line.endMs, speaker = line.speaker, text = tailText)
        if (k !in 1 until sourceRanges.size) {
            return line.copy(endMs = cut, text = headText, recognized = null, words = null, edited = true) to tail.copy(edited = true)
        }
        val c = sourceRanges[k].first
        val headSource = source.substring(0, c).trimEnd()
        val tailSource = source.substring(c).trimStart()
        return line.copy(
            endMs = cut, text = headText, recognized = headSource.takeIf { it != headText },
            words = times?.let { WordTimings.encode(it.subList(0, k)) },
        ) to tail.copy(
            recognized = tailSource.takeIf { it != tailText }, edited = line.edited,
            words = times?.let { t -> WordTimings.encode(t.subList(k, t.size).map { it.shift(line.startMs - cut) }) },
        )
    }

    /**
     * Where a line is cut before its word [word]: halfway between the end of the word before and the start of this one
     * (in tests on synthetic calls, within 140 ms of the real change of speaker even with no pause). With the timing of
     * only one of them, at that; with neither (a transcript from before 0.9.0, or words typed by hand), in proportion
     * to the characters before the word (up to about 0.4 s out).
     */
    fun cutTime(line: Segment, word: Int): Long {
        val times = WordTimings.of(line)
        val before = times?.getOrNull(word - 1)
        val from = times?.getOrNull(word)
        val offset = when {
            before != null && from != null -> (before.endMs + from.startMs) / 2
            from != null -> from.startMs
            before != null -> before.endMs
            else -> (line.endMs - line.startMs) * Words.ranges(line.text)[word].first / line.text.length.coerceAtLeast(1)
        }
        return (line.startMs + offset).coerceIn(line.startMs, line.endMs)
    }

    /**
     * Where shown word [w] falls among the words the recogniser wrote: the same word if it's there. In a stretch that
     * was changed, its start, or word for word when as many words replaced as many; -1 inside one that changed its
     * number of words ("UV" typed as "you vee"), which can't be divided. [match] is WordAlignment.align of the shown
     * words with the [sourceSize] recognised ones.
     */
    private fun sourceWord(match: IntArray, sourceSize: Int, w: Int): Int {
        if (match[w] >= 0) return match[w]
        val prev = (w - 1 downTo 0).firstOrNull { match[it] >= 0 } ?: -1
        val next = (w + 1 until match.size).firstOrNull { match[it] >= 0 } ?: match.size
        val prevSource = if (prev >= 0) match[prev] else -1
        val nextSource = if (next < match.size) match[next] else sourceSize
        return when {
            w == prev + 1 -> prevSource + 1
            next - prev == nextSource - prevSource -> prevSource + (w - prev)
            else -> -1
        }
    }

    /** Chinese or Japanese rules match inside longer words, so a shorter one would change too much. */
    private const val MIN_CJK_RULE = 3

    /**
     * "Always correct this?": after a turn was changed by hand from [old] to [new], the words to correct and what
     * replaced them, when exactly one phrase of 1–3 words became 1–3 words and more than case or punctuation changed
     * ("UV" → "Youvee"). Words are compared by their letters, so a comma or capital fixed next to the phrase isn't part
     * of it, and punctuation around the phrase is left out.
     *
     * A rule applies to what the recogniser wrote, so the words to correct are taken from that, [recognized] (the turn
     * before common corrections and edits by hand): where [old] shows the words of a correction ("Youvee", from a rule
     * for "UV"), the rule offered is for "UV", which replaces that one. Changing them back gives nothing to offer.
     * A Chinese or Japanese phrase of fewer than three characters isn't offered either. Null otherwise.
     */
    fun learnedPhrase(old: String, new: String, recognized: String = old, rules: Corrections = Corrections(emptyList())): Pair<String, String>? {
        val a = Words.ranges(old)
        val b = Words.ranges(new)
        val ka = a.map { WordAlignment.key(old.substring(it)) }
        val kb = b.map { WordAlignment.key(new.substring(it)) }
        var p = 0
        while (p < ka.size && p < kb.size && ka[p] == kb[p]) p++
        var s = 0
        while (s < ka.size - p && s < kb.size - p && ka[ka.size - 1 - s] == kb[kb.size - 1 - s]) s++
        val na = ka.size - p - s
        val nb = kb.size - p - s
        if (na !in 1..3 || nb !in 1..3) return null
        val source = recognizedSpan(old, a, p, p + na, recognized) ?: return null
        // Only when what the recogniser wrote there explains what was shown: the same words, or a rule's output. Words
        // typed by hand earlier pair up with recognised words by position only, which can be anything ("the" for "Kafé").
        val shown = lettersOf(old.substring(a[p].first, a[p + na - 1].last + 1))
        if (lettersOf(source) != shown && lettersOf(rules.apply(source)) != shown) return null
        val heard = trimPunctuation(source)
        val written = trimPunctuation(new.substring(b[p].first, b[p + nb - 1].last + 1))
        if (heard.isEmpty() || written.isEmpty()) return null
        if (Words.ranges(heard).size !in 1..3) return null
        if (lettersOf(heard) == lettersOf(written)) return null
        val letters = heard.filter { it.isLetterOrDigit() }
        if (letters.codePoints().allMatch { Words.isCjk(it) } && letters.codePointCount(0, letters.length) < MIN_CJK_RULE) return null
        return heard to written
    }

    /**
     * What the recogniser wrote where [shown]'s words [from] until [to] ([ranges]) are, found as a split finds a word
     * ([sourceWord]). Null when that can't be told: next to other words that were changed, into a different number.
     */
    private fun recognizedSpan(shown: String, ranges: List<IntRange>, from: Int, to: Int, recognized: String): String? {
        if (recognized == shown) return shown.substring(ranges[from].first, ranges[to - 1].last + 1)
        val r = Words.ranges(recognized)
        val match = WordAlignment.align(ranges.map { shown.substring(it) }, r.map { recognized.substring(it) })
        val start = sourceWord(match, r.size, from)
        val end = if (to == ranges.size) r.size else sourceWord(match, r.size, to)
        if (start < 0 || end <= start) return null
        return recognized.substring(r[start].first, r[end - 1].last + 1)
    }

    private fun lettersOf(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun trimPunctuation(s: String): String = s.trim { it.isWhitespace() || isPunctuation(it) }

    private fun isPunctuation(c: Char) = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}
