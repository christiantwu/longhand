package io.github.christiantwu.longhand.data

import android.content.Context
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
            } else if (rec.sizeBytes != f.size || rec.lastModified != f.lastModified) {
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
    fun list(context: Context, treeUri: Uri): List<ListedFile> {
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
                val mime = c.getString(2)
                if (mime == Document.MIME_TYPE_DIR || !ScanDiff.isAudio(name, mime)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                out += ListedFile(uri.toString(), name, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    /** True while the app still holds the persisted permission for [treeUri]. */
    fun hasAccess(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    /** Opens the folder picker at /Recordings/CallRecordings, where GrapheneOS saves calls. */
    val defaultFolder: Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Recordings/CallRecordings",
    )

    /** "primary:Recordings/CallRecordings" -> "Recordings/CallRecordings". */
    fun displayPath(treeUri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':') }.getOrDefault(treeUri.toString())
}
