package io.github.christiantwu.longhand.work

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.Caller
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.ScanDiff
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.engine.AudioDecoder
import io.github.christiantwu.longhand.engine.Models

/** The periodic and on-open folder check. */
class FolderScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        FolderScan.run(applicationContext)
        return Result.success()
    }
}

/**
 * Looks for new recordings in the watched folder, names their callers, and queues them for transcription.
 * Also keeps the models up to date ([Work.updateModels]).
 */
object FolderScan {

    suspend fun run(context: Context) {
        val settings = Settings(context).current()
        // Before setup is finished, the "include existing recordings" choice hasn't been made.
        if (!settings.setupDone) return
        // First, as it doesn't need the folder: an improved model file to download, or an old one to delete.
        Work.updateModels(context)
        val folder = settings.folderUri?.toUri() ?: return
        if (!FolderScanner.hasAccess(context, folder)) {
            Log.w(TAG, "scan: folder permission lost")
            return
        }
        val dao = AppDatabase.get(context).recordings()
        val listed = FolderScanner.list(context, folder)
        val plan = ScanDiff.plan(dao.all(), listed, System.currentTimeMillis())

        dao.insertAll(plan.newFiles.map {
            Recording(
                documentUri = it.uri, displayName = it.name, sizeBytes = it.size, lastModified = it.lastModified,
                status = ScanDiff.initialStatus(it, settings.skipBefore),
                durationMs = AudioDecoder.probeDurationMs(context, it.uri.toUri()),
            )
        })
        plan.changed.forEach { (id, f) -> dao.updateFileInfo(id, f.size, f.lastModified, f.name) }
        if (plan.requeueIds.isNotEmpty()) dao.requeue(plan.requeueIds)
        Log.i(TAG, "scan: ${listed.size} files, ${plan.newFiles.size} new, ${plan.changed.size} changed, ${plan.requeueIds.size} requeued")

        // Who each new call was with. When call log or contacts access has grown since the last
        // lookups (granted here or in system settings), every call is looked up again.
        val callLog = CallerLookup.canReadCallLog(context)
        val contacts = CallerLookup.canReadContacts(context)
        if (CallerLookup.accessGrown(context, callLog, contacts)) dao.resetCallerChecks()
        CallerLookup.rememberAccess(context, callLog, contacts)
        for (rec in dao.needingCallerLookup()) {
            val earlier = Caller(rec.phoneNumber, rec.contactName, rec.contactLookupKey, rec.callDirection)
            val c = CallerLookup.resolve(context, rec).orElse(earlier, CallerLookup::sameNumber)
            dao.setLookedUpCaller(rec.id, c.number, c.name, c.lookupKey, c.direction)
        }

        // Summaries count as waiting work too: one skipped because a call came in shouldn't wait
        // for the next recording. Plugged in, everything may run; on battery only the last day's
        // calls, or nothing with "Only while charging" (TranscribeWorker applies the same rule).
        // Recordings the user asked for always count. What's left is started by a later scan
        // that finds the phone plugged in.
        val summaries = Models.isInstalled(context, Models.Set.SUMMARY) && !SummaryBackoff.active(context)
        suspend fun waiting(since: Long) = dao.pendingCount(since) > 0 || (summaries && dao.summaryPendingCount(since) > 0)
        val since = when {
            CallState.onCharger(context) -> 0L
            settings.chargingOnly -> Long.MAX_VALUE // only what the user asked for
            else -> System.currentTimeMillis() - Work.RECENT_WINDOW_MS
        }
        // Transcripts from an older pipeline are redone too, on the charger.
        val redo = since == 0L && dao.redoCount() > 0
        when {
            !waiting(since) && !redo -> {}
            // During a call nothing heavy starts; the call's end triggers the after-call check.
            CallState.inCall(context) -> {
                Log.i(TAG, "scan: a call is in progress; processing starts after it ends")
                CallState.markPaused(context)
            }
            else -> Work.enqueueTranscribe(context)
        }
    }
}
