package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.CallLanguage
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.Models.Language.CJK
import io.github.christiantwu.longhand.engine.Models.Language.ENGLISH
import io.github.christiantwu.longhand.engine.Models.Language.EUROPEAN
import io.github.christiantwu.longhand.engine.Models.Language.HINDI
import io.github.christiantwu.longhand.ui.TranscribeAgain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Transcribe again" in another language, or with detection on, Automatic: which one the choice starts on, and what
 * choosing one does to the call.
 */
class TranscribeAgainTest {

    private val usable = listOf(ENGLISH, EUROPEAN, CJK)

    @Test fun startsOnTheCallsPinnedLanguage() {
        assertEquals(CJK, TranscribeAgain.preselected(pinned = CJK, madeWith = EUROPEAN, chosen = ENGLISH, previous = null, usable))
        assertEquals(ENGLISH, TranscribeAgain.preselected(pinned = ENGLISH, madeWith = null, chosen = EUROPEAN, previous = null, usable))
    }

    @Test fun elseOnTheLanguageItsTranscriptWasMadeIn() {
        assertEquals(EUROPEAN, TranscribeAgain.preselected(pinned = null, madeWith = EUROPEAN, chosen = ENGLISH, previous = null, usable))
        // Pinned to a language removed since: as if it weren't pinned.
        assertEquals(EUROPEAN, TranscribeAgain.preselected(pinned = HINDI, madeWith = EUROPEAN, chosen = ENGLISH, previous = null, usable))
    }

    @Test fun elseOnTheLanguageSettingsTranscribesIn() {
        // Transcribed before 0.10.0, so it doesn't say which language it was made in.
        assertEquals(ENGLISH, TranscribeAgain.preselected(pinned = null, madeWith = null, chosen = ENGLISH, previous = null, usable))
        // Made in a language removed since.
        assertEquals(CJK, TranscribeAgain.preselected(pinned = null, madeWith = HINDI, chosen = CJK, previous = null, usable))
        // Settings' language still downloading: the one transcribing meanwhile, else any, as for a new call.
        assertEquals(EUROPEAN, TranscribeAgain.preselected(pinned = null, madeWith = null, chosen = HINDI, previous = EUROPEAN, usable))
        assertEquals(ENGLISH, TranscribeAgain.preselected(pinned = null, madeWith = null, chosen = HINDI, previous = null, usable))
        assertNull(TranscribeAgain.preselected(pinned = CJK, madeWith = CJK, chosen = CJK, previous = null, emptyList()))
    }

    private fun disk(vararg sets: Models.Set): Models.Sizes {
        val files = sets.flatMap { it.files }.associate { it.path to it.sizeBytes }
        return Models.Sizes { files[it] ?: 0L }
    }

    @Test fun settingsLanguageClearsThePinAndAnyOtherPinsIt() {
        val disk = disk(Models.Set.SPEECH, Models.Set.MULTILINGUAL, Models.Set.CJK)
        assertNull(TranscribeAgain.pin(ENGLISH, chosen = ENGLISH, previous = null, disk))
        assertEquals(CJK, TranscribeAgain.pin(CJK, chosen = ENGLISH, previous = null, disk))
        // Settings changed after the call was pinned to English: the new language clears the pin, English keeps it.
        assertNull(TranscribeAgain.pin(EUROPEAN, chosen = EUROPEAN, previous = ENGLISH, disk))
        assertEquals(ENGLISH, TranscribeAgain.pin(ENGLISH, chosen = EUROPEAN, previous = ENGLISH, disk))
    }

