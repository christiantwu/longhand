package io.github.christiantwu.longhand

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.Pipeline
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingDao
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.Shrink
import io.github.christiantwu.longhand.data.fileTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The statements that guard shrinking a recording, run by Room on an in-memory SQLite database: the row names the WAV or
 * its copy, never a mix, and a shrunk call keeps its transcript.
 */
class ShrinkDaoTest {

    private lateinit var db: AppDatabase
    private val dao: RecordingDao get() = db.recordings()

    @Before fun open() {
        // Room asks the context only for things these settings leave out (the journal mode, a file to open).
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .build()
    }

    @After fun close() = db.close()

    // Fictional calls: the names follow the GrapheneOS Phone app's pattern, with 555 numbers.
    private val wavName = "CallRecord_20261004-101500_+15555550123.wav"
    private val copyName = "CallRecord_20261004-101500_+15555550123.m4a"
    private val wavUri = "content://storage/tree/CallRecordings/document/$wavName"
    private val copyUri = "content://storage/tree/CallRecordings/document/$copyName"

    private fun wav(id: Long = 1, uri: String = wavUri, name: String = wavName, modified: Long = 1_000) = Recording(
        id = id, documentUri = uri, displayName = name, sizeBytes = 115_200_000, lastModified = modified,
        status = RecordingStatus.DONE, pipeline = Pipeline.CURRENT,
    )

    private fun insert(vararg recs: Recording) = runBlocking { dao.insertAll(recs.toList()) }

    private fun save(id: Long = 1, from: String = wavUri, fromSize: Long = 115_200_000, uri: String = copyUri) = runBlocking {
        dao.saveShrunk(id, from, fromSize, uri, copyName, 7_200_000, 5_000)
    }

    private fun row(id: Long = 1) = runBlocking { dao.get(id) }

    @Test fun savingTheCopyPointsTheRowAtItAndKeepsTheCallsTime() {
        insert(wav())
        assertEquals(Shrink.Saved.DONE, save())
        val shrunk = row()!!
        assertEquals(copyUri, shrunk.documentUri)
        assertEquals(copyName, shrunk.displayName)
        assertEquals(7_200_000L, shrunk.sizeBytes)
        assertEquals(115_200_000L, shrunk.originalBytes)
        // The call's time stays; the copy's own time is what the folder check compares.
        assertEquals(1_000L, shrunk.lastModified)
        assertEquals(5_000L, shrunk.fileTime)
        // Never shrunk twice.
        assertEquals(Shrink.Saved.CHANGED, save(from = copyUri, fromSize = 7_200_000))
    }

    @Test fun aCallChangedMeanwhileIsLeftAsItWas() {
        insert(wav())
        val before = row()
        // Another file, another size, or no such call.
        assertEquals(Shrink.Saved.CHANGED, save(from = "content://storage/tree/CallRecordings/document/other.wav"))
        assertEquals(Shrink.Saved.CHANGED, save(fromSize = 115_200_001))
        assertEquals(Shrink.Saved.CHANGED, save(id = 99))
        assertEquals(before, row())
        // Queued again ("Transcribe again"), or being transcribed: its new transcript is made from the WAV.
        runBlocking { dao.requeue(listOf(1)) }
        assertEquals(Shrink.Saved.CHANGED, save())
        runBlocking { dao.markProcessing(1) }
        assertEquals(Shrink.Saved.CHANGED, save())
        assertEquals(wavUri, row()!!.documentUri)
        assertNull(row()!!.originalBytes)
    }

    @Test fun aCopyWhoseUriAnotherCallHasIsGivenUpOn() {
        // A transcript kept for an .m4a of that name that has since disappeared.
        insert(wav(), wav(id = 2, uri = copyUri, name = copyName))
        val before = row()
        assertEquals(Shrink.Saved.URI_TAKEN, save())
        assertEquals(before, row())
        // The same every time, so the worker gives up instead of trying again.
        assertEquals(Shrink.Saved.URI_TAKEN, save())
    }

    @Test fun aCopyNameOrUriAnotherCallHasIsFoundBeforeTheCopyIsWritten() {
        insert(wav())
        runBlocking {
            // Only this call's own row: nothing in the way.
            assertEquals(false, dao.copyNameTaken(1, copyUri, copyName))
            assertEquals(false, dao.copyNameTaken(1, null, copyName))
        }
        // A transcript kept for an .m4a at the copy's URI that has since disappeared.
        insert(wav(id = 2, uri = copyUri, name = copyName))
        runBlocking {
            assertEquals(true, dao.copyNameTaken(1, copyUri, copyName))
            // Found by its name too, when the storage's URIs can't be foretold.
            assertEquals(true, dao.copyNameTaken(1, null, copyName))
        }
        runBlocking { dao.delete(2) }
        // At another URI, under the copy's name in another case, which the phone's storage takes for the same name.
        insert(wav(id = 3, uri = "content://storage/tree/CallRecordings/document/3", name = "callrecord_20261004-101500_+15555550123.M4A"))
        runBlocking {
            assertEquals(true, dao.copyNameTaken(1, copyUri, copyName))
            assertEquals(false, dao.copyNameTaken(1, copyUri, "CallRecord_20261004-111500_+15555550188.m4a"))
        }
    }

