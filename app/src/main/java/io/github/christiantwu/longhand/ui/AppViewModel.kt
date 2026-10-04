package io.github.christiantwu.longhand.ui

import io.github.christiantwu.longhand.LonghandApp
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.AppSettings
import io.github.christiantwu.longhand.data.CallRow
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.Correction
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.data.KnownVoiceRow
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.SearchPattern
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.VoiceProfile
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.work.CallState
import io.github.christiantwu.longhand.work.CommonCorrections
import io.github.christiantwu.longhand.work.ModelDownloadWorker
import io.github.christiantwu.longhand.work.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ModelState(
    /** Usable, perhaps on an earlier file while an improved one downloads ([update]). */
    val installed: Boolean,
    /** Queued or running. */
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    /** Why a queued download isn't running yet, e.g. no Wi-Fi; null while it runs. */
    val waiting: String? = null,
    /** A network the user can choose to download over instead, while the download waits. */
    val offer: Work.DownloadNetwork? = null,
    /** Installed, with an improved file still to download; the download fields are about that file. */
    val update: Boolean = false,
) {
    val downloadText: String get() = waiting ?: "Downloading… ${(progress * 100).toInt()}%"
    /** The progress bar, shown only while bytes are actually arriving. */
    val runningProgress: Float? get() = if (downloading && waiting == null) progress else null
    /** A line under an installed set's usual text while it's updated; null when there's no update. */
    val updateText: String? get() = when {
        !update -> null
        error != null -> "Update failed: $error"
        !downloading -> "An improved version is ready to download."
        waiting != null -> "Updating to an improved version. $waiting"
        else -> "Updating to an improved version… ${(progress * 100).toInt()}%"
    }
}

/** Device state the setup and settings screens show; refreshed whenever the app resumes. */
data class DeviceState(
    /** False until the first refresh, so nothing is reported missing before it's been checked. */
    val loaded: Boolean = false,
    val folderAccessible: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val callLog: Boolean = false,
    val contacts: Boolean = false,
    /** The Phone permission, used only to notice when a call ends. */
    val phoneState: Boolean = false,
    val notifications: Boolean = false,
    /** On battery, automatic runs take only the last day's calls; older ones wait for this. */
    val charging: Boolean = false,
    /** How many calls the owner's voiceprint was learned from (0 = not set up). */
    val voiceSamples: Int = 0,
    /** Transcripts from before 0.5.0 still to be redone on the charger (their voices aren't learned). */
    val redoWaiting: Int = 0,
)

/**
 * "Calls with …": calls with the contact called [name] or with [number], and calls where a speaker
 * was given that name.
 */
