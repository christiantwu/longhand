package io.github.christiantwu.longhand.engine

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Common corrections: words the recogniser keeps getting wrong, written the user's way ("UV" → "Youvee").
 * Plain text replacement after recognition rather than biasing the recogniser (hotwords), so it works
 * with every model, can correct transcripts made before a rule was added, and runs again after a redo.
 *
 * A rule matches a whole word or phrase, in any case, with any whitespace between its words. Words end
 * where letters and digits end, except in Chinese and Japanese, which are written without spaces: there
 * a rule matches anywhere. Where rules overlap the longest phrase wins, and text is replaced in one pass,
 * so a replacement is never matched again. The replacement is written as typed, with its first letter
 * capitalised at the start of a sentence (unless its first word has capitals of its own, like "iPhone").
 * Matching whole words can't tell meanings apart: a rule for "UV" also changes "UV index".
 */
class Corrections(rules: List<Rule>) {

    /** Write [written] wherever [heard] is recognised. */
    data class Rule(val heard: String, val written: String)

    /** Longest phrase first, so the regex tries it first wherever two rules start at the same place. */
    private val rules: List<Rule> = rules
        .map { Rule(normalize(it.heard), it.written) }
        .filter { it.heard.isNotEmpty() }
        .distinctBy { keyOf(it.heard) }
        .sortedByDescending { keyOf(it.heard).length }

    /** One alternative per rule, each in its own group, so a match tells which rule it was. */
    private val pattern: Pattern? = this.rules.takeIf { it.isNotEmpty() }?.let { list ->
        Pattern.compile(list.joinToString("|") { "(${regexOf(it.heard)})" }, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    }

    val isEmpty: Boolean get() = pattern == null

    /** [text] with every rule applied. */
    fun apply(text: String): String {
        val m = pattern?.matcher(text) ?: return text
        if (!m.find()) return text
        val out = StringBuilder(text.length + 16)
        var last = 0
        do {
            val written = ruleOf(m).written
            out.append(text, last, m.start())
            out.append(if (atSentenceStart(text, m.start())) capitalised(written) else written)
            last = m.end()
        } while (m.find())
        return out.append(text, last, text.length).toString()
    }

    /** Some rule matches somewhere in [text]. */
    fun matches(text: String): Boolean = pattern?.matcher(text)?.find() == true

    /**
     * A line written again from what the recogniser wrote ([recognized]): its text with every rule applied, and what
     * to keep as recognised (null when that's the same as the text).
     */
    fun correct(recognized: String): Pair<String, String?> {
        val text = apply(recognized)
        return text to recognized.takeIf { it != text }
    }

    private fun ruleOf(m: Matcher): Rule = rules[(1..m.groupCount()).first { m.start(it) >= 0 } - 1]

    companion object {
        /** Finds [heard] as a rule for it would ([matches]), for picking out the lines a rule applies to. */
        fun saying(heard: String) = Corrections(listOf(Rule(heard, heard)))

        private val SPACES = Regex("[\\s\\p{Z}]+")

        /**
         * A letter, mark or digit that words are made of. Chinese and Japanese characters aren't: those
         * languages have no spaces between words, so a rule matches next to them, and inside them.
         */
        private const val WORD = "[\\p{L}\\p{M}\\p{N}&&[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\u30FC\\uFF70]]"
        private val WORD_CHAR = Pattern.compile(WORD)

        /** Where a sentence ends, before the words that start the next. */
        private const val SENTENCE_ENDS = ".!?…。！？।॥"

        /** Opening quotes and brackets, which may come between a sentence's end and its first word. */
        private const val OPENERS = "\"'“‘«([「『（"

        /** [heard] as stored and shown: trimmed, with single spaces between its words. */
        fun normalize(heard: String): String = heard.trim().split(SPACES).filter { it.isNotEmpty() }.joinToString(" ")

        /**
         * What makes two rules the same: [normalize]d and lower case. Compared in Kotlin, as SQLite's
         * NOCASE folds only A-Z (like KnownVoice.nameKey).
         */
        fun keyOf(heard: String): String = normalize(heard).lowercase(Locale.ROOT)

        /**
         * A LIKE pattern (`ESCAPE '\'`) that every text [heard] can match is like: its longest run of
         * characters SQLite compares without regard to case (ASCII, or characters that have no case),
         * which is enough to find candidates quickly. [matches] makes the final decision. "%" (any
         * text) when there is no such run, as in "Émile" → "mile" but "Ж" → "%".
         */
        fun likePattern(heard: String): String {
            val safe = { c: Char -> !c.isWhitespace() && (c.code < 128 || (c.lowercaseChar() == c && c.uppercaseChar() == c)) }
            var best = ""
            var run = StringBuilder()
            for (c in normalize(heard) + " ") {
                if (safe(c)) run.append(c)
                else {
                    if (run.length > best.length) best = run.toString()
                    run = StringBuilder()
                }
            }
            if (best.isEmpty()) return "%"
            return "%" + best.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }

        private fun isWordChar(cp: Int): Boolean = WORD_CHAR.matcher(String(Character.toChars(cp))).matches()

        /** [heard]'s words with any whitespace between them, bounded where a word begins or ends with a letter or digit. */
        private fun regexOf(heard: String): String {
            val body = heard.split(' ').joinToString("[\\s\\p{Z}]+") { Pattern.quote(it) }
            val before = if (isWordChar(heard.codePointAt(0))) "(?<!$WORD)" else ""
            val after = if (isWordChar(heard.codePointBefore(heard.length))) "(?!$WORD)" else ""
            return before + body + after
        }

        /** Nothing but whitespace and opening quotes before [index] since the start of the text, a line or a sentence. */
        private fun atSentenceStart(text: String, index: Int): Boolean {
            var i = index
            while (i > 0) {
                val c = text[i - 1]
                when {
                    c == '\n' || c in SENTENCE_ENDS -> return true
                    c.isWhitespace() || c in OPENERS -> i--
                    else -> return false
                }
            }
            return true
        }

        /** The first letter in capitals, unless the first word already has some ("iPhone", "eBay" stay as typed). */
        private fun capitalised(written: String): String {
            val first = written.indexOfFirst { it.isLetter() }
            if (first < 0) return written
            val word = written.substring(first).takeWhile { !it.isWhitespace() }
            if (word.any { it.isUpperCase() || it.isTitleCase() }) return written
            return written.substring(0, first) + written[first].titlecase() + written.substring(first + 1)
        }
    }
}