    @Test fun whileSettingsLanguageDownloadsTheOneTranscribingMeanwhileFollowsSettings() {
        // Hindi chosen and still downloading, so it isn't offered; the European languages transcribe meanwhile.
        val disk = disk(Models.Set.SPEECH, Models.Set.MULTILINGUAL)
        assertNull(TranscribeAgain.pin(EUROPEAN, chosen = HINDI, previous = EUROPEAN, disk))
        assertEquals(ENGLISH, TranscribeAgain.pin(ENGLISH, chosen = HINDI, previous = EUROPEAN, disk))
        // Once Hindi is in, a call left following Settings goes with it.
        assertEquals(HINDI, Models.languageFor(null, null, HINDI, EUROPEAN, disk(Models.Set.SPEECH, Models.Set.MULTILINGUAL, Models.Set.HINDI)))
    }

    @Test fun theCallIsTranscribedInTheLanguagePickedForIt() {
        val disk = disk(Models.Set.SPEECH, Models.Set.CJK)
        // A Japanese call, with English chosen in Settings.
        val pinned = TranscribeAgain.pin(CJK, chosen = ENGLISH, previous = null, disk)
        assertEquals(CJK, Models.languageFor(pinned, null, ENGLISH, null, disk))
        // And back: English, Settings' language, and the call follows Settings again.
        val cleared = TranscribeAgain.pin(ENGLISH, chosen = ENGLISH, previous = null, disk)
        assertNull(cleared)
        assertEquals(ENGLISH, Models.languageFor(cleared, null, ENGLISH, null, disk))
        assertEquals(CJK, Models.languageFor(cleared, null, CJK, null, disk))
    }

    @Test fun theEngineReloadsOnlyWhenACallNeedsOtherFiles() {
        var disk = disk(Models.Set.SPEECH, Models.Set.CJK)
        // A backlog: English, a call pinned to Japanese, English again, then two pinned calls in a row.
        val first = Models.engineFor(null, null, ENGLISH, null, loaded = null, disk)!!
        assertEquals(ENGLISH, first.language)
        assertEquals(true, first.reload)
        val pinned = Models.engineFor(CJK, null, ENGLISH, null, loaded = first.files, disk)!!
        assertEquals(CJK, pinned.language)
        assertEquals(true, pinned.reload)
        val back = Models.engineFor(null, null, ENGLISH, null, loaded = pinned.files, disk)!!
        assertEquals(ENGLISH, back.language)
        assertEquals(true, back.reload)
        val again = Models.engineFor(CJK, null, ENGLISH, null, loaded = back.files, disk)!!
        assertEquals(false, Models.engineFor(CJK, null, ENGLISH, null, loaded = again.files, disk)!!.reload)
        // The pinned language removed between calls: the next pinned call falls back to Settings', already loaded.
        disk = disk(Models.Set.SPEECH)
        val fallback = Models.engineFor(CJK, null, ENGLISH, null, loaded = first.files, disk)!!
        assertEquals(ENGLISH, fallback.language)
        assertEquals(false, fallback.reload)
        // Nothing downloaded: nothing to transcribe with.
        assertNull(Models.engineFor(CJK, null, HINDI, null, loaded = null, Models.Sizes { 0L }))
    }

    @Test fun withDetectionItStartsOnAutomaticUnlessTheCallIsPinned() {
        assertNull(TranscribeAgain.preselected(pinned = null, madeWith = EUROPEAN, chosen = ENGLISH, previous = null, usable, automatic = true))
        assertEquals(CJK, TranscribeAgain.preselected(pinned = CJK, madeWith = EUROPEAN, chosen = ENGLISH, previous = null, usable, automatic = true))
        // Pinned to a language removed since: as if it weren't pinned.
        assertNull(TranscribeAgain.preselected(pinned = HINDI, madeWith = EUROPEAN, chosen = ENGLISH, previous = null, usable, automatic = true))
    }

