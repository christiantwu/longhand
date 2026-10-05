package io.github.christiantwu.longhand.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import io.github.christiantwu.longhand.engine.Corrections
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.VoiceMath
import kotlinx.coroutines.flow.Flow

/**
 * A list row: the recording plus the start of its transcript, for calls without a summary. In search
 * results, also the first transcript line that matches and when it starts.
 */
data class CallRow(
    @Embedded val rec: Recording,
    val snippet: String?,
    val matchText: String?,
    val matchMs: Long?,
    /** The search these rows were found for, so a newer one typed meanwhile isn't highlighted in them. */
    val matchQuery: String?,
)

/** A line common corrections may change (not edited by hand), with what the recogniser wrote if that differs. */
data class CorrectableLine(val id: Long, val recordingId: Long, val text: String, val recognized: String?) {
    /** What corrections start from: the recogniser's text. */
    val source: String get() = recognized ?: text
}

/**
 * A call as it was before a change by hand (RecordingDao.changeLines, mergeSpeakers), to put back for Undo: its lines,
 * speakers' names, voice fingerprints and links to known voices, and from [recording] the owner, when it was edited
 * and the summary's state.
 */
class CallSnapshot(
    val recording: Recording,
    val segments: List<Segment>,
    val names: List<SpeakerName>,
    val voices: List<SpeakerVoice>,
    val samples: List<VoiceSample>,
    val rejections: List<VoiceRejection>,
)

/** Search text as a LIKE pattern that finds it literally, anywhere: `LIKE :pattern ESCAPE '\'`. */
object SearchPattern {
    fun contains(text: String): String =
        "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}

/** :pattern found in the call itself: its details, summary, follow-ups or speakers' names. */
private const val CALL_MATCHES = """(r.displayName LIKE :pattern ESCAPE '\' OR r.contactName LIKE :pattern ESCAPE '\'
   OR r.phoneNumber LIKE :pattern ESCAPE '\' OR r.topic LIKE :pattern ESCAPE '\' OR r.summary LIKE :pattern ESCAPE '\'
   OR r.followUps LIKE :pattern ESCAPE '\'
   OR EXISTS (SELECT 1 FROM speaker_names n WHERE n.recordingId = r.id AND n.name LIKE :pattern ESCAPE '\'))"""

/**
 * Rows with their first line, and the first line matching :pattern (a [SearchPattern]; NULL matches
 * none), but only when the call itself didn't match: a search for a contact opens their calls at the
 * top, not at a line that happens to say their name.
 */
private const val ROWS_WITH_MATCH = """SELECT r.*,
       (SELECT s.text FROM segments s WHERE s.recordingId = r.id ORDER BY s.startMs LIMIT 1) AS snippet,
       CASE WHEN $CALL_MATCHES THEN NULL ELSE m.text END AS matchText,
       CASE WHEN $CALL_MATCHES THEN NULL ELSE m.startMs END AS matchMs,
       :query AS matchQuery
   FROM recordings r LEFT JOIN segments m ON m.id =
       (SELECT s.id FROM segments s WHERE s.recordingId = r.id AND s.text LIKE :pattern ESCAPE '\'
        ORDER BY s.startMs, s.id LIMIT 1)"""

/** What search looks through: the call's details and summary, the speakers' names and every line said. */
private const val MATCHES = """($CALL_MATCHES OR m.id IS NOT NULL)"""

/** A phone number's digits: numbers are stored as the call log, a file name or a contact wrote them. */
private const val DIGITS =
    """REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(r.phoneNumber, '+', ''), ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')"""

@Dao
interface RecordingDao {

    @Query(
        """SELECT r.*, (SELECT s.text FROM segments s WHERE s.recordingId = r.id ORDER BY s.startMs LIMIT 1) AS snippet,
           NULL AS matchText, NULL AS matchMs, NULL AS matchQuery
           FROM recordings r ORDER BY r.lastModified DESC"""
    )
    fun observeRows(): Flow<List<CallRow>>

