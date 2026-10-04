package io.github.christiantwu.longhand.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.christiantwu.longhand.engine.CallLanguage
import io.github.christiantwu.longhand.engine.Corrections
import io.github.christiantwu.longhand.engine.Models

enum class RecordingStatus { PENDING, PROCESSING, DONE, FAILED, SKIPPED }

/** NONE: no summary wanted yet (e.g. the summary model isn't installed). */
enum class SummaryStatus { NONE, PENDING, PROCESSING, DONE, FAILED }

@Entity(tableName = "recordings", indices = [Index(value = ["documentUri"], unique = true)])
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val status: RecordingStatus,
    val durationMs: Long = 0,
    val progress: Float = 0f,
    /** Times processing started; a crash leaves the row PROCESSING and this counts it. */
    val attempts: Int = 0,
    val error: String? = null,
    val transcribedAt: Long? = null,
    val processingMs: Long? = null,

    // Who the call was with: matched from the call log and contacts, or picked by hand.
    val phoneNumber: String? = null,
    val contactName: String? = null,
    val contactLookupKey: String? = null,
    /** android.provider.CallLog.Calls.TYPE (1 incoming, 2 outgoing, ...) when known. */
    val callDirection: Int? = null,
    /** The automatic caller lookup has run (reset when the user grants call log access). */
    @ColumnInfo(defaultValue = "0") val callerChecked: Boolean = false,
    /** Picked by hand; automatic lookups never overwrite it. */
    @ColumnInfo(defaultValue = "0") val callerManual: Boolean = false,

    /** The diarized speaker who is the phone's owner, from voice matching or "Me". */
    val ownerSpeaker: Int? = null,
    /** Set by hand ("Me" / "That's not me"); voice matching leaves it alone. */
    @ColumnInfo(defaultValue = "0") val ownerManual: Boolean = false,

    val topic: String? = null,
    val summary: String? = null,
    /** Follow-ups, one per line. */
    val followUps: String? = null,
    @ColumnInfo(defaultValue = "NONE") val summaryStatus: SummaryStatus = SummaryStatus.NONE,
    /** Times a summary started; a crash leaves it PROCESSING and this counts it. */
    @ColumnInfo(defaultValue = "0") val summaryAttempts: Int = 0,
    /**
     * The user asked for this one (Transcribe again, a correction, Transcribe now), so the
     * battery rules don't hold it back. Cleared once its transcript and summary are done.
     */
    @ColumnInfo(defaultValue = "0") val requested: Boolean = false,
    /** The [Pipeline] version that made the transcript; older ones are redone on the charger. */
    @ColumnInfo(defaultValue = "1") val pipeline: Int = 0,
    /** The "call transcribed" notification has gone out, or isn't wanted (a redo of an old transcript). */
    @ColumnInfo(defaultValue = "1") val announced: Boolean = true,
    /** When the transcript was last changed by hand (a line's text or speaker); null if never. */
    val editedAt: Long? = null,
    /** The language the transcript was made in; null for transcripts made before 0.10.0. */
    val language: Models.Language? = null,
    /**
     * A language chosen by hand for this call ("Transcribe again"): every later transcription of it uses this one while
     * it's on the phone, and the language chosen in Settings otherwise. Null: the call follows Settings.
     */
    val pinnedLanguage: Models.Language? = null,
    /**
     * The language detected in each window of the call's speech (Whisper's codes, comma-separated, e.g. "en,ja,ja"; empty
     * when it has too little speech to tell, or while detection runs, so a crash in it isn't retried); null until it's
     * been detected. See [detection].
     */
    val spokenLanguages: String? = null,
    /** How much speech language detection found in the call, in seconds; null until it's been detected. */
    val speechSeconds: Float? = null,
    /** Detection put the transcript in [language] instead of Settings' language (not chosen by hand). */
    @ColumnInfo(defaultValue = "0") val languageDetected: Boolean = false,
)

/** What language detection found in the call; null until it's been detected. */
val Recording.detection: CallLanguage.Detection? get() = CallLanguage.Detection.of(spokenLanguages, speechSeconds)

