package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.CallLanguage
import io.github.christiantwu.longhand.engine.CallLanguage.Family
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which downloaded language a call is in: the windows identified, and the rule that decides from what they say. */
class CallLanguageTest {

    private val sr = 16_000

    private fun s(seconds: Int) = seconds * sr

    private fun decide(codes: String, speechSeconds: Float = 40f) =
        CallLanguage.decide(if (codes.isEmpty()) emptyList() else codes.split(","), speechSeconds)

    @Test fun shortSpeechIsOneWindowOrNone() {
        assertEquals(emptyList<Pair<Int, Int>>(), CallLanguage.windows(0))
        assertEquals(listOf(0 to s(5)), CallLanguage.windows(s(5)))
        assertEquals(listOf(0 to s(9)), CallLanguage.windows(s(9)))
    }

    @Test fun speechIsCutInto9SecondTilesKeepingALastOneOf4SecondsOrMore() {
        // 3 s left over is dropped, 4 s is a tile of its own.
        assertEquals(listOf(0 to s(9)), CallLanguage.windows(s(12)))
        assertEquals(listOf(0 to s(9), s(9) to s(13)), CallLanguage.windows(s(13)))
        assertEquals(listOf(0 to s(9), s(9) to s(18), s(18) to s(27)), CallLanguage.windows(s(27)))
    }

    @Test fun ofMoreTilesTheFirstMiddleAndLast() {
        // 40 s: four tiles and a 4 s one; the middle is the third.
        assertEquals(listOf(0 to s(9), s(18) to s(27), s(36) to s(40)), CallLanguage.windows(s(40)))
        // 60 s: six tiles and a 6 s one.
        assertEquals(listOf(0 to s(9), s(27) to s(36), s(54) to s(60)), CallLanguage.windows(s(60)))
        // An hour's speech: still three, none longer than sherpa-onnx takes at once.
        val hour = CallLanguage.windows(s(3600))
        assertEquals(3, hour.size)
        assertEquals(s(3591) to s(3600), hour.last())
    }

    @Test fun speechIsPaddedLikeTranscriptionAndJoinedWhereItOverlaps() {
        val padded = CallLanguage.padded(listOf(1_000 to 16_000, 17_000 to 30_000, 60_000 to 99_500), length = 100_000,
            before = 3_200, after = 1_600)
        assertEquals(listOf(0 to 31_600, 56_800 to 100_000), padded)
        assertEquals(emptyList<Pair<Int, Int>>(), CallLanguage.padded(emptyList(), 100_000, 3_200, 1_600))
    }

    @Test fun theSpeechIsEverythingDiarizationHeardAnyoneSayPaddedAndJoined() {
        // Raw segments in seconds, out of order, with two people talking at once at 2.5-3 s.
        val raw = listOf(Span(10f, 12f, 1), Span(1f, 3f, 0), Span(2.5f, 4f, 1), Span(4.25f, 5f, 0), Span(0.125f, 0.5f, 2),
            Span(39f, 40f, 0))
        assertEquals(listOf(0 to 9_600, 12_800 to 81_600, 156_800 to 193_600, 620_800 to s(40)),
            CallLanguage.speech(raw, length = s(40), before = 3_200, after = 1_600))
        // Turns found without diarization are the speech as they are, joined where they overlap.
        assertEquals(listOf(s(1) to s(7), s(8) to s(9)),
            CallLanguage.speech(listOf(Span(5f, 7f, 1), Span(8f, 9f, 0), Span(1f, 3f, 0), Span(2f, 6f, 0)), length = s(10)))
        assertEquals(emptyList<Pair<Int, Int>>(), CallLanguage.speech(emptyList(), s(10), 3_200, 1_600))
    }

