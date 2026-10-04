package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.SummaryLanguage
import io.github.christiantwu.longhand.engine.SummaryParser
import io.github.christiantwu.longhand.engine.SummaryPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SummaryTest {

    @Test fun parsesTheGrammarShape() {
        val s = SummaryParser.parse(
            """{"topic": "Harbor Road building materials.", "summary": "Jordan confirmed the \"Charcoal\" shingles.",
               "follow_ups": [ "Jordan sends the revised quote this afternoon", "Shingles arrive Thursday" ] }""",
        )!!
        assertEquals("Harbor Road building materials", s.topic)
        assertEquals("Jordan confirmed the \"Charcoal\" shingles.", s.summary)
        assertEquals(listOf("Jordan sends the revised quote this afternoon", "Shingles arrive Thursday"), s.followUps)
    }

    @Test fun handlesEmptyFollowUpsAndUnicodeEscapes() {
        // The reply contains a JSON escape (backslash, u, 2014); the parser must turn it into an em dash.
        val reply = "{\"topic\":\"vehicle extended warranty\",\"summary\":\"You declined \\u2014 politely.\",\"follow_ups\":[]}"
        val s = SummaryParser.parse(reply)!!
        assertEquals("You declined \u2014 politely.", s.summary)
        assertTrue(s.followUps.isEmpty())
    }

    @Test fun keepsWhatItCanFromACutOffReply() {
        val s = SummaryParser.parse("""{"topic":"lake cabin booking","summary":"You will send your share tod""")
        assertEquals("lake cabin booking", s?.topic)
        assertNull(SummaryParser.parse("""{"summary":"no topic here"}"""))
        assertNull(SummaryParser.parse("not json"))
    }

    @Test fun promptUsesQwenChatFormatWithThinkingOff() {
        val p = SummaryPrompt.build("caller: Jordan | direction: incoming | duration: 1:00", listOf("[00:00] You: Hi"), 10_000)
        assertTrue(p.startsWith("<|im_start|>system\nYou summarize phone calls"))
        assertTrue(p.contains("<|im_start|>user\ncaller: Jordan | direction: incoming | duration: 1:00\n\nTranscript:\n[00:00] You: Hi<|im_end|>"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test fun theEvaluatedInstructionsAndGrammarAreUnchanged() {
        // What was evaluated on the desktop, English and the other languages alike; a change needs evaluating again.
        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("76e470a6ac0e8af488c7efe9f75c93c783629ca7bc7037d46a420b4710065d0d", sha256(SummaryPrompt.SYSTEM))
        assertEquals("18742987b816221a516988434bb2489a76ac704e9da042c25444911595c38b97", sha256(SummaryPrompt.GRAMMAR))
    }

    private val english = listOf(
        "[00:00] Jordan Ellis: Hi, it's Jordan about the Harbor Road quote.",
        "[00:05] You: Great, send it over by Friday.",
    )

    @Test fun anEnglishCallsPromptIsTheSameAsBefore() {
        val header = "caller: Jordan Ellis | direction: incoming | duration: 0:12"
        val before = "<|im_start|>system\n${SummaryPrompt.SYSTEM}<|im_end|>\n<|im_start|>user\n" +
            "caller: Jordan Ellis | direction: incoming | duration: 0:12\n\nTranscript:\n" +
            "[00:00] Jordan Ellis: Hi, it's Jordan about the Harbor Road quote.\n[00:05] You: Great, send it over by Friday." +
            "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        assertEquals(before, SummaryPrompt.build(header, english, 22_000))
        assertEquals(before, SummaryPrompt.build(header, english, 22_000, SummaryLanguage.English))
        assertEquals(SummaryPrompt.charBudget(english), SummaryPrompt.charBudget(english, SummaryLanguage.English))
        // A Hindi call summarized in English keeps its budget, Devanagari priced as before.
        assertEquals(SummaryPrompt.charBudget(hindi), SummaryPrompt.charBudget(hindi, SummaryLanguage.English))
    }

    private val german = listOf(
        "[00:00] Jonas Becker: Guten Tag, hier ist Becker von der Dachdeckerei Größmann.",
        "[00:04] You: Hallo Herr Becker, es geht um das Angebot für die Garage.",
        "[00:09] Jonas Becker: Genau, wir kommen auf 3.850 Euro, Material inklusive.",
        "[00:15] You: Gut, dann schicken Sie mir bitte die Unterlagen bis Freitag. Schöne Grüße!",
    )

    @Test fun anotherLanguageIsAskedForAtTheEndOfTheTranscript() {
        val header = "caller: Jonas Becker | direction: outgoing | duration: 0:20"
        val p = SummaryPrompt.build(header, german, 22_000, SummaryLanguage.Named("German"))
        assertTrue(p.startsWith("<|im_start|>system\n${SummaryPrompt.SYSTEM}<|im_end|>\n<|im_start|>user\n$header\n\nTranscript:\n"))
        assertTrue(p.endsWith("[00:15] You: Gut, dann schicken Sie mir bitte die Unterlagen bis Freitag. Schöne Grüße!\n\n" +
            "Write the topic, summary and follow-ups in German.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        val unnamed = SummaryPrompt.build(header, german, 22_000, SummaryLanguage.Unnamed)
        assertTrue(unnamed.endsWith("Schöne Grüße!\n\nWrite the topic, summary and follow-ups in the language the call is in, " +
            "even if it isn't English.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        // After the part of a long call left out too.
        val long = (0 until 400).map { german[it % german.size] }
        val cut = SummaryPrompt.build(header, long, 5_000, SummaryLanguage.Named("German"))
        assertTrue(cut.contains("left out ...]\n"))
        assertTrue(cut.contains("Schöne Grüße!\n\nWrite the topic, summary and follow-ups in German.<|im_end|>"))
    }

    private val chinese = listOf(
        "[00:00] 王丽: 喂，你好，我是物业的王丽。",
        "[00:03] You: 你好，是关于停车位的事吗？",
        "[00:07] 王丽: 对，下个月的费用是三百五十元，周五前交就行。",
        "[00:13] You: 好的，我明天转账给你。",
    )

    private val japanese = listOf(
        "[00:00] 佐藤: もしもし、佐藤です。来週の打ち合わせの件でお電話しました。",
        "[00:05] You: はい、木曜日の午後二時でよろしいですか？",
        "[00:10] 佐藤: 大丈夫です。資料はメールで送ります。",
    )

    private val korean = listOf(
        "[00:00] 김민수: 여보세요, 김민수입니다. 이사 견적 때문에 전화드렸어요.",
        "[00:04] You: 네, 다음 주 토요일 오전으로 가능할까요?",
        "[00:09] 김민수: 네, 총 85만 원이고 계약금은 10만 원입니다.",
    )

    @Test fun otherLanguagesArePricedLineByLineAsMeasured() {
        // The measurement's own figures for these lines (its char_budget_p2), to the character: the same doubles in the
        // same order. Before, every one of them but Hindi got the whole 22,000 characters, too many for CJK.
        fun budget(lines: List<String>) = SummaryPrompt.charBudget(lines, SummaryLanguage.Named("X"))
        assertEquals(17_959, budget(german))
        assertEquals(9_112, budget(chinese))
        assertEquals(10_435, budget(japanese))
        assertEquals(9_725, budget(korean))
        assertEquals(16_156, budget(listOf(
            "[00:00] Ольга: Алло, это Ольга из автосервиса.",
            "[00:03] You: Да, здравствуйте, машина готова?",
            "[00:06] Ольга: Почти, завтра к обеду можно забирать.",
        )))
        assertEquals(13_348, budget(listOf(
            "[00:00] Νίκος: Γεια σας, ο Νίκος από το συνεργείο.",
            "[00:03] You: Καλημέρα, πότε θα είναι έτοιμο το αυτοκίνητο;",
        )))
        assertEquals(13_422, budget(hindi))
        // An hour's time costs more digits; a character beyond U+FFFF counts as its two halves.
        assertEquals(12_571, budget(listOf("[1:02:03] You: Ok.")))
        assertEquals(12_800, budget(listOf("[00:00] Lena: Bis morgen \uD83D\uDC4B")))
        assertEquals(22_000, budget(emptyList()))
        // The unnamed wording is priced the same way.
        assertEquals(17_959, SummaryPrompt.charBudget(german, SummaryLanguage.Unnamed))
    }

    @Test fun longCallsKeepTheStartAndEnd() {
        val lines = (0 until 1000).map { "[%02d:00] You: line number $it with some words".format(it % 60) }
        val fitted = SummaryPrompt.fit(lines, 5_000)
        assertTrue(fitted.sumOf { it.length + 1 } <= 5_000 + 60)
        assertEquals(lines.first(), fitted.first())
        assertEquals(lines.last(), fitted.last())
        assertTrue(fitted.any { it.contains("left out") })
        assertEquals(lines.take(3), SummaryPrompt.fit(lines.take(3), 5_000))
    }

    private val hindi = listOf(
        "[00:00] Ramesh Sharma: हेलो, नमस्ते भैया, रमेश बोल रहा हूँ।",
        "[00:03] You: हाँ रमेश जी नमस्ते, मैं किचन के काम के बारे में कॉल कर रहा था।",
        "[00:11] Ramesh Sharma: जी जी, तो कैसा लगा? टोटल ढाई लाख का बन रहा है।",
        "[00:18] You: थोड़ा ज़्यादा लग रहा है, चिमनी हटा दें तो कितना कम होगा?",
    )

    private val mixed = listOf(
        "[00:00] Ramesh Sharma: Hello, नमस्ते भैया, रमेश बोल रहा हूँ।",
        "[00:03] You: हाँ रमेश जी नमस्ते, मैं kitchen के काम के बारे में call कर रहा था।",
        "[00:11] Ramesh Sharma: जी जी, तो कैसा लगा? Total ढाई लाख का बन रहा है।",
        "[00:18] You: थोड़ा ज़्यादा लग रहा है, chimney हटा दें तो कितना कम होगा?",
    )

    @Test fun englishKeepsItsBudget() {
        assertEquals(22_000, SummaryPrompt.charBudget(listOf("[00:00] You: Hi, it's about the roof quote.", "[00:04] Jordan: Sure.")))
        assertEquals(22_000, SummaryPrompt.charBudget(emptyList()))
    }

    @Test fun devanagariGetsFewerCharacters() {
        // Qwen's tokenizer takes about 2 characters of a Hindi transcript a token, against 3.5 of English, so the same
        // tokens hold about 12,500 characters (13,000 or so here, with more of the line in Latin letters).
        val h = SummaryPrompt.charBudget(hindi)
        assertTrue("$h", h in 12_000..14_000)
        // Hindi with English words in Latin letters falls in between.
        val m = SummaryPrompt.charBudget(mixed)
        assertTrue("$m", m in h + 1 until 16_000)
        // A long Hindi call is cut to that budget at the first try.
        val long = (0 until 400).map { hindi[it % hindi.size] }
        val fitted = SummaryPrompt.fit(long, SummaryPrompt.charBudget(long))
        assertTrue(fitted.sumOf { it.length + 1 } <= h + 60)
        assertEquals(long.last(), fitted.last())
    }
}
