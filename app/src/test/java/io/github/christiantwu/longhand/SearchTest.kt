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
