package io.github.christiantwu.longhand.work

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The server's copy of a model file isn't the one this version of Longhand expects: its size or its SHA-256 differs.
 * Downloading it again would fetch the same bytes, so the download fails with this message instead of trying again.
 */
class WrongFileException(val path: String) :
    IOException("The file on the server isn't the one this version of Longhand expects. Check for a newer version of Longhand.")

/** A model file's download, checked before the file is used. */
object VerifiedDownload {

    /**
     * Completes [part] as the file of [size] bytes whose SHA-256 is [sha256], calling [fetch] to append what's missing
     * (or to write it whole, if the server won't resume). A complete [part], left when the app stopped before renaming
     * it, only needs checking.
     *
     * A file that doesn't check out is deleted. If it was resumed from bytes an earlier run left, those may be the
     * bad part (the server's file changed in between, say), so it's fetched once more, whole. A file fetched whole
     * that still doesn't check out is the server's: [WrongFileException], for the file called [path]. A fetch that
     * ends early keeps its bytes for the next run to resume, and throws an [IOException] like any dropped connection.
     */
    suspend fun run(part: File, size: Long, sha256: String, path: String, fetch: suspend () -> Unit) {
        var resumed = part.length() > 0
        while (true) {
            if (part.length() < size) fetch()
            val length = part.length()
            if (length < size) throw IOException("The download of $path stopped early")
            if (length == size && sha256(part) == sha256) return
            part.delete()
            if (!resumed) throw WrongFileException(path)
            resumed = false
        }
    }

    /** The whole file's size from a Content-Range header ("bytes 100-199/1000" → 1000); -1 when it doesn't say. */
    fun wholeSize(contentRange: String?): Long = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L

    fun sha256(file: File): String {
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
}
