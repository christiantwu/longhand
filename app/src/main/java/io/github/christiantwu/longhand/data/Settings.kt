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
    /** The language calls are transcribed in, which decides the speech model used. */
    val language: Models.Language = Models.Language.ENGLISH,
    /** The language used before [language] was chosen, if it was usable: it transcribes until [language] has downloaded. */
    val previousLanguage: Models.Language? = null,
    /** A language was ever chosen (or preselected at setup); until then [language] is only the default. */
    val languageChosen: Boolean = false,
    /**
     * With two or more languages downloaded, transcribe each call in the one it's in (Models.languageFor); calls with
     * too little speech to tell, and any before the detection model is downloaded, follow [language].
     */
    val detectLanguage: Boolean = true,
    /** Suggest the names of people the user has named when their voice is heard in another call. */
    val recogniseVoices: Boolean = false,
    /** The transcript's tip about long-pressing a line was dismissed, or a line's menu was opened. */
    val editTipSeen: Boolean = false,
    /**
     * Write each new summary in the language of the call (engine.SummaryLanguage); off, every summary is in English.
     * Summaries already written stay as they are.
     */
    val summariesInCallLanguage: Boolean = true,
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
        val previousLanguage = stringPreferencesKey("previous_language")
        /** 0.6.0, before the third language: true meant the 25 European languages. */
        val multilingual = booleanPreferencesKey("multilingual")
        val detectLanguage = booleanPreferencesKey("detect_language")
        val recogniseVoices = booleanPreferencesKey("recognise_voices")
        val editTipSeen = booleanPreferencesKey("edit_tip_seen")
        val summariesInCallLanguage = booleanPreferencesKey("summaries_in_call_language")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map(::from)

    suspend fun current(): AppSettings = flow.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setFolderUri(uri: String) = set(Keys.folderUri, uri)
    suspend fun setChargingOnly(v: Boolean) = set(Keys.chargingOnly, v)
    suspend fun setSetupDone(v: Boolean) = set(Keys.setupDone, v)
    suspend fun setSkipBefore(v: Long) = set(Keys.skipBefore, v)
    suspend fun setPhoneNoticeHidden(v: Boolean) = set(Keys.phoneNoticeHidden, v)
    /** Chooses [v], remembering [previous], when given, to transcribe with until [v] has downloaded. */
    suspend fun setLanguage(v: Models.Language, previous: Models.Language? = null) {
        context.dataStore.edit {
            it[Keys.language] = v.name
            if (previous != null) it[Keys.previousLanguage] = previous.name
        }
    }
    suspend fun setDetectLanguage(v: Boolean) = set(Keys.detectLanguage, v)
    suspend fun setRecogniseVoices(v: Boolean) = set(Keys.recogniseVoices, v)
    suspend fun setEditTipSeen(v: Boolean) = set(Keys.editTipSeen, v)
    suspend fun setSummariesInCallLanguage(v: Boolean) = set(Keys.summariesInCallLanguage, v)

    companion object {
        fun from(p: Preferences) = AppSettings(
            folderUri = p[Keys.folderUri],
            chargingOnly = p[Keys.chargingOnly] ?: false,
            setupDone = p[Keys.setupDone] ?: false,
            skipBefore = p[Keys.skipBefore] ?: 0L,
            phoneNoticeHidden = p[Keys.phoneNoticeHidden] ?: false,
            language = Models.Language.entries.firstOrNull { it.name == p[Keys.language] }
                ?: if (p[Keys.multilingual] == true) Models.Language.EUROPEAN else Models.Language.ENGLISH,
            languageChosen = p[Keys.language] != null || p[Keys.multilingual] != null,
            previousLanguage = Models.Language.entries.firstOrNull { it.name == p[Keys.previousLanguage] },
            detectLanguage = p[Keys.detectLanguage] ?: true,
            recogniseVoices = p[Keys.recogniseVoices] ?: false,
            editTipSeen = p[Keys.editTipSeen] ?: false,
            summariesInCallLanguage = p[Keys.summariesInCallLanguage] ?: true,
        )
    }
}
