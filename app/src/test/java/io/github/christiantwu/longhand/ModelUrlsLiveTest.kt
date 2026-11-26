package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.Models
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Asks the servers: is every model file the app downloads still there, with the size and SHA-256 written in
 * [Models]? Run it before a release (it needs the network, so it's skipped otherwise):
 *
 *     LONGHAND_LIVE_URLS=1 ./gradlew testDebugUnitTest --tests '*ModelUrlsLiveTest' --rerun
 *
 * Files up to [SMALL] bytes are downloaded and hashed. For the larger ones it takes the server's word: Hugging Face
 * gives a stored file's size and SHA-256 in the headers of its redirect, and GitHub lists a digest for each asset of
 * Longhand's own release.
 */
class ModelUrlsLiveTest {

    private data class Seen(val size: Long, val sha256: String)

    @Test fun everyModelFileIsWhereTheAppLooksForIt() {
        assumeTrue("set LONGHAND_LIVE_URLS=1 to check the servers", System.getenv("LONGHAND_LIVE_URLS") == "1")
        val files = Models.Set.entries.flatMap { it.files }.distinct()
        val problems = files.mapNotNull { f ->
            val seen = runCatching {
                when {
                    f.sizeBytes <= SMALL -> downloaded(f.url)
                    "huggingface.co" in f.url -> huggingFace(f.url)
                    f.url.startsWith(LONGHAND) -> longhandAssets.getValue(f.url)
                    else -> error("no way to check a file this large here")
                }
            }.getOrElse { return@mapNotNull "${f.path}: ${it.javaClass.simpleName}: ${it.message} (${f.url})" }
            val expected = Seen(f.sizeBytes, f.sha256)
            if (seen == expected) null else "${f.path}: the app expects $expected, the server has $seen (${f.url})"
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
        println("checked ${files.size} model files")
    }

    private fun open(url: String, method: String = "GET", follow: Boolean = true) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = follow
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "longhand-model-check")
        }

    private fun downloaded(url: String): Seen {
        val conn = open(url)
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    size += n
                }
            }
            return Seen(size, md.digest().joinToString("") { "%02x".format(it) })
        } finally {
            conn.disconnect()
        }
    }

    /** A file Hugging Face stores by hash: its redirect to the storage says which file it is. */
    private fun huggingFace(url: String): Seen {
        val conn = open(url, method = "HEAD", follow = false)
        try {
            check(conn.responseCode in 200..399) { "HTTP ${conn.responseCode}" }
            val size = checkNotNull(conn.getHeaderField("X-Linked-Size")) { "no X-Linked-Size header" }
            val hash = checkNotNull(conn.getHeaderField("X-Linked-Etag")) { "no X-Linked-Etag header" }
            return Seen(size.toLong(), hash.trim('"'))
        } finally {
            conn.disconnect()
        }
    }

    /** The assets of Longhand's models release by download address, as GitHub's API lists them. */
    private val longhandAssets: Map<String, Seen> by lazy {
        val conn = open("https://api.github.com/repos/christiantwu/longhand/releases/tags/models-1")
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode} from GitHub's API" }
            val json = conn.inputStream.use { it.readBytes().decodeToString() }
            // Each asset: … "size":N,"digest":"sha256:…","download_count":…,"browser_download_url":"…"
            Regex(""""size"\s*:\s*(\d+)\s*,\s*"digest"\s*:\s*"sha256:([0-9a-f]{64})"[^{}]*?"browser_download_url"\s*:\s*"([^"]+)"""")
                .findAll(json).associate { m -> m.groupValues[3] to Seen(m.groupValues[1].toLong(), m.groupValues[2]) }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val SMALL = 64_000_000L
        const val LONGHAND = "https://github.com/christiantwu/longhand/releases/download/models-1/"
    }
}
