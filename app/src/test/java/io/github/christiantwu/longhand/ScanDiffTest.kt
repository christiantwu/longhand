package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.ListedFile
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.ScanDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDiffTest {
    private val now = 10_000_000L
    private fun rec(id: Long, uri: String, size: Long, mod: Long, status: RecordingStatus) =
        Recording(id = id, documentUri = uri, displayName = uri, sizeBytes = size, lastModified = mod, status = status)

    @Test fun addsNewStableFiles() {
        val plan = ScanDiff.plan(emptyList(), listOf(ListedFile("a", "a.mp3", 10, now - 120_000)), now)
        assertEquals(listOf("a"), plan.newFiles.map { it.uri })
    }

    @Test fun waitsForFilesStillBeingRecorded() {
        val plan = ScanDiff.plan(emptyList(), listOf(ListedFile("a", "a.mp3", 10, now - 5_000)), now)
        assertTrue(plan.newFiles.isEmpty())
    }

    @Test fun ignoresUnchangedKnownFiles() {
        val plan = ScanDiff.plan(
            listOf(rec(1, "a", 10, now - 120_000, RecordingStatus.DONE)),
            listOf(ListedFile("a", "a.mp3", 10, now - 120_000)), now,
        )
        assertTrue(plan.newFiles.isEmpty() && plan.requeueIds.isEmpty() && plan.changed.isEmpty())
    }

    @Test fun requeuesChangedDoneOrFailedButNotSkippedOrPending() {
        val existing = listOf(
            rec(1, "done", 10, now - 200_000, RecordingStatus.DONE),
            rec(2, "failed", 10, now - 200_000, RecordingStatus.FAILED),
            rec(3, "skipped", 10, now - 200_000, RecordingStatus.SKIPPED),
            rec(4, "pending", 10, now - 200_000, RecordingStatus.PENDING),
        )
        val listed = existing.map { ListedFile(it.documentUri, it.documentUri, 20, now - 100_000) }
        val plan = ScanDiff.plan(existing, listed, now)
        assertEquals(listOf(1L, 2L), plan.requeueIds)
        assertEquals(listOf(1L, 2L, 3L, 4L), plan.changed.map { it.first })
        assertTrue(plan.changed.all { it.second.size == 20L })
    }

    @Test fun aCallEditedByHandIsntRequeuedWhenItsFileChanges() {
        // A new transcript would replace the edits without asking; only the file details are updated.
        val edited = rec(1, "edited", 10, now - 200_000, RecordingStatus.DONE).copy(editedAt = now - 300_000)
        val plain = rec(2, "plain", 10, now - 200_000, RecordingStatus.DONE)
        val listed = listOf(edited, plain).map { ListedFile(it.documentUri, it.documentUri, 20, now - 100_000) }
        val plan = ScanDiff.plan(listOf(edited, plain), listed, now)
        assertEquals(listOf(2L), plan.requeueIds)
        assertEquals(listOf(1L, 2L), plan.changed.map { it.first })
    }

    @Test fun aShrunkCallIsComparedWithItsCopysOwnTime() {
        // Its row keeps the call's time; the compressed copy was written later, and that's the time the folder lists.
        val shrunk = rec(1, "copy", 7_000, now - 900_000, RecordingStatus.DONE)
            .copy(fileModified = now - 120_000, originalBytes = 115_000)
        val same = ScanDiff.plan(listOf(shrunk), listOf(ListedFile("copy", "c.m4a", 7_000, now - 120_000)), now)
        assertTrue(same.newFiles.isEmpty() && same.requeueIds.isEmpty() && same.changed.isEmpty())
        // The copy itself changing is a change like any other.
        val changed = ScanDiff.plan(listOf(shrunk), listOf(ListedFile("copy", "c.m4a", 7_000, now - 100_000)), now)
        assertEquals(listOf(1L), changed.requeueIds)
    }

    @Test fun aCopyJustWrittenIsLeftUntilItHasSettled() {
        // Written moments ago, before its row names it: not yet a recording to add, even if a check saw it.
        val plan = ScanDiff.plan(emptyList(), listOf(ListedFile("copy", "c.m4a", 7_000, now - 2_000)), now)
        assertTrue(plan.newFiles.isEmpty())
    }

    @Test fun skipsRecordingsOlderThanSetupCutoff() {
        val old = ListedFile("a", "a.mp3", 10, 1_000)
        val new = ListedFile("b", "b.mp3", 10, 5_000)
        assertEquals(RecordingStatus.SKIPPED, ScanDiff.initialStatus(old, skipBefore = 2_000))
        assertEquals(RecordingStatus.PENDING, ScanDiff.initialStatus(new, skipBefore = 2_000))
        assertEquals(RecordingStatus.PENDING, ScanDiff.initialStatus(old, skipBefore = 0))
    }

    @Test fun afterCallCheckWaitsLongEnoughForTheRecordingToCountAsFinished() {
        // The scan skips files modified within STABLE_AFTER_MS; the after-call check must come later.
        assertTrue(io.github.christiantwu.longhand.work.Work.AFTER_CALL_DELAY_MS > ScanDiff.STABLE_AFTER_MS + 10_000)
    }

    @Test fun recognisesAudioFiles() {
        assertTrue(ScanDiff.isAudio("call.MP3", null))
        assertTrue(ScanDiff.isAudio("x", "audio/mpeg"))
        assertTrue(!ScanDiff.isAudio("notes.txt", "text/plain"))
    }
}
