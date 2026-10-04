package io.github.christiantwu.longhand.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.engine.Models
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads one set of models (speech for one language 290–730 MB, summaries ~2.6 GB), or what's missing
 * of it: for an installed set, an improved file that replaces an earlier one (see [Models.ModelFile.replaces]).
 * Partial files are resumed, and every file's SHA-256 is checked before it is put in place,
 * so a broken download never reaches the engine.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val set = Models.Set.valueOf(inputData.getString(SET) ?: Models.Set.SPEECH.name)

    // A job replaced by "Use mobile data" can still be finishing its last write when the new one
    // starts, so each set downloads under a lock and two workers never write the same file. The
    // speech sets share the speaker models, so they share a lock too.
    override suspend fun doWork(): Result = (if (set == Models.Set.SUMMARY) summaryLock else speechLock).withLock { downloadSet() }

    /** The set already works, on an earlier file that the one being downloaded replaces. */
    private var update = false

    /** Bytes of the set in place before this run; progress counts only the rest. */
    private var start = 0L

    private suspend fun downloadSet(): Result {
        // Only the chosen language's speech set is ever wanted. A download queued for another (an update
        // check racing a language change, say) would fetch hundreds of MB to be deleted again.
        if (set != Models.Set.SUMMARY && Settings(applicationContext).current().language.set != set) return Result.success()
        // A partial download of a file this set has since replaced is never finished; free its space before the
        // storage check counts it (the folder check that also deletes it waits for setup to finish).
        Models.recognizerLock.withLock { Models.deleteObsolete(applicationContext, set) }
        update = Models.needsUpdate(applicationContext, set)
        var done = set.files.sumOf { f ->
            val file = Models.file(applicationContext, f.path)
            if (file.length() == f.sizeBytes) f.sizeBytes else 0L
        }
        // What was missing when the download was asked for, so a retry or a restart doesn't move the
        // progress backwards; a request from before this was recorded counts from now.
        val missingAtRequest = inputData.getLong(MISSING, -1L)
        start = if (missingAtRequest >= 0) (set.totalBytes - missingAtRequest).coerceAtMost(done) else done
        report(done)
        return try {
            for (f in set.files) {
                val target = Models.file(applicationContext, f.path)
                if (target.length() == f.sizeBytes) continue
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                // An update keeps the earlier file until this one is verified, so it needs room on top of
                // everything installed. Without it every retry would fail at once; stop and say why.
                val needed = f.sizeBytes - part.length().coerceAtMost(f.sizeBytes)
                val free = Models.dir(applicationContext).usableSpace
                if (free < needed + SPACE_MARGIN) {
                    return Result.failure(workDataOf(ERROR to "Not enough storage: ${(needed + SPACE_MARGIN - free) / 1_000_000 + 1} MB more needed"))
                }
                val base = done
                // A complete .part (the app stopped before renaming it) only needs checking.
                if (part.length() < f.sizeBytes) download(f.url, part) { bytes -> done = base + bytes; report(done) }
                if (part.length() != f.sizeBytes || sha256(part) != f.sha256) {
                    part.delete()
                    throw IOException("Checksum mismatch for ${f.path}")
                }
                if (!part.renameTo(target)) throw IOException("Could not save ${f.path}")
                done = base + f.sizeBytes
                Log.i(TAG, "downloaded ${f.path}")
            }
            if (set == Models.Set.SUMMARY) {
                // Summarize the calls that were transcribed before the model arrived
                // (on battery, recent ones now and the rest on the charger).
                AppDatabase.get(applicationContext).recordings().queueMissingSummaries()
                Work.scanNow(applicationContext)
            } else {
                val chosen = Settings(applicationContext).current().language.set == set
                Models.recognizerLock.withLock {
                    // Every file is in place and verified, so an earlier file one of them replaces has done its job.
                    Models.deleteObsolete(applicationContext, set)
                    // The language chosen in Settings is now in place: the recognizer that worked while this
                    // one downloaded has done its job, and goes to free the space.
                    if (chosen) Models.removeRecognizers(applicationContext, Models.speechSets - set)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "model download failed", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(ERROR to (e.message ?: "Download failed")))
        }
    }

    private var lastReport = 0L

    private suspend fun report(bytes: Long) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 500 && bytes < set.totalBytes) return
        lastReport = now
        // Of what was missing, as the "About N MB" the app showed before the download.
        val missing = set.totalBytes - start
        val p = if (missing > 0) (bytes - start).toFloat() / missing else 1f
        setProgress(workDataOf(PROGRESS to p))
        val n = Work.progressNotification(
            applicationContext, title(set, update), "${(bytes - start) / 1_000_000} / ${missing / 1_000_000} MB", p,
        )
        runCatching { setForeground(Work.foregroundInfo(Work.NOTIF_DOWNLOAD, n, longProcessing = false)) }
    }

    /** Appends to [part], resuming with an HTTP Range request when it already has data. */
    private suspend fun download(url: String, part: File, onBytes: suspend (Long) -> Unit) {
        var existing = part.length()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) existing = 0 // server ignored Range; start over
            else if (code == 416) { // range not satisfiable: the partial file is bad, so start again
                part.delete()
                throw IOException("HTTP 416 for $url")
            } else if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code for $url")
            conn.inputStream.use { input ->
                FileOutputStream(part, existing > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = existing
                    while (true) {
                        val n = input.read(buf)
                        if (isStopped) throw IOException("Stopped")
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val speechLock = Mutex()
        private val summaryLock = Mutex()

        const val SET = "set"
        /** Bytes missing when the download was requested: the baseline for its progress. */
        const val MISSING = "missing"
        const val PROGRESS = "progress"
        const val ERROR = "error"
        /** Room left free after a download, for transcripts and everything else on the phone. */
        private const val SPACE_MARGIN = 300_000_000L

        /** The download notification's title; [update] when the set already works and an improved file replaces an earlier one. */
        fun title(set: Models.Set, update: Boolean): String = when (set) {
            Models.Set.SUMMARY -> "Downloading the summary model"
            Models.Set.SPEECH -> if (update) "Updating the English model" else "Downloading transcription models"
            Models.Set.MULTILINGUAL -> if (update) "Updating the European languages model" else "Downloading the European languages model"
            Models.Set.CJK -> "Downloading the Chinese, Japanese and Korean model"
            Models.Set.HINDI -> "Downloading the Hindi model"
        }
    }
}