    @Query("$ROWS_WITH_MATCH WHERE $MATCHES ORDER BY r.lastModified DESC")
    fun searchRows(pattern: String, query: String): Flow<List<CallRow>>

    /**
     * "Calls with …": calls with the contact called [name] or with the number whose [digits] these
     * are (compared on their last ten, so "+1 555…", "(555) …" and "555…" agree), and calls where a
     * speaker was named [name] (a conference call, say); names ignore case, though SQLite's NOCASE
     * folds only A-Z. A [pattern] narrows them down as [searchRows] does.
     */
    @Query(
        """$ROWS_WITH_MATCH
           WHERE (TRIM(r.contactName) = :name COLLATE NOCASE
              OR (:digits != '' AND r.phoneNumber IS NOT NULL AND SUBSTR($DIGITS, -10) = SUBSTR(:digits, -10))
              OR EXISTS (SELECT 1 FROM speaker_names n WHERE n.recordingId = r.id AND TRIM(n.name) = :name COLLATE NOCASE))
             AND (:pattern IS NULL OR $MATCHES)
           ORDER BY r.lastModified DESC"""
    )
    fun personRows(name: String, digits: String, pattern: String?, query: String?): Flow<List<CallRow>>

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

    /** The first [limit] waiting recordings [nextPending] would take, in its order, with no language chosen or detected yet. */
    @Query(
        """SELECT * FROM recordings WHERE status = 'PENDING' AND (lastModified >= :since OR requested = 1)
           AND pinnedLanguage IS NULL AND spokenLanguages IS NULL ORDER BY requested DESC, lastModified ASC LIMIT :limit"""
    )
    suspend fun pendingToDetect(since: Long, limit: Int): List<Recording>

    /**
     * Counted before detection reads the call: marked detected with nothing found, so it follows Settings. If the app
     * dies detecting it, it's transcribed next time, where its attempts are counted, instead of detected again.
     * [modified] is the file's own time (Recording.fileTime), as for [setDetection].
     */
    @Query(
        """UPDATE recordings SET spokenLanguages = '', speechSeconds = 0
           WHERE id = :id AND spokenLanguages IS NULL AND sizeBytes = :size AND COALESCE(fileModified, lastModified) = :modified"""
    )
    suspend fun startDetection(id: Long, size: Long, modified: Long)

    /** Detection was stopped before it finished ([startDetection]): the call is detected another time. */
    @Query("UPDATE recordings SET spokenLanguages = NULL, speechSeconds = NULL WHERE id = :id AND spokenLanguages = ''")
    suspend fun interruptDetection(id: Long)

    /**
     * What language detection found in the call, unless its file changed since it was read ([size], and [modified], the
     * file's own time: Recording.fileTime).
     */
    @Query(
        """UPDATE recordings SET spokenLanguages = :codes, speechSeconds = :speechSeconds
           WHERE id = :id AND sizeBytes = :size AND COALESCE(fileModified, lastModified) = :modified"""
    )
    suspend fun setDetection(id: Long, codes: String, speechSeconds: Float, size: Long, modified: Long)

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

    /**
     * Only the file columns: the worker may be changing the status at the same moment. The file changed, so the language
     * detected in it is detected again. A shrunk recording keeps the time of the call: [modified] is its copy's own time.
     */
    @Query(
        """UPDATE recordings SET sizeBytes = :size, displayName = :name, spokenLanguages = NULL, speechSeconds = NULL,
           lastModified = CASE WHEN fileModified IS NULL THEN :modified ELSE lastModified END,
           fileModified = CASE WHEN fileModified IS NULL THEN NULL ELSE :modified END WHERE id = :id"""
    )
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

    /** "Transcribe again" was confirmed: the edits are to be replaced, so the call no longer counts as edited. */
    @Query("UPDATE recordings SET editedAt = NULL WHERE id = :id")
    suspend fun forgetEdits(id: Long)

