package io.github.christiantwu.longhand.engine

import java.util.Locale

/**
 * Which downloaded language a call is in, from Whisper's spoken-language identification ([LanguageDetector]) on three
 * stretches of its speech. The windows and the rule were chosen on 1,064 calls coded like phone recordings, to move a
 * call to another model only when that's all but certain to transcribe it better.
 */
object CallLanguage {

    /** Which language model covers a language Whisper names; OTHER: none of them, or no answer. */
    enum class Family {
        ENGLISH, EUROPEAN, CJK, HINDI, OTHER;

        val language: Models.Language?
            get() = when (this) {
                ENGLISH -> Models.Language.ENGLISH
                EUROPEAN -> Models.Language.EUROPEAN
                CJK -> Models.Language.CJK
                HINDI -> Models.Language.HINDI
                OTHER -> null
            }

        companion object {
            fun of(language: Models.Language): Family = entries.first { it.language == language }
        }
    }

    /** What detection found in a call: Whisper's language code for each window, and how much speech the call has. */
    data class Detection(val codes: List<String>, val speechSeconds: Float) {
        /** The codes as Recording.spokenLanguages stores them, e.g. "en,ja,ja"; empty for none. */
        val stored: String get() = codes.joinToString(",")

        companion object {
            /** A call's stored detection; null if it hasn't been detected. */
            fun of(spokenLanguages: String?, speechSeconds: Float?): Detection? =
                if (spokenLanguages == null || speechSeconds == null) null
                else Detection(if (spokenLanguages.isEmpty()) emptyList() else spokenLanguages.split(","), speechSeconds)
        }
    }

    /** With less speech than this, Whisper's answers are too often wrong: the call follows Settings. */
    const val MIN_SPEECH_SECONDS = 10f

    private const val TILE_SECONDS = 9
    private const val MIN_LAST_SECONDS = 4

    /** Parakeet v3's 24 languages besides English. */
    private val EUROPEAN = setOf(
        "bg", "hr", "cs", "da", "nl", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt", "mt", "pl", "pt", "ro", "ru",
        "sk", "sl", "es", "sv", "uk",
    )

    /**
     * Close relatives of those that Whisper may name instead (bs and sr for Croatian, be for Russian or Ukrainian…), and
     * la: Whisper says Latin for European speech it can't place. They count for the European model, but aren't what was
     * spoken, so they're never named.
     */
    private val STAND_INS = setOf("bs", "sr", "mk", "be", "no", "nn", "ca", "gl", "af", "lb", "la")

    fun family(code: String): Family = when (code) {
        "en" -> Family.ENGLISH
        in EUROPEAN, in STAND_INS -> Family.EUROPEAN
        "zh", "yue", "ja", "ko" -> Family.CJK
        "hi", "ur" -> Family.HINDI
        else -> Family.OTHER // including "", Whisper's answer when it fails
    }

    /**
     * The windows to identify, as [start, end) sample ranges of the call's speech joined end to end: 9 s tiles, keeping
     * a last one of 4 s or more (under 9 s of speech, all of it is one), and of those the first, the middle and the
     * last. Each is well under the 29 s sherpa-onnx takes at once.
     */
    fun windows(speechSamples: Int, sampleRate: Int = MODEL_SAMPLE_RATE): List<Pair<Int, Int>> {
        val tile = TILE_SECONDS * sampleRate
        if (speechSamples < tile) return if (speechSamples > 0) listOf(0 to speechSamples) else emptyList()
        val whole = speechSamples / tile
        val tiles = (0 until whole).map { it * tile to (it + 1) * tile } +
            listOfNotNull((whole * tile to speechSamples).takeIf { speechSamples - whole * tile >= MIN_LAST_SECONDS * sampleRate })
        return if (tiles.size <= 3) tiles else listOf(tiles.first(), tiles[tiles.size / 2], tiles.last())
    }

