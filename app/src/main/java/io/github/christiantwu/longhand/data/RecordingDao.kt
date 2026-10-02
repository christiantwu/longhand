package io.github.christiantwu.longhand.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.christiantwu.longhand.engine.VoiceMath
import kotlinx.coroutines.flow.Flow

/** A list row: the recording plus the start of its transcript, for calls without a summary. */
data class CallRow(@Embedded val rec: Recording, val snippet: String?)

@Dao
interface RecordingDao {

    @Query(
        """SELECT r.*, (SELECT s.text FROM segments s WHERE s.recordingId = r.id ORDER BY s.startMs LIMIT 1) AS snippet
           FROM recordings r ORDER BY r.lastModified DESC"""
    )
    fun observeRows(): Flow<List<CallRow>>

    @Query(
        """SELECT r.*, (SELECT s.text FROM segments s WHERE s.recordingId = r.id ORDER BY s.startMs LIMIT 1) AS snippet
           FROM recordings r
           WHERE r.displayName LIKE '%' || :q || '%'
              OR r.contactName LIKE '%' || :q || '%'
              OR r.phoneNumber LIKE '%' || :q || '%'
              OR r.topic LIKE '%' || :q || '%'
              OR r.summary LIKE '%' || :q || '%'
              OR r.id IN (SELECT recordingId FROM segments WHERE text LIKE '%' || :q || '%')
           ORDER BY r.lastModified DESC"""
    )
    fun searchRows(q: String): Flow<List<CallRow>>



    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Query("SELECT * FROM recordings")
    suspend fun all(): List<Recording>

    /** Oldest first, so a backlog is worked through in call order; [since] limits it to recent calls. */
    @Query(
        """SELECT * FROM recordings WHERE status = 'PENDING' AND (lastModified >= :since OR requested = 1)
           ORDER BY requested DESC, lastModified ASC LIMIT 1"""
    )
    suspend fun nextPending(since: Long = 0): Recording?

    @Query("SELECT COUNT(*) FROM recordings WHERE status = 'PENDING' AND (lastModified >= :since OR requested = 1)")
    suspend fun pendingCount(since: Long = 0): Int

    /** Recordings the user asked for whose transcript or summary is still to come. */
    @Query("SELECT COUNT(*) FROM recordings WHERE requested = 1 AND (status = 'PENDING' OR summaryStatus = 'PENDING')")
    suspend fun requestedCount(): Int

    @Query("UPDATE recordings SET requested = 1 WHERE id IN (:ids)")
    suspend fun request(ids: List<Long>)

    /**
     * "Transcribe now": every recording waiting, older calls included, and the first summaries of
     * new calls. Summaries written again for calls already announced (after a redo, voice
     * matching or a correction, or the backlog after the model download) wait for the charger.
     */
    @Query("UPDATE recordings SET requested = 1 WHERE status = 'PENDING' OR (summaryStatus = 'PENDING' AND announced = 0)")
    suspend fun requestAllWaiting()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(recordings: List<Recording>)

    /** Only the file columns: the worker may be changing the status at the same moment. */
    @Query("UPDATE recordings SET sizeBytes = :size, lastModified = :modified, displayName = :name WHERE id = :id")
    suspend fun updateFileInfo(id: Long, size: Long, modified: Long, name: String)

    @Query("UPDATE recordings SET status = 'PROCESSING', progress = 0, error = NULL, attempts = attempts + 1 WHERE id = :id")
    suspend fun markProcessing(id: Long)