data class PersonFilter(val name: String, val number: String?) {
    companion object {
        /** The call's contact, or its number when it has no name; null when it has neither. */
        fun of(rec: Recording): PersonFilter? {
            val name = CallText.caller(rec, CallerLookup::formatNumber)?.trim() ?: return null
            return PersonFilter(name, rec.phoneNumber?.takeIf { it.isNotBlank() })
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = Settings(app)
    private val dao = AppDatabase.get(app).recordings()
    private val voiceDao = AppDatabase.get(app).voices()
    private val correctionDao = AppDatabase.get(app).corrections()
    private val workManager = WorkManager.getInstance(app)

    val settings: StateFlow<AppSettings?> = settingsStore.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val query = MutableStateFlow("")

    /** "Calls with …": the list shows only calls with this person. */
    val person = MutableStateFlow<PersonFilter?>(null)

    val rows: StateFlow<List<CallRow>> = combine(query, person) { q, p -> q.trim() to p }
        .flatMapLatest { (q, p) ->
            val pattern = q.ifEmpty { null }?.let(SearchPattern::contains)
            when {
                p != null -> dao.personRows(p.name, p.number?.filter(Char::isDigit).orEmpty(), pattern, q.ifEmpty { null })
                pattern != null -> dao.searchRows(pattern, q)
                else -> dao.observeRows()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every call, whatever the search or filter: the queue notices are about all of them. */
    val allRows: StateFlow<List<CallRow>> = dao.observeRows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val refreshTick = MutableStateFlow(0)

    private fun modelState(set: Models.Set): StateFlow<ModelState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(Work.downloadName(set)), refreshTick,
    ) { infos, _ ->
        val info = infos.firstOrNull()
        val installed = Models.isInstalled(getApplication(), set)
        // An installed set may still work on an earlier file, while the improved one that replaces it downloads.
        val base = ModelState(installed, update = installed && Models.needsUpdate(getApplication(), set))
        val queued = base.copy(downloading = true)
        when {
            installed && !base.update -> base
            info?.state == WorkInfo.State.RUNNING ->
                queued.copy(progress = info.progress.getFloat(ModelDownloadWorker.PROGRESS, 0f))
            // Downloads wait for Wi-Fi unless the user chose mobile data; a retry also waits a little.
            info?.state == WorkInfo.State.ENQUEUED -> when {
                !network().connected -> queued.copy(waiting = "Waiting for a connection…")
                info.constraints.requiredNetworkType == NetworkType.UNMETERED && !network().unmetered ->
                    if (network().wifi) {
                        queued.copy(offer = Work.DownloadNetwork.WIFI,
                            waiting = "This Wi-Fi is metered. Waiting for an unmetered connection…")
                    } else {
                        queued.copy(offer = Work.DownloadNetwork.ANY, waiting = "Waiting for Wi-Fi…")
                    }
                // "Download anyway" on a metered Wi-Fi keeps to Wi-Fi when that drops.
                info.constraints.requiredNetworkRequest?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !network().wifi ->
                    queued.copy(offer = Work.DownloadNetwork.ANY, waiting = "Waiting for Wi-Fi…")
                info.runAttemptCount > 0 -> queued.copy(waiting = "Interrupted; trying again shortly…")
                else -> queued.copy(waiting = "Starting…")
            }
            info?.state == WorkInfo.State.FAILED ->
                base.copy(error = info.outputData.getString(ModelDownloadWorker.ERROR) ?: "Download failed")
            else -> base
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelState(Models.isInstalled(getApplication(), set)))

    val speechModels = modelState(Models.Set.SPEECH)
    val multilingualModels = modelState(Models.Set.MULTILINGUAL)
    val cjkModels = modelState(Models.Set.CJK)
    val hindiModels = modelState(Models.Set.HINDI)
    val summaryModel = modelState(Models.Set.SUMMARY)
    val languageIdModel = modelState(Models.Set.LANGUAGE_ID)

    private val speechStates = listOf(speechModels, multilingualModels, cjkModels, hindiModels)

    /** How many languages calls can be transcribed in now: with two or more, each call's can be detected. */
    val usableLanguages: StateFlow<Int> = combine(speechStates) { sets -> sets.count { it.installed } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Models.usableLanguages(app).size)

    /** Calls can be transcribed: some language's models are in place. */
    val speechReady: StateFlow<Boolean> = combine(speechStates) { sets -> sets.any { it.installed } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Models.speechReady(app))

    /** The speech set calls are transcribed with now, picked as TranscribeWorker picks it; null while none is usable. */
    val speechInUse: StateFlow<Models.Set?> = combine(settings, combine(speechStates) { it.toList() }) { s, _ ->
        s?.let { Models.recognizer(app, it.language.set, it.previousLanguage?.set) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceState = MutableStateFlow(DeviceState())

    private data class Network(val connected: Boolean, val unmetered: Boolean, val wifi: Boolean)

    private fun network(): Network {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Network(connected = false, unmetered = false, wifi = false)
        return Network(true, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    /** Called on resume: permissions and battery settings may have changed in system settings. */
    fun refresh() {
        refreshTick.value++
        viewModelScope.launch {
            val app = getApplication<Application>()
            val settings = settingsStore.current()
            val folder = settings.folderUri?.toUri()
            val state = DeviceState(
                loaded = true,
                folderAccessible = folder != null && FolderScanner.hasAccess(app, folder),
                batteryUnrestricted = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName),
                callLog = CallerLookup.canReadCallLog(app),
                contacts = CallerLookup.canReadContacts(app),
                phoneState = granted(Manifest.permission.READ_PHONE_STATE),
                notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
                charging = CallState.onCharger(app),
                voiceSamples = VoiceProfile(app).samples(),
                redoWaiting = dao.redoCount(),
            )
            deviceState.value = state
            // Call log or contacts access granted since the last lookups: the scan looks every call up again.
            if (settings.setupDone && CallerLookup.accessGrown(app, state.callLog, state.contacts)) Work.scanNow(app)
        }
    }

    fun setFolder(uri: Uri) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val readWrite = read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Write access too, so a call can be deleted with its recording. Android forgets a write grant
        // that isn't kept now once the phone restarts. Read alone if the picker didn't give write.
        runCatching { resolver.takePersistableUriPermission(uri, readWrite) }
            .onFailure { resolver.takePersistableUriPermission(uri, read) }
        // Drop all access to any folder picked earlier.
        resolver.persistedUriPermissions.filter { it.uri != uri }
            .forEach { runCatching { resolver.releasePersistableUriPermission(it.uri, readWrite) } }
        settingsStore.setFolderUri(uri.toString())
        refresh()
        if (settingsStore.current().setupDone) Work.scanNow(getApplication())
    }

    fun downloadModels(set: Models.Set, network: Work.DownloadNetwork = Work.DownloadNetwork.UNMETERED) =
        Work.downloadModels(getApplication(), set, network)

    /**
     * A language already on the phone takes over at once, and fetches any improved file it lacks. One not
     * downloaded yet is fetched (over Wi-Fi, like the others) while the language used until now keeps
     * transcribing. Every usable language stays until it's removed; a download of a language no longer
     * chosen stops, and what it leaves that nothing can use is deleted.
     */
    fun setLanguage(language: Models.Language) = viewModelScope.launch(Dispatchers.IO) { languageLock.withLock {
        val app = getApplication<Application>()
        val s = settingsStore.current()
        settingsStore.setLanguage(language, Models.previousAfter(app, s.language, language, s.previousLanguage))
        val chosen = language.set
        for (set in Models.speechSets - chosen) workManager.cancelUniqueWork(Work.downloadName(set)).result.get()
        Models.recognizerLock.withLock { Models.deleteAbandoned(app, chosen) }
        if (Models.missingBytes(app, chosen) > 0) Work.downloadModels(app, chosen)
        refreshTick.value++
    } }

    /** Deletes a downloaded language that is neither chosen nor transcribing in its place. The speaker models stay. */
    fun removeLanguage(language: Models.Language) = viewModelScope.launch(Dispatchers.IO) { languageLock.withLock {
        val app = getApplication<Application>()
        val s = settingsStore.current()
        // A tap handled after a change of language may no longer be one to act on.
        if (language.set !in Models.removable(app, s.language.set, s.previousLanguage?.set)) return@withLock
        workManager.cancelUniqueWork(Work.downloadName(language.set)).result.get()
        Models.recognizerLock.withLock { Models.removeRecognizers(app, listOf(language.set)) }
        // With one language left, there's nothing to detect between: its model goes too.
        Work.updateLanguageId(app)
        refreshTick.value++
    } }

    /** Turning detection on downloads its model (over Wi-Fi, retrying one that failed); turning it off deletes it. */
    fun setDetectLanguage(on: Boolean) = viewModelScope.launch(Dispatchers.IO) { languageLock.withLock {
        Work.updateLanguageId(getApplication(), detect = on)
        refreshTick.value++
    } }

    /** Quick taps on one language after another (or on Remove) are handled in order, so the last one wins. */
    private val languageLock = Mutex()

    /** Setup's starting choice, from the phone's language: saved without downloading anything, like the English default. */
    fun preselectLanguage(language: Models.Language) = viewModelScope.launch(Dispatchers.IO) { languageLock.withLock {
        if (!settingsStore.current().languageChosen) settingsStore.setLanguage(language)
    } }

    fun removeSummaryModel() = viewModelScope.launch(Dispatchers.IO) {
        Models.remove(getApplication(), Models.Set.SUMMARY)
        refreshTick.value++
    }

    fun forgetVoice() = viewModelScope.launch(Dispatchers.IO) {
        VoiceProfile(getApplication()).clear()
        refresh()
    }

    /** The people Recognise voices has learned, by name, with how many calls each was learned from. */
    val knownVoices: StateFlow<List<KnownVoiceRow>> =
        voiceDao.observeKnown().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Turning Recognise voices off also forgets every voice it learned. */
    fun setRecogniseVoices(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        settingsStore.setRecogniseVoices(on)
        if (!on) voiceDao.forgetAll()
    }

    /** New summaries only: those already written stay in the language they're in. */
    fun setSummariesInCallLanguage(on: Boolean) = viewModelScope.launch(Dispatchers.IO) { settingsStore.setSummariesInCallLanguage(on) }

    fun forgetKnownVoice(id: Long) = viewModelScope.launch(Dispatchers.IO) { voiceDao.forget(id) }

    fun forgetAllKnownVoices() = viewModelScope.launch(Dispatchers.IO) { voiceDao.forgetAll() }

    /** Common corrections, in the order Settings lists them. */
    val corrections: StateFlow<List<Correction>> =
        correctionDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addCorrection(heard: String, written: String, earlierCalls: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        CommonCorrections.add(getApplication(), heard, written, earlierCalls)
    }

    /**
     * Rules removed but not yet for [UNDO_WINDOW_MS]: removing one rewrites earlier transcripts, so it waits until Undo is
     * no longer offered, and Undo simply keeps the rule. They're left out of the list meanwhile.
     */
    val removingCorrections = MutableStateFlow<Set<Long>>(emptySet())
    private val removals = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    fun removeCorrection(rule: Correction) {
        removingCorrections.update { it + rule.id }
        removals[rule.id] = getApplication<LonghandApp>().appScope.launch(Dispatchers.IO) {
            delay(UNDO_WINDOW_MS)
            CommonCorrections.remove(getApplication(), rule)
            removals.remove(rule.id)
            removingCorrections.update { it - rule.id }
        }
    }

    fun undoRemoveCorrection(rule: Correction) {
        removals.remove(rule.id)?.cancel()
        removingCorrections.update { it - rule.id }
    }

    /** For "Also correct N earlier calls". */
    suspend fun earlierCallsSaying(heard: String): Int =
        withContext(Dispatchers.Default) { CommonCorrections.earlierCalls(getApplication(), heard) }

    /** Saves the choices before [onDone] runs, so no scan can start with the old settings. */
    fun finishSetup(includeExisting: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        settingsStore.setSkipBefore(if (includeExisting) 0L else System.currentTimeMillis())
        settingsStore.setSetupDone(true)
        Work.schedulePeriodicScan(getApplication())
        Work.scanNow(getApplication())
        onDone()
    }

    fun scanNow() = Work.scanNow(getApplication())

    fun hidePhoneNotice() = viewModelScope.launch { settingsStore.setPhoneNoticeHidden(true) }

    /** Starts processing everything waiting, right away, even when not charging. */
    fun transcribeNow() = viewModelScope.launch(Dispatchers.IO) {
        dao.requestAllWaiting()
        Work.enqueueTranscribe(getApplication(), followUp = true)
    }

    fun transcribeSkipped() = viewModelScope.launch(Dispatchers.IO) {
        val now = Work.mayRunNow(getApplication())
        dao.requeueSkipped(requested = now)
        if (now) Work.enqueueTranscribe(getApplication(), followUp = true)
    }

    fun setChargingOnly(v: Boolean) = viewModelScope.launch {
        settingsStore.setChargingOnly(v)
        Work.scanNow(getApplication())
    }

}

/** How long Undo is offered after removing a correction: a long snackbar's time. */
const val UNDO_WINDOW_MS = 10_000L
