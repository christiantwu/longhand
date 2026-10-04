package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.CallLanguage
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.SummaryLanguage
import io.github.christiantwu.longhand.engine.SummaryLanguage.English
import io.github.christiantwu.longhand.engine.SummaryLanguage.Named
import io.github.christiantwu.longhand.engine.SummaryLanguage.Unnamed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which language a call's summary is written in, from what detection heard and the transcript's model and script. */
class SummaryLanguageTest {

    private fun detected(codes: String, speechSeconds: Float = 40f) =
        CallLanguage.Detection(if (codes.isEmpty()) emptyList() else codes.split(","), speechSeconds)

    private fun of(model: Models.Language?, detection: CallLanguage.Detection? = null, texts: List<String> = emptyList()) =
        SummaryLanguage.forCall(true, model, detection, texts)

    private val english = listOf("Hi, it's about the roof quote.", "Sure, I'll send it on Friday.")
    private val german = listOf("Guten Tag, es geht um das Angebot für die Garage.", "Gut, bis Freitag.")
    private val chinese = listOf("喂，你好，我是物业的王丽。", "好的，我明天转账给你。")
    private val japanese = listOf("もしもし、佐藤です。来週の打ち合わせの件です。", "はい、木曜日の午後二時でお願いします。")
    private val korean = listOf("여보세요, 이사 견적 때문에 전화드렸어요.", "네, 다음 주 토요일 오전으로 가능할까요?")

    @Test fun theSentenceNamesTheLanguageInEnglish() {
        assertNull(English.instruction)
        assertEquals("Write the topic, summary and follow-ups in German.", Named("German").instruction)
        assertEquals("Write the topic, summary and follow-ups in the language the call is in, even if it isn't English.",
            Unnamed.instruction)
    }

    @Test fun turnedOffEveryCallIsInEnglish() {
        assertEquals(English, SummaryLanguage.forCall(false, Models.Language.EUROPEAN, detected("de,de,de"), german))
        assertEquals(English, SummaryLanguage.forCall(false, Models.Language.CJK, null, japanese))
        assertEquals(English, SummaryLanguage.forCall(false, Models.Language.HINDI, null, emptyList()))
    }

    @Test fun englishCallsAddNothing() {
        assertEquals(English, of(Models.Language.ENGLISH))
        // The English model writes English whatever was heard.
        assertEquals(English, of(Models.Language.ENGLISH, detected("de,de,de"), german))
        // English heard most, with every other model.
        assertEquals(English, of(Models.Language.EUROPEAN, detected("en,en,en"), german))
        assertEquals(English, of(Models.Language.EUROPEAN, detected("en,en,de"), german))
        assertEquals(English, of(Models.Language.CJK, detected("en,ja,en"), english))
        // Transcripts from before 0.10.0 don't say their model.
        assertEquals(English, of(null, detected("de,de,de"), german))
    }

    @Test fun theLanguageDetectedIsNamed() {
        assertEquals(Named("German"), of(Models.Language.EUROPEAN, detected("de,de,en")))
        assertEquals(Named("Swedish"), of(Models.Language.EUROPEAN, detected("sv,sv,sv")))
        assertEquals(Named("Polish"), of(Models.Language.EUROPEAN, detected("pl,en,pl")))
        // With no letters in the transcript to go by, Chinese, Japanese and Korean calls go by what was heard.
        assertEquals(Named("Japanese"), of(Models.Language.CJK, detected("ja,ja,ja")))
        assertEquals(Named("Korean"), of(Models.Language.CJK, detected("ko,en,ko")))
        assertEquals(Named("Chinese"), of(Models.Language.CJK, detected("zh,zh,zh")))
        // Cantonese wasn't measured: it's written as Chinese.
        assertEquals(Named("Chinese"), of(Models.Language.CJK, detected("yue,yue,zh")))
    }

    @Test fun standInsAreNeverNamed() {
        // Norwegian stands in for Swedish or Danish: it doesn't count, and alone it names nothing.
        assertEquals(Named("Swedish"), of(Models.Language.EUROPEAN, detected("no,no,sv")))
        assertEquals(English, of(Models.Language.EUROPEAN, detected("no,no,la"), english))
    }