    /** The language chosen for this call in "Transcribe again"; null: it follows Settings. */
    @Query("UPDATE recordings SET pinnedLanguage = :language WHERE id = :id")
    suspend fun setPinnedLanguage(id: Long, language: Models.Language?)

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
           transcribedAt = :at, processingMs = :processingMs, ownerSpeaker = :ownerSpeaker, ownerManual = 0, editedAt = NULL,
           language = :language, languageDetected = :languageDetected,
           topic = CASE WHEN :keepSummary THEN topic ELSE NULL END,
           summary = CASE WHEN :keepSummary THEN summary ELSE NULL END,
           followUps = CASE WHEN :keepSummary THEN followUps ELSE NULL END,
           summaryStatus = :summaryStatus, summaryAttempts = 0, pipeline = :pipeline,
           announced = CASE WHEN :redo THEN announced ELSE 0 END,
           requested = CASE WHEN :summaryStatus = 'PENDING' THEN requested ELSE 0 END WHERE id = :id"""
    )
    suspend fun markDone(
        id: Long, durationMs: Long, at: Long, processingMs: Long, ownerSpeaker: Int?, language: Models.Language,
        languageDetected: Boolean, summaryStatus: SummaryStatus, keepSummary: Boolean, pipeline: Int, redo: Boolean,
    )

    /**
     * Stores a finished transcript, made in [language] ([detected]: the one detection found), with its speakers' voice
     * fingerprints, and queues a new summary when [summarize] is true. [segments] say what the recogniser wrote; common
     * corrections are applied here, keeping that as Segment.recognized. Speakers are numbered afresh, so names given
     * to the previous transcript's speakers are dropped, and so are their links to known voices and
     * the suggestions turned down for them. A [redo] of an old transcript (the same audio) keeps its
     * summary until the new one replaces it, unless no speech was found this time, and isn't
     * announced again. Hand edits are replaced too ("Transcribe again" warns of that), except that a
     * [redo] of a transcript edited while it ran is dropped.
     * @return false when nothing was saved: the call was deleted, or (a [redo]) edited meanwhile.
     */
    @Transaction
    suspend fun saveTranscript(
        id: Long,
        segments: List<Segment>,
        voices: List<SpeakerVoice>,
        durationMs: Long,
        processingMs: Long,
        ownerSpeaker: Int?,
        language: Models.Language,
        detected: Boolean,
        pinned: Models.Language?,
        summarize: Boolean,
        redo: Boolean = false,
    ): Boolean {
        // Deleted while it was being transcribed: nothing to save it to.
        val rec = get(id) ?: return false
        // Given another language while this ran ([pinned] is the one it started with): "Transcribe again" queued it
        // again, and that run, in the language chosen, is the one to keep.
        if (rec.pinnedLanguage != pinned) return false
        // A redo leaves the transcript on show, where it can be edited while the redo runs: the edits win.
        if (redo && rec.editedAt != null) return false
        // The rules as they are in this transaction: one removed before it isn't applied, and the job putting back a
        // removed rule's words (CorrectionsWorker) runs after it, so it finds these lines.
        val rules = correctionRules().toCorrections()
        deleteSegments(id)
        insertSegments(segments.map { it.corrected(rules) })
        deleteVoices(id)
        insertVoices(voices)
        deleteSpeakerNames(id)
        deleteVoiceSamples(id)
        deleteVoiceRejections(id)
        deleteUnusedKnownVoices()
        markDone(id, durationMs, System.currentTimeMillis(), processingMs, ownerSpeaker, language, detected,
            if (summarize && segments.isNotEmpty()) SummaryStatus.PENDING else SummaryStatus.NONE,
            keepSummary = redo && segments.isNotEmpty(), pipeline = Pipeline.CURRENT, redo = redo)
        return true
    }

    // ---- common corrections ----

    /** The common corrections, read here so a change and the rules it's made with are in one transaction. */
    @Query("SELECT * FROM corrections")
    suspend fun correctionRules(): List<Correction>

    /** Call [id]'s lines not edited by hand whose recognised text is like [pattern] (Corrections.likePattern). */
    @Query("""SELECT id, recordingId, text, recognized FROM segments WHERE recordingId = :id AND edited = 0 AND COALESCE(recognized, text) LIKE :pattern ESCAPE '\'""")
    suspend fun correctableLines(id: Long, pattern: String): List<CorrectableLine>

