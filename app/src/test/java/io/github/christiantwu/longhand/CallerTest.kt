package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.CallLogEntry
import io.github.christiantwu.longhand.data.Caller
import io.github.christiantwu.longhand.data.CallMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerTest {
    private val t0 = 1_790_000_000_000L
    private fun call(n: String, startOffsetSec: Long, dur: Long, type: Int = 1) = CallLogEntry(n, t0 + startOffsetSec * 1000, dur, type, null)

    @Test fun matchesTheCallThatEndedJustBeforeTheFileWasWritten() {
        // 222 ended 20 s before the file was written; 333 was still going on then, so it can't be the one.
        val calls = listOf(call("111", -3600, 300), call("222", -400, 360), call("333", -15, 60))
        assertEquals("222", CallMatcher.best(calls, t0 - 20_000, recordingMs = 0)?.number)
    }

    @Test fun ignoresMissedCallsAndCallsTooShortForTheRecording() {
        val calls = listOf(call("missed", -100, 0, type = 3), call("short", -100, 40), call("right", -200, 150))
        assertEquals("right", CallMatcher.best(calls, t0 - 45_000, recordingMs = 140_000)?.number)
    }

    @Test fun allowsARecordingThatStartedLate() {
        val calls = listOf(call("x", -600, 540))
        assertEquals("x", CallMatcher.best(calls, t0 - 50_000, recordingMs = 120_000)?.number)
    }

    @Test fun noMatchWhenNothingEndedNearby() {
        assertNull(CallMatcher.best(listOf(call("x", -7200, 60)), t0, recordingMs = 0))
    }

    @Test fun findsNumbersInFileNamesButNotDates() {
        assertEquals("+15555550123", CallMatcher.numberFromFileName("Call_2026-09-30_10-15-03_+15555550123.mp3"))
        assertEquals("5555550142", CallMatcher.numberFromFileName("5555550142_20260930.mp3"))
        assertNull(CallMatcher.numberFromFileName("Call recording 2026-09-30 10-15-03.mp3"))
        assertNull(CallMatcher.numberFromFileName("20260930_101503.mp3"))
    }

    private val digitsMatch = { a: String, b: String -> a.filter(Char::isDigit).takeLast(10) == b.filter(Char::isDigit).takeLast(10) }

    @Test fun aWeakerLookupKeepsWhatWasFoundBefore() {
        val earlier = Caller("+15555550142", "Jordan Ellis", "key-1", 1)
        // Call log access is gone: only the number from the file name, no direction, no contact.
        val now = Caller("5555550142", null, null, null)
        assertEquals(Caller("5555550142", "Jordan Ellis", "key-1", 1), now.orElse(earlier, digitsMatch))
        assertEquals(earlier, Caller(null, null, null, null).orElse(earlier, digitsMatch))
    }

    @Test fun aDifferentNumberReplacesTheEarlierCaller() {
        val earlier = Caller("+15555550142", "Jordan Ellis", "key-1", 1)
        val now = Caller("+15555550199", null, null, 2)
        assertEquals(now, now.orElse(earlier, digitsMatch))
    }
}