    /**
     * The speech in [spans] (seconds, such as everything diarization heard anyone say) as sample ranges in order, padded
     * and joined ([padded]).
     */
    fun speech(spans: List<Span>, length: Int, before: Int = 0, after: Int = 0): List<Pair<Int, Int>> {
        fun sample(seconds: Float) = (seconds.toDouble() * MODEL_SAMPLE_RATE).toInt()
        return padded(spans.map { sample(it.start) to sample(it.end) }.sortedBy { it.first }, length, before, after)
    }

    /**
     * Speech ranges in order of their start, padded as speech recognition pads them ([before], [after], within [length])
     * and joined where they then overlap.
     */
    fun padded(ranges: List<Pair<Int, Int>>, length: Int, before: Int, after: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for ((a, b) in ranges) {
            val start = maxOf(a - before, 0)
            val end = minOf(b + after, length)
            val last = out.lastOrNull()
            if (last != null && start <= last.second) out[out.size - 1] = last.first to maxOf(last.second, end)
            else out += start to end
        }
        return out
    }

    /** Where the stretch [from, to) of the [speech] ranges joined end to end lies in the call, piece by piece. */
    fun pieces(speech: List<Pair<Int, Int>>, from: Int, to: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var offset = 0
        for ((a, b) in speech) {
            val start = maxOf(from - offset, 0)
            val end = minOf(to - offset, b - a)
            if (start < end) out += a + start to a + end
            offset += b - a
            if (offset >= to) break
        }
        return out
    }

    /**
     * The family to transcribe a call with, from its windows' [codes] and its [speechSeconds]; null to stay with
     * Settings. A language the models don't cover counts against switching, and English alone moves a call to the
     * English model only when every window says so: every other model transcribes English too.
     */
    fun decide(codes: List<String>, speechSeconds: Float): Family? {
        if (speechSeconds < MIN_SPEECH_SECONDS || codes.isEmpty()) return null
        val families = codes.map(::family)
        val counts = families.groupingBy { it }.eachCount()
        val others = counts.filterKeys { it != Family.ENGLISH && it != Family.OTHER }
        val uncovered = counts[Family.OTHER] ?: 0
        val english = counts[Family.ENGLISH] ?: 0
        val need = minOf(2, families.size)
        return when (others.size) {
            0 -> Family.ENGLISH.takeIf { english == families.size && english >= need }
            1 -> {
                val (family, n) = others.entries.first().toPair()
                when {
                    uncovered > 1 -> null
                    n >= need -> family
                    n + english == families.size -> family // one window of it, the rest English: a mixed call only it covers
                    else -> null
                }
            }
            2 -> {
                val sorted = others.entries.sortedByDescending { it.value }
                sorted[0].key.takeIf { sorted[0].value >= 2 && sorted[1].value == 1 && uncovered == 0 }
            }
            else -> null
        }
    }

    /** The language [detection] puts the call in, downloaded or not; null to stay with Settings. */
    fun languageOf(detection: Detection): Models.Language? = decide(detection.codes, detection.speechSeconds)?.language

    /**
     * The language to name as spoken in a call: of the codes a model covers, the most common (the earliest on a tie),
     * among those of the [family] the call went to when it's given. Urdu counts as Hindi (Whisper often says Urdu for
     * Hindi speech), and [STAND_INS] don't count. Null if there's none, or a tie ("fr,sv" could be either).
     */
    fun spoken(codes: List<String>, family: Family? = null): String? {
        val counts = codes
            .map { if (it == "ur") "hi" else it }
            .filter { it !in STAND_INS && family(it) != Family.OTHER && (family == null || family(it) == family) }
            .groupingBy { it }.eachCount()
        val top = counts.maxByOrNull { it.value } ?: return null
        return top.key.takeIf { counts.values.count { it == top.value } == 1 }
    }

    /** The language spoken in a call ([spoken]) as named in English, e.g. "Japanese"; of the family it went to, if any. */
    fun spokenName(detection: Detection, family: Family? = decide(detection.codes, detection.speechSeconds)): String? =
        spoken(detection.codes, family)?.let(::displayName)

    fun displayName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
}