    /**
     * The calls with such lines, by id only: a pattern that can't narrow them down ("%", for words in Cyrillic, say)
     * matches every line, so their text is read a call at a time.
     */
    @Query("""SELECT DISTINCT recordingId FROM segments WHERE edited = 0 AND COALESCE(recognized, text) LIKE :pattern ESCAPE '\'""")
    suspend fun callsWithCorrectableLines(pattern: String): List<Long>

    @Query("UPDATE segments SET text = :text, recognized = :recognized WHERE id = :id")
    suspend fun setLineText(id: Long, text: String, recognized: String?)

    @Query("UPDATE recordings SET topic = :topic, summary = :summary, followUps = :followUps WHERE id = :id")
    suspend fun setSummaryText(id: Long, topic: String?, summary: String?, followUps: String?)

    /**
     * Common corrections in one call, for the rule for [heard] just added or removed (whether it's still there says
     * which): every line whose recognised text says it, unless it was edited by hand, is written again from the
     * recogniser's text with all the rules there are now, read in this transaction, so a removed rule's words come back.
     *
     * When a line changes, the summary follows without being written again: when the rule was added, its words are
     * replaced in the topic, summary and follow-ups too, and a summary being written from the old lines at this
     * moment is queued to be written again. When it was removed, its words can't be told apart in the summary, so
     * with [resummarize] a written one is queued again.
     * @return true when a line's text changed.
     */
    @Transaction
    suspend fun correctCall(id: Long, heard: String, resummarize: Boolean): Boolean {
        val all = correctionRules()
        val rules = all.toCorrections()
        val added = all.firstOrNull { it.heardKey == Corrections.keyOf(heard) }?.let { listOf(it).toCorrections() }
        val phrase = Corrections.saying(heard)
        var changed = false
        for (line in correctableLines(id, Corrections.likePattern(heard))) {
            if (!phrase.matches(line.source)) continue
            val (text, recognized) = rules.correct(line.source)
            if (text != line.text || recognized != line.recognized) setLineText(line.id, text, recognized)
            if (text != line.text) changed = true
        }
        if (!changed) return false
        val rec = get(id) ?: return true
        val summarized = rec.summaryStatus == SummaryStatus.DONE || rec.summaryStatus == SummaryStatus.PROCESSING
        if (added != null) {
            setSummaryText(id, rec.topic?.let(added::apply), rec.summary?.let(added::apply), rec.followUps?.let(added::apply))
            if (rec.summaryStatus == SummaryStatus.PROCESSING) setSummaryStatus(id, SummaryStatus.PENDING)
        } else if (resummarize && summarized) {
            setSummaryStatus(id, SummaryStatus.PENDING)
        }
        return true
    }

    // ---- changes by hand (TranscriptViewModel; the edits themselves are worked out by engine.TranscriptEdits) ----

    @Query("SELECT * FROM voice_samples WHERE recordingId = :id")
    suspend fun voiceSamples(id: Long): List<VoiceSample>

    @Query("SELECT * FROM voice_rejections WHERE recordingId = :id")
    suspend fun voiceRejections(id: Long): List<VoiceRejection>

    /** The call as it is now, for undoing a change; null if it's gone. */
    @Transaction
    suspend fun snapshot(id: Long): CallSnapshot? {
        val rec = get(id) ?: return null
        return CallSnapshot(rec, segments(id), speakerNames(id), voices(id), voiceSamples(id), voiceRejections(id))
    }

