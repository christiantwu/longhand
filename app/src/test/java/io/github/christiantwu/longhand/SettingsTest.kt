package io.github.christiantwu.longhand

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import io.github.christiantwu.longhand.data.AppSettings
import io.github.christiantwu.longhand.data.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun recogniseVoicesIsOffUntilTurnedOn() {
        assertFalse(AppSettings().recogniseVoices)
        assertFalse(Settings.from(emptyPreferences()).recogniseVoices)
        assertTrue(Settings.from(preferencesOf(booleanPreferencesKey("recognise_voices") to true)).recogniseVoices)
    }
}
