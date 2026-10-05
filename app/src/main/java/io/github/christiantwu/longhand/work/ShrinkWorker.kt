package io.github.christiantwu.longhand.work

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.AppSettings
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingDao
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.data.Shrink
import io.github.christiantwu.longhand.data.fileTime
import io.github.christiantwu.longhand.engine.AacShrinker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Shrinks WAV recordings once they're transcribed (Settings → Shrink WAV recordings, off by default; which calls, and the
 * format, are in [Shrink]). Each is encoded as AAC in an .m4a file in the app's cache, and that copy is decoded to its end
 * and checked against the WAV ([Shrink.problem], and [Shrink.wavProblem] for a WAV that decodes short). Only then is it
 * written into the recordings folder beside the WAV, under a name the folder check ignores, and synced to the storage;
 * once its bytes read back the same, it takes the WAV's name with ".m4a", the call's row is pointed at it in one
 * statement, and the WAV is deleted ([Shrinking.swap]). Anything that goes wrong before the row names the copy leaves the WAV and the row as they were (the
 * copy is deleted), and the call is tried again on a later run, at most [Shrink.MAX_ATTEMPTS] times in all.
 *
 * It runs on the charger while nothing else does: the folder check starts it when there's nothing to transcribe, and it
 * stops, between recordings or partway through one, once a call comes in, the charger is unplugged, or transcription is
 * queued (Work.enqueueTranscribe cancels it). The recording it was on stays a WAV.
 */
class ShrinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dao = AppDatabase.get(context).recordings()

    override suspend fun doWork(): Result {
        val app = applicationContext
        val folder = Settings(app).current().folderUri?.toUri() ?: return Result.success()
        FolderScan.lock.withLock { Shrinking.recover(app, dao) }
        // Copies left in the cache by a run the app was stopped in the middle of.
        File(app.cacheDir, "shrink").listFiles()?.forEach { it.delete() }
        if (!FolderScanner.canWrite(app, folder)) {
            Log.w(TAG, "shrink: Longhand may only read the recordings folder")
            return Result.success()
        }
        var shrunk = 0
        var freed = 0L
        val tried = HashSet<Long>()
        while (!isStopped && Shrinking.mayRun(app)) {
            if (Shrinking.unsettled(app)) {
                Log.i(TAG, "shrink: waits until the swap cut short before is settled")
                break
            }
            val rec = Shrinking.next(app, dao, folder, except = tried) ?: break
            tried += rec.id
            val copySize = shrink(rec, folder) ?: continue
            shrunk++
            freed += rec.sizeBytes - copySize
        }
        if (shrunk > 0) Log.i(TAG, "shrink: $shrunk recordings shrunk, ${freed / 1_000_000} MB freed")
        return Result.success()
    }

    private var checkedAt = 0L

    /**
     * Asked by the codecs after every block: false once the job is stopped, or, looked at every two seconds, once a call
     * comes in, the charger is unplugged or transcription is queued.
     */
    private val keepGoing: () -> Boolean = {
        val now = SystemClock.elapsedRealtime()
        when {
            isStopped -> false
            now - checkedAt < 2_000 -> true
            else -> {
                checkedAt = now
                Shrinking.free(applicationContext)
            }
        }
    }

    /** @return the copy's size once it has replaced [rec]'s WAV in [folder]; null when the WAV stays. */
    private suspend fun shrink(rec: Recording, folder: Uri): Long? {
        val app = applicationContext
        val cache = File(File(app.cacheDir, "shrink").apply { mkdirs() }, "${rec.id}.m4a")
        // Counted before the file is read, so a recording that crashes the app is given up on after a few tries.
        dao.startShrink(rec.id)
        val started = SystemClock.elapsedRealtime()
        try {
            // Only the file the call was transcribed from: one changed since is for the folder check to queue again.
            val file = FolderScanner.stat(app, rec.documentUri.toUri())
            if (file == null || file.size != rec.sizeBytes || file.lastModified != rec.fileTime) {
                Log.i(TAG, "shrink: ${rec.displayName} changed or is gone; left to the folder check")
                dao.interruptShrink(rec.id)
                return null
            }
            val original = AacShrinker.encode(app, rec.documentUri.toUri(), cache, keepGoing)
            // Decoding stops where the WAV's header says its audio ends, which a recorder stopped partway may leave short.
            Shrink.wavProblem(AacShrinker.wavHeader(app, rec.documentUri.toUri()), file.size, original.durationMs)
                ?.let { throw AacShrinker.Unsupported(it) }
            val copy = AacShrinker.stats(cache, keepGoing)
            Shrink.problem(original, copy)?.let { throw IOException("The copy failed its check: $it") }
            // From here on it isn't stopped halfway: the folder holds either the WAV or the copy, never both or neither.
            val swapped = withContext(NonCancellable) {
                FolderScan.lock.withLock { Shrinking.swap(app, dao, rec, folder, cache) }
            }
            when (swapped) {
                Shrinking.Swap.DONE -> {
                    Log.i(TAG, "shrink: ${rec.displayName} → ${cache.length() / 1000} kB from ${rec.sizeBytes / 1000} kB " +
                        "(${original.durationMs / 1000} s of audio) in ${SystemClock.elapsedRealtime() - started} ms")
                    return cache.length()
                }
                Shrinking.Swap.CHANGED -> {
                    Log.i(TAG, "shrink: ${rec.displayName} was deleted, queued again or changed meanwhile; the WAV stays")
                    dao.interruptShrink(rec.id)
                }
                Shrinking.Swap.NAME_TAKEN -> {
                    Log.w(TAG, "shrink: a file named ${Shrink.copyName(rec.displayName)} is already in the folder; the WAV stays")
                    dao.giveUpShrink(rec.id)
                }
                Shrinking.Swap.URI_TAKEN -> {
                    Log.w(TAG, "shrink: another call's transcript is kept for ${Shrink.copyName(rec.displayName)}; the WAV stays")
                    dao.giveUpShrink(rec.id)
                }
                Shrinking.Swap.UNSETTLED -> {
                    Log.w(TAG, "shrink: the WAV ${rec.displayName} couldn't be deleted, and what's left isn't all tidied up; " +
                        "that's finished before the folder is next checked")
                }
            }
        } catch (e: AacShrinker.Stopped) {
            withContext(NonCancellable) { dao.interruptShrink(rec.id) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.interruptShrink(rec.id) }
            throw e
        } catch (e: AacShrinker.Unsupported) {
            Log.w(TAG, "shrink: ${rec.displayName} can't be shrunk (${e.message}); the WAV stays")
            dao.giveUpShrink(rec.id)
        } catch (e: Exception) {
            Log.w(TAG, "shrink: ${rec.displayName} failed (try ${rec.shrinkAttempts + 1} of ${Shrink.MAX_ATTEMPTS}); the WAV stays", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "shrink: out of memory on ${rec.displayName}; the WAV stays", e)
        } finally {
            cache.delete()
        }
        return null
    }
}

