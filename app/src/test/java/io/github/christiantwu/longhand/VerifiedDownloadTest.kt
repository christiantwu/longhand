package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.work.VerifiedDownload
import io.github.christiantwu.longhand.work.Work
import io.github.christiantwu.longhand.work.WrongFileException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** A model file's download, with the server played by a function that appends to the partial file. */
class VerifiedDownloadTest {

    private val part = File.createTempFile("model", ".part").apply { delete() }
    private val good = "the model's bytes".toByteArray()
    private val hash = File.createTempFile("model", ".good").let { f ->
        f.writeBytes(good)
        VerifiedDownload.sha256(f).also { f.delete() }
    }

    @After fun cleanUp() {
        part.delete()
    }

    /** A server that sends [bytes] from wherever the partial file ends, as a resumed download does. */
    private fun serving(bytes: ByteArray, fetches: MutableList<Long>): suspend () -> Unit = {
        val have = part.length()
        fetches += have
        part.appendBytes(bytes.copyOfRange(have.toInt().coerceAtMost(bytes.size), bytes.size))
    }

    private fun run(fetch: suspend () -> Unit) = runBlocking {
        VerifiedDownload.run(part, good.size.toLong(), hash, "parakeet/decoder.int8.onnx", fetch)
    }

    @Test fun aWholeDownloadThatChecksOut() {
        val fetches = mutableListOf<Long>()
        run(serving(good, fetches))
        assertEquals(listOf(0L), fetches)
        assertTrue(part.readBytes().contentEquals(good))
    }

    @Test fun aCompletePartOnlyNeedsChecking() {
        part.writeBytes(good)
        run { fail("nothing is left to fetch") }
        assertTrue(part.readBytes().contentEquals(good))
    }

    @Test fun aResumedDownloadIsFinished() {
        part.writeBytes(good.copyOf(5))
        val fetches = mutableListOf<Long>()
        run(serving(good, fetches))
        assertEquals(listOf(5L), fetches)
        assertTrue(part.readBytes().contentEquals(good))
    }

    /** What 0.10.0 can leave: the start of a file the server has since replaced. It's fetched again, whole, once. */
    @Test fun badBytesFromAnEarlierRunAreFetchedAgainWhole() {
        part.writeBytes("XXXXX".toByteArray())
        val fetches = mutableListOf<Long>()
        run(serving(good, fetches))
        assertEquals(listOf(5L, 0L), fetches)
        assertTrue(part.readBytes().contentEquals(good))
    }

    @Test fun aCompleteButBadPartIsFetchedAgain() {
        part.writeBytes(ByteArray(good.size) { 'X'.code.toByte() })
        val fetches = mutableListOf<Long>()
        run(serving(good, fetches))
        assertEquals(listOf(0L), fetches)
        assertTrue(part.readBytes().contentEquals(good))
    }

    /** The server's file has the right size and other bytes, as Parakeet v2's decoder did: no second try. */
    @Test fun aWholeDownloadOfAnotherFileFailsAtOnce() {
        val other = ByteArray(good.size) { 'X'.code.toByte() }
        val fetches = mutableListOf<Long>()
        try {
            run(serving(other, fetches))
            fail("expected WrongFileException")
        } catch (e: WrongFileException) {
            assertEquals("parakeet/decoder.int8.onnx", e.path)
            assertTrue(e.message, "newer version of Longhand" in e.message.orEmpty())
        }
        assertEquals(listOf(0L), fetches)
        assertFalse(part.exists())
    }

    @Test fun aResumedDownloadOfAnotherFileFailsAfterOneWholeTry() {
        part.writeBytes(good.copyOf(5))
        val other = ByteArray(good.size) { 'X'.code.toByte() }
        val fetches = mutableListOf<Long>()
        try {
            run(serving(other, fetches))
            fail("expected WrongFileException")
        } catch (_: WrongFileException) {
        }
        assertEquals(listOf(5L, 0L), fetches)
        assertFalse(part.exists())
    }

    /** A longer file than expected, as a one-byte-shorter expectation would see Parakeet v3's old decoder. */
    @Test fun aLongerFileIsTheWrongFile() {
        val fetches = mutableListOf<Long>()
        try {
            run(serving(good + good, fetches))
            fail("expected WrongFileException")
        } catch (_: WrongFileException) {
        }
        assertEquals(listOf(0L), fetches)
    }

    /** A connection that closes early isn't a wrong file: the bytes stay, and the next run resumes. */
    @Test fun aDownloadThatStopsEarlyKeepsItsBytes() {
        try {
            run { part.appendBytes(good.copyOf(7)) }
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(e is WrongFileException)
        }
        assertEquals(7L, part.length())
        val fetches = mutableListOf<Long>()
        run(serving(good, fetches))
        assertEquals(listOf(7L), fetches)
    }

    @Test fun aFetchThatThrowsKeepsItsBytes() {
        try {
            run {
                part.appendBytes(good.copyOf(3))
                throw IOException("connection reset")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        assertEquals(3L, part.length())
    }

    @Test fun theWholeSizeInAContentRange() {
        assertEquals(1000L, VerifiedDownload.wholeSize("bytes 100-999/1000"))
        assertEquals(7_257_753L, VerifiedDownload.wholeSize("bytes 4096-7257752/7257753"))
        assertEquals(-1L, VerifiedDownload.wholeSize("bytes 100-999/*"))
        assertEquals(-1L, VerifiedDownload.wholeSize("bytes 100-999"))
        assertEquals(-1L, VerifiedDownload.wholeSize(null))
    }

    @Test fun whenTheFolderCheckStartsADownload() {
        // Day to day: only an improved file for a language that works, and not again after it failed.
        assertTrue(Work.downloadDue(missing = true, update = true, failed = false, newVersion = false))
        assertFalse(Work.downloadDue(missing = true, update = true, failed = true, newVersion = false))
        assertFalse(Work.downloadDue(missing = true, update = false, failed = false, newVersion = false))
        assertFalse(Work.downloadDue(missing = true, update = false, failed = true, newVersion = false))
        // A new version of the app: whatever the chosen language is missing, even after a failure.
        assertTrue(Work.downloadDue(missing = true, update = false, failed = true, newVersion = true))
        assertTrue(Work.downloadDue(missing = true, update = false, failed = false, newVersion = true))
        assertTrue(Work.downloadDue(missing = true, update = true, failed = true, newVersion = true))
        // Nothing missing, nothing to do.
        for (update in listOf(false, true)) for (failed in listOf(false, true)) for (new in listOf(false, true)) {
            assertFalse(Work.downloadDue(missing = false, update = update, failed = failed, newVersion = new))
        }
    }
}
