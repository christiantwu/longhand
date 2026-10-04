package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.Models.Language.CJK
import io.github.christiantwu.longhand.engine.Models.Language.ENGLISH
import io.github.christiantwu.longhand.engine.Models.Language.EUROPEAN
import io.github.christiantwu.longhand.engine.Models.Language.HINDI
import io.github.christiantwu.longhand.ui.TranscribeAgain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Transcribe again" in another language: which one the choice starts on, and what choosing one does to the call. */
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
        assertEquals(HINDI, Models.languageFor(null, HINDI, EUROPEAN, disk(Models.Set.SPEECH, Models.Set.MULTILINGUAL, Models.Set.HINDI)))
    }

    @Test fun theCallIsTranscribedInTheLanguagePickedForIt() {
        val disk = disk(Models.Set.SPEECH, Models.Set.CJK)
        // A Japanese call, with English chosen in Settings.
        val pinned = TranscribeAgain.pin(CJK, chosen = ENGLISH, previous = null, disk)
        assertEquals(CJK, Models.languageFor(pinned, ENGLISH, null, disk))
        // And back: English, Settings' language, and the call follows Settings again.
        val cleared = TranscribeAgain.pin(ENGLISH, chosen = ENGLISH, previous = null, disk)
        assertNull(cleared)
        assertEquals(ENGLISH, Models.languageFor(cleared, ENGLISH, null, disk))
        assertEquals(CJK, Models.languageFor(cleared, CJK, null, disk))
    }

    @Test fun theEngineReloadsOnlyWhenACallNeedsOtherFiles() {
        var disk = disk(Models.Set.SPEECH, Models.Set.CJK)
        // A backlog: English, a call pinned to Japanese, English again, then two pinned calls in a row.
        val first = Models.engineFor(null, ENGLISH, null, loaded = null, disk)!!
        assertEquals(ENGLISH, first.language)
        assertEquals(true, first.reload)
        val pinned = Models.engineFor(CJK, ENGLISH, null, loaded = first.files, disk)!!
        assertEquals(CJK, pinned.language)
        assertEquals(true, pinned.reload)
        val back = Models.engineFor(null, ENGLISH, null, loaded = pinned.files, disk)!!
        assertEquals(ENGLISH, back.language)
        assertEquals(true, back.reload)
        val again = Models.engineFor(CJK, ENGLISH, null, loaded = back.files, disk)!!
        assertEquals(false, Models.engineFor(CJK, ENGLISH, null, loaded = again.files, disk)!!.reload)
        // The pinned language removed between calls: the next pinned call falls back to Settings', already loaded.
        disk = disk(Models.Set.SPEECH)
        val fallback = Models.engineFor(CJK, ENGLISH, null, loaded = first.files, disk)!!
        assertEquals(ENGLISH, fallback.language)
        assertEquals(false, fallback.reload)
        // Nothing downloaded: nothing to transcribe with.
        assertNull(Models.engineFor(CJK, HINDI, null, loaded = null, Models.Sizes { 0L }))
    }
}
