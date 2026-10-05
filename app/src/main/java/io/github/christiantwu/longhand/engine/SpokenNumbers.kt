package io.github.christiantwu.longhand.engine

import java.util.Locale

/**
 * Writes the numbers in an English transcript line as digits, US style: "three hundred and eighty dollars" -> "$380",
 * "nine six two" -> "962", "nine thirty a.m." -> "9:30 a.m.", "twenty five percent" -> "25%".
 *
 * Parakeet TDT v2 writes many numbers as words, especially digits read out one by one and amounts in conversational
 * speech. This puts them in digits the way a careful transcriber would, and leaves the words alone where digits would
 * be wrong or odd: "one of them", "a couple", "the first time", "just a second", "two or three weeks". On held-out
 * recorded phone conversations, 95% of the numbers it wrote were right and it wrote 91% of those it should have;
 * NeMo's inverse text normalization (which sherpa-onnx can run) got 68% and 63%.
 *
 * - Numbers from 10 up become digits; zero to nine stay words ("I need two") unless money, a percentage or a time
 *   follows, or they start a range that ends in digits ("seven to ten days" -> "7 to 10 days"). "a hundred" and
 *   "a thousand" alone stay words ("a thousand times"), and so do "my two cents" and counting ("one two three four").
 * - Three or more single digits read out are one digit string ("five five eight" -> 558, also "Five, five, eight.",
 *   "seven four oh" -> 740, "three six ninety five" -> 3695), or two when that is the whole line ("Two six."); ten
 *   digits are written like a phone number (202-555-0143).
 * - Two-digit groups: "nineteen ninety nine" -> 1999, "one eighty two" -> 182, "nine oh five" -> 905; a pair that
 *   could be a time or a price ("one thirty", "four fifty") stays words unless a time, an address or a street name
 *   makes it clear.
 * - Times before am/pm or o'clock, or after at/by/until/from...: "ten fifteen AM" -> "10:15 AM", "at three forty
 *   five" -> "at 3:45"; am/pm is kept as the recogniser wrote it, and its own "9.30 a.m.", "845 AM" and
 *   "nine: thirty AM" become 9:30 a.m., 8:45 AM and 9:30 AM.
 * - Money and percent: "<n> dollars [and] [<m> cents]" -> $n[.mm], "<n> million dollars" -> $n million,
 *   "<n> percent" -> n%; bucks, grand, cents and other currencies keep their word.
 * - Decimals ("two point five" -> 2.5, but not "at one point one of them"), ordinals from 10th ("twenty first" ->
 *   21st; first to ninth only next to a month or weekday: "March third" -> March 3rd), hyphenated numbers
 *   ("thirty-four" -> 34, "forty-year-old" -> 40-year-old).
 *
 * English only: call it for lines in English (it knows nothing of other languages' number words).
 */
object SpokenNumbers {

    /** [text] with its numbers in digits. */
    fun write(text: String): String = convert(text).text

    /**
     * [line] with its numbers in digits. Its word timings (one per word of its text, see [WordTimings]) are carried over:
     * each word written is timed from the first to the last of the words it was written from, so "$2.5 million" gets
     * the time of "two point five million dollars" for both its words. They're dropped if the words don't line up.
     */
    fun write(line: Recognized): Recognized {
        val r = convert(line.text)
        if (r.text == line.text) return line
        val words = line.words?.takeIf { r.pieces.lastOrNull()?.to == it.size - 1 }?.let { times ->
            r.pieces.flatMap { p -> List(Words.ranges(p.text).size) { WordTime(times[p.from].startMs, times[p.to].endMs) } }
        }
        return Recognized(r.text, words?.takeIf { it.size == Words.ranges(r.text).size })
    }

    /**
     * A line in digits ([text]) and, for each of its pieces, the words of the original line it was written from
     * (indices into the line split at whitespace), in order and each word once.
     */
    class Result(val text: String, val pieces: List<Piece>)

    class Piece(val text: String, val from: Int, val to: Int)

    fun convert(text: String): Result {
        val toks = text.split(SPACES).filter { it.isNotEmpty() }.map { Tok(it) }
        val pieces = ArrayList<Piece>()
        var i = 0
        while (i < toks.size) {
            val members = run(toks, i)
            if (members == null) {
                pieces += Piece(digitTime(toks, i), i, i)
                i++
                continue
            }
            i = Line(toks).convertRun(i, members, pieces)
        }
        return Result(pieces.joinToString(" ") { it.text }, pieces)
    }

