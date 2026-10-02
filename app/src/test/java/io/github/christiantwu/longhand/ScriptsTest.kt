package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.Scripts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which pieces of a Hindi call get a second try as Hindi. */
class ScriptsTest {

    @Test fun hindiAndEnglishAreKeptAsTheyAre() {
        assertFalse(Scripts.hasOtherScript("मैं किचन के काम के बारे में कॉल कर रहा था।"))
        assertFalse(Scripts.hasOtherScript("In the UT the region is bounded by the Sahel"))
        // Mixed, with digits, the rupee sign, a danda, Devanagari digits and accented Latin letters.
        assertFalse(Scripts.hasOtherScript("Vatican सिटी की जनसंख्या 800 है। ₹2,000 और १५ café"))
        assertFalse(Scripts.hasOtherScript("Mozas"))
        assertFalse(Scripts.hasOtherScript(""))
        assertFalse(Scripts.hasOtherScript("... ?"))
    }

    @Test fun anyOtherScriptGetsASecondTry() {
        assertTrue(Scripts.hasOtherScript("Сачик"))
        assertTrue(Scripts.hasOtherScript("हाँ ठीक है 谢谢"))
        assertTrue(Scripts.hasOtherScript("نعم"))
        assertTrue(Scripts.hasOtherScript("OK γεια"))
        // Bengali is close to Devanagari, but a different script.
        assertTrue(Scripts.hasOtherScript("হ্যাঁ"))
    }

    @Test fun devanagariCharacters() {
        assertTrue("कि".all(Scripts::isDevanagari)) // vowel signs included
        assertFalse(Scripts.isDevanagari('k'))
        assertFalse(Scripts.isDevanagari(' '))
    }
}
