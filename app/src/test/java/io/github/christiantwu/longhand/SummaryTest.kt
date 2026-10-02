package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.SummaryParser
import io.github.christiantwu.longhand.engine.SummaryPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
