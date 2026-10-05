package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.data.corrected
import io.github.christiantwu.longhand.engine.Corrections
import io.github.christiantwu.longhand.engine.Corrections.Rule
import io.github.christiantwu.longhand.engine.Recognized
import io.github.christiantwu.longhand.engine.SpokenNumbers
import io.github.christiantwu.longhand.engine.TranscriptEdits
import io.github.christiantwu.longhand.engine.WordTime
import io.github.christiantwu.longhand.engine.WordTimings
import io.github.christiantwu.longhand.engine.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SpokenNumbersTest {

    private fun check(cases: List<Pair<String, String>>) {
        val wrong = cases.mapNotNull { (heard, written) ->
            SpokenNumbers.write(heard).takeIf { it != written }?.let { "$heard -> $it (not $written)" }
        }
        assertEquals("", wrong.joinToString("\n"))
    }

    @Test fun writesNumbersAsDigits() = check(conversions)

    @Test fun leavesWordsThatAreNotNumbersAlone() = check(kept.map { it to it })

    /** Numbers from 10 up are digits even in set phrases; these are the known ones. */
    @Test fun setPhrasesWithNumbersFromTenUp() = check(setPhrases)

    @Test fun piecesPointAtTheWordsTheyCameFrom() {
        val r = SpokenNumbers.convert("It's one hundred and eight dollars, thanks.")
        assertEquals("It's \$108, thanks.", r.text)
        assertEquals(listOf("It's" to (0 to 0), "\$108," to (1 to 5), "thanks." to (6 to 6)), r.pieces.map { it.text to (it.from to it.to) })
    }

    /** Word timings follow the pieces, so every word must be in exactly one, in order. */
    @Test fun piecesTakeEveryWordOnceInOrder() {
        for (heard in conversions.map { it.first } + kept + setPhrases.map { it.first }) {
            val covered = SpokenNumbers.convert(heard).pieces.flatMap { (it.from..it.to).toList() }
            assertEquals(heard, Words.of(heard).indices.toList(), covered)
        }
    }

    @Test fun aLineInDigitsKeepsWhenEachWordWasSaid() {
        // Tokens as Parakeet writes them (a word starts with a space), with their starts and durations in seconds.
        val text = "It's one hundred and eight dollars, thanks."
        val tokens = arrayOf(" It", "'s", " one", " hundred", " and", " eight", " dollars", ",", " thanks", ".")
        val starts = floatArrayOf(0f, 0.16f, 0.4f, 0.72f, 1.12f, 1.28f, 1.6f, 2f, 2.4f, 2.72f)
        val durations = floatArrayOf(0.16f, 0.16f, 0.24f, 0.32f, 0.16f, 0.24f, 0.4f, 0.08f, 0.32f, 0.08f)
        val heard = WordTimings.fromTokens(text, tokens, starts, durations, lead = 0f, length = 3f)
        assertEquals(
            listOf(WordTime(0, 320), WordTime(400, 640), WordTime(720, 1040), WordTime(1120, 1280), WordTime(1280, 1520),
                WordTime(1600, 2000), WordTime(2400, 2720)),
            heard,
        )
        val line = SpokenNumbers.write(Recognized(text, heard))
        assertEquals("It's \$108, thanks.", line.text)
        // "$108," was said from the start of "one" to the end of "dollars".
        assertEquals(listOf(WordTime(0, 320), WordTime(400, 2000), WordTime(2400, 2720)), line.words)

        // Stored as the worker stores it, common corrections go on top, and the timings stay with what was recognised.
        val saved = Segment(recordingId = 1, startMs = 10_000, endMs = 13_000, speaker = 0, text = line.text,
            words = line.words?.let(WordTimings::encode)).corrected(Corrections(listOf(Rule("thanks", "thank you"))))
        assertEquals("It's \$108, thank you.", saved.text)
        assertEquals("It's \$108, thanks.", saved.recognized)
        assertEquals(listOf(WordTime(0, 320), WordTime(400, 2000), null, null), WordTimings.of(saved))
        // Splitting the line before "$108," cuts between "It's" and "one".
        assertEquals(10_360L, TranscriptEdits.cutTime(saved, 1))
    }

    @Test fun wordsWrittenFromTheSameWordsShareTheirTime() {
        val times = (0L until 5L).map { WordTime(it * 400, it * 400 + 300) }
        val line = SpokenNumbers.write(Recognized("two point five million dollars", times))
        assertEquals("\$2.5 million", line.text)
        assertEquals(listOf(WordTime(0, 1900), WordTime(0, 1900)), line.words)
    }

    @Test fun timingsThatDontFitTheWordsAreDropped() {
        assertNull(SpokenNumbers.write(Recognized("twenty dollars", listOf(WordTime(0, 300)))).words)
        assertNull(SpokenNumbers.write(Recognized("twenty dollars", null)).words)
        val unchanged = Recognized("I need two", listOf(WordTime(0, 100), WordTime(100, 200), WordTime(200, 300)))
        assertSame(unchanged, SpokenNumbers.write(unchanged))
    }

    private val conversions = listOf(
        "Okay, your savings account balance is one hundred and sixty three dollars. Is there anything else I can help you with?" to "Okay, your savings account balance is \$163. Is there anything else I can help you with?",
        "nine seven eight eight two." to "97882.",
        "Five, five, eight." to "558.",
        "Five five eight." to "558.",
        "Eight, five, six." to "856.",
        "The amount of the bill is a hundred and eight dollars." to "The amount of the bill is \$108.",
        "one thousand three hundred and seventy eight dollars. Ethan, is that all?" to "\$1,378. Ethan, is that all?",
        "my phone number is three four one zero five two zero eight six four" to "my phone number is 341-052-0864",
        "It's two oh two, five five five, zero one four three." to "It's 202-555-0143.",
        "The branch hours are from nine thirty AM to five p.m." to "The branch hours are from 9:30 AM to 5 p.m.",
        "ten fifteen a m" to "10:15 a m",
        "for Friday at ten fifteen AM." to "for Friday at 10:15 AM.",
        "scheduled for Thursday at three forty five" to "scheduled for Thursday at 3:45",
        "Wednesday at ten A.M. Is there anything" to "Wednesday at 10 A.M. Is there anything",
        "um the company address is one eighty two main street" to "um the company address is 182 main street",
        "The address is seven eight zero Main Street, Forest Ranch, California." to "The address is 780 Main Street, Forest Ranch, California.",
        "One six one First Street." to "161 First Street.",
        "in nineteen ninety nine" to "in 1999",
        "in twenty twenty four" to "in 2024",
        "fiscal two thousand five and specified that the FBI must devote ten agents" to "fiscal 2005 and specified that the FBI must devote 10 agents",
        "of thirty-four additional overheating incidents" to "of 34 additional overheating incidents",
        "the 11th, twelfth, and 13th centuries" to "the 11th, 12th, and 13th centuries",
        "the twenty first century" to "the 21st century",
        "on March third" to "on March 3rd",
        "the third of March" to "the 3rd of March",
        "It's about twenty five percent." to "It's about 25%.",
        "two point five million dollars" to "\$2.5 million",
        "two million dollars" to "\$2 million",
        "three hundred thousand people" to "300,000 people",
        "it's gonna be forty grand" to "it's gonna be 40 grand",
        "five dollars and fifty cents" to "\$5.50",
        "twenty dollars" to "\$20",
        "one dollar" to "\$1",
        "fifty cents" to "50 cents",
        "a forty-year-old man" to "a 40-year-old man",
        "working twenty four seven" to "working 24/7",
        "call nine one one" to "call 911",
        "ninety nine point nine percent" to "99.9%",
        "between ten to eleven p.m." to "between 10 to 11 p.m.",
        "a hundred percent" to "100%",
        "one hundred percent" to "100%",
        "ten to fifteen minutes" to "10 to 15 minutes",
        "twelve" to "12",
        "eleven people" to "11 people",
        "at one thirty" to "at 1:30",
        "at nine p.m." to "at 9 p.m.",
        "five o'clock" to "5 o'clock",
        "two thousand" to "2000",
        "two thousand and five" to "2005",
        "fifteen hundred dollars" to "\$1,500",
        "twenty five hundred" to "2500",
        "it was the seven hundred and eighteenth night" to "it was the 718th night",
        "the one hundred and first" to "the 101st",
        "june third eighteen seventy one" to "june 3rd 1871",
        "fourteen ninety nine" to "1499",
        "ten per cent a week" to "10% a week",
        "The branch hours are 9.30 a.m. to 5 p.m." to "The branch hours are 9:30 a.m. to 5 p.m.",
        "845 AM." to "8:45 AM.",
        "last digit is three six ninety five" to "last digit is 3695",
        "zero nine three ninety nine" to "09399",
        "okay that's four three five one nine seven one six sixty six" to "okay that's 435-197-1666",
        "zero zero five twenty nine" to "00529",
        "no it's seven four oh" to "no it's 740",
        "within seven to ten business days" to "within 7 to 10 business days",
        "the branch hours are from nine to five p m" to "the branch hours are from 9 to 5 p m",
        "nine or ten people" to "9 or 10 people",
        "Your savings account balance is three hundred twenty three dollars seventy two cents." to "Your savings account balance is \$323.72.",
        "The branch hours are nine: thirty A.M. to five p.m." to "The branch hours are 9:30 A.M. to 5 p.m.",
        "Note: thirty people came." to "Note: 30 people came.",
        "So then it's gonna be eight hundredth." to "So then it's gonna be 800th.",
        "the hundredth time" to "the 100th time",
        "it's one two three Main Street" to "it's 123 Main Street",
    )

    private val kept = listOf(
        "a five-year-old",
        "Seven is zero.",
        "one of them said a couple of things",
        "Hold on for one moment.",
        "Can you send me a new one?",
        "they're paying it one way or the other",
        "I want to be the one to pull them",
        "no one knows, someone does",
        "At one point one of them left.",
        "the first time, at first, first of all",
        "just a second, on second thought",
        "a third party",
        "two or three times",
        "two three times",
        "one or two",
        "I need two",
        "In just two weeks",
        "one-on-one meeting",
        "a thousand times",
        "a million things",
        "one in a million",
        "hundreds of people and thousands of years",
        "nine to five job",
        "number one priority",
        "in his twenties",
        "the nineties",
        "a couple hundred dollars",
        "ten-four, over",
        "one to three related adult males",
        "a half, one and a half",
        "it was one thirty",
        "at five",
        "a fifty fifty proposition",
        "at approximately 12.00 GMT",
        "in 2005 a.m. radio",
        "o five eight",
        "Oh, five eight.",
        "Oh one more thing.",
        "That's just my two cents.",
        "It's a nine to five job.",
        "one twenty",
        "it's five oh.",
        "name each one, whether that's ABCD or one two three four five.",
        "One of them said a couple of things.",
        "Give me one second, I'll pull it up.",
        "Hang on one sec.",
        "They're paying it one way or the other.",
        "I want to be the one to pull them.",
        "No one knows, but someone does.",
        "The first time, at first, first of all.",
        "Just a second, on second thought.",
        "It's a third party thing.",
        "Two or three times a week.",
        "We tried two three times.",
        "One or two of those.",
        "I need two.",
        "In just two weeks.",
        "We had a one-on-one meeting.",
        "A thousand times, I told you.",
        "There were a million things going on.",
        "It's one in a million.",
        "Hundreds of people and thousands of years.",
        "That's our number one priority.",
        "He's in his twenties.",
        "Back in the nineties.",
        "It cost a couple hundred dollars.",
        "One to three related adult males.",
        "It was one thirty.",
        "We'll meet at five.",
        "A half, one and a half.",
        "First things first.",
        "For one thing, it's late.",
        "One by one, one at a time.",
        "Back to square one.",
        "I'm on cloud nine.",
        "It's a fifty-fifty chance.",
        "The million dollar question.",
        "It's a dime a dozen.",
        "Take five, guys.",
        "Give me a high five.",
        "Third time's the charm.",
        "The second one is better.",
        "Every once in a while.",
        "One day we'll see.",
        "It's the one with the red door.",
        "That's a two-way street.",
        "I'll be there in a minute or two.",
        "They're like two peas in a pod.",
        "A one-time fee.",
        "Both of the two options.",
        "The last one, not the first one.",
    )

    private val setPhrases = listOf(
        "Ten out of ten." to "10 out of 10.",
        "At the eleventh hour." to "At the 11th hour.",
    )
}