    @Test fun undoingNamesTheWavAgainJustAsItWas() {
        val original = wav()
        insert(original)
        save()
        // Only while the row names that copy.
        assertEquals(0, runBlocking { dao.undoShrunk(1, "content://storage/tree/CallRecordings/document/other.m4a", wavUri, wavName) })
        assertEquals(copyUri, row()!!.documentUri)
        assertEquals(1, runBlocking { dao.undoShrunk(1, copyUri, wavUri, wavName) })
        assertEquals(original, row())
        assertTrue(Shrink.eligible(row()!!))
        // A WAV never shrunk is left alone.
        assertEquals(0, runBlocking { dao.undoShrunk(1, wavUri, wavUri, wavName) })
        assertEquals(original, row())
    }

    @Test fun aShrunkCallKeepsItsTimeWhenItsCopyChanges() {
        insert(wav(), wav(id = 2, uri = "$wavUri.2", name = "CallRecord_20261004-111500_+15555550188.wav", modified = 2_000))
        save()
        runBlocking {
            dao.updateFileInfo(1, 7_300_000, 9_000, copyName)
            dao.updateFileInfo(2, 116_000_000, 9_500, "CallRecord_20261004-111500_+15555550188.wav")
        }
        val shrunk = row(1)!!
        assertEquals(7_300_000L, shrunk.sizeBytes)
        assertEquals(1_000L, shrunk.lastModified)
        assertEquals(9_000L, shrunk.fileModified)
        // A WAV's time is the file's own, as before.
        val other = row(2)!!
        assertEquals(9_500L, other.lastModified)
        assertNull(other.fileModified)
    }

    @Test fun detectionChecksAShrunkCallsCopyByItsOwnTime() {
        insert(wav())
        save()
        runBlocking {
            // Read when the file had the call's time: it changed since.
            dao.setDetection(1, "en", 30f, 7_200_000, 1_000)
            assertNull(dao.get(1)!!.spokenLanguages)
            dao.setDetection(1, "en", 30f, 7_200_000, 5_000)
            assertEquals("en", dao.get(1)!!.spokenLanguages)
        }
    }

    @Test fun shrunkCallsArentRedoneForANewerPipeline() {
        val older = Pipeline.CURRENT - 1
        insert(
            wav().copy(pipeline = older),
            wav(id = 2, uri = "$wavUri.2", name = "CallRecord_20261004-111500_+15555550188.wav", modified = 2_000).copy(pipeline = older),
        )
        runBlocking {
            assertEquals(2, dao.redoCount())
            assertEquals(2L, dao.nextRedo()?.id)
        }
        // Shrunk, the newer call is only redone by "Transcribe again": a redo would be made from its compressed copy.
        assertEquals(Shrink.Saved.DONE, save(id = 2, from = "$wavUri.2"))
        runBlocking {
            assertEquals(1, dao.redoCount())
            assertEquals(1L, dao.nextRedo()?.id)
        }
        assertEquals(false, Shrink.redoWaiting(row(2)!!))
    }

    @Test fun candidatesAreTranscribedWavsNotShrunkOrGivenUpOn() {
        insert(
            wav(modified = 3_000),
            wav(id = 2, uri = "$wavUri.2", name = "CallRecord_20261003-090000_+15555550188.WAV", modified = 2_000),
            wav(id = 3, uri = "$wavUri.3", name = "CallRecord_20261002-090000_+15555550199.m4a"),
            wav(id = 4, uri = "$wavUri.4", name = "CallRecord_20261001-090000_+15555550142.wav").copy(status = RecordingStatus.PENDING),
            wav(id = 5, uri = "$wavUri.5", name = "CallRecord_20260930-090000_+15555550177.wav"),
        )
        runBlocking {
            dao.giveUpShrink(5)
            // Oldest call first.
            assertEquals(listOf(2L, 1L), dao.shrinkCandidates().map { it.id })
            // A try is counted when it starts, and a clean stop takes it back, never below none.
            dao.startShrink(1)
            dao.startShrink(1)
            dao.interruptShrink(1)
            assertEquals(1, dao.get(1)!!.shrinkAttempts)
            dao.interruptShrink(2)
            assertEquals(0, dao.get(2)!!.shrinkAttempts)
            repeat(Shrink.MAX_ATTEMPTS - 1) { dao.startShrink(1) }
            assertEquals(listOf(2L), dao.shrinkCandidates().map { it.id })
        }
        save(id = 2, from = "$wavUri.2")
        assertEquals(emptyList<Long>(), runBlocking { dao.shrinkCandidates().map { it.id } })
    }
}