    @Update
    suspend fun updateSegments(segments: List<Segment>)

    @Query("DELETE FROM segments WHERE id IN (:ids)")
    suspend fun deleteSegmentsById(ids: List<Long>)

    @Query("UPDATE segments SET speaker = :into WHERE recordingId = :id AND speaker = :from")
    suspend fun moveSpeakerLines(id: Long, from: Int, into: Int)

    @Query("UPDATE recordings SET editedAt = :at WHERE id = :id")
    suspend fun setEditedAt(id: Long, at: Long)

    @Query("UPDATE recordings SET ownerSpeaker = :speaker, ownerManual = :manual WHERE id = :id")
    suspend fun setOwner(id: Long, speaker: Int?, manual: Boolean)

    /**
     * A speaker left without lines is gone: their name, voice fingerprint, links to known voices and suggestions
     * turned down, and the owner's label if it was theirs (voice matching may then find the owner again). A known voice
     * left without samples isn't forgotten here, so Undo can link it again; it isn't listed or suggested meanwhile, and
     * the next naming forgets it (VoiceDao.deleteUnused).
     */
    @Transaction
    suspend fun dropSpeakersWithoutLines(id: Long) {
        deleteNamesWithoutLines(id)
        deleteVoicesWithoutLines(id)
        deleteSamplesWithoutLines(id)
        deleteRejectionsWithoutLines(id)
        dropOwnerWithoutLines(id)
    }

    @Query("DELETE FROM speaker_names WHERE recordingId = :id AND speaker NOT IN (SELECT speaker FROM segments WHERE recordingId = :id)")
    suspend fun deleteNamesWithoutLines(id: Long)

    @Query("DELETE FROM speaker_voices WHERE recordingId = :id AND speaker NOT IN (SELECT speaker FROM segments WHERE recordingId = :id)")
    suspend fun deleteVoicesWithoutLines(id: Long)

    @Query("DELETE FROM voice_samples WHERE recordingId = :id AND speaker NOT IN (SELECT speaker FROM segments WHERE recordingId = :id)")
    suspend fun deleteSamplesWithoutLines(id: Long)

    @Query("DELETE FROM voice_rejections WHERE recordingId = :id AND speaker NOT IN (SELECT speaker FROM segments WHERE recordingId = :id)")
    suspend fun deleteRejectionsWithoutLines(id: Long)

    @Query(
        """UPDATE recordings SET ownerSpeaker = NULL, ownerManual = 0 WHERE id = :id AND ownerSpeaker IS NOT NULL
           AND ownerSpeaker NOT IN (SELECT speaker FROM segments WHERE recordingId = :id)"""
    )
    suspend fun dropOwnerWithoutLines(id: Long)

    /**
     * What every change by hand ends with: the call is marked edited (so redos leave it alone), speakers left without
     * lines are dropped, and with [resummarize] a summary is queued to be written again, without asking for it now:
     * the screen asks once the user leaves the transcript, so a few edits in a row don't load the summary model each.
     */
    @Transaction
    suspend fun afterHandChange(id: Long, resummarize: Boolean) {
        setEditedAt(id, System.currentTimeMillis())
        dropSpeakersWithoutLines(id)
        val rec = get(id) ?: return
        if (resummarize && rec.summaryStatus != SummaryStatus.NONE) setSummaryStatus(id, SummaryStatus.PENDING)
    }

