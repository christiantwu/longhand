package io.github.christiantwu.longhand.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.christiantwu.longhand.engine.Corrections
import kotlinx.coroutines.flow.Flow

/** Common corrections, as Settings lists them. Transcripts are corrected by RecordingDao.correctCall and TranscribeWorker. */
@Dao
interface CorrectionDao {

    @Query("SELECT * FROM corrections ORDER BY heardKey")
    fun observe(): Flow<List<Correction>>

    @Query("SELECT * FROM corrections ORDER BY heardKey")
    suspend fun all(): List<Correction>

    /** Write [written] wherever [heard] is recognised, replacing any rule for the same words in another case or spacing. */
    suspend fun add(heard: String, written: String): Correction {
        val rule = Correction(
            heard = Corrections.normalize(heard), heardKey = Corrections.keyOf(heard),
            written = written.trim(), createdAt = System.currentTimeMillis(),
        )
        return rule.copy(id = insert(rule))
    }

    @Query("DELETE FROM corrections WHERE id = :id")
    suspend fun remove(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: Correction): Long
}
