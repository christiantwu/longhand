package io.github.christiantwu.longhand.engine

import java.lang.Character.UnicodeScript

/**
 * The language a call's topic, summary and follow-ups are written in, and the sentence ([instruction]) that asks the
 * summary model for it at the end of the prompt ([SummaryPrompt.build]). Measured with the shipped model on fictional
 * calls in German, Spanish, French, Swedish, Polish, Japanese, Chinese, Korean, Hindi and Hindi-English: naming the
 * language at the end of the user message put 75 of 76 fields in it, with facts and follow-ups as accurate as the
 * English summaries of the same calls. Every one of those languages was good enough to keep.
 */
sealed class SummaryLanguage(val instruction: String?) {

    /** English, as every summary was before: nothing is added, so the prompt is byte for byte the one evaluated. */
    data object English : SummaryLanguage(null)

    /** A language named in English, as Locale names it: "German", "Japanese", "Hindi". */
    data class Named(val name: String) : SummaryLanguage("Write the topic, summary and follow-ups in $name.")

    /**
     * A call known not to be English whose language isn't known. It gets the call's language less often than naming it
     * (21 of 26 test calls, and neither Swedish one), and it changes English summaries, so it's only for calls that
     * can't be English.
     */
    data object Unnamed :
        SummaryLanguage("Write the topic, summary and follow-ups in the language the call is in, even if it isn't English.")

    companion object {

        /**
         * The language to summarize a call in: English for every call unless [inCallsLanguage] (Settings). A call whose
         * [detection] names a language (the most common of its windows, [CallLanguage.spoken]) gets it when that's one
         * the [model] that made the transcript writes, and English when it's English. Otherwise the model and the
         * transcript's [texts] (the lines' own words, without the speakers' names) tell:
         * - English: English.
         * - Hindi: Hindi, calls that mix it with English too; English only for a call detected as English throughout.
         * - Chinese, Japanese and Korean: by the script of the lines ([byScript]), whatever detection heard: the script is
         *   what the model actually wrote, and Whisper sometimes hears Korean as Japanese.
         * - European: [Unnamed] when at least 1% of the lines' letters are beyond ASCII, which an English call's few
         *   foreign names ("José", "Zürich") don't reach, and detection heard no English; otherwise English.
         * A language detected in a call that the model doesn't write (a call pinned to another language by hand) isn't
         * named: a summary in the wrong language is worse than one in English. Transcripts made before 0.10.0, which
         * don't say their model, get English.
         */
        fun forCall(
            inCallsLanguage: Boolean, model: Models.Language?, detection: CallLanguage.Detection?, texts: List<String>,
        ): SummaryLanguage {
            if (!inCallsLanguage || model == null) return English
            val family = CallLanguage.Family.of(model)
            val codes = detection?.takeIf { it.speechSeconds >= CallLanguage.MIN_SPEECH_SECONDS }?.codes.orEmpty()
            val detected = CallLanguage.spoken(codes)
            return when {
                model == Models.Language.ENGLISH -> English
                model == Models.Language.HINDI ->
                    if (detected == "en" && codes.none { CallLanguage.family(it) == CallLanguage.Family.HINDI }) English
                    else Named(nameOf("hi"))
                model == Models.Language.CJK -> byScript(texts)
                    ?: detected?.takeIf { CallLanguage.family(it) == CallLanguage.Family.CJK }?.let { Named(nameOf(it)) } ?: English
                detected == "en" -> English
                detected != null -> if (CallLanguage.family(detected) == family) Named(nameOf(detected)) else English
                "en" !in codes && mostlyBeyondAscii(texts) -> Unnamed
                else -> English
            }
        }

        /** [code]'s name in English. Cantonese wasn't measured, so it's written as Chinese. */
        private fun nameOf(code: String): String = if (code == "yue") nameOf("zh") else CallLanguage.displayName(code)

        /** At least 1% of [texts]' letters are beyond ASCII: German's umlauts reach it, an English call's odd "José" doesn't. */
        private fun mostlyBeyondAscii(texts: List<String>): Boolean {
            var letters = 0
            var beyond = 0
            for (text in texts) for (c in text.codePoints().toArray()) {
                if (!Character.isLetter(c)) continue
                letters++
                if (c >= 0x80) beyond++
            }
            return beyond > 0 && beyond * 100 >= letters
        }

        /**
         * The language of a SenseVoice transcript by its script: a line with Hangul is Korean, one with kana Japanese,
         * one with Han characters only Chinese, and each counts for its language with its letters. Mostly Latin letters
         * means English. Deciding line by line keeps a stray line in another script from naming that language. Null when
         * there are no letters to go by.
         */
        private fun byScript(texts: List<String>): SummaryLanguage? {
            var latin = 0
            val cjk = LinkedHashMap<String, Int>()
            for (text in texts) {
                var hangul = 0
                var kana = 0
                var han = 0
                for (c in text.codePoints().toArray()) {
                    if (!Character.isLetter(c)) continue
                    when (UnicodeScript.of(c)) {
                        UnicodeScript.LATIN -> latin++
                        UnicodeScript.HANGUL -> hangul++
                        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> kana++
                        UnicodeScript.HAN -> han++
                        else -> {}
                    }
                }
                val language = when {
                    hangul > 0 -> "ko"
                    kana > 0 -> "ja"
                    han > 0 -> "zh"
                    else -> continue
                }
                cjk.merge(language, hangul + kana + han, Int::plus)
            }
            val top = cjk.maxByOrNull { it.value } ?: return if (latin > 0) English else null
            return if (latin * 2 > latin + cjk.values.sum()) English else Named(nameOf(top.key))
        }
    }
}
