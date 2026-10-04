package io.github.christiantwu.longhand

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.christiantwu.longhand.data.AppSettings
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.engine.Models
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun recogniseVoicesIsOffUntilTurnedOn() {
        assertFalse(AppSettings().recogniseVoices)
        assertFalse(Settings.from(emptyPreferences()).recogniseVoices)
        assertTrue(Settings.from(preferencesOf(booleanPreferencesKey("recognise_voices") to true)).recogniseVoices)
    }

    @Test fun noLanguageWasUsedBeforeUntilOneIsLeft() {
        // Installs from before this was remembered have none: any usable language stands in.
        assertNull(Settings.from(emptyPreferences()).previousLanguage)
        assertNull(Settings.from(preferencesOf(stringPreferencesKey("previous_language") to "SWAHILI")).previousLanguage)
        assertEquals(Models.Language.EUROPEAN,
            Settings.from(preferencesOf(stringPreferencesKey("previous_language") to "EUROPEAN")).previousLanguage)
    }

    @Test fun theLongPressTipShowsUntilSeen() {
        assertFalse(Settings.from(emptyPreferences()).editTipSeen)
        assertTrue(Settings.from(preferencesOf(booleanPreferencesKey("edit_tip_seen") to true)).editTipSeen)
    }

    @Test fun eachCallsLanguageIsDetectedUntilTurnedOff() {
        assertTrue(AppSettings().detectLanguage)
        assertTrue(Settings.from(emptyPreferences()).detectLanguage)
        assertFalse(Settings.from(preferencesOf(booleanPreferencesKey("detect_language") to false)).detectLanguage)
    }
}