    @Test fun aWindowOfTheJoinedSpeechIsFoundPieceByPieceInTheCall() {
        val speech = listOf(100 to 200, 500 to 700, 1_000 to 1_100)
        assertEquals(listOf(150 to 200, 500 to 650), CallLanguage.pieces(speech, 50, 250))
        assertEquals(listOf(100 to 200, 500 to 700, 1_000 to 1_100), CallLanguage.pieces(speech, 0, 400))
        assertEquals(listOf(1_050 to 1_100), CallLanguage.pieces(speech, 350, 400))
        // The windows of a call's speech, laid end to end, are exactly that much of the call.
        val ranges = listOf(0 to s(7), s(10) to s(30), s(31) to s(52))
        val total = ranges.sumOf { (a, b) -> b - a }
        for ((from, to) in CallLanguage.windows(total)) {
            assertEquals(to - from, CallLanguage.pieces(ranges, from, to).sumOf { (a, b) -> b - a })
        }
    }

    @Test fun languagesByTheModelThatCoversThem() {
        assertEquals(Family.ENGLISH, CallLanguage.family("en"))
        for (code in listOf("de", "fr", "pl", "uk", "mt", "sv")) assertEquals(code, Family.EUROPEAN, CallLanguage.family(code))
        // Close relatives Whisper names instead, and Latin for European speech it can't place.
        for (code in listOf("bs", "sr", "mk", "be", "no", "nn", "ca", "gl", "af", "lb", "la")) {
            assertEquals(code, Family.EUROPEAN, CallLanguage.family(code))
        }
        for (code in listOf("zh", "yue", "ja", "ko")) assertEquals(code, Family.CJK, CallLanguage.family(code))
        for (code in listOf("hi", "ur")) assertEquals(code, Family.HINDI, CallLanguage.family(code))
        // Not covered, or no answer.
        for (code in listOf("ar", "tr", "vi", "")) assertEquals(code, Family.OTHER, CallLanguage.family(code))
        assertEquals(Models.Language.HINDI, Family.HINDI.language)
        assertNull(Family.OTHER.language)
        assertEquals(Family.EUROPEAN, Family.of(Models.Language.EUROPEAN))
    }

    @Test fun underTenSecondsOfSpeechOrNoWindowsStays() {
        assertNull(decide("ja,ja,ja", speechSeconds = 9.9f))
        assertNull(decide("en", speechSeconds = 8f))
        assertNull(decide("", speechSeconds = 40f))
        assertEquals(Family.CJK, decide("ja", speechSeconds = 10f))
    }

    @Test fun oneLanguageTheModelsCoverSwitchesWithTwoWindows() {
        assertEquals(Family.CJK, decide("ja,ja,ja"))
        assertEquals(Family.CJK, decide("ja,ko,zh"))
        assertEquals(Family.EUROPEAN, decide("de,de,en"))
        assertEquals(Family.HINDI, decide("hi,ur"))
        // With one window, that one is enough.
        assertEquals(Family.HINDI, decide("hi", speechSeconds = 12f))
        // One window of a language nobody covers doesn't stop it; two do.
        assertEquals(Family.EUROPEAN, decide("fr,ar,fr"))
        assertNull(decide("fr,ar,ar"))
        assertNull(decide("ar,ar,ar"))
    }

    @Test fun oneWindowAndTheRestEnglishIsAMixedCallOnlyThatModelCovers() {
        assertEquals(Family.HINDI, decide("hi,en,en"))
        assertEquals(Family.CJK, decide("en,ja"))
        // With something uncovered in it, it isn't.
        assertNull(decide("de,en,tr"))
    }

    @Test fun twoFamiliesSwitchOnlyTwoToOneWithNothingUncovered() {
        assertEquals(Family.EUROPEAN, decide("de,ja,fr"))
        assertEquals(Family.CJK, decide("ja,de,ja"))
        assertNull(decide("ja,de"))
        assertNull(decide("ja,de,hi"))
    }

    @Test fun englishOnlyWhenEveryWindowIsEnglish() {
        assertEquals(Family.ENGLISH, decide("en,en,en"))
        assertEquals(Family.ENGLISH, decide("en,en"))
        assertEquals(Family.ENGLISH, decide("en", speechSeconds = 11f))
        assertNull(decide("en,en,ar"))
        assertNull(decide("en,,en"))
        assertNull(decide("en,en,en", speechSeconds = 9f))
    }