    /**
     * Lines changed by hand: [old], as the change was worked out from, become [new] (an existing id is updated, id 0
     * inserted, and an id left out deleted). With [owner], that speaker becomes the owner, set by hand; [names] are
     * given to speakers. Nothing changes unless the call still shows the same [transcript] (a redo numbers the speakers
     * afresh) with [old] exactly as they were.
     * @return the call as it was before, for Undo; null if nothing was changed.
     */
    @Transaction
    suspend fun changeLines(
        id: Long, transcript: Long?, old: List<Segment>, new: List<Segment>,
        owner: Int? = null, names: List<SpeakerName> = emptyList(), resummarize: Boolean,
    ): CallSnapshot? {
        val before = snapshot(id) ?: return null
        if (before.recording.status != RecordingStatus.DONE || before.recording.transcribedAt != transcript) return null
        val current = before.segments.associateBy { it.id }
        if (old.isEmpty() || old.any { current[it.id] != it }) return null
        val kept = new.mapNotNull { it.id.takeIf { id -> id != 0L } }.toSet()
        deleteSegmentsById(old.map { it.id }.filter { it !in kept })
        updateSegments(new.filter { it.id != 0L })
        insertSegments(new.filter { it.id == 0L })
        if (owner != null) {
            setOwner(id, owner, manual = true)
            deleteSpeakerName(id, owner)
        }
        if (names.isNotEmpty()) upsertSpeakerNames(names)
        afterHandChange(id, resummarize)
        return before
    }

    /**
     * "Same person as…": speaker [from]'s lines become [into]'s. [into] keeps their name, or takes [from]'s if they have
     * none (and aren't the owner); the owner's label moves with [from]'s lines. [from]'s voice fingerprint and links to
     * known voices go: the fingerprints are worked out again from the lines (VoiceRefreshWorker).
     * @return the call as it was before, for Undo; null if nothing was changed (as for [changeLines]).
     */
    @Transaction
    suspend fun mergeSpeakers(id: Long, transcript: Long?, from: Int, into: Int, resummarize: Boolean): CallSnapshot? {
        val before = snapshot(id) ?: return null
        val rec = before.recording
        if (rec.status != RecordingStatus.DONE || rec.transcribedAt != transcript || from == into) return null
        if (before.segments.none { it.speaker == from } || before.segments.none { it.speaker == into }) return null
        moveSpeakerLines(id, from, into)
        val names = before.names.associate { it.speaker to it.name }
        val fromName = names[from]?.takeIf { it.isNotBlank() }
        if (fromName != null && names[into].isNullOrBlank() && rec.ownerSpeaker != into) {
            upsertSpeakerNames(listOf(SpeakerName(id, into, fromName)))
        }
        if (rec.ownerSpeaker == from) setOwner(id, into, rec.ownerManual)
        afterHandChange(id, resummarize)
        return before
    }

    @Query("INSERT OR REPLACE INTO voice_samples (recordingId, speaker, voiceId) SELECT :id, :speaker, k.id FROM known_voices k WHERE k.id = :voiceId")
    suspend fun restoreSample(id: Long, speaker: Int, voiceId: Long)

    @Query("INSERT OR IGNORE INTO voice_rejections (recordingId, speaker, voiceId) SELECT :id, :speaker, k.id FROM known_voices k WHERE k.id = :voiceId")
    suspend fun restoreRejection(id: Long, speaker: Int, voiceId: Long)

    @Query("UPDATE recordings SET ownerSpeaker = :owner, ownerManual = :ownerManual, editedAt = :editedAt WHERE id = :id")
    suspend fun restoreEdits(id: Long, owner: Int?, ownerManual: Boolean, editedAt: Long?)

    /** A summary queued by the change being undone, and not started, goes back to how it was. */
    @Query("UPDATE recordings SET summaryStatus = :status WHERE id = :id AND summaryStatus = 'PENDING'")
    suspend fun unqueueSummary(id: Long, status: SummaryStatus)

