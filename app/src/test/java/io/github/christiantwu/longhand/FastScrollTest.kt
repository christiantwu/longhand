package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.ui.FastScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the calls list's handle sits, and where dragging it takes the list. */
class FastScrollTest {

    @Test fun onlyLongListsGetAHandle() {
        assertFalse(FastScroll.wanted(visible = 0, total = 0))
        assertFalse(FastScroll.wanted(visible = 0, total = 500)) // nothing measured yet
        assertFalse(FastScroll.wanted(visible = 9, total = 9))
        assertFalse(FastScroll.wanted(visible = 9, total = 35))
        assertTrue(FastScroll.wanted(visible = 9, total = 36))
        assertTrue(FastScroll.wanted(visible = 9, total = 1_400))
    }

    @Test fun theHandleGoesFromTheTopToTheEnd() {
        assertEquals(0f, FastScroll.fraction(first = 0, scrolled = 0f, visible = 10, total = 1_010), 0f)
        assertEquals(0.5f, FastScroll.fraction(first = 500, scrolled = 0f, visible = 10, total = 1_010), 1e-6f)
        assertEquals(1f, FastScroll.fraction(first = 1_000, scrolled = 0f, visible = 10, total = 1_010), 0f)
        // Partway through a row counts, so the handle moves smoothly instead of a row at a time.
        assertEquals(0.0005f, FastScroll.fraction(first = 0, scrolled = 0.5f, visible = 10, total = 1_010), 1e-6f)
        // The last screen can show more rows than the one before: never past the end.
        assertEquals(1f, FastScroll.fraction(first = 1_005, scrolled = 0.9f, visible = 10, total = 1_010), 0f)
    }

    @Test fun aListThatFitsHasNowhereToGo() {
        assertEquals(0f, FastScroll.fraction(first = 0, scrolled = 0f, visible = 8, total = 8), 0f)
        assertEquals(0f, FastScroll.fraction(first = 0, scrolled = 0f, visible = 8, total = 3), 0f)
        assertEquals(0f, FastScroll.fraction(first = 0, scrolled = 0f, visible = 0, total = 0), 0f)
        assertEquals(0, FastScroll.index(0.7f, visible = 0, total = 0))
        assertEquals(0, FastScroll.index(0.5f, visible = 8, total = 3))
    }

    @Test fun draggingGoesToTheRowUnderTheHandle() {
        assertEquals(0, FastScroll.index(0f, visible = 10, total = 1_010))
        assertEquals(500, FastScroll.index(0.5f, visible = 10, total = 1_010))
        assertEquals(250, FastScroll.index(0.25f, visible = 10, total = 1_010))
        // At the bottom, the last row: the list settles at its end whatever the rows' heights.
        assertEquals(1_009, FastScroll.index(1f, visible = 10, total = 1_010))
        // A finger past either end stays in the list.
        assertEquals(0, FastScroll.index(-0.2f, visible = 10, total = 1_010))
        assertEquals(1_009, FastScroll.index(1.3f, visible = 10, total = 1_010))
    }

    @Test fun whereTheHandleIsIsWhereDraggingItStarts() {
        for (first in listOf(0, 1, 37, 499, 730, 999)) {
            val at = FastScroll.fraction(first, scrolled = 0f, visible = 10, total = 1_010)
            assertEquals(first, FastScroll.index(at, visible = 10, total = 1_010))
        }
    }
}
