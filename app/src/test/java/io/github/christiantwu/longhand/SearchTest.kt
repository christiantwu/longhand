package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.SearchPattern
import io.github.christiantwu.longhand.export.SearchMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {

    @Test fun occurrencesIgnoreCaseAndDontOverlap() {
        assertEquals(listOf(0..3, 6..9, 12..15), SearchMatch.occurrences("Roof, roof, ROOF", "roof"))
        assertEquals(listOf(0..1), SearchMatch.occurrences("aaa", "aa"))
        assertEquals(listOf(4..8), SearchMatch.occurrences("New gutter", "  GUTTE "))
    }

    @Test fun nothingIsFoundForBlankOrAbsentText() {
        assertEquals(emptyList<IntRange>(), SearchMatch.occurrences("The roof quote", " "))
        assertEquals(emptyList<IntRange>(), SearchMatch.occurrences("The roof quote", "chimney"))
    }

    @Test fun aMatchNearTheStartShowsTheWholeLine() {
        val line = "Hi, it's about the roof quote."
        assertEquals(SearchMatch.Excerpt(line, listOf(19..28)), SearchMatch.excerpt(line, "ROOF QUOTE"))
    }

    @Test fun aLaterMatchIsCutToAWordShortlyBefore() {
        val line = "Thanks for calling back. We went over the dates and the budget last week, " +
            "so today I wanted to settle the cabin booking for the trip. The cabin needs a deposit too."
        val e = SearchMatch.excerpt(line, "cabin")
        // About 24 columns of lead-in, so the match shows within two lines on a narrow phone.
        assertEquals("…I wanted to settle the cabin booking for the trip. The cabin needs a deposit too.", e.text)
        // Every occurrence in the excerpt is marked, the first one included.
        assertEquals(listOf(24..28, 56..60), e.matches)
        assertEquals("cabin", e.text.substring(e.matches[0]))
    }

    @Test fun textWithoutSpacesIsCutAtTheContext() {
        // Wide characters count two columns: 10 columns of lead-in is five characters.
        val line = "我们上周已经讨论过日期和预算的问题了所以今天我想把小屋的预订定下来"
        assertEquals(SearchMatch.Excerpt("…今天我想把小屋的预订定下来", listOf(6..7)), SearchMatch.excerpt(line, "小屋", context = 10))
    }

    @Test fun theCutNeverSplitsACharacter() {
        assertEquals(SearchMatch.Excerpt("…\uD83D\uDE00roof", listOf(3..6)), SearchMatch.excerpt("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00roof", "roof", context = 3))
    }

    @Test fun theCutNeverSplitsASyllable() {
        // Three columns back lands between "क" and its vowel sign "ि", so the cut moves on to the next "कि".
        assertEquals(SearchMatch.Excerpt("…किx", listOf(3..3)), SearchMatch.excerpt("किकिकिकिकिx", "x", context = 3))
    }

    @Test fun aMatchTakesInTheRestOfASyllable() {
        // "कि" is "क" and the vowel sign "ि". Search text ending or starting inside it marks all of it, so the
        // sign is never drawn apart from its consonant.
        val line = "आप किताब लाए"
        assertEquals(listOf(3..4), SearchMatch.occurrences(line, "क"))
        assertEquals("किता", line.substring(SearchMatch.occurrences(line, "कित").single()))
        assertEquals("किताब", line.substring(SearchMatch.occurrences(line, "िताब").single()))
        assertEquals(listOf(5..6), SearchMatch.occurrences(line, "त"))
        // A conjunct and its vowel sign stay whole too, in an excerpt as well.
        val long = "हाँ रमेश जी, मैंने किचन के काम का वो पूरा एस्टीमेट देख लिया है"
        val e = SearchMatch.excerpt(long, "एस्ट")
        assertEquals("एस्टी", e.text.substring(e.matches.single()))
        assertEquals("…किचन के काम का वो पूरा एस्टीमेट देख लिया है", e.text)
    }

    @Test fun aMatchTakesInAnAccentAfterIt() {
        // "é" written as "e" and a combining acute accent.
        val line = "Café au lait at the cafe"
        assertEquals(listOf(0..4, 21..24), SearchMatch.occurrences(line, "cafe"))
        assertEquals(listOf(3..4, 19..19, 24..24), SearchMatch.occurrences(line, "E"))
        assertEquals(listOf(0..4), SearchMatch.occurrences(line, "café"))
    }

    @Test fun aLineWithoutTheTextIsShownWhole() {
        assertEquals(SearchMatch.Excerpt("See you Thursday.", emptyList()), SearchMatch.excerpt("See you Thursday.", "roof"))
    }

    @Test fun likeWildcardsInSearchTextAreLiteral() {
        assertEquals("%roof quote%", SearchPattern.contains("roof quote"))
        assertEquals("""%50\%%""", SearchPattern.contains("50%"))
        assertEquals("""%unit\_2%""", SearchPattern.contains("unit_2"))
        assertEquals("""%C:\\quotes%""", SearchPattern.contains("""C:\quotes"""))
        // The escape character first, so the escapes added for the wildcards stay escapes.
        assertEquals("""%\\\%\\\_%""", SearchPattern.contains("""\%\_"""))
    }
}
