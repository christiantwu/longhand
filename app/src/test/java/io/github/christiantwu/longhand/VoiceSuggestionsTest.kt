package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.KnownVoice
import io.github.christiantwu.longhand.data.KnownVoiceRow
import io.github.christiantwu.longhand.export.SpeakerNames
import io.github.christiantwu.longhand.ui.KnownVoiceCentroid
import io.github.christiantwu.longhand.ui.VoiceSuggestion
import io.github.christiantwu.longhand.ui.voiceSuggestions
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which speakers of a call get a suggestion. Fictional voices and names only. */
class VoiceSuggestionsTest {
    private val dana = KnownVoiceCentroid(KnownVoiceRow(1, "Dana Whitfield", 3), floatArrayOf(1f, 0f, 0f))
    private val erin = KnownVoiceCentroid(KnownVoiceRow(2, "Erin Shaw", 1), floatArrayOf(0f, 1f, 0f))
    private val known = listOf(dana, erin)

    // A conference call: speaker 0 is the owner, 1 sounds like Dana, 2 like Erin, 3 like nobody known. 3 speaks longest
    // after the owner, so is shown with the caller's name.
    private val voices = mapOf(
        0 to floatArrayOf(0f, 0f, 1f),
        1 to floatArrayOf(0.8f, 0.1f, 0.59f),
        2 to floatArrayOf(0.1f, 0.9f, 0.42f),
        3 to floatArrayOf(0.3f, 0.3f, 0.9f),
    )
    private val names = SpeakerNames(owner = 0, callerName = "Morgan Avery",
        speech = mapOf(0 to 120_000L, 1 to 30_000L, 2 to 20_000L, 3 to 90_000L))

    @Test fun suggestsForSpeakersStillShownAsSpeakerN() {
        assertEquals(
            mapOf(1 to VoiceSuggestion(1, "Dana Whitfield", 3), 2 to VoiceSuggestion(2, "Erin Shaw", 1)),
            voiceSuggestions(names, voices, known, emptyMap()),
        )
    }

    @Test fun neverANameAlreadyGivenInThisCall() {
        // Speaker 3 was named "dana whitfield" by hand: speaker 1 isn't offered her too, and 3 has a name.
        val named = names.copy(manual = mapOf(3 to " dana whitfield "))
        assertEquals(mapOf(2 to VoiceSuggestion(2, "Erin Shaw", 1)), voiceSuggestions(named, voices, known, emptyMap()))
        // A speaker with a name of their own gets no suggestion.
        assertEquals(mapOf(1 to VoiceSuggestion(1, "Dana Whitfield", 3)),
            voiceSuggestions(names.copy(manual = mapOf(2 to "Erin")), voices, known, emptyMap()))
    }

    @Test fun neverOneTurnedDownForThatSpeaker() {
        assertEquals(mapOf(2 to VoiceSuggestion(2, "Erin Shaw", 1)), voiceSuggestions(names, voices, known, mapOf(1 to setOf(1L))))
        // Turned down for another speaker only.
        assertEquals(setOf(1, 2), voiceSuggestions(names, voices, known, mapOf(3 to setOf(1L))).keys)
    }

    @Test fun notForTheCallerNamedAutomatically() {
        val twoPeople = SpeakerNames(owner = 0, callerName = "Dana Whitfield", speech = mapOf(0 to 60_000L, 1 to 40_000L))
        assertEquals(emptyMap<Int, VoiceSuggestion>(), voiceSuggestions(twoPeople, voices, known, emptyMap()))
        // Shown as the caller, Morgan, though it sounds like Erin: no suggestion either.
        val morgan = SpeakerNames(owner = 0, callerName = "Morgan Avery", speech = mapOf(0 to 60_000L, 2 to 40_000L))
        assertEquals(emptyMap<Int, VoiceSuggestion>(), voiceSuggestions(morgan, voices, known, emptyMap()))
        // Without a known owner the caller's name isn't applied, so the voice is suggested.
        assertEquals(setOf(1), voiceSuggestions(twoPeople.copy(owner = null), voices, known, emptyMap()).keys)
        // With other voices on the call too, the caller's name still goes to whoever else speaks longest.
        val dana = names.copy(callerName = "Dana Whitfield", speech = names.speech + (1 to 100_000L))
        assertEquals(mapOf(2 to VoiceSuggestion(2, "Erin Shaw", 1)), voiceSuggestions(dana, voices, known, emptyMap()))
    }

    @Test fun notTheCallersNameShownForAnotherSpeaker() {
        // Speaker 3 is shown as Dana, the caller: speaker 1, who sounds like her, isn't offered her name too.
        val dana = names.copy(callerName = "Dana Whitfield")
        assertEquals(mapOf(2 to VoiceSuggestion(2, "Erin Shaw", 1)), voiceSuggestions(dana, voices, known, emptyMap()))
        // Once speaker 3 is named something else by hand, nobody is shown as Dana.
        assertEquals(setOf(1, 2), voiceSuggestions(dana.copy(manual = mapOf(3 to "Sam")), voices, known, emptyMap()).keys)
    }

    @Test fun eachKnownVoiceGoesToOneSpeakerOnly() {
        // Speaker 4 also sounds like Dana, a little less than speaker 1: one person isn't two speakers.
        val five = names.copy(speech = names.speech + (4 to 10_000L))
        val withFourth = voices + (4 to floatArrayOf(0.7f, 0.05f, 0.6f))
        assertEquals(setOf(1, 2), voiceSuggestions(five, withFourth, known, emptyMap()).keys)
        // Once speaker 1 turns Dana down, speaker 4 is the one most like her.
        assertEquals(VoiceSuggestion(1, "Dana Whitfield", 3), voiceSuggestions(five, withFourth, known, mapOf(1 to setOf(1L)))[4])
    }

    @Test fun namesMatchWhateverTheCaseInAnyScript() {
        assertEquals(KnownVoice.keyOf(" Émile Brun "), KnownVoice.keyOf("émile brun"))
        assertEquals(KnownVoice.keyOf("АННА"), KnownVoice.keyOf("анна"))
    }

    @Test fun nothingWithoutAStoredVoiceOrKnownVoices() {
        assertEquals(setOf(2), voiceSuggestions(names, voices - 1, known, emptyMap()).keys)
        assertEquals(emptyMap<Int, VoiceSuggestion>(), voiceSuggestions(names, voices, emptyList(), emptyMap()))
    }
}
