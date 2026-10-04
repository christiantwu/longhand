package io.github.christiantwu.longhand.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.data.SummaryStatus
import io.github.christiantwu.longhand.data.detection
import io.github.christiantwu.longhand.engine.AudioDecoder
import io.github.christiantwu.longhand.engine.LanguageDetector
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.Separation
import io.github.christiantwu.longhand.engine.SpeakerSeparation
import io.github.christiantwu.longhand.engine.Summarizer
import io.github.christiantwu.longhand.engine.SummaryLanguage
import io.github.christiantwu.longhand.engine.TranscriptionEngine
import io.github.christiantwu.longhand.engine.VoiceMath
import io.github.christiantwu.longhand.engine.VoiceProfile
import io.github.christiantwu.longhand.engine.WordTimings
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.export.SpeakerNames
import io.github.christiantwu.longhand.export.TranscriptFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Works through the queue in rounds: every PENDING recording is transcribed (speech models
 * loaded once), then every transcript waiting for a summary is summarized (summary model loaded
 * once), then, on the charger, transcripts made by an older pipeline are redone. Before a call's
 * language is detected, the calls waiting for that are detected a few at a time, with the recognizer
 * unloaded. No two of these models are ever in memory together (speaker separation, small and the
 * same for every language, stays loaded beside them for the whole pass), and new calls always go
 * before redone ones. Runs as a foreground job so Android doesn't stop it after 10 minutes.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dao = AppDatabase.get(context).recordings()
    private val format: (String) -> String = CallerLookup::formatNumber

    /** Checked afresh each time: the model can be downloaded or removed while a long run goes on. */
    private fun summariesOn() = Models.isInstalled(applicationContext, Models.Set.SUMMARY)

    /** After the summary model fails to load, summaries wait an hour, then are tried again. */
    private fun summariesWaiting() = summariesOn() && !SummaryBackoff.active(applicationContext)

    override suspend fun doWork(): Result {
        showProgress("Getting ready…", null)
        dao.recoverInterrupted()
        dao.recoverSummaries()
        // Transcripts made by an older pipeline are redone, but only on the charger, after new work.
        suspend fun redoWaiting() = CallState.onCharger(applicationContext) && dao.redoCount() > 0
        // A call recorded while summaries were running is picked up by going round again,
        // as long as the last round got something done.
        do {
            val transcribed = transcribeQueue(redo = false)
            // Loading the summary model is heavy work too, so it waits if a call started meanwhile.
            val summarized = if (!isStopped && summariesWaiting() && !pausedForCall() && dao.summaryPendingCount(since()) > 0) summarizeAll() else emptyList()
            announceReady()
            val redone = if (!isStopped && !pausedForCall() && redoWaiting()) transcribeQueue(redo = true) else emptyList()
            val progressed = transcribed.isNotEmpty() || summarized.isNotEmpty() || redone.isNotEmpty()
            val more = dao.pendingCount(since()) > 0 || (summariesWaiting() && dao.summaryPendingCount(since()) > 0) || redoWaiting()
        } while (!isStopped && progressed && more && !pausedForCall())

        // Whatever is left (older calls on battery, or everything with "Only while charging") is
        // started by the folder scan, which runs every 15 minutes, once the phone is plugged in.
        if (dao.pendingCount() > dao.pendingCount(since())) Log.i(TAG, "on battery: older recordings wait for the charger")
        announceReady()
        return Result.success()
    }

    /**
     * One notification per batch for new transcripts (redone ones aren't news), each once its
     * summary is in, naming each call as precisely as we can. Sent every round, so a new call
     * isn't held back by a long redo of old ones.
     */
    private suspend fun announceReady() {
        val ready = dao.toAnnounce()
        if (ready.isEmpty()) return
        Work.notifyDone(applicationContext, ready.map { CallText.sentence(it, format) })
        dao.markAnnounced(ready.map { it.id })
    }

    /**
     * The oldest recording this run may process right now. On battery it takes only the last
     * day's calls, or none with "Only while charging"; the rest waits for the charger, except
     * recordings the user asked for (Recording.requested), which the queries always include.
     * It's checked before every recording and summary, so plugging in, unplugging or changing
     * the setting mid-run takes effect straight away.
     */
    private suspend fun since(): Long = when {
        CallState.onCharger(applicationContext) -> 0L
        Settings(applicationContext).current().chargingOnly -> Long.MAX_VALUE // on battery: nothing
        else -> System.currentTimeMillis() - Work.RECENT_WINDOW_MS
    }

    /**
     * Heavy work pauses while a call is in progress; the call's end triggers the after-call
     * check, which starts processing again. (A recording already being transcribed finishes.)
     */
    private fun pausedForCall(): Boolean = CallState.inCall(applicationContext).also {
        if (it) {
            Log.i(TAG, "a call started: pausing until it ends")
            // So the call's end restarts processing even if it was only ringing.
            CallState.markPaused(applicationContext)
        }
    }

    /**
     * Transcribes waiting recordings, or with [redo] redoes transcripts made by an older pipeline:
     * those only on the charger, and only while no new recording, and no summary the user asked
     * for, waits.
     * @return ids of recordings transcribed in this pass.
     */
    private suspend fun transcribeQueue(redo: Boolean): List<Long> {
        if (!Models.speechReady(applicationContext)) {
            Log.w(TAG, "transcribe: speech models not installed")
            return emptyList()
        }
        val done = ArrayList<Long>()
        var engine: TranscriptionEngine? = null
        var engineFiles: List<String>? = null
        // Loaded once for the pass, the first time it's needed, whatever languages the calls are in.
        val separator = lazy {
            val t = SystemClock.elapsedRealtime()
            SpeakerSeparation(applicationContext).also {
                Log.i(TAG, "speaker separation loaded in ${SystemClock.elapsedRealtime() - t} ms")
            }
        }
        // Speakers found in calls while their languages were detected, so transcribing them doesn't find them again.
        // Room for one backlog batch and a batch of calls asked for meanwhile, which goes first.
        val separated = SeparationCache(2 * DETECT_BATCH)
        // Calls whose language detection was tried in this pass, whether it found anything or not: each is tried once.
        val detectionTried = HashSet<Long>()
        try {
            while (!isStopped && !pausedForCall()) {
                val rec = when {
                    !redo -> dao.nextPending(since())
                    CallState.onCharger(applicationContext) && dao.pendingCount(since()) == 0 &&
                        !(summariesWaiting() && dao.requestedCount() > 0) -> dao.nextRedo()
                    else -> null
                } ?: break
                val settings = Settings(applicationContext).current()
                if (rec.id !in detectionTried && Models.needsDetection(rec.pinnedLanguage, rec.spokenLanguages != null,
                        settings.detectLanguage, Models.sizes(applicationContext))) {
                    // Never the detection model and a recognizer in memory together, and neither loaded for every
                    // call: the next few waiting calls that need it are detected now (a redo's only when it comes up),
                    // then the loop goes on.
                    engine?.close()
                    engine = null
                    val calls = if (redo) listOf(rec) else detectionBatch(rec, dao.pendingToDetect(since(), DETECT_BATCH), detectionTried)
                    detectionTried += detectLanguages(calls, { separator.value }, separated)
                    continue
                }
                // Chosen afresh for every recording: the call's own language if one was chosen for it, else the one
                // detected in it, else Settings'. A language chosen or finished downloading meanwhile, or an improved
                // model file, takes over at once, instead of the old one carrying on through a long backlog.
                val pick = Models.recognizerLock.withLock {
                    val pick = Models.engineFor(rec.pinnedLanguage, rec.detection.takeIf { settings.detectLanguage }, settings.language,
                        settings.previousLanguage, engineFiles.takeIf { engine != null }, Models.sizes(applicationContext))
                        ?: return@withLock null
                    if (pick.reload) {
                        engine?.close() // never two engines in memory
                        engine = null
                        val t = SystemClock.elapsedRealtime()
                        engine = TranscriptionEngine(applicationContext, pick.language.set)
                        engineFiles = pick.files
                        Log.i(TAG, "speech models (${pick.language.set}, ${pick.files.first()}) loaded in ${SystemClock.elapsedRealtime() - t} ms")
                    }
                    pick
                } ?: break
                // Read for every call: the owner may confirm their voice while a long batch runs.
                val profile = VoiceProfile(applicationContext).load()
                val kept = separated.take(rec, profile)
                if (kept != null) Log.i(TAG, "${rec.displayName}: speakers found while detecting its language")
                if (transcribe(rec, engine!!, separator.value, kept, pick.language, pick.detected, profile, summariesOn(), redo)) done += rec.id
            }
        } finally {
            engine?.close()
            if (separator.isInitialized()) separator.value.close()
        }
        return done
    }

    /**
     * Detects the language of each of [calls], with Whisper loaded once and released after, and stores what it found
     * (Recording.spokenLanguages). Whisper goes by the speech that speaker separation ([loadSeparator]) finds, and the
     * speakers it finds are kept in [separated] for transcribing the call. The recognizer lock is held while the model
     * loads and while each call is detected, so its files can't be deleted underneath it; once detection is turned off
     * or a language removed, it stops, and the calls left follow Settings.
     */
    /**
     * Detects the language of [calls], keeping the speakers found in [separated]. @return the calls tried: all of them, or
     * those started before a call asked for came up (finding speakers takes a while, and it goes first).
     */
    private suspend fun detectLanguages(
        calls: List<Recording>, loadSeparator: () -> SpeakerSeparation, separated: SeparationCache,
    ): List<Long> {
        if (calls.isEmpty()) return emptyList()
        val tried = ArrayList<Long>()
        showProgress("Loading language detection…", null)
        suspend fun available() = Models.canDetect(applicationContext, Settings(applicationContext).current().detectLanguage)
        // Each call is marked before any native work on it (RecordingDao.startDetection), the first before Whisper even
        // loads, so a call that crashes the app isn't detected again. Stopping short (detection turned off, the job
        // stopped) takes the mark off the call it was on.
        var current: Recording? = null
        suspend fun start(rec: Recording) {
            current = rec
            tried += rec.id
            dao.startDetection(rec.id, rec.sizeBytes, rec.lastModified)
        }
        var detector: LanguageDetector? = null
        try {
            start(calls.first())
            val t = SystemClock.elapsedRealtime()
            val lid = Models.recognizerLock.withLock {
                // Checked again with the lock held: sherpa-onnx ends the app on a Whisper file that isn't the right one.
                if (!available()) return calls.map { it.id }
                try {
                    LanguageDetector(applicationContext)
                } catch (e: Throwable) {
                    // These calls are transcribed in Settings' language instead.
                    Log.e(TAG, "language detection failed to load", e)
                    return calls.map { it.id }
                }
            }
            detector = lid
            Log.i(TAG, "language detection loaded in ${SystemClock.elapsedRealtime() - t} ms, for ${calls.size} calls")
            val separator = loadSeparator()
            val ids = calls.map { it.id }.toSet()
            for (rec in calls) {
                if (isStopped || pausedForCall()) break
                // A call asked for meanwhile goes first: the rest of a backlog batch waits for a later turn.
                if (rec !== calls.first() && !calls.first().requested &&
                    dao.nextPending(since())?.let { it.requested && it.id !in ids } == true) break
                val name = CallText.title(rec, format)
                showProgress("$name · detecting the language", null)
                start(rec)
                val started = SystemClock.elapsedRealtime()
                // Set when the call is deleted while its speakers are found: the work on it stops there.
                var deleted = false
                try {
                    val audio = AudioDecoder.decode(applicationContext, rec.documentUri.toUri())
                    // Read for every call, as transcription does: the speakers are kept for it only with the same voiceprint.
                    val owner = VoiceProfile(applicationContext).load()
                    var lastShown = -1
                    val separation = separator.separate(
                        audio,
                        owner,
                        onProgress = { p ->
                            val pct = (p / SpeakerSeparation.SHARE * 100).toInt()
                            if (pct != lastShown) {
                                lastShown = pct
                                // Called from inside native code on this thread, as in transcribe().
                                runBlocking {
                                    // Shown on the call's row while it waits (RecordingsScreen); 0 again once detected.
                                    deleted = dao.setProgress(rec.id, pct / 100f) == 0
                                    showProgress("$name · detecting the language", pct / 100f)
                                }
                            }
                        },
                        isStopped = { isStopped || deleted },
                    )
                    separated.put(rec, owner, separation)
                    val found = Models.recognizerLock.withLock {
                        if (available()) lid.detect(audio.mono, separation.speech) else null
                    } ?: break
                    dao.setDetection(rec.id, found.stored, found.speechSeconds, rec.sizeBytes, rec.lastModified)
                    Log.i(TAG, "${rec.displayName}: languages ${found.codes} in %.1f s of speech, took %d ms"
                        .format(found.speechSeconds, SystemClock.elapsedRealtime() - started))
                } catch (e: CancellationException) {
                    if (!deleted) throw e
                    Log.i(TAG, "${rec.displayName} was deleted while its language was being detected")
                } catch (e: Exception) {
                    // Transcribed in Settings' language; transcribing it reports what's wrong with the recording, if anything.
                    Log.w(TAG, "language detection failed for ${rec.displayName}", e)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "out of memory detecting the language of ${rec.displayName}", e)
                } finally {
                    withContext(NonCancellable) { dao.setProgress(rec.id, 0f) }
                }
                current = null
            }
        } finally {
            detector?.close()
            current?.let { withContext(NonCancellable) { dao.interruptDetection(it.id) } }
        }
        return tried
    }

    /**
     * A [redo] leaves the recording DONE, so its old transcript stays on show until the new one
     * replaces it, and if the redo fails the old transcript simply stays. [engine] transcribes in [language], which
     * was [detected] in the call or not, with the speakers [separated] while its language was detected, if they were,
     * else the ones [separator] finds now.
     * @return true when a transcript was saved.
     */
    private suspend fun transcribe(
        rec: Recording, engine: TranscriptionEngine, separator: SpeakerSeparation, separated: Separation?,
        language: Models.Language, detected: Boolean, profile: FloatArray?, summarize: Boolean, redo: Boolean,
    ): Boolean {
        if (redo) dao.startRedo(rec.id) else dao.markProcessing(rec.id)
        val name = CallText.title(rec, format)
        showProgress("$name · decoding audio", null)
        val started = SystemClock.elapsedRealtime()
        // Set when the call is deleted while it's being transcribed: the work stops there.
        var deleted = false
        return try {
            val audio = AudioDecoder.decode(applicationContext, rec.documentUri.toUri())
            var lastShown = -1
            val onProgress: (Float) -> Unit = { p ->
                val pct = (p * 100).toInt()
                if (pct != lastShown) {
                    lastShown = pct
                    // Called from inside native code on this thread; the DB and
                    // notification updates are quick, so blocking briefly is fine.
                    runBlocking {
                        deleted = dao.setProgress(rec.id, p) == 0
                        showProgress("$name · $pct%", p)
                    }
                }
            }
            val stopped = { isStopped || deleted }
            // With the speakers found while its language was detected, progress starts at 30%.
            // Kept from detection only for the same audio: a file rewritten in place since then is separated again.
            val separation = separated?.takeIf { it.matches(audio) } ?: separator.separate(audio, profile, onProgress, stopped)
            val result = engine.transcribe(audio, separation, onProgress, stopped)
            val owner = profile?.let { VoiceMath.pickOwner(it, result.voices) }
            val elapsed = SystemClock.elapsedRealtime() - started
            // What the recogniser wrote, with its word timings: common corrections are applied as it's saved, with the
            // rules there are then, and the recogniser's text is kept, so a rule removed later is undone. The summary,
            // written after, sees the corrected text.
            val saved = dao.saveTranscript(
                id = rec.id,
                segments = result.lines.map {
                    Segment(recordingId = rec.id, startMs = it.startMs, endMs = it.endMs, speaker = it.speaker, text = it.text,
                        words = it.words?.let(WordTimings::encode))
                },
                voices = result.voices.toSpeakerVoices(rec.id),
                durationMs = audio.durationMs,
                processingMs = elapsed,
                ownerSpeaker = owner,
                language = language,
                detected = detected,
                pinned = rec.pinnedLanguage,
                summarize = summarize,
                redo = redo,
            )
            if (!saved) {
                // Deleted meanwhile, given another language (queued again in it), or (a redo) edited by hand while it
                // ran: the edits stay, and the redo isn't counted.
                if (redo) dao.interruptRedo(rec.id)
                Log.i(TAG, "${rec.displayName} wasn't saved: ${if (redo) "edited, " else ""}deleted or given another language meanwhile")
                return false
            }
            Log.i(TAG, "${if (redo) "redone" else "done"} ${rec.displayName}: ${result.lines.size} lines, ${result.voices.size} voices, owner=$owner, " +
                "audio ${audio.durationMs} ms, took $elapsed ms (RTF %.2f)".format(elapsed.toDouble() / audio.durationMs.coerceAtLeast(1)))
            true
        } catch (e: CancellationException) {
            // The coroutine may already be cancelled; still record the state.
            withContext(NonCancellable) { if (redo) dao.interruptRedo(rec.id) else dao.markInterrupted(rec.id) }
            if (deleted) Log.i(TAG, "${rec.displayName} was deleted while it was being transcribed")
            if (isStopped || deleted) false else throw e
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory on ${rec.displayName}", e)
            if (redo) dao.giveUpRedo(rec.id) else dao.markFailed(rec.id, "Recording too long to fit in memory")
            false
        } catch (e: Exception) {
            // A redo that fails (the recording was deleted, say) keeps the old transcript.
            Log.e(TAG, "failed ${rec.displayName}", e)
            if (redo) dao.giveUpRedo(rec.id) else dao.markFailed(rec.id, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** @return ids of recordings that got a summary. */
    private suspend fun summarizeAll(): List<Long> {
        showProgress("Loading the summary model…", null)
        val t = SystemClock.elapsedRealtime()
        SummaryBackoff.loading(applicationContext)
        val summarizer = try {
            Summarizer(Models.file(applicationContext, Models.SUMMARY_MODEL).absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "summary model failed to load", e)
            SummaryBackoff.failed(applicationContext)
            return emptyList()
        }
        SummaryBackoff.loaded(applicationContext)
        Log.i(TAG, "summary model loaded in ${SystemClock.elapsedRealtime() - t} ms")
        // The model call blocks this thread. When Android stops the job (charger unplugged),
        // it cancels this coroutine at the same moment, so the watcher must live in its own
        // scope to still be running when isStopped turns true.
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                if (isStopped) {
                    Log.i(TAG, "job stopped: cancelling the summary in progress")
                    summarizer.cancel()
                    break
                }
                delay(250)
            }
        }
        val done = ArrayList<Long>()
        val speechReady = Models.speechReady(applicationContext)
        try {
            while (!isStopped && !pausedForCall()) {
                // A new recording goes first (after at least one summary, so the round counts as
                // progress); the next round comes back to the summaries.
                if (done.isNotEmpty() && speechReady && dao.pendingCount(since()) > 0) break
                val next = dao.nextSummaryPending(since()) ?: break
                dao.markSummaryProcessing(next.id)
                // Read again now that it's marked: the owner or names may have changed meanwhile.
                val rec = dao.get(next.id) ?: continue
                showProgress("Summarizing · ${CallText.title(rec, format)}", null)
                val segments = dao.segments(rec.id)
                val names = SpeakerNames(
                    manual = dao.speakerNames(rec.id).associate { it.speaker to it.name },
                    owner = rec.ownerSpeaker,
                    callerName = CallText.caller(rec, format),
                    speakers = segments.map { it.speaker }.toSet(),
                )
                // In the call's language, from what detection heard and the transcript's model and script; read for
                // every call, so turning the setting off takes effect from the next summary.
                val language = SummaryLanguage.forCall(Settings(applicationContext).current().summariesInCallLanguage,
                    rec.language, rec.detection, segments.map { it.text })
                val started = SystemClock.elapsedRealtime()
                val summary = try {
                    summarizer.summarize(
                        TranscriptFormatter.summaryHeader(rec, format),
                        TranscriptFormatter.summaryLines(segments, names),
                        language,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "summary failed for ${rec.displayName}", e)
                    null
                }
                // Saved even if the job is being stopped: a finished summary shouldn't be thrown away.
                withContext(NonCancellable) {
                    when {
                        summary != null -> {
                            dao.saveSummary(rec.id, summary.topic, summary.summary, summary.followUps.joinToString("\n"))
                            done += rec.id
                            Log.i(TAG, "summary for ${rec.displayName} ($language) in ${SystemClock.elapsedRealtime() - started} ms: ${summary.topic}")
                        }
                        isStopped -> dao.endSummaryRun(rec.id, SummaryStatus.PENDING)
                        // A redone transcript keeps its previous summary rather than none.
                        else -> dao.endSummaryRun(rec.id, if (rec.topic != null) SummaryStatus.DONE else SummaryStatus.FAILED)
                    }
                }
                // New calls are announced once they're all summarized, not after the summaries of
                // redone calls queued behind them.
                if (!rec.announced && dao.unannouncedSummariesWaiting() == 0) announceReady()
            }
        } finally {
            // Join before freeing the model so the watcher can't call cancel() on freed memory.
            withContext(NonCancellable) { watcher.cancelAndJoin() }
            summarizer.close()
        }
        return done
    }

    private suspend fun showProgress(text: String, progress: Float?) {
        val n = Work.progressNotification(applicationContext, "Processing call recordings", text, progress)
        try {
            setForeground(Work.foregroundInfo(Work.NOTIF_PROGRESS, n, longProcessing = true))
        } catch (e: Exception) {
            // Android 12+ refuses to start a foreground service from the background unless
            // the app is exempt from battery optimization. Keep going; the job may then be
            // stopped after 10 minutes and resumes on the next run.
            Log.w(TAG, "could not go foreground: ${e.message}")
        }
    }

    companion object {
        /** Calls detected in one go: a call asked for meanwhile waits for no more than these before it's transcribed. */
        const val DETECT_BATCH = 8

        /**
         * The calls to detect now: [next], the call up, and the ones [waiting] after it in the order they're taken,
         * leaving out those [tried] in this pass; at most [DETECT_BATCH]. A call asked for brings only other calls asked
         * for, so it isn't transcribed after the backlog's detection.
         */
        fun detectionBatch(next: Recording, waiting: List<Recording>, tried: Set<Long>): List<Recording> =
            (listOf(next) + waiting.filter { !next.requested || it.requested })
                .distinctBy { it.id }.filter { it.id !in tried }.take(DETECT_BATCH)
    }
}