    @Test fun automaticClearsThePinAndWithDetectionEveryLanguagePinsIt() {
        val disk = disk(Models.Set.SPEECH, Models.Set.CJK, Models.Set.LANGUAGE_ID)
        assertNull(TranscribeAgain.pin(null, chosen = ENGLISH, previous = null, disk, automatic = true))
        assertEquals(CJK, TranscribeAgain.pin(CJK, chosen = ENGLISH, previous = null, disk, automatic = true))
        // Settings' language too: otherwise detection would put the call in the one it found instead.
        assertEquals(ENGLISH, TranscribeAgain.pin(ENGLISH, chosen = ENGLISH, previous = null, disk, automatic = true))
        val japanese = CallLanguage.Detection(listOf("ja", "ja", "ja"), 40f)
        assertEquals(ENGLISH, Models.languageFor(ENGLISH, japanese, ENGLISH, null, disk))
        assertEquals(CJK, Models.languageFor(TranscribeAgain.pin(null, ENGLISH, null, disk, automatic = true), japanese, ENGLISH, null, disk))
        // Without detection, Settings' language clears the pin as before.
        assertNull(TranscribeAgain.pin(ENGLISH, chosen = ENGLISH, previous = null, disk, automatic = false))
    }

    @Test fun withDetectionOnButItsModelNotInTheLanguageTheCallGetsAnywayClearsThePin() {
        // No Automatic to offer, but the call's stored detection still puts it in Japanese.
        val disk = disk(Models.Set.SPEECH, Models.Set.CJK)
        val japanese = CallLanguage.Detection(listOf("ja", "ja", "ja"), 40f)
        assertEquals(CJK, Models.languageFor(null, japanese, ENGLISH, null, disk))
        // Settings' English pins the call, or detection would put it back in Japanese.
        val english = TranscribeAgain.pin(ENGLISH, chosen = ENGLISH, previous = null, disk, detection = japanese)
        assertEquals(ENGLISH, english)
        assertEquals(ENGLISH, Models.languageFor(english, japanese, ENGLISH, null, disk))
        // Japanese is what it gets anyway: no pin, and it goes on following Settings and detection.
        assertNull(TranscribeAgain.pin(CJK, chosen = ENGLISH, previous = null, disk, detection = japanese))
        // With too little speech, detection leaves it to Settings, whose language clears the pin.
        assertNull(TranscribeAgain.pin(ENGLISH, chosen = ENGLISH, previous = null, disk, detection = CallLanguage.Detection.of("", 0f)))
    }

    private fun detection(codes: String, speechSeconds: Float = 40f) = CallLanguage.Detection(codes.split(","), speechSeconds)

    @Test fun automaticSaysWhatWasDetected() {
        assertEquals("Detects the language spoken", TranscribeAgain.automaticDetail(null, usable))
        assertEquals("Detected: Japanese", TranscribeAgain.automaticDetail(detection("en,ja,ja"), usable))
        assertEquals("Detected: English", TranscribeAgain.automaticDetail(detection("en,en", 15f), usable))
        assertEquals("Detected: German", TranscribeAgain.automaticDetail(detection("la,la,de"), usable))
        // Only stand-ins for European languages: no language to name.
        assertEquals("Detected: a European language", TranscribeAgain.automaticDetail(detection("sr,sr,la"), usable))
    }

    @Test fun automaticSaysWhyTheCallWouldFollowSettings() {
        assertEquals("Too little speech to tell", TranscribeAgain.automaticDetail(CallLanguage.Detection(emptyList(), 7.5f), usable))
        // Marked while it was detected, and the app stopped.
        assertEquals("Too little speech to tell", TranscribeAgain.automaticDetail(CallLanguage.Detection.of("", 0f), usable))
        // No language a model covers, or no clear answer.
        assertEquals("No downloaded language detected", TranscribeAgain.automaticDetail(detection("ar,ar,"), usable))
        assertEquals("No single language detected", TranscribeAgain.automaticDetail(detection("ja,de,hi"), usable))
        // One that isn't downloaded.
        assertEquals("Detected: Hindi, not downloaded", TranscribeAgain.automaticDetail(detection("hi,en,en"), usable))
        assertEquals("Detected: German, not downloaded", TranscribeAgain.automaticDetail(detection("de,de,de"), listOf(ENGLISH, CJK)))
    }
}
