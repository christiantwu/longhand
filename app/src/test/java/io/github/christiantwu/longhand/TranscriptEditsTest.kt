package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.engine.Corrections
import io.github.christiantwu.longhand.engine.TranscriptEdits
import io.github.christiantwu.longhand.engine.WordAlignment
import io.github.christiantwu.longhand.engine.WordTime
import io.github.christiantwu.longhand.engine.WordTimings
import io.github.christiantwu.longhand.engine.Words
import io.github.christiantwu.longhand.export.TranscriptFormatter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEditsTest {

    private fun line(
        text: String, start: Long = 0, end: Long = 5_000, speaker: Int = 0, id: Long = 1,
        words: String? = null, recognized: String? = null, edited: Boolean = false,
    ) = Segment(id = id, recordingId = 1, startMs = start, endMs = end, speaker = speaker, text = text,
        words = words, recognized = recognized, edited = edited)

    /** Each part's timings belong to the words of what the recogniser wrote for it. */
    private fun assertTimingsFit(line: Segment) {
        val times = WordTimings.decode(line.words) ?: return
        assertEquals(line.toString(), Words.of(line.recognized ?: line.text).size, times.size)
    }

    // ---- words and their timings ----

    @Test fun wordsLieBetweenSpacesExceptInChineseAndJapanese() {
        assertEquals(listOf("Hello,", "what", "is", "your", "name?"), Words.of(" Hello, what is  your name? "))
        assertEquals(listOf("你", "好。", "我", "想"), Words.of("你好。我想"))
        assertEquals(listOf("OK", "我", "知", "道", "了。"), Words.of("OK我知道了。"))
        assertEquals(listOf("我", "们", "的", "app", "很", "好"), Words.of("我们的app很好"))
        assertEquals(listOf("了。", "OK"), Words.of("了。OK"))
        assertEquals(listOf("は", "い、", "わ", "か", "ー"), Words.of("はい、わかー"))
        assertEquals(listOf("네.", "알겠습니다."), Words.of("네. 알겠습니다."))
        assertEquals(listOf("नमस्ते", "दोस्त"), Words.of("नमस्ते दोस्त"))
        assertEquals(emptyList<String>(), Words.of("   "))
    }

    @Test fun timingsAreStoredInCentisecondsFromTheLinesStart() {
        val stored = WordTimings.encode(listOf(WordTime(0, 240), WordTime(720, 880), WordTime(1234, 1300)))
        assertEquals("0,24 72,88 123,130", stored)
        assertEquals(listOf(WordTime(0, 240), WordTime(720, 880), WordTime(1230, 1300)), WordTimings.decode(stored))
        assertNull(WordTimings.decode(null))
        assertEquals(emptyList<WordTime>(), WordTimings.decode(""))
        assertNull(WordTimings.decode("12;30"))
        assertNull(WordTimings.decode("12,x"))
    }

    @Test fun parakeetTokensWithDurationsGiveEachWordItsLetters() {
        // As sherpa-onnx returned them for Parakeet TDT 0.6B v2 on a synthetic call: a word starts with a space, and the
        // comma's own time (0.48–0.72 s) isn't part of "Hi,".
        val tokens = arrayOf(" H", "i", ",", " this", " is", " Ch", "r", "is", " from", " I", "'", "m", " call", "ing")
        val starts = floatArrayOf(0f, 0.24f, 0.48f, 0.72f, 0.88f, 1.04f, 1.12f, 1.28f, 1.44f, 3.12f, 3.2f, 3.28f, 3.28f, 3.44f)
        val durations = floatArrayOf(0.24f, 0.24f, 0.24f, 0.16f, 0.16f, 0.08f, 0.16f, 0.16f, 0.16f, 0.08f, 0.08f, 0f, 0.16f, 0.08f)
        val text = "Hi, this is Chris from I'm calling"
        assertEquals(
            listOf(WordTime(0, 480), WordTime(720, 880), WordTime(880, 1040), WordTime(1040, 1440), WordTime(1440, 1600),
                WordTime(3120, 3280), WordTime(3280, 3520)),
            WordTimings.fromTokens(text, tokens, starts, durations, lead = 0f, length = 4f),
        )
    }

    @Test fun theDollarFixKeepsWordsAndTimingsTogether() {
        // cleanText puts a space in "around$2,000"; the words still find their tokens.
        val tokens = arrayOf(" around", "$", "2", ",", "000", " but")
        val starts = floatArrayOf(0f, 0.4f, 0.5f, 0.6f, 0.7f, 1.0f)
        val durations = floatArrayOf(0.4f, 0.1f, 0.1f, 0.1f, 0.2f, 0.2f)
        val words = WordTimings.fromTokens("around \$2,000 but", tokens, starts, durations, lead = 0f, length = 2f)
        assertEquals(listOf(WordTime(0, 400), WordTime(500, 900), WordTime(1000, 1200)), words)
    }

    @Test fun senseVoiceCharactersLastUntilTheNextOne() {
        // SenseVoice: a character at a time, a start each and no durations; punctuation stays with the word before.
        val tokens = arrayOf("您", "好", "，", "我", "是")
        val starts = floatArrayOf(0.12f, 0.3f, 0.54f, 0.66f, 0.84f)
        assertEquals(
            listOf(WordTime(120, 300), WordTime(300, 540), WordTime(660, 840), WordTime(840, 1140)),
            WordTimings.fromTokens("您好，我是", tokens, starts, durations = floatArrayOf(), lead = 0f, length = 1.2f),
        )
    }

    @Test fun streamingTimesLoseTheLeadInAndTagsAreNotText() {
        val tokens = arrayOf("<|hi|>", " hello", " there")
        val starts = floatArrayOf(0f, 0.5f, 0.9f)
        assertEquals(
            listOf(WordTime(200, 600), WordTime(600, 900)),
            WordTimings.fromTokens("hello there", tokens, starts, durations = null, lead = 0.3f, length = 2f),
        )
    }

    @Test fun tokensThatDontSpellTheTextGiveNoTimings() {
        assertNull(WordTimings.fromTokens("hello there", arrayOf(" hello", " their"), floatArrayOf(0f, 0.5f), null, 0f, 1f))
        assertNull(WordTimings.fromTokens("hello", arrayOf(" hello"), floatArrayOf(), null, 0f, 1f))
    }

    @Test fun wordsAreMatchedAcrossCorrectionsIgnoringCaseAndPunctuation() {
        val shown = Words.of("Okay, Youvee is here. Thanks")
        val recognised = Words.of("okay UV is here thanks")
        assertArrayEquals(intArrayOf(0, -1, 2, 3, 4), WordAlignment.align(shown, recognised))
        assertArrayEquals(intArrayOf(0, -1, -1, 2), WordAlignment.align(Words.of("Call you vee now"), Words.of("Call UV now")))
    }

    // ---- splitting ----

    private val question = line(
        "Hello, what is your name?", start = 10_000, end = 12_000, words = "0,40 50,70 72,80 100,120 125,160",
    )

    @Test fun aLineIsCutHalfwayBetweenTwoWords() {
        // "is" ends 0.80 s in, "your" starts 1.00 s in.
        assertEquals(10_900L, TranscriptEdits.cutTime(question, 3))
    }

    @Test fun withoutTimingsTheCutGoesByCharacters() {
        // "your" starts 15 characters into 25.
        assertEquals(11_200L, TranscriptEdits.cutTime(question.copy(words = null), 3))
    }

    @Test fun aCorrectedWordIsCutAfterTheWordBefore() {
        val corrected = question.copy(text = "Hello, what is Youvee name?", recognized = "Hello, what is UV name?")
        assertEquals(10_800L, TranscriptEdits.cutTime(corrected, 3))
        val (head, tail) = TranscriptEdits.cutLine(corrected, 3)
        assertEquals("Hello, what is" to null, head.text to head.recognized)
        assertEquals("Youvee name?" to "UV name?", tail.text to tail.recognized)
        assertEquals(10_800L to 12_000L, tail.startMs to tail.endMs)
        assertTimingsFit(head)
        assertTimingsFit(tail)
    }

    @Test fun cuttingALineDividesItsTextAndTimings() {
        val (head, tail) = TranscriptEdits.cutLine(question, 3)
        assertEquals(Segment(id = 1, recordingId = 1, startMs = 10_000, endMs = 10_900, speaker = 0, text = "Hello, what is",
            words = "0,40 50,70 72,80"), head)
        assertEquals(Segment(id = 0, recordingId = 1, startMs = 10_900, endMs = 12_000, speaker = 0, text = "your name?",
            words = "10,30 35,70"), tail)
    }

    @Test fun oneRecognisedWordTypedAsSeveralCantKeepWhatWasRecognised() {
        val typed = line("Call you vee now", words = "0,20 30,60 70,90", recognized = "Call UV now")
        val (head, tail) = TranscriptEdits.cutLine(typed, 2)
        assertEquals("Call you" to "vee now", head.text to tail.text)
        assertTrue(head.edited && tail.edited)
        assertNull(head.recognized ?: tail.recognized ?: head.words ?: tail.words)
    }

    @Test fun theForumsExampleTakesTwoSplits() {
        // "Bob: hello, what is Tracy: your name? Tracy. Hi Tracy." Bob is speaker 0, Tracy speaker 1.
        val bob = line("Hello, what is", start = 0, end = 1_000, speaker = 0, id = 1, words = "0,30 35,60 65,90")
        val tracy = line("your name? Tracy. Hi Tracy.", start = 1_000, end = 4_000, speaker = 1, id = 2,
            words = "5,25 30,60 110,160 220,240 245,290")
        // Tracy's turn from "Tracy." on is hers; "your name?" is Bob's.
        val first = TranscriptEdits.split(listOf(tracy), at = 2, before = 0, after = 1)
        assertEquals(listOf("your name?" to 0, "Tracy. Hi Tracy." to 1), first.map { it.text to it.speaker })
        assertEquals(1_850L, first[1].startMs) // halfway between "name?" (ends 1.60 s) and "Tracy." (starts 2.10 s)
        // Then "Hi Tracy." is Bob's again.
        val second = TranscriptEdits.split(listOf(first[1].copy(id = 3)), at = 1, before = 1, after = 0)
        val lines = listOf(bob, first[0]) + second.mapIndexed { i, s -> if (s.id == 0L) s.copy(id = 4L + i) else s }
        lines.forEach(::assertTimingsFit)
        assertEquals(
            listOf("Hello, what is your name?" to 0, "Tracy." to 1, "Hi Tracy." to 0),
            TranscriptFormatter.turns(lines).map { it.text to it.speaker },
        )
    }

    @Test fun splittingAtTheStartOfALineOnlyChangesSpeakers() {
        val a = line("Hi there.", start = 0, end = 2_000, id = 1)
        val b = line("Calling about the roof.", start = 2_500, end = 4_000, id = 2)
        assertEquals(listOf(a.copy(speaker = 0), b.copy(speaker = 1)), TranscriptEdits.split(listOf(a, b), at = 2, before = 0, after = 1))
        assertEquals(listOf("Hi", "there.", "Calling", "about", "the", "roof."), TranscriptEdits.words(listOf(a, b)).map { it.text })
    }

    // ---- editing text ----

    @Test fun anEditedTurnBecomesOneLineKeepingWhatWasRecognised() {
        val a = line("Hi there.", start = 0, end = 2_000, id = 1, words = "0,20 30,60")
        val b = line("Calling about Youvee.", start = 2_500, end = 4_000, id = 2, words = "0,30 35,60 65,90",
            recognized = "Calling about UV.")
        val edited = TranscriptEdits.edit(listOf(a, b), "  Hi there.\nCalling about Youvee's roof. ")
        assertEquals(
            Segment(id = 1, recordingId = 1, startMs = 0, endMs = 4_000, speaker = 0, text = "Hi there. Calling about Youvee's roof.",
                words = "0,20 30,60 250,280 285,310 315,340", recognized = "Hi there. Calling about UV.", edited = true),
            edited,
        )
        assertTimingsFit(edited)
        // Lines without timings (before 0.9.0) leave the edited line without them.
        assertNull(TranscriptEdits.edit(listOf(a, b.copy(words = null)), "Hi.").words)
    }

    @Test fun editedChineseKeepsItsCharactersApart() {
        val a = line("你好。", start = 0, end = 1_000, id = 1, words = "0,20 20,50")
        val b = line("我是赵伟。", start = 1_500, end = 3_000, id = 2, words = "0,10 10,30 30,50 50,80")
        val edited = TranscriptEdits.edit(listOf(a, b), "你好。我是曹薇。")
        assertEquals("你好。我是赵伟。", edited.recognized)
        assertEquals("0,20 20,50 150,160 160,180 180,200 200,230", edited.words)
    }

    // ---- "Always correct this?" ----

    @Test fun oneReplacedPhraseIsOfferedAsACorrection() {
        assertEquals("UV" to "Youvee", TranscriptEdits.learnedPhrase("We checked the UV rating.", "We checked the Youvee rating."))
        assertEquals("UV" to "Youvee", TranscriptEdits.learnedPhrase("Thanks, UV.", "Thanks, Youvee."))
        assertEquals("Kate and Lynn" to "Katelynn", TranscriptEdits.learnedPhrase("Call Kate and Lynn today", "Call Katelynn today"))
        assertEquals("赵小伟" to "曹小薇", TranscriptEdits.learnedPhrase("我是赵小伟。", "我是曹小薇。"))
    }

    @Test fun aCommaOrCapitalFixedBesideThePhraseIsntPartOfIt() {
        assertEquals("UV" to "Youvee", TranscriptEdits.learnedPhrase("Yeah UV is great", "Yeah, Youvee is great"))
        assertEquals("uv" to "Youvee", TranscriptEdits.learnedPhrase("so the uv is fine", "So the Youvee is fine"))
        assertEquals("UV" to "Youvee", TranscriptEdits.learnedPhrase("okay UV thanks", "Okay. Youvee, thanks."))
    }

    @Test fun theOfferIsForWhatTheRecogniserWrote() {
        // A rule for "UV" wrote "Youvee"; writing it "Yuvi" offers a rule for "UV", which replaces that one.
        val rules = Corrections(listOf(Corrections.Rule("UV", "Youvee"), Corrections.Rule("Donna", "Dana")))
        assertEquals("UV" to "Yuvi", TranscriptEdits.learnedPhrase("Call Youvee now", "Call Yuvi now", recognized = "Call UV now", rules = rules))
        // Next to another word a rule wrote, word for word.
        assertEquals("UV" to "Yuvi", TranscriptEdits.learnedPhrase("Dana Youvee rating", "Dana Yuvi rating", recognized = "Donna UV rating", rules = rules))
        // Words the recogniser wrote itself are offered as they are.
        assertEquals("UV" to "Youvee", TranscriptEdits.learnedPhrase("Dana UV rating", "Dana Youvee rating", recognized = "Donna UV rating"))
        // The "UV index" collision put back as recognised: there's no rule to offer.
        assertNull(TranscriptEdits.learnedPhrase("The Youvee index is high.", "The UV index is high.", recognized = "The UV index is high."))
        // Where it can't be told which recognised words those were, nothing is offered.
        assertNull(TranscriptEdits.learnedPhrase("Dana Youvee rating", "Dana Yuvi rating", recognized = "Donna U V rating", rules = rules))
    }

    @Test fun otherEditsAreNotOffered() {
        // Only case or punctuation.
        assertNull(TranscriptEdits.learnedPhrase("the uv rating", "the UV rating"))
        assertNull(TranscriptEdits.learnedPhrase("Okay thanks", "Okay, thanks."))
        // Two places changed, more than three words, or words only added or taken out.
        assertNull(TranscriptEdits.learnedPhrase("one two three four five", "uno two three four cinco"))
        assertNull(TranscriptEdits.learnedPhrase("a b c d e", "a w x y z"))
        assertNull(TranscriptEdits.learnedPhrase("I said um yes", "I said yes"))
        assertNull(TranscriptEdits.learnedPhrase("I said yes", "I said yes indeed"))
        // One or two Chinese or Japanese characters would match inside other words ("公事" in "办公事务").
        assertNull(TranscriptEdits.learnedPhrase("我想去北经", "我想去北京"))
        assertNull(TranscriptEdits.learnedPhrase("我是赵伟。", "我是曹薇。"))
        assertNull(TranscriptEdits.learnedPhrase("这是公事。", "这是公司。"))
        assertNull(TranscriptEdits.learnedPhrase("same", "same"))
    }

    @Test fun noOfferInsideWordsTypedByHandEarlier() {
        // The recogniser wrote "the cafe"; an earlier edit made it "Kafé Lund". Fixing "Kafé" must not offer a rule for "the".
        val recognized = "we should meet at the cafe tomorrow"
        assertEquals(null, TranscriptEdits.learnedPhrase("let's meet at Kafé Lund tomorrow", "let's meet at Café Lund tomorrow", recognized))
    }

    @Test fun aWordARuleWroteGivesARuleForTheRecognisersWords() {
        val rules = Corrections(listOf(Corrections.Rule("UV", "Youvee")))
        assertEquals("UV" to "Yuvi",
            TranscriptEdits.learnedPhrase("We checked the Youvee rating.", "We checked the Yuvi rating.", "We checked the UV rating.", rules))
    }
}