    /**
     * Undo: puts the call back as [snapshot] has it, unless its transcript was replaced meanwhile (a redo). Lines not
     * edited by hand get the common corrections there are now, so a rule added or removed since the snapshot (whose
     * job may already have been through this call) isn't undone with them. A known voice forgotten since (named
     * elsewhere meanwhile) isn't brought back. A summary queued by the change goes back to how it was if it hasn't
     * started; one being written from the changed lines is queued again.
     * @return false if nothing was put back.
     */
    @Transaction
    suspend fun restore(snapshot: CallSnapshot): Boolean {
        val was = snapshot.recording
        val id = was.id
        val rec = get(id) ?: return false
        if (rec.status != RecordingStatus.DONE || rec.transcribedAt != was.transcribedAt) return false
        val rules = correctionRules().toCorrections()
        deleteSegments(id)
        insertSegments(snapshot.segments.map { it.corrected(rules) })
        deleteSpeakerNames(id)
        upsertSpeakerNames(snapshot.names)
        deleteVoices(id)
        insertVoices(snapshot.voices)
        deleteVoiceSamples(id)
        snapshot.samples.forEach { restoreSample(id, it.speaker, it.voiceId) }
        deleteVoiceRejections(id)
        snapshot.rejections.forEach { restoreRejection(id, it.speaker, it.voiceId) }
        restoreEdits(id, was.ownerSpeaker, was.ownerManual, was.editedAt)
        when {
            rec.summaryStatus == SummaryStatus.PROCESSING -> setSummaryStatus(id, SummaryStatus.PENDING)
            was.summaryStatus == SummaryStatus.DONE || was.summaryStatus == SummaryStatus.FAILED -> unqueueSummary(id, was.summaryStatus)
        }
        deleteUnusedKnownVoices()
        return true
    }

    /**
     * Voice fingerprints worked out again after a change of speakers (VoiceRefreshWorker), saved only if the call still
     * shows the same [transcript] and its lines are still where and whose they were when [analysed].
     */
    @Transaction
    suspend fun replaceVoices(id: Long, transcript: Long?, analysed: List<Segment>, voices: List<SpeakerVoice>): Boolean {
        val rec = get(id) ?: return false
        if (rec.transcribedAt != transcript) return false
        fun spans(lines: List<Segment>) = lines.map { listOf(it.id, it.speaker.toLong(), it.startMs, it.endMs) }.sortedBy { it[0] }
        if (spans(segments(id)) != spans(analysed)) return false
        deleteVoices(id)
        insertVoices(voices)
        return true
    }

    // ---- redoing transcripts made by an older pipeline ----

    /**
     * The newest transcript made by an older pipeline whose redo hasn't failed (or crashed three times). One edited by
     * hand is kept as it is: a redo would replace the edits. So is a shrunk one (Shrink.redoWaiting has the same rule):
     * its redo would be made from the compressed copy, and replace a transcript made from the WAV.
     */
    @Query(
        """SELECT * FROM recordings WHERE status = 'DONE' AND pipeline < :current AND attempts < 3 AND editedAt IS NULL
           AND originalBytes IS NULL ORDER BY lastModified DESC LIMIT 1"""
    )
    suspend fun nextRedo(current: Int = Pipeline.CURRENT): Recording?

    /** How many transcripts [nextRedo] has still to give. */
    @Query(
        """SELECT COUNT(*) FROM recordings WHERE status = 'DONE' AND pipeline < :current AND attempts < 3 AND editedAt IS NULL
           AND originalBytes IS NULL"""
    )
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

    // ---- shrinking WAV recordings (work.ShrinkWorker; which ones is up to Shrink.eligible) ----

    /** Transcribed WAV recordings not shrunk or given up on, oldest call first: [Shrink.eligible] has the last word. */
    @Query(
        """SELECT * FROM recordings WHERE status = 'DONE' AND originalBytes IS NULL AND shrinkAttempts < :maxAttempts
           AND displayName LIKE '%.wav' ORDER BY lastModified ASC"""
    )
    suspend fun shrinkCandidates(maxAttempts: Int = Shrink.MAX_ATTEMPTS): List<Recording>

    /** For Settings: the calls shrunk, and the transcribed WAVs that may still be. */
    @Query("SELECT * FROM recordings WHERE originalBytes IS NOT NULL OR (status = 'DONE' AND displayName LIKE '%.wav')")
    fun observeShrinkable(): Flow<List<Recording>>

