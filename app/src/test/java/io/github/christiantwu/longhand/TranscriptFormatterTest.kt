package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.export.SpeakerNames
import io.github.christiantwu.longhand.export.TranscriptFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatterTest {
    private val segments = listOf(
        Segment(recordingId = 1, startMs = 0, endMs = 3000, speaker = 0, text = "Hi there."),
        Segment(recordingId = 1, startMs = 3500, endMs = 6000, speaker = 0, text = "Calling about the roof."),
        Segment(recordingId = 1, startMs = 83_000, endMs = 85_000, speaker = 1, text = "Great, thanks."),
    )
    private val rec = Recording(
        id = 1, documentUri = "u", displayName = "call_2026.mp3", sizeBytes = 1, lastModified = 0,
        status = RecordingStatus.DONE, durationMs = 85_000,
    )

    @Test fun groupsConsecutiveLinesIntoTurns() {
        val turns = TranscriptFormatter.turns(segments)
        assertEquals(2, turns.size)
        assertEquals("Hi there. Calling about the roof.", turns[0].text)
        assertEquals(83_000L, turns[1].startMs)
    }

    @Test fun turnsKnowTheirLinesInOrder() {
        val lines = listOf(
            Segment(id = 9, recordingId = 1, startMs = 3500, endMs = 6000, speaker = 0, text = "Calling about the roof."),
            Segment(id = 8, recordingId = 1, startMs = 83_000, endMs = 85_000, speaker = 1, text = "Great, thanks."),
            Segment(id = 7, recordingId = 1, startMs = 0, endMs = 3000, speaker = 0, text = "Hi there."),
            // Lines that start together are taken in the order they were stored (a line split by hand, say).
            Segment(id = 12, recordingId = 1, startMs = 85_000, endMs = 86_000, speaker = 0, text = "Bye."),
            Segment(id = 11, recordingId = 1, startMs = 85_000, endMs = 85_000, speaker = 1, text = "Okay."),
        )
        val turns = TranscriptFormatter.turns(lines)
        assertEquals(listOf(listOf(7L, 9L), listOf(8L, 11L), listOf(12L)), turns.map { it.segmentIds })
        assertEquals("Great, thanks. Okay.", turns[1].text)
    }

    @Test fun exportsSayWhenACallWasEditedByHand() {
        val meta = { r: Recording -> TranscriptFormatter.format(r, segments, SpeakerNames(), markdown = false).lines()[1] }
        assertTrue(meta(rec.copy(editedAt = 1L)).endsWith(" · Edited"))
        assertTrue(!meta(rec).contains("Edited"))
        assertTrue(TranscriptFormatter.format(rec.copy(editedAt = 1L), segments, SpeakerNames(), markdown = true).contains(" · Edited*"))
    }

    @Test fun aLaterLineOfTheSameSpeakerKeepsItsOwnTime() {
        // The other person's short "Sure." overlaps the start of the owner's question; their reply
        // comes 4 s later. The reply must not join the "Sure." under its time, or tapping it plays
        // from the wrong place.
        val lines = listOf(
            Segment(recordingId = 1, startMs = 60_000, endMs = 64_700, speaker = 1, text = "Could you send both quotes by Friday?"),
            Segment(recordingId = 1, startMs = 60_030, endMs = 61_000, speaker = 0, text = "Sure."),
            Segment(recordingId = 1, startMs = 65_300, endMs = 70_000, speaker = 0, text = "I'll email them this afternoon."),
        )
        val turns = TranscriptFormatter.turns(lines)
        assertEquals(listOf(60_000L, 60_030L, 65_300L), turns.map { it.startMs })
        assertEquals("I'll email them this afternoon.", turns[2].text)
    }

    @Test fun aReplyAfterAShortLineTheyTalkedOverIsItsOwnTurn() {
        // Same, but the owner's line is short: the gap is under 2 s, yet the owner spoke in between.
        val lines = listOf(
            Segment(recordingId = 1, startMs = 100_000, endMs = 101_800, speaker = 1, text = "Sure, that works."),
            Segment(recordingId = 1, startMs = 100_050, endMs = 100_600, speaker = 0, text = "Okay."),
            Segment(recordingId = 1, startMs = 102_200, endMs = 108_000, speaker = 0, text = "Then Thursday at nine."),
        )
        assertEquals(listOf(100_000L, 100_050L, 102_200L), TranscriptFormatter.turns(lines).map { it.startMs })
    }

    @Test fun aPauseWithNobodyElseTalkingStaysOneTurn() {
        val lines = listOf(
            Segment(recordingId = 1, startMs = 0, endMs = 4_000, speaker = 0, text = "The estimate is ready."),
            Segment(recordingId = 1, startMs = 5_500, endMs = 9_000, speaker = 0, text = "I'll send it tonight."),
        )
        val turns = TranscriptFormatter.turns(lines)
        assertEquals(1, turns.size)
        assertEquals(9_000L, turns[0].endMs)
    }

    @Test fun chineseAndJapaneseLinesJoinWithoutSpaces() {
        fun joined(vararg texts: String) = TranscriptFormatter.turns(texts.mapIndexed { i, t ->
            Segment(recordingId = 1, startMs = i * 3_000L, endMs = i * 3_000L + 2_500, speaker = 0, text = t)
        }).single().text
        assertEquals("你好。我想问一下报价。", joined("你好。", "我想问一下报价。"))
        assertEquals("はいわかりました", joined("はい", "わかりました"))
        assertEquals("네. 알겠습니다.", joined("네.", "알겠습니다."))
        assertEquals("OK. 我知道了。", joined("OK.", "我知道了。"))
    }

    @Test fun speakerNamesPreferHandTypedThenOwnerThenCaller() {
        val n = SpeakerNames(manual = mapOf(2 to "Dana"), owner = 1, callerName = "Jordan Ellis", speech = mapOf(0 to 5_500L, 1 to 2_000L))
        assertEquals("You", n.label(1))
        assertEquals("Jordan Ellis", n.label(0))
        assertEquals("Dana", n.label(2))
    }

    @Test fun callerNameNeedsAKnownOwner() {
        val speech = mapOf(0 to 9_000L, 1 to 4_000L)
        assertEquals("Speaker 1", SpeakerNames(owner = null, callerName = "Jordan", speech = speech).label(0))
        // The owner's voice was found, but none of their lines are left (moved to someone else by hand).
        assertNull(SpeakerNames(owner = 2, callerName = "Jordan", speech = speech).caller)
        assertNull(SpeakerNames(owner = 0, callerName = null, speech = speech).caller)
        assertEquals("Speaker 3", SpeakerNames(manual = mapOf(2 to "  ")).label(2))
    }

    @Test fun callerNameGoesToTheOtherVoiceHeardMost() {
        // You, the contact, and a few seconds of someone in the background, heard first: they stay "Speaker 2".
        val n = SpeakerNames(owner = 0, callerName = "Jordan", speech = mapOf(1 to 3_000L, 0 to 60_000L, 2 to 45_000L))
        assertEquals(2, n.caller)
        assertEquals("Jordan", n.label(2))
        assertEquals("Speaker 2", n.label(1))
        assertEquals("You", n.label(0))
        assertTrue(n.isUnnamed(1))
        assertFalse(n.isUnnamed(2))
        // Two who speak as long: the one heard first.
        assertEquals(2, SpeakerNames(owner = 0, callerName = "Jordan", speech = mapOf(0 to 10_000L, 2 to 5_000L, 1 to 5_000L)).caller)
        // Just the owner: nobody to name.
        assertNull(SpeakerNames(owner = 0, callerName = "Jordan", speech = mapOf(0 to 10_000L)).caller)
    }

    @Test fun namesTypedByHandStillWinOverTheCallersName() {
        val n = SpeakerNames(owner = 0, callerName = "Jordan", speech = mapOf(0 to 60_000L, 1 to 45_000L, 2 to 3_000L))
        // The main voice named by hand: the caller's name doesn't move on to the other voice.
        val named = n.copy(manual = mapOf(1 to "Riley"))
        assertEquals("Riley", named.label(1))
        assertEquals("Speaker 3", named.label(2))
        // The caller's name typed for another voice: nobody else is shown with it.
        val elsewhere = n.copy(manual = mapOf(2 to " jordan "))
        assertNull(elsewhere.caller)
        assertEquals("jordan", elsewhere.label(2))
        assertEquals("Speaker 2", elsewhere.label(1))
    }

    @Test fun speechAddsUpEachSpeakersLinesInTheOrderTheyreFirstHeard() {
        val lines = listOf(
            Segment(id = 1, recordingId = 1, startMs = 5_000, endMs = 9_000, speaker = 2, text = "Sure."),
            Segment(id = 2, recordingId = 1, startMs = 1_000, endMs = 2_000, speaker = 0, text = "Hello?"),
            Segment(id = 3, recordingId = 1, startMs = 10_000, endMs = 13_500, speaker = 0, text = "See you then."),
        )
        val speech = SpeakerNames.speechOf(lines)
        assertEquals(mapOf(0 to 4_500L, 2 to 4_000L), speech)
        assertEquals(listOf(0, 2), speech.keys.toList())
    }

    @Test fun sentenceReadsLikeTheUsersExample() {
        val r = rec.copy(contactName = "Jordan Ellis", topic = "Harbor Road building materials")
        assertEquals("Call with Jordan Ellis regarding Harbor Road building materials", CallText.sentence(r))
        assertEquals("Call with Jordan Ellis", CallText.sentence(r.copy(topic = null)))
        assertEquals("Call regarding lake cabin booking", CallText.sentence(rec.copy(topic = "lake cabin booking")))
        assertEquals("call_2026", CallText.sentence(rec))
        assertEquals("Lake cabin booking", CallText.topicLine("lake cabin booking"))
    }

    @Test fun titleFallsBackFromContactToNumberToFileName() {
        assertEquals("Jordan Ellis", CallText.title(rec.copy(contactName = "Jordan Ellis", phoneNumber = "+15555550142")))
        assertEquals("(555) 555-0142", CallText.title(rec.copy(phoneNumber = "5555550142")) { "(555) 555-0142" })
        assertEquals("call_2026", CallText.title(rec))
    }

    @Test fun plainTextIncludesSummaryFollowUpsAndNames() {
        val r = rec.copy(contactName = "Jordan", topic = "roof estimate", summary = "You quoted the roof.", followUps = "Send quote\nCall back Friday", ownerSpeaker = 1)
        val names = SpeakerNames(owner = 1, callerName = "Jordan", speech = SpeakerNames.speechOf(segments))
        val text = TranscriptFormatter.format(r, segments, names, markdown = false)
        assertTrue(text.startsWith("Call with Jordan regarding roof estimate\n"))
        assertTrue(text.contains("You quoted the roof."))
        assertTrue(text.contains("- Call back Friday"))
        assertTrue(text.contains("[00:00:00] Jordan: Hi there. Calling about the roof."))
        assertTrue(text.contains("[00:01:23] You: Great, thanks."))
        assertTrue(text.endsWith("thanks.\n"))
    }

    @Test fun markdownHasTitleAndBoldLabels() {
        val md = TranscriptFormatter.format(rec, segments, SpeakerNames(), markdown = true)
        assertTrue(md.startsWith("# call_2026\n"))
        assertTrue(md.contains("**[00:00:00] Speaker 1:** Hi there."))
    }

    @Test fun summaryPromptPartsMatchTheEvaluatedFormat() {
        val r = rec.copy(contactName = "Jordan Ellis", callDirection = 1, durationMs = 185_000)
        assertEquals("caller: Jordan Ellis | direction: incoming | duration: 3:05", TranscriptFormatter.summaryHeader(r))
        val lines = TranscriptFormatter.summaryLines(segments, SpeakerNames(owner = 1, callerName = "Jordan Ellis", speech = SpeakerNames.speechOf(segments)))
        assertEquals("[00:00] Jordan Ellis: Hi there. Calling about the roof.", lines[0])
        assertEquals("[01:23] You: Great, thanks.", lines[1])
    }
}
