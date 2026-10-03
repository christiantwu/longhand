package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.data.corrected
import io.github.christiantwu.longhand.engine.Corrections
import io.github.christiantwu.longhand.engine.Corrections.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionsTest {

    private fun apply(text: String, vararg rules: Pair<String, String>) =
        Corrections(rules.map { (heard, written) -> Rule(heard, written) }).apply(text)

    @Test fun theForumsExample() {
        // "change UV -> youvee in future transcriptions", typed in lower case.
        assertEquals("I'll send you the youvee file.", apply("I'll send you the UV file.", "UV" to "youvee"))
        assertEquals("Youvee sent it over.", apply("UV sent it over.", "UV" to "youvee"))
        assertEquals("Thanks, youvee.", apply("Thanks, uv.", "UV" to "youvee"))
    }

    @Test fun aRuleCantTellMeaningsApart() {
        // Expected: whole-word matching changes every "UV", the UV index included (the collision measured in the design).
        assertEquals("The Youvee index is high today.", apply("The UV index is high today.", "UV" to "Youvee"))
    }

    @Test fun onlyWholeWordsMatch() {
        val rule = "UV" to "Youvee"
        // Letters or digits on either side make it part of another word.
        assertEquals("UVA and UV2 and 2UV and fluvial", apply("UVA and UV2 and 2UV and fluvial", rule))
        assertEquals("Émile", apply("Émile", "mile" to "X"))
        // Punctuation and spaces end a word.
        assertEquals("Youvee-light, (Youvee), Youvee's, \"Youvee\"", apply("UV-light, (UV), UV's, \"UV\"", rule))
        // A vowel sign continues a Devanagari word: "राम" isn't the start of "रामायण".
        assertEquals("रामायण और Ram", apply("रामायण और राम", "राम" to "Ram"))
    }

    @Test fun caseIsIgnoredAndTheReplacementIsWrittenAsTyped() {
        assertEquals("on GrapheneOS now", apply("on graphene OS now", "Graphene OS" to "GrapheneOS"))
        assertEquals("on GrapheneOS now", apply("on GRAPHENE os now", "graphene os" to "GrapheneOS"))
        assertEquals("Ask Émile today", apply("Ask ÉMILE today", "émile" to "Émile"))
    }

    @Test fun anyWhitespaceSeparatesAPhrasesWords() {
        val rule = "Graphene OS" to "GrapheneOS"
        assertEquals("a GrapheneOS b", apply("a Graphene  OS b", rule))
        assertEquals("a GrapheneOS b", apply("a Graphene\tOS b", rule))
        assertEquals("a GrapheneOS b", apply("a Graphene\u00A0OS b", rule))
        assertEquals("a GrapheneOS b", apply("a Graphene OS b", "  Graphene   OS " to "GrapheneOS"))
        // But a space is needed: "GrapheneOS" isn't two words.
        assertEquals("a GrapheneOS b", apply("a GrapheneOS b", rule))
    }

    @Test fun theLongestPhraseWins() {
        val text = "I use Graphene OS daily, and the OS is great"
        val expected = "I use GrapheneOS daily, and the operating system is great"
        assertEquals(expected, apply(text, "OS" to "operating system", "Graphene OS" to "GrapheneOS"))
        assertEquals(expected, apply(text, "Graphene OS" to "GrapheneOS", "OS" to "operating system"))
    }

    @Test fun textIsReplacedInOnePass() {
        // A replacement is never matched again, by its own rule or another.
        assertEquals("Youvee and UV", apply("UV and Youvee", "UV" to "Youvee", "Youvee" to "UV"))
        assertEquals("Kate Smith called Kate Smith", apply("Kate called Kate", "Kate" to "Kate Smith"))
        assertEquals("Youvee Youvee", apply("UV UV", "UV" to "Youvee"))
    }

    @Test fun chineseAndJapaneseMatchAnywhere() {
        // No spaces between words, so neither the rule's words nor English ones in the text need them.
        assertEquals("我们用Youvee的产品", apply("我们用优维的产品", "优维" to "Youvee"))
        assertEquals("这个Youvee灯很好", apply("这个UV灯很好", "UV" to "Youvee"))
        assertEquals("Youveeのライトです", apply("ユーブイのライトです", "ユーブイ" to "Youvee"))
        assertEquals("これはYouveeです", apply("これはUVです", "uv" to "Youvee"))
    }

    @Test fun theFirstLetterIsCapitalisedAtTheStartOfASentence() {
        val rule = "uv" to "youvee"
        assertEquals("Youvee is here. Youvee again? And youvee", apply("uv is here. UV again? And uv", rule))
        // Opening quotes and brackets may come first; a quote in the middle of a sentence isn't a new one.
        assertEquals("He left. \"Youvee,\" she said. (Youvee) maybe the \"youvee\" one",
            apply("He left. \"uv,\" she said. (uv) maybe the \"uv\" one", rule))
        assertEquals("好的。Youvee", apply("好的。UV", rule))
        // Each follow-up is on its own line.
        assertEquals("Call Sam\nYouvee order", apply("Call Sam\nuv order", rule))
        // Not when the first word has capitals of its own.
        assertEquals("iPhone is broken, my iPhone", apply("Eye phone is broken, my eye phone", "eye phone" to "iPhone"))
        // Nor when it doesn't start with a letter.
        assertEquals("#1 again", apply("Number one again", "number one" to "#1"))
    }

    @Test fun nothingChangesWithoutAMatchingRule() {
        assertEquals("Hello there.", apply("Hello there."))
        assertTrue(Corrections(emptyList()).isEmpty)
        assertTrue(Corrections(listOf(Rule("  ", "x"))).isEmpty)
        assertEquals("Hello there.", apply("Hello there.", "UV" to "Youvee"))
        assertEquals("", apply("", "UV" to "Youvee"))
    }

    @Test fun matchesFindsAnyRule() {
        val c = Corrections(listOf(Rule("UV", "Youvee"), Rule("Graphene OS", "GrapheneOS")))
        assertTrue(c.matches("the uv file"))
        assertTrue(c.matches("on graphene   os"))
        assertFalse(c.matches("UVA"))
        assertFalse(c.matches("GrapheneOS"))
    }

    @Test fun keysIgnoreCaseAndSpacing() {
        assertEquals("Graphene OS", Corrections.normalize("  Graphene \t OS "))
        assertEquals("graphene os", Corrections.keyOf("  Graphene \t OS "))
        assertEquals("émile", Corrections.keyOf("ÉMILE"))
        assertEquals("", Corrections.keyOf(" \u3000 "))
        // The first of two rules for the same words is kept.
        assertEquals("Youvee", apply("UV", "UV" to "Youvee", "uv" to "You Vee"))
    }

    @Test fun aLineIsCorrectedAfreshFromWhatTheRecogniserWrote() {
        // As saveTranscript, the corrections job and Undo (RecordingDao.restore) write a line: from what was recognised.
        val rules = Corrections(listOf(Rule("UV", "Youvee")))
        val raw = Segment(id = 7, recordingId = 1, startMs = 0, endMs = 1_000, speaker = 0, text = "the UV file", words = "0,10 20,30 40,50")
        val corrected = raw.corrected(rules)
        assertEquals(raw.copy(text = "the Youvee file", recognized = "the UV file"), corrected)
        // Corrected again with the same rules, nothing changes.
        assertEquals(corrected, corrected.corrected(rules))
        // A rule removed since (say while Undo was on offer) is undone, and its words come back as recognised.
        assertEquals(raw, corrected.corrected(Corrections(emptyList())))
        // A rule added since is applied, and a replaced one gives way.
        assertEquals(raw.copy(text = "the Yuvi file", recognized = "the UV file"), corrected.corrected(Corrections(listOf(Rule("uv", "Yuvi")))))
        // A line typed by hand is left as it is.
        val typed = corrected.copy(text = "the Youvee files", edited = true)
        assertEquals(typed, typed.corrected(Corrections(emptyList())))
        assertEquals("Youvee" to "UV", rules.correct("UV"))
        assertEquals("hello" to null, rules.correct("hello"))
    }

    @Test fun likePatternsFindCandidatesSQLiteCanCompare() {
        assertEquals("%Graphene%", Corrections.likePattern("Graphene OS"))
        // SQLite's LIKE ignores the case of A-Z only, so "É" is left out.
        assertEquals("%mile%", Corrections.likePattern("Émile"))
        assertEquals("%优维%", Corrections.likePattern("优维"))
        assertEquals("%", Corrections.likePattern("Ж"))
        assertEquals("%50\\%\\_off%", Corrections.likePattern("50%_off"))
    }
}
