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
import io.github.christiantwu.longhand.engine.AudioDecoder
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.Summarizer
import io.github.christiantwu.longhand.engine.TranscriptionEngine
import io.github.christiantwu.longhand.engine.VoiceMath
import io.github.christiantwu.longhand.engine.VoiceProfile
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
 * once), then, on the charger, transcripts made by an older pipeline are redone. The two model
 * sets are never in memory together, and new calls always go before redone ones.
 * Runs as a foreground job so Android doesn't stop it after 10 minutes.
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
        try {
            while (!isStopped && !pausedForCall()) {
                val rec = when {
                    !redo -> dao.nextPending(since())
                    CallState.onCharger(applicationContext) && dao.pendingCount(since()) == 0 &&
                        !(summariesWaiting() && dao.requestedCount() > 0) -> dao.nextRedo()
                    else -> null
                } ?: break
                // Chosen afresh for every recording: a language, or an improved model file, that finished
                // downloading meanwhile takes over at once, instead of the old one carrying on through a long backlog.
                val chosen = Settings(applicationContext).current().language.set
                val loaded = Models.recognizerLock.withLock {
                    val speech = Models.recognizer(applicationContext, chosen) ?: return@withLock false
                    val files = Models.filesInUse(applicationContext, speech)
                    if (engine == null || files != engineFiles) {
                        engine?.close() // never two engines in memory
                        engine = null
                        val t = SystemClock.elapsedRealtime()
                        engine = TranscriptionEngine(applicationContext, speech)
                        engineFiles = files
                        Log.i(TAG, "speech models ($speech, ${files.first()}) loaded in ${SystemClock.elapsedRealtime() - t} ms")
                    }
                    true
                }
                if (!loaded) break
                // Read for every call: the owner may confirm their voice while a long batch runs.
                val profile = VoiceProfile(applicationContext).load()
                if (transcribe(rec, engine!!, profile, summariesOn(), redo)) done += rec.id
            }
        } finally {
            engine?.close()
        }
        return done
    }

    /**
     * A [redo] leaves the recording DONE, so its old transcript stays on show until the new one
     * replaces it, and if the redo fails the old transcript simply stays.
     * @return true when a transcript was saved.
     */
    private suspend fun transcribe(rec: Recording, engine: TranscriptionEngine, profile: FloatArray?, summarize: Boolean, redo: Boolean): Boolean {
        if (redo) dao.startRedo(rec.id) else dao.markProcessing(rec.id)
        val name = CallText.title(rec, format)
        showProgress("$name · decoding audio", null)
        val started = SystemClock.elapsedRealtime()
        // Set when the call is deleted while it's being transcribed: the work stops there.
        var deleted = false
        return try {
            val audio = AudioDecoder.decode(applicationContext, rec.documentUri.toUri())
            var lastShown = -1
            val result = engine.transcribe(
                audio,
                owner = profile,
                onProgress = { p ->
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
                },
                isStopped = { isStopped || deleted },
            )
            val owner = profile?.let { VoiceMath.pickOwner(it, result.voices) }
            val elapsed = SystemClock.elapsedRealtime() - started
            dao.saveTranscript(
                id = rec.id,
                segments = result.lines.map { Segment(recordingId = rec.id, startMs = it.startMs, endMs = it.endMs, speaker = it.speaker, text = it.text) },
                voices = result.voices.toSpeakerVoices(rec.id),
                durationMs = audio.durationMs,
                processingMs = elapsed,
                ownerSpeaker = owner,
                summarize = summarize,
                redo = redo,
            )
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
                val started = SystemClock.elapsedRealtime()
                val summary = try {
                    summarizer.summarize(
                        TranscriptFormatter.summaryHeader(rec, format),
                        TranscriptFormatter.summaryLines(segments, names),
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
                            Log.i(TAG, "summary for ${rec.displayName} in ${SystemClock.elapsedRealtime() - started} ms: ${summary.topic}")
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