    @Test fun theDecisionAsALanguage() {
        assertEquals(Models.Language.CJK, CallLanguage.languageOf(CallLanguage.Detection(listOf("ko", "ko", "en"), 30f)))
        assertEquals(Models.Language.ENGLISH, CallLanguage.languageOf(CallLanguage.Detection(listOf("en", "en", "en"), 30f)))
        assertNull(CallLanguage.languageOf(CallLanguage.Detection(listOf("ar", "ar", "ar"), 30f)))
        assertNull(CallLanguage.languageOf(CallLanguage.Detection(emptyList(), 6f)))
    }

    @Test fun storedAsCommaSeparatedCodes() {
        assertNull(CallLanguage.Detection.of(null, null))
        assertNull(CallLanguage.Detection.of("en", null))
        assertEquals(CallLanguage.Detection(emptyList(), 6.5f), CallLanguage.Detection.of("", 6.5f))
        val detection = CallLanguage.Detection(listOf("en", "ja", "ja"), 41.2f)
        assertEquals("en,ja,ja", detection.stored)
        assertEquals(detection, CallLanguage.Detection.of(detection.stored, 41.2f))
        // A window Whisper failed on stays one.
        assertEquals(listOf("en", "", "ja"), CallLanguage.Detection.of("en,,ja", 30f)?.codes)
    }

    @Test fun aCallMarkedWhileItIsDetectedHasNoCodesAndFollowsSettings() {
        // RecordingDao.startDetection's mark, left by a crash: detected, with nothing found.
        val marked = CallLanguage.Detection.of("", 0f)!!
        assertEquals(emptyList<String>(), marked.codes)
        assertNull(CallLanguage.languageOf(marked))
        assertNull(CallLanguage.spokenName(marked))
    }

    @Test fun theLanguageSpokenIsTheMostCommonOfThoseCovered() {
        assertEquals("ja", CallLanguage.spoken(listOf("en", "ja", "ja")))
        assertEquals("en", CallLanguage.spoken(listOf("en", "ar", "ar")))
        // A tie names neither.
        assertNull(CallLanguage.spoken(listOf("de", "fr")))
        assertNull(CallLanguage.spoken(listOf("ar", "", "tr")))
        // In the family the call went to.
        assertEquals("ja", CallLanguage.spoken(listOf("ja", "en", "en"), Family.CJK))
        assertEquals("Japanese", CallLanguage.spokenName(CallLanguage.Detection(listOf("en", "ja", "ja"), 40f)))
        // A mixed call goes to the model of its other language, which is the one named.
        assertEquals("Hindi", CallLanguage.spokenName(CallLanguage.Detection(listOf("hi", "en", "en"), 40f)))
        assertEquals("English", CallLanguage.spokenName(CallLanguage.Detection(listOf("en", "en", "en"), 40f)))
        assertEquals("Cantonese", CallLanguage.displayName("yue"))
        assertEquals("Chinese", CallLanguage.displayName("zh"))
    }

    private fun spokenName(codes: String) = CallLanguage.spokenName(CallLanguage.Detection(codes.split(","), 40f))

    @Test fun whispersStandInsAreNeverNamed() {
        // Latin for European speech Whisper can't place: the next most common language of the family is named.
        assertEquals("German", spokenName("la,la,de"))
        assertEquals("Croatian", spokenName("bs,hr,sr"))
        // Whisper often says Urdu for Hindi speech: it's named Hindi.
        assertEquals("Hindi", spokenName("ur,hi,en"))
        assertEquals("Hindi", spokenName("ur,ur,ur"))
        assertEquals("hi", CallLanguage.spoken(listOf("ur", "en", "en"), Family.HINDI))
        // Only stand-ins: still the European model, but no language to name.
        assertEquals(Family.EUROPEAN, decide("sr,sr,la"))
        assertNull(spokenName("sr,sr,la"))
        // Nor one of another family instead.
        assertNull(spokenName("no,no,en"))
        // A tie names nobody: a Swedish call heard as "fr,sv,la" goes to the European model, unnamed.
        assertNull(CallLanguage.spoken(listOf("fr", "sv", "la"), CallLanguage.Family.EUROPEAN))
    }
}