    /** @return 0 when the recording no longer exists (it was deleted). */
    @Query("UPDATE recordings SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float): Int

    /** Worker was stopped cleanly (e.g. charger unplugged): retry later without counting it. */
    @Query("UPDATE recordings SET status = 'PENDING', progress = 0, attempts = MAX(attempts - 1, 0) WHERE id = :id")
    suspend fun markInterrupted(id: Long)

    @Query("UPDATE recordings SET status = 'FAILED', progress = 0, error = :error, requested = 0 WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    /** Queue again from scratch (manual retry, or the file changed). */
    @Query("UPDATE recordings SET status = 'PENDING', progress = 0, attempts = 0, error = NULL WHERE id IN (:ids)")
    suspend fun requeue(ids: List<Long>)

    @Query("UPDATE recordings SET status = 'PENDING', progress = 0, attempts = 0, error = NULL, requested = :requested WHERE status = 'SKIPPED'")
    suspend fun requeueSkipped(requested: Boolean)

    /**
     * Rows left PROCESSING mean the process died mid-transcription (e.g. a native crash or
     * out-of-memory kill). Retry them, but give up after three attempts.
     */
    @Transaction
    suspend fun recoverInterrupted() {
        failCrashLoops()
        resetProcessing()
    }

    @Query("UPDATE recordings SET status = 'FAILED', error = 'Stopped unexpectedly 3 times (out of memory?)', requested = 0 WHERE status = 'PROCESSING' AND attempts >= 3")
    suspend fun failCrashLoops()

    @Query("UPDATE recordings SET status = 'PENDING', progress = 0 WHERE status = 'PROCESSING'")
    suspend fun resetProcessing()

    @Query("SELECT * FROM segments WHERE recordingId = :id ORDER BY startMs")
    fun observeSegments(id: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE recordingId = :id ORDER BY startMs")
    suspend fun segments(id: Long): List<Segment>

    @Query("DELETE FROM segments WHERE recordingId = :id")
    suspend fun deleteSegments(id: Long)

    @Insert
    suspend fun insertSegments(segments: List<Segment>)

    /**
     * With [keepSummary], a previous summary's text stays on show until the new one replaces it
     * (or, if the new one fails, for good: only for a redo, whose audio is the same).
     * A [redo] keeps the announcement state: a call announced before isn't announced again, and
     * one still waiting for its first summary is announced once the new one is in.
     */
    @Query(
        """UPDATE recordings SET status = 'DONE', progress = 1, error = NULL, attempts = 0, durationMs = :durationMs,
           transcribedAt = :at, processingMs = :processingMs, ownerSpeaker = :ownerSpeaker, ownerManual = 0,
           topic = CASE WHEN :keepSummary THEN topic ELSE NULL END,
           summary = CASE WHEN :keepSummary THEN summary ELSE NULL END,
           followUps = CASE WHEN :keepSummary THEN followUps ELSE NULL END,
           summaryStatus = :summaryStatus, summaryAttempts = 0, pipeline = :pipeline,
           announced = CASE WHEN :redo THEN announced ELSE 0 END,
           requested = CASE WHEN :summaryStatus = 'PENDING' THEN requested ELSE 0 END WHERE id = :id"""
    )
    suspend fun markDone(
        id: Long, durationMs: Long, at: Long, processingMs: Long, ownerSpeaker: Int?, summaryStatus: SummaryStatus,
        keepSummary: Boolean, pipeline: Int, redo: Boolean,
    )

    /**
     * Stores a finished transcript with its speakers' voice fingerprints, and queues a new summary
     * when [summarize] is true. Speakers are numbered afresh, so names given to the previous
     * transcript's speakers are dropped, and so are their links to known voices and the
     * suggestions turned down for them. A [redo] of an old transcript (the same audio) keeps its
     * summary until the new one replaces it, unless no speech was found this time, and isn't
     * announced again.
     */
    @Transaction
    suspend fun saveTranscript(
        id: Long,
        segments: List<Segment>,
        voices: List<SpeakerVoice>,
        durationMs: Long,
        processingMs: Long,
        ownerSpeaker: Int?,
        summarize: Boolean,
        redo: Boolean = false,
    ) {
        // Deleted while it was being transcribed: nothing to save it to.
        if (get(id) == null) return
        deleteSegments(id)
        insertSegments(segments)
        deleteVoices(id)
        insertVoices(voices)
        deleteSpeakerNames(id)
        deleteVoiceSamples(id)
        deleteVoiceRejections(id)
        deleteUnusedKnownVoices()
        markDone(id, durationMs, System.currentTimeMillis(), processingMs, ownerSpeaker,
            if (summarize && segments.isNotEmpty()) SummaryStatus.PENDING else SummaryStatus.NONE,
            keepSummary = redo && segments.isNotEmpty(), pipeline = Pipeline.CURRENT, redo = redo)
    }

    // ---- redoing transcripts made by an older pipeline ----

    /** The newest transcript made by an older pipeline whose redo hasn't failed (or crashed three times). */
    @Query("SELECT * FROM recordings WHERE status = 'DONE' AND pipeline < :current AND attempts < 3 ORDER BY lastModified DESC LIMIT 1")
    suspend fun nextRedo(current: Int = Pipeline.CURRENT): Recording?

    @Query("SELECT COUNT(*) FROM recordings WHERE status = 'DONE' AND pipeline < :current AND attempts < 3")
    suspend fun redoCount(current: Int = Pipeline.CURRENT): Int

    /** Counted before a redo starts, so one that keeps crashing the app is given up on. */
    @Query("UPDATE recordings SET attempts = attempts + 1 WHERE id = :id")
    suspend fun startRedo(id: Long)

    /** The job was stopped (charger unplugged, say): not counted against the recording. */
    @Query("UPDATE recordings SET attempts = MAX(attempts - 1, 0) WHERE id = :id")
    suspend fun interruptRedo(id: Long)

    /**
     * A redo that fails keeps the old transcript and isn't tried again. The transcript stays marked
     * as made by the older pipeline, so its voices are never trusted ("Transcribe again" resets it).
     */
    @Query("UPDATE recordings SET attempts = 3 WHERE id = :id")
    suspend fun giveUpRedo(id: Long)

    // ---- "call transcribed" notifications ----

    /** New transcripts whose summary, if any, is finished. */
    @Query("SELECT * FROM recordings WHERE status = 'DONE' AND announced = 0 AND summaryStatus NOT IN ('PENDING', 'PROCESSING') ORDER BY lastModified")
    suspend fun toAnnounce(): List<Recording>

    @Query("UPDATE recordings SET announced = 1 WHERE id IN (:ids)")
    suspend fun markAnnounced(ids: List<Long>)

    /**
     * Deletes a call's row; its transcript, voices, speaker names and links to known voices go with
     * it (foreign keys), and so does a known voice learned only from this call.
     */
    @Transaction
    suspend fun delete(id: Long) {
        deleteRow(id)
        deleteUnusedKnownVoices()
    }

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /** New transcripts whose summary is still to come, which their announcement waits for. */
    @Query("SELECT COUNT(*) FROM recordings WHERE status = 'DONE' AND announced = 0 AND summaryStatus IN ('PENDING', 'PROCESSING')")
    suspend fun unannouncedSummariesWaiting(): Int

    // ---- caller ----

    @Query("SELECT * FROM recordings WHERE callerChecked = 0 AND callerManual = 0")
    suspend fun needingCallerLookup(): List<Recording>

    @Query(
        """UPDATE recordings SET phoneNumber = :number, contactName = :name, contactLookupKey = :lookupKey,
           callDirection = :direction, callerChecked = 1, callerManual = :manual WHERE id = :id"""
    )
    suspend fun setCaller(id: Long, number: String?, name: String?, lookupKey: String?, direction: Int?, manual: Boolean)

    /** An automatic lookup's result; leaves the row alone if the user picked the caller meanwhile. */
    @Query(
        """UPDATE recordings SET phoneNumber = :number, contactName = :name, contactLookupKey = :lookupKey,
           callDirection = :direction, callerChecked = 1 WHERE id = :id AND callerManual = 0"""
    )
    suspend fun setLookedUpCaller(id: Long, number: String?, name: String?, lookupKey: String?, direction: Int?)


    /** Look callers up again, e.g. after the user grants call log or contacts access. */
    @Query("UPDATE recordings SET callerChecked = 0 WHERE callerManual = 0")
    suspend fun resetCallerChecks()

    // ---- voices ----

    @Query("SELECT * FROM speaker_voices WHERE recordingId = :id")
    suspend fun voices(id: Long): List<SpeakerVoice>

    @Query("SELECT DISTINCT recordingId FROM speaker_voices")
    suspend fun recordingsWithVoices(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoices(voices: List<SpeakerVoice>)

    @Query("DELETE FROM speaker_voices WHERE recordingId = :id")
    suspend fun deleteVoices(id: Long)

    /** Finished transcripts without stored voices (they need their audio re-analysed). */
    @Query("SELECT * FROM recordings WHERE status = 'DONE' AND pipeline >= :current AND id NOT IN (SELECT recordingId FROM speaker_voices)")
    suspend fun doneWithoutVoices(current: Int = Pipeline.CURRENT): List<Recording>

    /** From voice matching: never replaces an owner the user set by hand. */
    @Query("UPDATE recordings SET ownerSpeaker = :speaker WHERE id = :id AND ownerManual = 0")
    suspend fun setOwnerFromVoice(id: Long, speaker: Int?)

    /**
     * Voice matching for one call: which of its stored voices is the owner's [profile]. The voices
     * are read in the same transaction as the row, so a redo saved meanwhile can't mix numberings.
     * A summary written (or being written) with another owner says "Speaker 1" where it should say
     * "you", so it's written again. Transcripts from an older pipeline, whose speakers may mix two
     * people, are left to their redo.
     */
    @Transaction
    suspend fun applyOwnerFromVoice(id: Long, profile: FloatArray) {
        val rec = get(id) ?: return
        if (rec.pipeline < Pipeline.CURRENT || rec.ownerManual) return
        val voices = voices(id)
        if (voices.isEmpty()) return
        val speaker = VoiceMath.pickOwner(profile, voices.associate { it.speaker to VoiceMath.fromBytes(it.embedding) })
        if (rec.ownerSpeaker == speaker) return
        setOwnerFromVoice(id, speaker)
        if (rec.summaryStatus == SummaryStatus.DONE || rec.summaryStatus == SummaryStatus.PROCESSING) setSummaryStatus(id, SummaryStatus.PENDING)
    }

    @Query("UPDATE recordings SET ownerSpeaker = :speaker, ownerManual = 1 WHERE id = :id")
    suspend fun setOwnerByHand(id: Long, speaker: Int?)

    // ---- summaries ----

    @Query(
        """SELECT * FROM recordings WHERE summaryStatus = 'PENDING' AND (lastModified >= :since OR requested = 1)
           ORDER BY requested DESC, lastModified DESC LIMIT 1"""
    )
    suspend fun nextSummaryPending(since: Long = 0): Recording?

    @Query("SELECT COUNT(*) FROM recordings WHERE summaryStatus = 'PENDING' AND (lastModified >= :since OR requested = 1)")
    suspend fun summaryPendingCount(since: Long = 0): Int

    /** A finished or failed summary ends the user's request; a new one starts with fresh attempts. */
    @Query(
        """UPDATE recordings SET summaryStatus = :status,
           requested = CASE WHEN :status IN ('DONE', 'FAILED', 'NONE') THEN 0 ELSE requested END,
           summaryAttempts = CASE WHEN :status = 'PENDING' THEN 0 ELSE summaryAttempts END WHERE id = :id"""
    )
    suspend fun setSummaryStatus(id: Long, status: SummaryStatus)

    @Query("UPDATE recordings SET summaryStatus = 'PROCESSING', summaryAttempts = summaryAttempts + 1 WHERE id = :id")
    suspend fun markSummaryProcessing(id: Long)

    /**
     * A finished summary run. If the summary was queued again while it ran (a speaker was named,
     * say), the text is still shown but the summary stays queued, to be written again.
     */
    @Query(
        """UPDATE recordings SET topic = :topic, summary = :summary, followUps = :followUps,
           summaryStatus = CASE WHEN summaryStatus = 'PROCESSING' THEN 'DONE' ELSE summaryStatus END,
           requested = CASE WHEN summaryStatus = 'PROCESSING' THEN 0 ELSE requested END WHERE id = :id"""
    )
    suspend fun saveSummary(id: Long, topic: String, summary: String, followUps: String)

    /**
     * A summary run that produced nothing, unless the summary was queued again while it ran. Back
     * to PENDING (the job was stopped) doesn't count as an attempt.
     */
    @Query(
        """UPDATE recordings SET summaryStatus = :status,
           summaryAttempts = CASE WHEN :status = 'PENDING' THEN 0 ELSE summaryAttempts END,
           requested = CASE WHEN :status IN ('DONE', 'FAILED', 'NONE') THEN 0 ELSE requested END
           WHERE id = :id AND summaryStatus = 'PROCESSING'"""
    )
    suspend fun endSummaryRun(id: Long, status: SummaryStatus)

    /** Queue every finished transcript that has no summary yet (e.g. right after the model is installed). */
    @Query("UPDATE recordings SET summaryStatus = 'PENDING', summaryAttempts = 0 WHERE status = 'DONE' AND summaryStatus IN ('NONE', 'FAILED') AND id IN (SELECT recordingId FROM segments)")
    suspend fun queueMissingSummaries()

    /**
     * Summaries left PROCESSING by a crash (an out-of-memory kill, or a native crash in the
     * model) are retried, but fail after three attempts, as transcriptions do.
     */
    @Transaction
    suspend fun recoverSummaries() {
        failSummaryCrashLoops()
        resetSummaryProcessing()
    }

    @Query("UPDATE recordings SET summaryStatus = 'FAILED', requested = 0 WHERE summaryStatus = 'PROCESSING' AND summaryAttempts >= 3")
    suspend fun failSummaryCrashLoops()

    @Query("UPDATE recordings SET summaryStatus = 'PENDING' WHERE summaryStatus = 'PROCESSING'")
    suspend fun resetSummaryProcessing()

    @Query("SELECT * FROM speaker_names WHERE recordingId = :id")
    fun observeSpeakerNames(id: Long): Flow<List<SpeakerName>>

    @Query("SELECT * FROM speaker_names WHERE recordingId = :id")
    suspend fun speakerNames(id: Long): List<SpeakerName>

    @Upsert
    suspend fun upsertSpeakerNames(names: List<SpeakerName>)

    @Query("DELETE FROM speaker_names WHERE recordingId = :id AND speaker = :speaker")
    suspend fun deleteSpeakerName(id: Long, speaker: Int)

    @Query("DELETE FROM speaker_names WHERE recordingId = :id")
    suspend fun deleteSpeakerNames(id: Long)

    // ---- known voices ("Recognise voices"; the rest is in VoiceDao) ----

    @Query("DELETE FROM voice_samples WHERE recordingId = :id")
    suspend fun deleteVoiceSamples(id: Long)

    @Query("DELETE FROM voice_rejections WHERE recordingId = :id")
    suspend fun deleteVoiceRejections(id: Long)

    /** A known voice left without samples is forgotten, as in VoiceDao.deleteUnused. */
    @Query("DELETE FROM known_voices WHERE id NOT IN (SELECT voiceId FROM voice_samples)")
    suspend fun deleteUnusedKnownVoices()
}