/**
 * The speakers found in calls while their languages were detected ([Separation]), kept for transcribing the calls later
 * in the same pass. A call's are used once, and only while its file is unchanged and the owner's voiceprint is the same:
 * telling the speakers apart keeps the owner a speaker of their own. It holds at most [capacity] calls, the oldest going
 * first.
 */
class SeparationCache(private val capacity: Int) {

    private class Kept(val sizeBytes: Long, val lastModified: Long, val owner: FloatArray?, val separation: Separation)

    private val kept = LinkedHashMap<Long, Kept>()

    fun put(rec: Recording, owner: FloatArray?, separation: Separation) {
        kept.remove(rec.id)
        kept[rec.id] = Kept(rec.sizeBytes, rec.lastModified, owner, separation)
        while (kept.size > capacity) kept.remove(kept.keys.first())
    }

    /** [rec]'s speakers, if they still hold with the owner's voiceprint [owner]; no longer kept either way. */
    fun take(rec: Recording, owner: FloatArray?): Separation? {
        val k = kept.remove(rec.id) ?: return null
        return k.separation.takeIf { k.sizeBytes == rec.sizeBytes && k.lastModified == rec.lastModified && k.owner contentEquals owner }
    }
}

/**
 * After the summary model fails to load (say, not enough memory), summaries wait an hour, so
 * the 15-minute check doesn't reload a 2.6 GB model over and over. A load that never finished
 * because the process was killed or crashed counts too: the marker written before loading is
 * still there when the next run starts.
 */
object SummaryBackoff {
    private const val PAUSE_MS = 3600_000L

    private fun prefs(context: Context) = context.getSharedPreferences("summary_backoff", Context.MODE_PRIVATE)

    /** Written synchronously, so it survives the process dying during the load. */
    fun loading(context: Context) = prefs(context).edit(commit = true) { putLong("loading_since", System.currentTimeMillis()) }
    fun loaded(context: Context) = prefs(context).edit { remove("loading_since"); remove("failed_at") }
    fun failed(context: Context) = prefs(context).edit { remove("loading_since"); putLong("failed_at", System.currentTimeMillis()) }

    fun active(context: Context): Boolean {
        val p = prefs(context)
        val since = maxOf(p.getLong("failed_at", 0L), p.getLong("loading_since", 0L))
        return since != 0L && System.currentTimeMillis() - since < PAUSE_MS
    }
}