/** When shrinking runs, which call is next, and swapping a WAV for its checked copy in the recordings folder. */
object Shrinking {

    /** Whether shrinking may go on: turned on, and [free]. */
    suspend fun mayRun(context: Context): Boolean {
        val s = Settings(context).current()
        return s.setupDone && s.shrinkWav && free(context)
    }

    /** On the charger, with no call in progress and no transcription running or queued. */
    fun free(context: Context): Boolean {
        if (!CallState.onCharger(context)) return false
        if (CallState.inCall(context)) {
            // So the call's end checks the folder, which starts shrinking again.
            CallState.markPaused(context)
            return false
        }
        return !Work.transcribing(context)
    }

    /**
     * The next recording to shrink, of those not tried in this run ([except]): oldest call first, in [folder] (one left in
     * a folder watched before can't be replaced), and not about to have its voices worked out again from its audio.
     */
    suspend fun next(context: Context, dao: RecordingDao, folder: Uri, except: Set<Long> = emptySet()): Recording? =
        dao.shrinkCandidates().asSequence()
            .filter { it.id !in except && Shrink.eligible(it) && FolderScanner.sameTree(it.documentUri.toUri(), folder) }
            .firstOrNull { !Work.refreshingVoices(context, it.id) }

    /**
     * Called by the folder check when there's nothing to transcribe: starts shrinking, when it's turned on, may run now and
     * any recording waits for it.
     */
    suspend fun startIfDue(context: Context, dao: RecordingDao, settings: AppSettings) {
        if (!settings.shrinkWav) return
        val folder = settings.folderUri?.toUri() ?: return
        if (!FolderScanner.canWrite(context, folder) || !free(context)) return
        if (next(context, dao, folder) != null) Work.shrink(context)
    }

