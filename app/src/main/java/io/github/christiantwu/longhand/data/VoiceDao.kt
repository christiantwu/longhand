package io.github.christiantwu.longhand.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A known voice as Settings lists it: how many calls it was learned from. */
data class KnownVoiceRow(val id: Long, val name: String, val calls: Int)

/** One sample's voice fingerprint, for its known voice's centroid. */
class VoiceSampleEmbedding(val voiceId: Long, val embedding: ByteArray)

/** "Recognise voices": the people the user has named, the speakers they were named in, and "Not them". */
@Dao
interface VoiceDao {

    @Query(
        """SELECT k.id, k.name, COUNT(DISTINCT s.recordingId) AS calls FROM known_voices k
           JOIN voice_samples s ON s.voiceId = k.id GROUP BY k.id ORDER BY k.name"""
    )
    fun observeKnown(): Flow<List<KnownVoiceRow>>

    @Query(
        """SELECT s.voiceId, v.embedding FROM voice_samples s
           JOIN speaker_voices v ON v.recordingId = s.recordingId AND v.speaker = s.speaker"""
    )
    fun observeSampleEmbeddings(): Flow<List<VoiceSampleEmbedding>>

    @Query("SELECT * FROM speaker_voices WHERE recordingId = :recordingId")
    fun observeSpeakerVoices(recordingId: Long): Flow<List<SpeakerVoice>>

    @Query("SELECT * FROM voice_rejections WHERE recordingId = :recordingId")
    fun observeRejections(recordingId: Long): Flow<List<VoiceRejection>>

    /**
     * A name the user chose for a speaker: their stored voice becomes a sample of the known voice
     * called [name] (in any case; created if new, and spelt as chosen last), moving any link the
     * speaker had. False, with any old link removed, if the speaker has no stored voice.
     *
     * Nothing happens unless the call is still as the user named it: the same [transcript], the
     * speaker not the owner and still called [name]. Finding the voice can take seconds, in which a
     * redo, "Me" or another name may have come in; each of those updates the links itself.
     */
    @Transaction
    suspend fun link(recordingId: Long, speaker: Int, name: String, transcript: Long?): Boolean {
        val trimmed = name.trim()
        if (transcript == null || transcribedAt(recordingId) != transcript || ownerSpeaker(recordingId) == speaker ||
            speakerName(recordingId, speaker)?.trim() != trimmed
        ) return false
        if (trimmed.isEmpty() || !hasVoice(recordingId, speaker)) {
            unlink(recordingId, speaker)
            return false
        }
        val key = KnownVoice.keyOf(trimmed)
        val voiceId = voiceId(key)?.also { rename(it, trimmed) } ?: insertVoice(KnownVoice(name = trimmed, nameKey = key))
        upsertSample(VoiceSample(recordingId, speaker, voiceId))
        // Named as them by hand after turning them down once: the naming wins.
        deleteRejection(recordingId, speaker, voiceId)
        deleteUnused()
        return true
    }

    /** The speaker's name was cleared or changed to one that isn't learned ("Me", say). */
    @Transaction
    suspend fun unlink(recordingId: Long, speaker: Int) {
        deleteSample(recordingId, speaker)
        deleteUnused()
    }

    /** "Not them". Nothing happens if the voice or the call is gone meanwhile. */
    @Query(
        """INSERT OR IGNORE INTO voice_rejections (recordingId, speaker, voiceId)
           SELECT r.id, :speaker, k.id FROM recordings r, known_voices k WHERE r.id = :recordingId AND k.id = :voiceId"""
    )
    suspend fun reject(recordingId: Long, speaker: Int, voiceId: Long)

    /** Its samples and rejections go with it (foreign keys). */
    @Query("DELETE FROM known_voices WHERE id = :id")
    suspend fun forget(id: Long)

    /** Every known voice, with all samples and rejections (foreign keys). */
    @Query("DELETE FROM known_voices")
    suspend fun forgetAll()

    @Query("SELECT EXISTS(SELECT 1 FROM speaker_voices WHERE recordingId = :recordingId AND speaker = :speaker)")
    suspend fun hasVoice(recordingId: Long, speaker: Int): Boolean

    @Query("SELECT id FROM known_voices WHERE nameKey = :nameKey")
    suspend fun voiceId(nameKey: String): Long?

    @Query("SELECT transcribedAt FROM recordings WHERE id = :recordingId")
    suspend fun transcribedAt(recordingId: Long): Long?

    @Query("SELECT ownerSpeaker FROM recordings WHERE id = :recordingId")
    suspend fun ownerSpeaker(recordingId: Long): Int?

    @Query("SELECT name FROM speaker_names WHERE recordingId = :recordingId AND speaker = :speaker")
    suspend fun speakerName(recordingId: Long, speaker: Int): String?

    @Insert
    suspend fun insertVoice(voice: KnownVoice): Long

    @Query("UPDATE known_voices SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Upsert
    suspend fun upsertSample(sample: VoiceSample)

    @Query("DELETE FROM voice_samples WHERE recordingId = :recordingId AND speaker = :speaker")
    suspend fun deleteSample(recordingId: Long, speaker: Int)

    @Query("DELETE FROM voice_rejections WHERE recordingId = :recordingId AND speaker = :speaker AND voiceId = :voiceId")
    suspend fun deleteRejection(recordingId: Long, speaker: Int, voiceId: Long)

    /** A known voice left without samples is forgotten. */
    @Query("DELETE FROM known_voices WHERE id NOT IN (SELECT voiceId FROM voice_samples)")
    suspend fun deleteUnused()
}