    @Test fun tooLittleSpeechOrNoSingleLanguageGoesByTheTranscript() {
        assertEquals(Unnamed, of(Models.Language.EUROPEAN, detected("", 6f), german))
        assertEquals(Unnamed, of(Models.Language.EUROPEAN, detected("fr,sv"), german))
        assertEquals(English, of(Models.Language.EUROPEAN, detected("fr,sv"), english))
        assertEquals(Named("Korean"), of(Models.Language.CJK, detected("ko,ja"), korean))
    }

    @Test fun aLanguageTheModelDoesntWriteIsntNamed() {
        // Pinned to the European languages by hand, a call Whisper heard as Japanese: not named, not guessed.
        assertEquals(English, of(Models.Language.EUROPEAN, detected("ja,ja,ja"), german))
    }

    @Test fun senseVoiceTranscriptsGoByTheirScriptWhateverDetectionHeard() {
        // Whisper heard a Korean call as Japanese, or a Chinese call pinned by hand as German: the text decides.
        assertEquals(Named("Korean"), of(Models.Language.CJK, detected("ja,ja,ko"), korean))
        assertEquals(Named("Chinese"), of(Models.Language.CJK, detected("de,de,de"), chinese))
        assertEquals(Named("Japanese"), of(Models.Language.CJK, detected("en,en,en"), japanese))
    }

    @Test fun theHindiModelsCallsAreInHindi() {
        assertEquals(Named("Hindi"), of(Models.Language.HINDI))
        // Whisper often says Urdu for Hindi.
        assertEquals(Named("Hindi"), of(Models.Language.HINDI, detected("ur,ur,hi")))
        // Hindi mixed with English, even with English heard most.
        assertEquals(Named("Hindi"), of(Models.Language.HINDI, detected("en,hi,en")))
        assertEquals(Named("Hindi"), of(Models.Language.HINDI, detected("en,ur")))
        // Only a call heard as English throughout is summarized in English.
        assertEquals(English, of(Models.Language.HINDI, detected("en,en,en")))
    }

    @Test fun senseVoiceTranscriptsGoByScript() {
        assertEquals(Named("Korean"), of(Models.Language.CJK, texts = korean))
        assertEquals(Named("Japanese"), of(Models.Language.CJK, texts = japanese))
        assertEquals(Named("Chinese"), of(Models.Language.CJK, texts = chinese))
        // Mostly Latin letters is English; a few English words don't make a call English.
        assertEquals(English, of(Models.Language.CJK, texts = english + "好的。"))
        assertEquals(Named("Chinese"), of(Models.Language.CJK, texts = chinese + "OK."))
        // A stray line in another script doesn't name its language.
        assertEquals(Named("Chinese"), of(Models.Language.CJK, texts = chinese + chinese + "はい。"))
        assertEquals(Named("Japanese"), of(Models.Language.CJK, texts = japanese + "네."))
        // Digits and punctuation aren't letters: nothing to go by is English.
        assertEquals(English, of(Models.Language.CJK, texts = listOf("85，10。", "…")))
    }

    @Test fun europeanCallsOfUnknownLanguageNeedLettersBeyondAscii() {
        assertEquals(Unnamed, of(Models.Language.EUROPEAN, texts = german))
        assertEquals(Unnamed, of(Models.Language.EUROPEAN, texts = listOf("Алло, это Ольга из автосервиса.")))
        // Without them it could be English, which the unnamed wording would change: nothing is added.
        assertEquals(English, of(Models.Language.EUROPEAN, texts = english))
        assertEquals(English, of(Models.Language.EUROPEAN, texts = listOf("Ciao, sono Marco, ti chiamo per la consegna.")))
        // Digits and signs beyond ASCII aren't letters.
        assertEquals(English, of(Models.Language.EUROPEAN, texts = listOf("That's 40 € — fine.")))
        // An English call's foreign name isn't enough, and neither is a call detection heard English in.
        val call = List(20) { "Sure, I'll send the revised roof quote over by Friday afternoon." } + "Hi, it's José from the garage."
        assertEquals(English, of(Models.Language.EUROPEAN, texts = call))
        assertEquals(English, of(Models.Language.EUROPEAN, detected("en,fr"), german))
    }
}
