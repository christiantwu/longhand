package io.github.christiantwu.longhand.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.christiantwu.longhand.engine.Models
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val folderUri: String? = null,
    /** False: process each call right after it ends. True: wait for the charger. */
    val chargingOnly: Boolean = false,
    val setupDone: Boolean = false,
    /** Recordings last modified before this time are added as SKIPPED (0 = transcribe everything). */
    val skipBefore: Long = 0L,
    /** The calls-list reminder about the Phone permission was dismissed. */
    val phoneNoticeHidden: Boolean = false,
    /** The language calls are transcribed in, which decides the speech model kept on the phone. */
    val language: Models.Language = Models.Language.ENGLISH,
)

private val Context.dataStore by preferencesDataStore("settings")

class Settings(private val context: Context) {
    private object Keys {
        val folderUri = stringPreferencesKey("folder_uri")
        val chargingOnly = booleanPreferencesKey("charging_only")
        val setupDone = booleanPreferencesKey("setup_done")
        val skipBefore = longPreferencesKey("skip_before")
        val phoneNoticeHidden = booleanPreferencesKey("phone_notice_hidden")
        val language = stringPreferencesKey("language")
        /** 0.6.0, before the third language: true meant the 25 European languages. */
        val multilingual = booleanPreferencesKey("multilingual")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            folderUri = p[Keys.folderUri],
            chargingOnly = p[Keys.chargingOnly] ?: false,
            setupDone = p[Keys.setupDone] ?: false,
            skipBefore = p[Keys.skipBefore] ?: 0L,
            phoneNoticeHidden = p[Keys.phoneNoticeHidden] ?: false,
            language = Models.Language.entries.firstOrNull { it.name == p[Keys.language] }
                ?: if (p[Keys.multilingual] == true) Models.Language.EUROPEAN else Models.Language.ENGLISH,
        )
    }

    suspend fun current(): AppSettings = flow.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setFolderUri(uri: String) = set(Keys.folderUri, uri)
    suspend fun setChargingOnly(v: Boolean) = set(Keys.chargingOnly, v)
    suspend fun setSetupDone(v: Boolean) = set(Keys.setupDone, v)
    suspend fun setSkipBefore(v: Long) = set(Keys.skipBefore, v)
    suspend fun setPhoneNoticeHidden(v: Boolean) = set(Keys.phoneNoticeHidden, v)
    suspend fun setLanguage(v: Models.Language) = set(Keys.language, v.name)
}