    enum class Swap {
        /** The copy has replaced the WAV. */
        DONE,
        /** The call was deleted, queued again or its file changed meanwhile; nothing was changed. */
        CHANGED,
        /** A file with the copy's name is already in the folder; nothing was changed. */
        NAME_TAKEN,
        /**
         * Another call's row names the copy's URI or its name (RecordingDao.copyNameTaken, [Shrink.Saved.URI_TAKEN]);
         * nothing was changed.
         */
        URI_TAKEN,
        /**
         * The row names the copy, but the WAV couldn't be deleted, and what's left can't all be tidied up now (whether the
         * WAV is still there as it was can't be told, or the copy couldn't be deleted once the row named the WAV again):
         * the swap stays noted, and [recover] finishes or undoes it before the folder is next listed.
         */
        UNSETTLED,
    }

    /**
     * Puts [cache], the checked copy of [rec]'s WAV, in its place in [folder]. Called with FolderScan.lock held, so no
     * folder check lists the folder meanwhile, and no call is deleted from the transcript screen. Throws when the copy
     * can't be written, leaving the WAV and the row as they were, or when the WAV can't be deleted and the row names it
     * again ([settle]).
     */
    suspend fun swap(context: Context, dao: RecordingDao, rec: Recording, folder: Uri, cache: File): Swap {
        val resolver = context.contentResolver
        val wav = rec.documentUri.toUri()
        // Read again now that the folder check is held off: the call may have been deleted, queued again or changed.
        val now = dao.get(rec.id)
        val file = FolderScanner.stat(context, wav)
        if (now == null || now.documentUri != rec.documentUri || now.sizeBytes != rec.sizeBytes ||
            now.status != RecordingStatus.DONE || now.originalBytes != null ||
            file == null || file.size != rec.sizeBytes || file.lastModified != rec.fileTime) return Swap.CHANGED
        // The name as the folder lists it now, which the row normally has too.
        val name = Shrink.copyName(file.name)
        // The phone's storage ignores case in names.
        val siblings = FolderScanner.children(context, folder) { _, _ -> true }
        if (siblings.any { it.name.equals(name, ignoreCase = true) }) return Swap.NAME_TAKEN
        // A row naming the copy's URI or name (a transcript kept for a file of that name that disappeared) would take the
        // copy for its own file, and the folder check queue it as that call's: nothing is written then.
        if (dao.copyNameTaken(rec.id, expectedUri(folder, wav, file.name, name), name)) return Swap.URI_TAKEN
        // Left by a try that couldn't delete them; never all there is of a call.
        siblings.filter { Shrink.isPart(it.name, name) }.forEach { deleted(context, it.uri.toUri()) }

        // What the copy read back from the folder must be, there and after a crash ([settle]).
        val fingerprint = cache.inputStream().use { Shrink.fingerprint(it) }
        val entry = InFlight.Entry(rec.id, rec.documentUri, file.name, folder.toString(), name, fingerprint)
        InFlight.start(context, entry)
        // Whether the row names the copy. Until it does, whatever stops this short is tidied up as a swap cut short is:
        // the copy, half-written or renamed, goes while the WAV is there, and the swap is forgotten once it's gone.
        var saved = false
        try {
            val parent = FolderScanner.documentUri(folder, DocumentsContract.getTreeDocumentId(folder))
            val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", Shrink.partName(file.name))
                ?: throw IOException("Couldn't create the copy")
            val pfd = resolver.openFileDescriptor(created, "w") ?: throw IOException("Couldn't write the copy")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                cache.inputStream().use { it.copyTo(out) }
                // On the storage itself before the row names it and the WAV goes, not only in memory: otherwise a crash or
                // power cut soon after could keep the WAV's deletion but lose the copy's bytes. Reading it back below
                // checks what the storage was given, not what it kept.
                out.flush()
                out.fd.sync()
            }
            if (FolderScanner.stat(context, created)?.size != fingerprint.size || intact(context, created, entry) != true) {
                throw IOException("The copy in the folder isn't what was written")
            }
            val renamed = DocumentsContract.renameDocument(resolver, created, name) ?: created
            // Named as the folder check names its files, so it finds the row it's given.
            val kept = FolderScanner.documentUri(folder, DocumentsContract.getDocumentId(renamed))
            val written = FolderScanner.stat(context, kept) ?: throw IOException("The copy is gone")
            if (written.size != cache.length()) throw IOException("The copy is ${written.size} bytes, not ${cache.length()}")
            when (dao.saveShrunk(rec.id, rec.documentUri, rec.sizeBytes, kept.toString(), written.name, written.size, written.lastModified)) {
                Shrink.Saved.DONE -> saved = true
                Shrink.Saved.CHANGED -> return Swap.CHANGED
                Shrink.Saved.URI_TAKEN -> return Swap.URI_TAKEN
            }
            // The call's recording is the copy from here: it stays unless the WAV is certainly there and can't be deleted.
            if (deleted(context, wav)) {
                InFlight.clear(context)
                return Swap.DONE
            }
            // Settled as a swap cut short is: the row names the WAV again only if it's there just as it was copied, and
            // otherwise the swap stays noted for [recover] to finish.
            return when (settle(context, dao, entry, wavUndeletable = true)) {
                Shrink.Recovery.DONE, Shrink.Recovery.DELETE_WAV -> Swap.DONE
                Shrink.Recovery.UNDO, Shrink.Recovery.DELETE_COPY -> throw IOException("Couldn't delete the WAV")
                Shrink.Recovery.WAIT -> Swap.UNSETTLED
            }
        } finally {
            if (!saved) {
                try {
                    if (settle(context, dao, entry) == Shrink.Recovery.WAIT) Log.w(TAG, "shrink: $name isn't tidied up yet; tried again later")
                } catch (e: Exception) {
                    Log.w(TAG, "shrink: couldn't tidy up $name; tried again later", e)
                }
            }
        }
    }

    /**
     * A swap is still noted ([InFlight]): [recover] couldn't settle it yet. Only one is noted at a time, so no other
     * starts until it is.
     */
    fun unsettled(context: Context): Boolean = InFlight.read(context) != null

    /**
     * A swap of call [id]'s recording is still noted: its WAV and its copy may both be there, so which file is the call's
     * recording isn't settled yet.
     */
    fun unsettled(context: Context, id: Long): Boolean = InFlight.read(context)?.id == id

    /**
     * Finishes or undoes a swap the app was stopped in the middle of, or that couldn't be settled when it happened, so the
     * folder is listed with the WAV or its copy, never both. Called with FolderScan.lock held, before the folder is listed.
     * The swap stays noted until nothing it left may still be there.
     */
    suspend fun recover(context: Context, dao: RecordingDao) {
        val left = InFlight.read(context) ?: return
        try {
            when (settle(context, dao, left)) {
                Shrink.Recovery.WAIT -> Log.w(TAG, "shrink: ${left.name}, cut short before, can't be tidied up yet; tried again later")
                Shrink.Recovery.DELETE_WAV -> Log.i(TAG, "shrink: finished ${left.name}, cut short before")
                Shrink.Recovery.DONE -> Log.i(TAG, "shrink: ${left.name}, cut short before, needed nothing more")
                else -> Log.i(TAG, "shrink: undid ${left.name}, cut short before")
            }
        } catch (e: Exception) {
            Log.w(TAG, "shrink: couldn't tidy up ${left.name} after it was cut short; tried again later", e)
        }
    }

    /**
     * Does what [Shrink.recovery] says for the swap [left], cut short, or in [swap] after its WAV couldn't be deleted
     * ([wavUndeletable]); half-written copies go too. Forgets the swap once all that's done and confirmed.
     * @return what was done, or [Shrink.Recovery.WAIT] when anything is left to look at again.
     */
    private suspend fun settle(
        context: Context, dao: RecordingDao, left: InFlight.Entry, wavUndeletable: Boolean = false,
    ): Shrink.Recovery {
        val wav = left.wav.toUri()
        val row = dao.get(left.id)
        // Everything in the folder; null when it can't be listed.
        val siblings = runCatching { FolderScanner.children(context, left.folder.toUri()) { _, _ -> true } }.getOrNull()
        // The files of the copy's name in the folder; null when it can't be listed.
        val named = siblings?.filter { it.name.equals(left.name, ignoreCase = true) }
        val namedCopy = row?.documentUri?.takeIf { it != left.wav }?.toUri()
        val copy = when {
            namedCopy != null -> state(context, namedCopy)
            named == null -> Shrink.FileState.Unknown
            else -> named.firstOrNull()?.let { Shrink.FileState.Present(it.size) } ?: Shrink.FileState.Gone
        }
        // Whether another call's row names the WAV, or the copy (the file the row names, or those of its name).
        suspend fun elsewhere(uri: String) = dao.idForUri(uri)?.let { it != left.id } == true
        val copyElsewhere = (namedCopy?.let { listOf(it.toString()) } ?: named.orEmpty().map { it.uri }).any { elsewhere(it) }
        val action = Shrink.recovery(
            left.wav, row, state(context, wav), copy, elsewhere(left.wav), wavUndeletable, copyElsewhere,
            // Read through only when the WAV would go on the strength of it.
            copyIntact = { namedCopy?.let { intact(context, it, left) } },
        )
        val done = when (action) {
            Shrink.Recovery.DONE -> true
            Shrink.Recovery.WAIT -> false
            // Not deletable: looked at again as such, so the row names the WAV again if it's there as it was.
            Shrink.Recovery.DELETE_WAV -> deleted(context, wav) || return settle(context, dao, left, wavUndeletable = true)
            Shrink.Recovery.DELETE_COPY -> named.orEmpty().map { deleted(context, it.uri.toUri()) }.all { it }
            Shrink.Recovery.UNDO ->
                dao.undoShrunk(left.id, row!!.documentUri, left.wav, left.wavName) == 1 &&
                    (copy !is Shrink.FileState.Present || deleted(context, namedCopy!!))
        }
        // Half-written copies are never all there is of a call, whatever else happens.
        val parts = siblings?.filter { Shrink.isPart(it.name, left.name) }?.map { deleted(context, it.uri.toUri()) }?.all { it } ?: false
        if (!done || !parts) return Shrink.Recovery.WAIT
        InFlight.clear(context)
        return action
    }

    /** [uri]'s file: there, with its size; certainly gone; or not known, when it can't be read. */
    private fun state(context: Context, uri: Uri): Shrink.FileState =
        FolderScanner.stat(context, uri)?.let { Shrink.FileState.Present(it.size) }
            ?: if (FolderScanner.isGone(context, uri)) Shrink.FileState.Gone else Shrink.FileState.Unknown

    /** Deletes [uri]'s file. @return whether it's gone: deleted, or found not to be there. */
    private fun deleted(context: Context, uri: Uri): Boolean {
        val ok = try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "shrink: couldn't delete a file: ${e.javaClass.simpleName}")
            false
        }
        return ok || FolderScanner.isGone(context, uri)
    }

    /**
     * Whether [uri] holds the copy the swap [left] noted ([InFlight.Entry.copy]: its length and SHA-256), read back through
     * the storage; null when it can't be read.
     */
    private fun intact(context: Context, uri: Uri, left: InFlight.Entry): Boolean? = try {
        context.contentResolver.openInputStream(uri)?.use { Shrink.fingerprint(it) == left.copy }
    } catch (e: Exception) {
        Log.w(TAG, "shrink: couldn't read ${left.name} back: ${e.javaClass.simpleName}")
        null
    }

    /**
     * The URI the copy called [name] is to have beside [wav], called [wavName], in [folder], when the storage names a
     * document by its path, as the phone's own storage does; null when [wav]'s doesn't end in its name.
     */
    private fun expectedUri(folder: Uri, wav: Uri, wavName: String, name: String): String? {
        val id = runCatching { DocumentsContract.getDocumentId(wav) }.getOrNull()?.takeIf { it.endsWith(wavName) } ?: return null
        return FolderScanner.documentUri(folder, id.removeSuffix(wavName) + name).toString()
    }

    /**
     * The swap under way: written before its copy is created, and cleared once the WAV or the copy is certainly gone, so
     * if the app is stopped in between, or what it left can't all be tidied up then, [recover] knows what to tidy up.
     * Written at once, so it survives the process dying.
     */
    private object InFlight {
        /**
         * Call [id]'s WAV [wav], called [wavName], in [folder], being replaced by [name], written first as its part file
         * ([Shrink.partName]), whose bytes are to be [copy].
         */
        class Entry(val id: Long, val wav: String, val wavName: String, val folder: String, val name: String, val copy: Shrink.Fingerprint)

        private fun prefs(context: Context) = context.getSharedPreferences("shrink", Context.MODE_PRIVATE)

        fun start(context: Context, entry: Entry) = prefs(context).edit(commit = true) {
            putLong("id", entry.id).putString("wav", entry.wav).putString("wavName", entry.wavName)
                .putString("folder", entry.folder).putString("name", entry.name)
                .putLong("copySize", entry.copy.size).putString("copySha256", entry.copy.sha256)
        }

        fun read(context: Context): Entry? {
            val p = prefs(context)
            if (!p.contains("id")) return null
            return Entry(
                p.getLong("id", -1L), p.getString("wav", null) ?: return null, p.getString("wavName", null) ?: return null,
                p.getString("folder", null) ?: return null, p.getString("name", null) ?: return null,
                // Noted without them, no copy matches: the WAV is never deleted on the strength of one.
                Shrink.Fingerprint(p.getLong("copySize", -1L), p.getString("copySha256", null) ?: ""),
            )
        }

        fun clear(context: Context) = prefs(context).edit(commit = true) { clear() }
    }
}