    /** Counted before the recording is read, so one that keeps crashing the app is given up on. */
    @Query("UPDATE recordings SET shrinkAttempts = shrinkAttempts + 1 WHERE id = :id")
    suspend fun startShrink(id: Long)

    /** Stopped cleanly (a call came in, the charger was unplugged), or the call changed meanwhile: not counted. */
    @Query("UPDATE recordings SET shrinkAttempts = MAX(shrinkAttempts - 1, 0) WHERE id = :id")
    suspend fun interruptShrink(id: Long)

    /**
     * It can't be shrunk (a form the encoder doesn't take, a file of the copy's name already there, or another call's row
     * naming the copy's URI): not tried again.
     */
    @Query("UPDATE recordings SET shrinkAttempts = :maxAttempts WHERE id = :id")
    suspend fun giveUpShrink(id: Long, maxAttempts: Int = Shrink.MAX_ATTEMPTS)

    @Query("SELECT id FROM recordings WHERE documentUri = :uri")
    suspend fun idForUri(uri: String): Long?

    /**
     * Whether a row other than call [id]'s names the URI its copy is expected at ([uri]; null when it can't be told) or a
     * file called [name], in any case (the phone's storage ignores it in names): a transcript kept for a file of that name
     * that disappeared, which a copy written there would be taken for. Asked before the copy is written, so nothing is
     * written to a URI another call has.
     */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM recordings WHERE id != :id AND (documentUri = :uri OR displayName = :name COLLATE NOCASE))"""
    )
    suspend fun copyNameTaken(id: Long, uri: String?, name: String): Boolean

    /** [saveShrunk]'s statement. @return the rows changed: 1, or 0 when the call isn't as it was read. */
    @Query(
        """UPDATE recordings SET documentUri = :uri, displayName = :name, sizeBytes = :size, fileModified = :modified,
           originalBytes = sizeBytes WHERE id = :id AND documentUri = :from AND sizeBytes = :fromSize AND status = 'DONE'
           AND originalBytes IS NULL"""
    )
    suspend fun setShrunk(id: Long, from: String, fromSize: Long, uri: String, name: String, size: Long, modified: Long): Int

    /**
     * The recording now names its compressed copy: [uri], called [name], of [size] bytes, last modified at [modified]
     * (Recording.fileModified; the call's time stays). One statement, so the folder check finds the row with the WAV or
     * with the copy, never neither or both. Only while the call is still transcribed from the WAV it was read from ([from],
     * [fromSize]): one deleted, queued again or changed meanwhile is left alone (Shrink.Saved.CHANGED), and so is a copy
     * whose URI another row has (Shrink.Saved.URI_TAKEN: a transcript kept for a file of that name that disappeared).
     */
    @Transaction
    suspend fun saveShrunk(id: Long, from: String, fromSize: Long, uri: String, name: String, size: Long, modified: Long): Shrink.Saved {
        if (idForUri(uri)?.let { it != id } == true) return Shrink.Saved.URI_TAKEN
        return if (setShrunk(id, from, fromSize, uri, name, size, modified) == 1) Shrink.Saved.DONE else Shrink.Saved.CHANGED
    }

    /**
     * The WAV couldn't be deleted after [saveShrunk], or its copy is gone: the row names it again ([from], called
     * [fromName]), as it was, if it still names the copy [uri].
     * @return the rows changed: 1, or 0 when it didn't name the copy.
     */
    @Query(
        """UPDATE recordings SET documentUri = :from, displayName = :fromName, sizeBytes = originalBytes, fileModified = NULL,
           originalBytes = NULL WHERE id = :id AND documentUri = :uri AND originalBytes IS NOT NULL"""
    )
    suspend fun undoShrunk(id: Long, uri: String, from: String, fromName: String): Int

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
