package io.github.christiantwu.longhand.ui

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
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.data.KnownVoiceRow
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.VoiceProfile
import io.github.christiantwu.longhand.work.CallState
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

data class ModelState(
    val installed: Boolean,
    /** Queued or running. */
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    /** Why a queued download isn't running yet, e.g. no Wi-Fi; null while it runs. */
    val waiting: String? = null,
    /** A network the user can choose to download over instead, while the download waits. */
    val offer: Work.DownloadNetwork? = null,
) {
    val downloadText: String get() = waiting ?: "Downloading… ${(progress * 100).toInt()}%"
    /** The progress bar, shown only while bytes are actually arriving. */
    val runningProgress: Float? get() = if (downloading && waiting == null) progress else null
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

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = Settings(app)
    private val dao = AppDatabase.get(app).recordings()
    private val voiceDao = AppDatabase.get(app).voices()
    private val workManager = WorkManager.getInstance(app)

    val settings: StateFlow<AppSettings?> = settingsStore.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val query = MutableStateFlow("")

    val rows: StateFlow<List<CallRow>> = query
        .flatMapLatest { q -> if (q.isBlank()) dao.observeRows() else dao.searchRows(q.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val refreshTick = MutableStateFlow(0)

    private fun modelState(set: Models.Set): StateFlow<ModelState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(Work.downloadName(set)), refreshTick,
    ) { infos, _ ->
        val info = infos.firstOrNull()
        when {
            Models.isInstalled(getApplication(), set) -> ModelState(installed = true)
            info?.state == WorkInfo.State.RUNNING ->
                ModelState(false, downloading = true, progress = info.progress.getFloat(ModelDownloadWorker.PROGRESS, 0f))
            // Downloads wait for Wi-Fi unless the user chose mobile data; a retry also waits a little.
            info?.state == WorkInfo.State.ENQUEUED -> when {
                !network().connected -> ModelState(false, downloading = true, waiting = "Waiting for a connection…")
                info.constraints.requiredNetworkType == NetworkType.UNMETERED && !network().unmetered ->
                    if (network().wifi) {
                        ModelState(false, downloading = true, offer = Work.DownloadNetwork.WIFI,
                            waiting = "This Wi-Fi is metered. Waiting for an unmetered connection…")
                    } else {
                        ModelState(false, downloading = true, offer = Work.DownloadNetwork.ANY, waiting = "Waiting for Wi-Fi…")
                    }
                // "Download anyway" on a metered Wi-Fi keeps to Wi-Fi when that drops.
                info.constraints.requiredNetworkRequest?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !network().wifi ->
                    ModelState(false, downloading = true, offer = Work.DownloadNetwork.ANY, waiting = "Waiting for Wi-Fi…")
                info.runAttemptCount > 0 -> ModelState(false, downloading = true, waiting = "Interrupted; trying again shortly…")
                else -> ModelState(false, downloading = true, waiting = "Starting…")
            }
            info?.state == WorkInfo.State.FAILED ->
                ModelState(false, error = info.outputData.getString(ModelDownloadWorker.ERROR) ?: "Download failed")
            else -> ModelState(false)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelState(Models.isInstalled(getApplication(), set)))

    val speechModels = modelState(Models.Set.SPEECH)
    val multilingualModels = modelState(Models.Set.MULTILINGUAL)
    val cjkModels = modelState(Models.Set.CJK)
    val summaryModel = modelState(Models.Set.SUMMARY)

    /** Calls can be transcribed: some language's models are in place. */
    val speechReady: StateFlow<Boolean> = combine(speechModels, multilingualModels, cjkModels) { sets ->
        sets.any { it.installed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Models.speechReady(app))

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
     * A language not downloaded yet is fetched (over Wi-Fi, like the others) while the installed one
     * keeps working; once it's in, the old one is deleted. A language that is still installed takes
     * over at once. Either way, any download of a language no longer chosen stops and is deleted.
     */
    fun setLanguage(language: Models.Language) = viewModelScope.launch(Dispatchers.IO) { languageLock.withLock {
        settingsStore.setLanguage(language)
        val app = getApplication<Application>()
        val chosen = language.set
        // Keep an installed language only to transcribe with until the chosen one arrives.
        val unneeded = (Models.speechSets - chosen)
            .filter { Models.isInstalled(app, chosen) || !Models.isInstalled(app, it) }
        for (set in unneeded) workManager.cancelUniqueWork(Work.downloadName(set)).result.get()
        Models.recognizerLock.withLock { Models.removeRecognizers(app, unneeded) }
        if (!Models.isInstalled(app, chosen)) Work.downloadModels(app, chosen)
        refreshTick.value++
    } }

    /** Quick taps on one language after another are handled in order, so the last one wins. */
    private val languageLock = Mutex()

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

    fun forgetKnownVoice(id: Long) = viewModelScope.launch(Dispatchers.IO) { voiceDao.forget(id) }

    fun forgetAllKnownVoices() = viewModelScope.launch(Dispatchers.IO) { voiceDao.forgetAll() }

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