    // ---- words ----

    private val UNITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        .withIndex().associate { it.value to it.index }
    private val TEENS = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen").withIndex().associate { it.value to 10 + it.index }
    private val TENS = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        .withIndex().associate { it.value to 20 + 10 * it.index }
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L,
        "trillion" to 1_000_000_000_000L)
    private val ORD_UNITS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
        .withIndex().associate { it.value to 1 + it.index }
    private val ORD_TEENS = listOf("tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth").withIndex().associate { it.value to 10 + it.index }
    private val ORD_TENS = listOf("twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth",
        "eightieth", "ninetieth").withIndex().associate { it.value to 20 + 10 * it.index }
    private val ORD_BIG = mapOf("hundredth" to 100, "thousandth" to 1_000, "millionth" to 1_000_000)
    private val ORDINALS = ORD_UNITS + ORD_TEENS + ORD_TENS + ORD_BIG

    private val MONTHS = setOf("january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december")
    private val WEEKDAYS = setOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val TIME_PREP = setOf("at", "by", "until", "till", "til", "from", "around", "before", "after", "between")
    private val DOLLAR = setOf("dollar", "dollars")
    private val CENT = setOf("cent", "cents")
    private val MONEY_WORDS = setOf("bucks", "buck", "grand", "euro", "euros", "pounds", "francs", "yen", "rupees", "pesos") + CENT
    private val OCLOCK = setOf("o'clock", "oclock")
    private val STREET = setOf("street", "avenue", "road", "drive", "lane", "way", "boulevard", "court", "place", "circle",
        "parkway", "highway", "st", "ave", "rd", "dr", "ln", "blvd")
    private val BIG_SCALES = setOf("million", "billion", "trillion")
    private val POSSESSIVES = setOf("my", "our", "your", "his", "her", "their")
    private val RANGE = setOf("to", "or", "through", "until", "till")

    private const val MIN_SEQ = 3
    private const val SMALL_MAX = 9

    private val SPACES = Regex("\\s+")

    /**
     * What `\w` matches with Pattern.UNICODE_CHARACTER_CLASS: letters, marks, digits, connectors and joiners. Spelled
     * out because Android's regex refuses that flag.
     */
    private fun isWordChar(cp: Int): Boolean = Character.isAlphabetic(cp) || cp == 0x200C || cp == 0x200D ||
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION -> true
            else -> false
        }

    private enum class Kind { UNIT, TEEN, TENS, HUNDRED, SCALE, ORD }

    private fun kind(w: String): Kind? = when {
        w in UNITS -> Kind.UNIT
        w in TEENS -> Kind.TEEN
        w in TENS -> Kind.TENS
        w == "hundred" -> Kind.HUNDRED
        w in SCALES -> Kind.SCALE
        w in ORDINALS -> Kind.ORD
        else -> null
    }

    /** A word as written: punctuation before and after, and the number words it stands for. */
    private class Tok(word: String) {
        val pre: String
        val core: String
        val post: String
        val low: String
        /** "thirty-four" -> [thirty, four]; null when it isn't a number. */
        val words: List<String>?
        /** The rest of "forty-year-old" after the number: "-year-old". */
        val suffix: String

        init {
            var a = 0
            while (a < word.length && !isWordChar(word.codePointAt(a))) a += Character.charCount(word.codePointAt(a))
            var b = word.length
            while (b > a && !isWordChar(word.codePointBefore(b))) b -= Character.charCount(word.codePointBefore(b))
            if (a == word.length) {
                pre = ""; core = word; post = ""
            } else {
                pre = word.substring(0, a); core = word.substring(a, b); post = word.substring(b)
            }
            low = core.lowercase(Locale.ROOT)
            val parts = low.split('-')
            var k = 0
            while (k < parts.size && kind(parts[k]) != null) k++
            when {
                k == parts.size -> { words = parts; suffix = "" }
                k > 0 && parts.size > 1 -> { words = parts.subList(0, k); suffix = "-" + core.split('-').drop(k).joinToString("-") }
                else -> { words = null; suffix = "" }
            }
        }

        val raw: String get() = pre + core + post
    }

    /** How many words am/pm takes at toks[k]: "a.m.", "AM", "pm", "a m"; 0 if it isn't there. */
    private fun ampm(toks: List<Tok>, k: Int): Int {
        if (k >= toks.size || toks[k].pre.isNotEmpty()) return 0
        val w = toks[k].low.replace(".", "")
        if (w == "am" || w == "pm") return 1
        if ((w == "a" || w == "p") && toks[k].post.trim('.').isEmpty() && k + 1 < toks.size &&
            toks[k + 1].low.replace(".", "") == "m") return 2
        return 0
    }

    private val DIGIT_TIME = Regex("([0-9]{1,2})\\.([0-9]{2})|([0-9]{1,2})([0-9]{2})")

    /** A time the recogniser wrote in digits without a colon, before am/pm: "9.30 a.m." -> 9:30, "845 AM" -> 8:45. */
    private fun digitTime(toks: List<Tok>, i: Int): String {
        val t = toks[i]
        val m = DIGIT_TIME.matchEntire(t.core)
        if (m != null && t.post.isEmpty() && ampm(toks, i + 1) > 0) {
            val g = m.groupValues
            val (h, mm) = if (g[1].isNotEmpty()) g[1] to g[2] else g[3] to g[4]
            if (h.toInt() in 1..12 && mm.toInt() <= 59 && !h.startsWith("0")) return t.pre + "${h.toInt()}:$mm" + t.post
        }
        return t.raw
    }

    private class Member(val tok: Int, val word: String, val comma: Boolean)

    /** The number words of the run starting at toks[i], or null when it doesn't start a number. */
    private fun run(toks: List<Tok>, i: Int): List<Member>? {
        val t = toks[i]
        val members = ArrayList<Member>()
        if (t.words == null) {
            if (!(t.low == "a" && t.post.isEmpty() && i + 1 < toks.size &&
                    toks[i + 1].low in setOf("hundred", "thousand", "million") &&
                    toks[i + 1].pre.isEmpty())) return null
            members += Member(i, "a", false)
        } else {
            t.words.forEach { members += Member(i, it, false) }
        }
        var j = i + 1
        while (j < toks.size && toks[j - 1].suffix.isEmpty()) {
            val prev = toks[j - 1]
            val cur = toks[j]
            val pw = members.last().word
            var comma = false
            if (prev.post.isNotEmpty()) {
                // "Five, five, eight.": single digits read out with commas between them
                if (prev.post == "," && (pw in UNITS || pw == "oh") && cur.low in UNITS && cur.words?.size == 1) comma = true
                // "nine: thirty A.M." (the recogniser's colon) -> 9:30 A.M.
                else if (prev.post == ":" && members.size == 1 && kind(pw) in setOf(Kind.UNIT, Kind.TEEN) && cur.words != null &&
                    kind(cur.words[0]) in setOf(Kind.TENS, Kind.TEEN) && cur.pre.isEmpty()) Unit
                else break
            }
            if (cur.pre.isNotEmpty()) break
            if (cur.words != null) {
                if (comma && cur.words.size != 1) break
                cur.words.forEachIndexed { q, w -> members += Member(j, w, comma && q == 0) }
                j++
                continue
            }
            val nxt = toks.getOrNull(j + 1)
            val nw = if (nxt?.words != null && cur.post.isEmpty() && nxt.pre.isEmpty()) nxt.words[0] else null
            val pk = kind(pw)
            when {
                cur.low == "and" && (pw == "hundred" || pw in SCALES) && nw != null &&
                    kind(nw) in setOf(Kind.UNIT, Kind.TEEN, Kind.TENS, Kind.ORD) -> members += Member(j, "and", false)
                (cur.low == "oh" || cur.low == "o") && pk in setOf(Kind.UNIT, Kind.TEEN, Kind.TENS) && nw in UNITS &&
                    nw != "zero" && !comma -> members += Member(j, "oh", false)
                // "seven four oh" -> 740
                (cur.low == "oh" || cur.low == "o") && pw in UNITS && members.size >= 2 && members[members.size - 2].word in UNITS &&
                    (cur.post.isNotEmpty() || j + 1 == toks.size) && !comma -> members += Member(j, "oh", false)
                cur.low == "point" && nw in UNITS && pk != null -> members += Member(j, "point", false)
                (cur.low == "double" || cur.low == "triple") && nw in UNITS && (pw in UNITS || pw == "oh") ->
                    members += Member(j, cur.low, false)
                else -> break
            }
            j++
        }
        return members
    }

    // ---- cardinal grammar over lower-case number words ----

    private class Read(val used: Int, val value: Long)

    private fun below100(w: List<String>, i: Int): Read? {
        if (i >= w.size) return null
        val x = w[i]
        TENS[x]?.let { tens ->
            val u = w.getOrNull(i + 1)
            if (u != null && u in UNITS && u != "zero") return Read(2, (tens + UNITS.getValue(u)).toLong())
            return Read(1, tens.toLong())
        }
        TEENS[x]?.let { return Read(1, it.toLong()) }
        UNITS[x]?.let { return Read(1, it.toLong()) }
        return null
    }

    private fun afterHundred(w: List<String>, j: Int): Read? {
        val k = if (w.getOrNull(j) == "and") j + 1 else j
        val b = below100(w, k) ?: return null
        if (b.value == 0L) return null
        return Read(k - j + b.used, b.value)
    }

    /** [unit|a|teen|tens unit] hundred [and] [below100] | below100 */
    private fun below1000(w: List<String>, i: Int): Read? {
        if (i + 1 < w.size && w[i] == "a" && w[i + 1] == "hundred") {
            val r = afterHundred(w, i + 2)
            return if (r != null) Read(2 + r.used, 100 + r.value) else Read(2, 100)
        }
        val b = below100(w, i) ?: return null
        if (b.value >= 1 && w.getOrNull(i + b.used) == "hundred") {
            val r = afterHundred(w, i + b.used + 1)
            return if (r != null) Read(b.used + 1 + r.used, b.value * 100 + r.value) else Read(b.used + 1, b.value * 100)
        }
        return b
    }

    private class Cardinal(val value: Long, val used: Int, val endsWithScale: Boolean)

    /** The longest number at w[i]: groups below a thousand, each followed by a smaller scale than the one before. */
    private fun cardinal(w: List<String>, i: Int): Cardinal? {
        if (w.getOrNull(i) == "zero") return Cardinal(0, 1, false)
        var total = 0L
        var j = i
        var lastScale: Long? = null
        var endsScale = false
        while (true) {
            var k = j
            if (lastScale != null && w.getOrNull(k) == "and") k++
            val g = if (k + 1 < w.size && w[k] == "a" && w[k + 1] in SCALES && lastScale == null) Read(1, 1) else below1000(w, k)
            if (g == null || g.value == 0L) break
            val nxt = k + g.used
            val scale = w.getOrNull(nxt)?.let { SCALES[it] }
            if (scale != null && (lastScale == null || scale < lastScale)) {
                total += g.value * scale
                lastScale = scale
                j = nxt + 1
                endsScale = true
                continue
            }
            if (lastScale != null && g.value >= lastScale) break
            total += g.value
            j = nxt
            endsScale = false
            break
        }
        return if (j == i) null else Cardinal(total, j - i, endsScale)
    }

    private fun ordinalSuffix(n: Long): String = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1L -> "st"
        n % 10 == 2L -> "nd"
        n % 10 == 3L -> "rd"
        else -> "th"
    }

    private fun grouped(v: Long): String = String.format(Locale.ROOT, "%,d", v)

    private fun digitString(d: String): String = when {
        d.length == 10 && d[0] !in "01" -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
        d.length == 11 && d[0] == '1' && d[1] !in "01" -> "1-${d.substring(1, 4)}-${d.substring(4, 7)}-${d.substring(7)}"
        else -> d
    }

    /**
     * How a run's words starting at w[i] are written: [used] words, as [text] (null: keep the words), [after] words after
     * the run taken too.
     */
    private class Reading(val used: Int, val text: String?, val after: Int = 0)

    /** One line's words, and what's known around the run being converted. */
    private class Line(val toks: List<Tok>) {
        var whole = false
        var before: List<String> = emptyList()
        var after: List<String> = emptyList()
        var last = 0

        fun convertRun(i0: Int, members: List<Member>, out: MutableList<Piece>): Int {
            val w = members.map { it.word }
            val tokOf = members.map { it.tok }
            val commas = members.any { it.comma }
            last = tokOf.last()
            val n = w.size
            whole = i0 == 0 && last == toks.size - 1
            before = toks.subList(maxOf(0, i0 - 4), i0).map { it.low }
            after = toks.subList(last + 1, minOf(toks.size, last + 4)).map { it.low }
            class Seg(val fa: Int, val fb: Int, val text: String?, val after: Int)
            val segs = ArrayList<Seg>()
            var f = 0
            while (f < n) {
                val prevLow = if (f == 0) toks.getOrNull(i0 - 1)?.takeIf { it.post.isEmpty() }?.low else w[f - 1]
                val r = read(w, f, prevLow, commas)
                if (r == null) {
                    segs += Seg(f, f + 1, null, 0); f++
                } else {
                    segs += Seg(f, f + r.used, r.text, r.after); f += r.used
                }
            }
            val emitted = HashSet<Int>()
            var end = last + 1
            for (s in segs) {
                val ta = tokOf[s.fa]
                val tb = tokOf[s.fb - 1]
                var text = s.text
                // a conversion can't start or end inside a hyphenated word
                if (text != null && ((s.fa > 0 && tokOf[s.fa - 1] == ta) || (s.fb < n && tokOf[s.fb] == tb))) text = null
                if (text == null) {
                    for (t in ta..tb) if (emitted.add(t)) out += Piece(toks[t].raw, t, t)
                    continue
                }
                if (tb == last && toks[last].suffix.isNotEmpty()) text += toks[last].suffix
                val post = if (s.after > 0) toks[tb + s.after].post else toks[tb].post
                out += Piece(toks[ta].pre + text + post, ta, tb + s.after)
                for (t in ta..tb) emitted.add(t)
                end = maxOf(end, tb + s.after + 1)
            }
            return end
        }

        /** The word k after the run's last, when no punctuation separates them. */
        fun nextLow(k: Int = 1): String? {
            for (q in last until last + k) {
                if (q >= toks.size || toks[q].post.isNotEmpty() || (q + 1 < toks.size && toks[q + 1].pre.isNotEmpty())) return null
            }
            return toks.getOrNull(last + k)?.low
        }

        fun read(w: List<String>, i: Int, prevLow: String?, commas: Boolean): Reading? {
            val n = w.size
            val seg = w.subList(i, n)
            when (seg) {
                listOf("twenty", "four", "seven") -> return Reading(3, "24/7")
                listOf("nine", "eleven") -> return Reading(2, "9/11")
                listOf("ten", "four") -> return null
            }
            if (seg.size >= 2 && seg[0] == "fifty" && seg[1] == "fifty") return Reading(2, null)

            // single digits read out
            val digits = StringBuilder()
            var k = i
            while (k < n) {
                val x = w[k]
                when {
                    x in UNITS -> { digits.append(UNITS.getValue(x)); k++ }
                    x == "oh" && k > i -> { digits.append('0'); k++ }
                    (x == "double" || x == "triple") && k + 1 < n && w[k + 1] in UNITS -> {
                        repeat(if (x == "double") 2 else 3) { digits.append(UNITS.getValue(w[k + 1])) }; k += 2
                    }
                    else -> break
                }
            }
            // digits then a final two-digit group: "three six ninety five" -> 3695, "zero zero five twenty nine" -> 00529
            if (k - i >= 2 && k < n && !commas) {
                val b = below100(w, k)
                if (b != null && b.value >= 10 && k + b.used == n) return Reading(n - i, digitString(digits.toString() + b.value))
            }
            // a line that is nothing but digits ("Two six.") is part of a code: two are enough
            val minSeq = if (whole && i == 0 && k == n) 2 else MIN_SEQ
            // counting, not a number: "one two three four five"
            if (k - i >= 4 && (digits[0] == '0' || digits[0] == '1') && "0123456789".startsWith(digits, digits[0] - '0')) {
                return Reading(k - i, null)
            }
            if (commas) return if (k == n && digits.length >= minSeq) Reading(n - i, digitString(digits.toString())) else null
            if (k - i >= 2 && digits.length >= minSeq && (k == n || (w[k] != "hundred" && w[k] != "point" && w[k] !in SCALES))) {
                return Reading(k - i, digitString(digits.toString()))
            }

            ORDINALS[w[i]]?.let { return ordinal(it.toLong(), 1, i == n - 1, prevLow) }
            if (w[i] in setOf("oh", "point", "double", "triple", "and")) return null

            val c = cardinal(w, i) ?: return null
            val v = c.value
            val j = i + c.used
            // "twenty first", "one hundred and first", "two thousandth"
            if (j < n && w[j] in ORDINALS &&
                ((w[j - 1] in TENS && w[j] in ORD_UNITS) || w[j - 1] == "hundred" || w[j - 1] == "and" || w[j - 1] in SCALES ||
                    (w[j] in ORD_BIG && !c.endsWithScale))) {  // "eight hundredth"
                val o = ORDINALS.getValue(w[j]).toLong()
                return ordinal(if (w[j] in ORD_BIG) v * o else v + o, c.used + 1, j + 1 == n, prevLow)
            }
            // "seven hundred and eighteenth"
            if (j + 1 < n && w[j] == "and" && (w[j - 1] == "hundred" || w[j - 1] in SCALES) && w[j + 1] in ORDINALS &&
                w[j + 1] !in ORD_BIG) {
                return ordinal(v + ORDINALS.getValue(w[j + 1]), c.used + 2, j + 2 == n, prevLow)
            }

            // "two point five", "one point five million"; not "at one point one of them"
            if (j + 1 < n && w[j] == "point" && prevLow != "at") {
                val d = StringBuilder()
                var q = j + 1
                while (q < n && (w[q] in UNITS || w[q] == "oh")) { d.append(if (w[q] == "oh") 0 else UNITS.getValue(w[q])); q++ }
                var text = "$v.$d"
                if (q < n && w[q] in BIG_SCALES) { text += " " + w[q]; q++ }
                return finish(text, null, q - i, w, i, plainNumber = false)
            }

            // two-digit groups: "nineteen ninety nine", "one eighty two", "nine oh five", "three forty five"
            if (v <= 99 && !c.endsWithScale && j < n) {
                val chunks = arrayListOf(v.toString())
                var q = j
                while (q < n) {
                    if (w[q] == "oh" && q + 1 < n && w[q + 1] in UNITS) {
                        chunks += "0" + UNITS.getValue(w[q + 1]); q += 2
                        continue
                    }
                    val b = below100(w, q)
                    if (b == null || b.value < 10 || (q + b.used < n && (w[q + b.used] == "hundred" || w[q + b.used] in SCALES))) break
                    chunks += b.value.toString(); q += b.used
                }
                if (chunks.size >= 2) {
                    val used = q - i
                    val c1 = v
                    val secondIsOh = chunks[1].startsWith("0")
                    val c2 = chunks[1].toInt()
                    val atEnd = i + used == n
                    if (chunks.size == 2 && c1 in 1..12 && (secondIsOh || c2 in 10..59) && atEnd) {
                        if ((ampm(toks, last + 1) > 0 && toks[last].post.isEmpty()) || nextLow() in OCLOCK ||
                            (i == 0 && prevLow in TIME_PREP)) {
                            return Reading(used, "$c1:" + c2.toString().padStart(2, '0'))
                        }
                        // "one thirty", "four fifty": a time, a price or a number; keep the words unless it's an address
                        if (!secondIsOh && !(after.any { it in STREET } || (i == 0 && "address" in before))) return Reading(used, null)
                    }
                    return finish(chunks.joinToString(""), null, used, w, i, plainNumber = false)
                }
            }
            return finish(v.toString(), v, c.used, w, i, plainNumber = true, endsWithScale = c.endsWithScale, cardinal = true)
        }

        fun ordinal(v: Long, used: Int, atEnd: Boolean, prevLow: String?): Reading? {
            if (v >= 10) return Reading(used, "$v${ordinalSuffix(v)}")
            // first to ninth: only with a date ("March third", "the third of March", "Tuesday the second")
            val date = prevLow in MONTHS || (atEnd && nextLow() == "of" && nextLow(2) in MONTHS) ||
                (before.size >= 2 && before[before.size - 1] == "the" && before[before.size - 2].trimEnd(',') in WEEKDAYS)
            return if (date) Reading(used, "$v${ordinalSuffix(v)}") else null
        }

        /** [text] (the number [value] when it's a whole number) written for what follows the run, or null: keep the words. */
        fun finish(
            text0: String, value: Long?, used: Int, w: List<String>, i: Int, plainNumber: Boolean,
            endsWithScale: Boolean = false, cardinal: Boolean = false,
        ): Reading? {
            val atEnd = i + used == w.size
            val nl = if (atEnd) nextLow() else null
            val nl2 = if (atEnd) nextLow(2) else null
            var text = text0
            if (value != null && endsWithScale && value >= 1_000_000) scaleWords(w.subList(i, i + used))?.let { text = it }
            val plain = value != null && !text.contains(' ')
            if (nl in DOLLAR) {
                var cents = ""
                var after = 1
                if (nl2 == "and") cents(last + 3)?.let { (c, taken) -> cents = c; after = 2 + taken }
                // "three hundred twenty three dollars seventy two cents"
                else cents(last + 2)?.let { (c, taken) -> cents = c; after = 1 + taken }
                if (plain && value >= 1000) text = grouped(value)
                return Reading(used, "\$" + text + cents, after)
            }
            if (nl in MONEY_WORDS) {
                // "my two cents"
                if (nl in CENT && value == 2L && used == 1 && before.lastOrNull() in POSSESSIVES) return null
                if (plain && value >= 10_000) text = grouped(value)
                return Reading(used, text)
            }
            if (nl == "percent") return Reading(used, "$text%", 1)
            if (nl == "per" && nl2 == "cent") return Reading(used, "$text%", 2)
            if (value != null && value in 1..12 && used <= 2 && atEnd && toks[last].post.isEmpty() &&
                (ampm(toks, last + 1) > 0 || nl in OCLOCK)) return Reading(used, text)
            // the end of a range of times: "9:30 a.m. to five"
            if (value != null && value in 1..12 && used == 1 && i == 0 && before.size >= 2 &&
                before[before.size - 1] in setOf("to", "until", "till") &&
                before[before.size - 2].replace(".", "") in setOf("am", "pm", "m")) {
                return Reading(used, text)
            }
            if (!cardinal) return Reading(used, text)
            if (used == 1 && value!! <= SMALL_MAX) {
                // "seven to ten days" -> 7 to 10, "nine to five p.m." -> 9 to 5 p.m.; otherwise "one", "two" ... stay words
                if (value >= 1 && atEnd && nl in RANGE && rangeEndInDigits(last + 2)) return Reading(used, text)
                return null
            }
            if (w[i] == "a" && used == 2) return null               // "a hundred", "a thousand"
            if (plain && value >= 10_000) text = grouped(value)
            return Reading(used, text)
        }

        /** The number starting at toks[k], the end of a range, is written in digits: 10 or more, or a time. */
        fun rangeEndInDigits(k: Int): Boolean {
            if (k >= toks.size || toks[k - 1].post.isNotEmpty() || toks[k].pre.isNotEmpty()) return false
            val r = run(toks, k) ?: return false
            val w = r.map { it.word }
            val c = cardinal(w, 0) ?: return false
            if (c.used != w.size) return false
            val end = r.last().tok
            return c.value >= 10 || (c.value in 1..12 && toks[end].post.isEmpty() && ampm(toks, end + 1) > 0)
        }

        /** "two million" -> "2 million", "three hundred million" -> "300 million". */
        fun scaleWords(seg: List<String>): String? {
            if (seg.last() !in BIG_SCALES) return null
            val c = cardinal(seg.subList(0, seg.size - 1), 0) ?: return null
            if (c.used != seg.size - 1 || c.endsWithScale) return null
            return grouped(c.value) + " " + seg.last()
        }

        /** "fifty cents" at toks[k], after "dollars and": ".50" and the words it takes. */
        fun cents(k: Int): Pair<String, Int>? {
            val ws = ArrayList<String>()
            var q = k
            while (q < toks.size && toks[q].words != null && toks[q].suffix.isEmpty() && toks[q].pre.isEmpty()) {
                ws += toks[q].words!!; q++
                if (toks[q - 1].post.isNotEmpty()) break
            }
            if (ws.isEmpty() || q >= toks.size || toks[q].low !in CENT || toks[q - 1].post.isNotEmpty()) return null
            val c = cardinal(ws, 0) ?: return null
            if (c.used != ws.size || c.value > 99) return null
            return "." + c.value.toString().padStart(2, '0') to q - k + 1
        }
    }
}