/** Its speakers are being found while its language is detected: the worker shows that progress on the waiting call. */
val Recording.detecting: Boolean get() = status == RecordingStatus.PENDING && progress > 0f

/** Versions of the transcription pipeline. Transcripts made by an older one are redone on the charger. */
object Pipeline {
    /** 2 (0.5.0): band-limited resampling and an automatic speaker count. */
    const val CURRENT = 2
}

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(Recording::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordingId")],
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val startMs: Long,
    val endMs: Long,
    val speaker: Int,
    val text: String,
    /**
     * Each recognised word's start and end, in centiseconds from [startMs], in the order the recogniser
     * wrote the words; null when they weren't kept (transcripts made before 0.9.0).
     */
    val words: String? = null,
    /** What the recogniser wrote, before common corrections or a hand edit; null when it's [text]. */
    val recognized: String? = null,
    /** The text was typed by hand, so common corrections leave it alone. */
    @ColumnInfo(defaultValue = "0") val edited: Boolean = false,
)

@Entity(
    tableName = "speaker_names",
    primaryKeys = ["recordingId", "speaker"],
    foreignKeys = [ForeignKey(Recording::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
)
data class SpeakerName(val recordingId: Long, val speaker: Int, val name: String)

/** A voice fingerprint (speaker embedding) for one speaker in one recording. */
@Entity(
    tableName = "speaker_voices",
    primaryKeys = ["recordingId", "speaker"],
    foreignKeys = [ForeignKey(Recording::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE)],
)
class SpeakerVoice(val recordingId: Long, val speaker: Int, val embedding: ByteArray)

/** Someone the user has named, whose voice "Recognise voices" suggests in other calls. */
@Entity(tableName = "known_voices", indices = [Index(value = ["nameKey"], unique = true)])
data class KnownVoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [keyOf] the name. SQLite's NOCASE folds only A-Z, so "Émile" and "émile" would be two people. */
    val nameKey: String,
) {
    companion object {
        fun keyOf(name: String) = name.trim().lowercase(java.util.Locale.ROOT)
    }
}

/** A speaker the user named as a [KnownVoice]: their [SpeakerVoice] is one sample of that voice. */
@Entity(
    tableName = "voice_samples",
    primaryKeys = ["recordingId", "speaker"],
    foreignKeys = [
        ForeignKey(Recording::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(KnownVoice::class, ["id"], ["voiceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("voiceId")],
)
data class VoiceSample(val recordingId: Long, val speaker: Int, val voiceId: Long)

/** "Not them": the known voice is never suggested for this speaker again. */
@Entity(
    tableName = "voice_rejections",
    primaryKeys = ["recordingId", "speaker", "voiceId"],
    foreignKeys = [
        ForeignKey(Recording::class, ["id"], ["recordingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(KnownVoice::class, ["id"], ["voiceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("voiceId")],
)
data class VoiceRejection(val recordingId: Long, val speaker: Int, val voiceId: Long)

/** A common correction: [written] wherever the recogniser writes [heard] (engine.Corrections does the matching). */
@Entity(tableName = "corrections", indices = [Index(value = ["heardKey"], unique = true)])
data class Correction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** As typed, trimmed and with single spaces ([Corrections.normalize]). */
    val heard: String,
    /** [Corrections.keyOf] [heard]: one rule per word or phrase, whatever its case or spacing. */
    val heardKey: String,
    val written: String,
    val createdAt: Long,
)

/** The rules to apply, as the matcher takes them. */
fun List<Correction>.toCorrections(): Corrections = Corrections(map { Corrections.Rule(it.heard, it.written) })

/**
 * This line with common corrections [rules] applied afresh to what the recogniser wrote (so a rule removed since is
 * undone), unless it was typed by hand. Its word timings belong to what the recogniser wrote, which doesn't change.
 */
fun Segment.corrected(rules: Corrections): Segment {
    if (edited) return this
    val (corrected, source) = rules.correct(recognized ?: text)
    return copy(text = corrected, recognized = source)
}
