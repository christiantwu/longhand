package io.github.christiantwu.longhand.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.Correction
import io.github.christiantwu.longhand.engine.Corrections
import io.github.christiantwu.longhand.engine.Models
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/**
 * Adding and removing common corrections, from Settings or a transcript. New transcripts and redos are
 * corrected as they're saved (RecordingDao.saveTranscript); earlier ones by [CorrectionsWorker].
 */
object CommonCorrections {

    /**
     * Write [written] wherever [heard] is recognised, replacing a rule for the same words. With
     * [earlierCalls], transcripts made before are corrected too, and with [call] that one at once (the
     * transcript the rule was made from); lines edited by hand are left alone either way.
     */
    suspend fun add(context: Context, heard: String, written: String, earlierCalls: Boolean, call: Long? = null, except: Long? = null): Correction {
        val db = AppDatabase.get(context)
        val rule = db.corrections().add(heard, written)
        if (call != null) db.recordings().correctCall(call, rule.heard, Models.isInstalled(context, Models.Set.SUMMARY))
        if (earlierCalls) Work.correctEarlierCalls(context, rule.heard, except)
        return rule
    }

    /** Removes [rule] and puts back what the recogniser wrote wherever it was applied, in every transcript. */
    suspend fun remove(context: Context, rule: Correction) {
        AppDatabase.get(context).corrections().remove(rule.id)
        Work.correctEarlierCalls(context, rule.heard)
    }

    /**
     * How many calls, other than [except], say [heard], as recognised, in lines not edited by hand: what "Also correct
     * N earlier calls" would correct. A call at a time, so words SQL can't narrow the search for (all in Cyrillic, say)
     * never have every line in memory at once; stops when the dialog asks for another count.
     */
    suspend fun earlierCalls(context: Context, heard: String, except: Long? = null): Int {
        if (Corrections.keyOf(heard).isEmpty()) return 0
        val dao = AppDatabase.get(context).recordings()
        val phrase = Corrections.saying(heard)
        val pattern = Corrections.likePattern(heard)
        var calls = 0
        for (id in dao.callsWithCorrectableLines(pattern)) {
            currentCoroutineContext().ensureActive()
            if (id != except && dao.correctableLines(id, pattern).any { phrase.matches(it.source) }) calls++
        }
        return calls
    }

    /** How many lines of [call], not edited by hand, say [heard] as recognised. */
    suspend fun linesInCall(context: Context, call: Long, heard: String): Int {
        if (Corrections.keyOf(heard).isEmpty()) return 0
        val phrase = Corrections.saying(heard)
        return AppDatabase.get(context).recordings().correctableLines(call, Corrections.likePattern(heard))
            .count { phrase.matches(it.source) }
    }
}

/**
 * Applies common corrections to earlier transcripts after the rule for [HEARD] was added or removed:
 * every line that says it, as recognised, is written again from the recogniser's text with the rules
 * there are now, unless it was edited by hand (RecordingDao.correctCall). Whether the rule is still
 * there decides which it was, so an add and a quick removal (queued one after the other) end with the
 * lines as recognised. Only the database is touched: nothing is transcribed or summarized here.
 */
class CorrectionsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val heard = inputData.getString(HEARD) ?: return Result.success()
        // The transcript the rule was made from, when its other lines were to be left as they are.
        val except = inputData.getLong(EXCEPT, -1L)
        val db = AppDatabase.get(applicationContext)
        val dao = db.recordings()
        return try {
            val added = db.corrections().all().any { it.heardKey == Corrections.keyOf(heard) }
            // A removed rule's words can't be found in a summary again, so it's written again (on the charger, like other rewrites).
            val resummarize = Models.isInstalled(applicationContext, Models.Set.SUMMARY)
            var changed = 0
            // Each call in its own transaction, with the rules as they are then; a stopped job runs again from the
            // start, which changes nothing twice.
            for (id in dao.callsWithCorrectableLines(Corrections.likePattern(heard))) {
                if (isStopped) break
                if (id == except) continue
                if (dao.correctCall(id, heard, resummarize)) changed++
            }
            Log.i(TAG, "corrections: a rule was ${if (added) "added" else "removed"}; $changed calls changed")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logged rather than failed, so corrections queued after this one still run.
            Log.e(TAG, "correcting earlier calls failed", e)
            Result.success()
        }
    }

    companion object {
        const val HEARD = "heard"
        const val EXCEPT = "except"
    }
}
