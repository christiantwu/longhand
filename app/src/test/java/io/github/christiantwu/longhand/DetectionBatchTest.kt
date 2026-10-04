package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.work.TranscribeWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which waiting calls have their language detected in one go, before transcription carries on. */
class DetectionBatchTest {

    private fun rec(id: Long) = Recording(id = id, documentUri = "u$id", displayName = "c$id", sizeBytes = 1, lastModified = id,
        status = RecordingStatus.PENDING)

    private fun ids(batch: List<Recording>) = batch.map { it.id }

    @Test fun aFewCallsAtATimeInTheOrderTheyreTaken() {
        // A call asked for goes first, then the backlog oldest first, as nextPending takes them: a call asked for
        // later waits for no more than these.
        val asked = rec(20)
        val waiting = listOf(asked) + (1L..19L).map(::rec)
        assertEquals(8, TranscribeWorker.DETECT_BATCH)
        assertEquals(listOf(20L) + (1L..7L), ids(TranscribeWorker.detectionBatch(asked, waiting, emptySet())))
        assertEquals(listOf(30L), ids(TranscribeWorker.detectionBatch(rec(30), listOf(rec(30)), emptySet())))
    }

    @Test fun aCallAskedForBringsOnlyOthersAskedFor() {
        val asked = rec(40).copy(requested = true)
        val alsoAsked = rec(41).copy(requested = true)
        val waiting = listOf(asked, alsoAsked) + (1L..19L).map(::rec)
        assertEquals(listOf(40L, 41L), ids(TranscribeWorker.detectionBatch(asked, waiting, emptySet())))
    }

    @Test fun eachCallIsTriedOnceAPass() {
        val waiting = (1L..5L).map(::rec)
        assertEquals(listOf(6L, 3L, 5L), ids(TranscribeWorker.detectionBatch(rec(6), waiting, tried = setOf(1L, 2L, 4L))))
    }
}
