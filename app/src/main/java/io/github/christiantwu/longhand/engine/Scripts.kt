package io.github.christiantwu.longhand.engine

import java.lang.Character.UnicodeScript

/** The writing systems in a text, which matter for Hindi transcripts: how they're recognised and summarized. */
object Scripts {

    /** Devanagari and Latin; Common and Inherited are the few letters that scripts share, such as "ʼ". */
    private val DEVANAGARI_OR_LATIN =
        setOf(UnicodeScript.DEVANAGARI, UnicodeScript.LATIN, UnicodeScript.COMMON, UnicodeScript.INHERITED)

    /**
     * [text] has letters of a script other than Devanagari or Latin. Detecting the language of each turn itself, the Hindi
     * model writes Hindi in Devanagari and English in Latin letters or Devanagari, but puts some short turns into Cyrillic
     * or another script altogether. Digits, punctuation and vowel signs aren't letters, so they don't count.
     */
    fun hasOtherScript(text: String): Boolean =
        text.codePoints().anyMatch { Character.isLetter(it) && UnicodeScript.of(it) !in DEVANAGARI_OR_LATIN }

    fun isDevanagari(c: Char): Boolean = UnicodeScript.of(c.code) == UnicodeScript.DEVANAGARI
}
