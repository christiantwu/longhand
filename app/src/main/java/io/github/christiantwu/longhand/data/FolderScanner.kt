package io.github.christiantwu.longhand.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** A file found in the watched folder. */
data class ListedFile(val uri: String, val name: String, val size: Long, val lastModified: Long)

/** [changed] pairs an existing recording's id with the file's new size/date. */
data class ScanPlan(val newFiles: List<ListedFile>, val requeueIds: List<Long>, val changed: List<Pair<Long, ListedFile>>)

object ScanDiff {

    /** Files modified this recently may still be written by an ongoing call. */
    const val STABLE_AFTER_MS = 60_000L

    /**
     * Decides what a scan should do:
     * - files seen for the first time are added (once they've stopped changing),
     * - a finished or failed transcript whose file changed is queued again, unless it was edited by
     *   hand: a new transcript would replace the edits, which only "Transcribe again" does, after
     *   asking (its file details are still updated),
     * - transcripts of files that disappeared are kept.
     *
     * A shrunk recording is compared with its compressed copy's own time ([fileTime]), not the call's.
     */
    fun plan(existing: List<Recording>, listed: List<ListedFile>, now: Long): ScanPlan {
        val byUri = existing.associateBy { it.documentUri }
        val newFiles = ArrayList<ListedFile>()
        val requeue = ArrayList<Long>()
        val changed = ArrayList<Pair<Long, ListedFile>>()
        for (f in listed) {
            if (now - f.lastModified < STABLE_AFTER_MS) continue
            val rec = byUri[f.uri]
            if (rec == null) {
                newFiles += f
            } else if (rec.sizeBytes != f.size || rec.fileTime != f.lastModified) {
                changed += rec.id to f
                val finished = rec.status == RecordingStatus.DONE || rec.status == RecordingStatus.FAILED
                if (finished && rec.editedAt == null) requeue += rec.id
            }
        }
        return ScanPlan(newFiles, requeue, changed)
    }

    /** Recordings older than the setup-time cutoff are kept but not transcribed automatically. */
    fun initialStatus(file: ListedFile, skipBefore: Long): RecordingStatus =
        if (file.lastModified < skipBefore) RecordingStatus.SKIPPED else RecordingStatus.PENDING

    private val audioExtensions = setOf("mp3", "m4a", "aac", "amr", "ogg", "opus", "wav", "3gp", "flac")

    fun isAudio(name: String, mime: String?): Boolean =
        mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in audioExtensions
}

object FolderScanner {

    /** Lists audio files directly inside the picked folder (not subfolders). */
    fun list(context: Context, treeUri: Uri): List<ListedFile> = children(context, treeUri) { name, mime ->
        mime != Document.MIME_TYPE_DIR && ScanDiff.isAudio(name, mime)
    }

    /**
     * The files directly inside [treeUri] that [keep] (given each one's name and type) keeps, with their URIs built as
     * [list] builds them, so a recording's row and the folder's listing always name a file alike.
     */
    fun children(context: Context, treeUri: Uri, keep: (name: String, mime: String?) -> Boolean): List<ListedFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<ListedFile>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!keep(name, c.getString(2))) continue
                out += ListedFile(documentUri(treeUri, c.getString(0)).toString(), name, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    /** The URI of document [documentId] in [treeUri], as [list] writes it. */
    fun documentUri(treeUri: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /** A document's name, size and last-modified time, as [list] would give them; null when it can't be read (or is gone). */
    fun stat(context: Context, uri: Uri): ListedFile? = try {
        context.contentResolver.query(
            uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c -> if (c.moveToFirst()) ListedFile(uri.toString(), c.getString(0) ?: "", c.getLong(1), c.getLong(2)) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * Whether [uri]'s document no longer exists. For a tree URI, ExternalStorageProvider reports a
     * missing file as IllegalArgumentException rather than FileNotFoundException.
     */
    fun isGone(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { !it.moveToFirst() } ?: true
    } catch (e: IllegalArgumentException) {
        true
    } catch (e: java.io.FileNotFoundException) {
        true
    } catch (e: Exception) {
        false // no access, for example: not known to be gone
    }

    /** True while the app still holds the persisted permission for [treeUri]. */
    fun hasAccess(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    /** True while the app may also change files in [treeUri]: delete a call's recording, or shrink one. */
    fun canWrite(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /**
     * Keeps write access to [treeUri], if the folder was granted with it (chosen since 0.6.0, or chosen again for this),
     * in case only read access was kept. False when Longhand may only read it.
     */
    fun takeWriteAccess(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }.isSuccess

    /** Whether [a] and [b] are documents (or the folder itself) of the same folder picked in the folder picker. */
    fun sameTree(a: Uri, b: Uri): Boolean = runCatching {
        a.authority == b.authority && DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)
    }.getOrDefault(false)

    /** Opens the folder picker at /Recordings/CallRecordings, where GrapheneOS saves calls. */
    val defaultFolder: Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Recordings/CallRecordings",
    )

    /** "primary:Recordings/CallRecordings" -> "Recordings/CallRecordings". */
    fun displayPath(treeUri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':') }.getOrDefault(treeUri.toString())
}
