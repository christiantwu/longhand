package io.github.christiantwu.longhand.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.VoiceAnalyzer
import io.github.christiantwu.longhand.engine.VoiceProfile
import java.util.concurrent.CancellationException

/**
 * After the owner confirms their voice, labels "You" in every transcript. Transcripts
 * made before voices were stored get their speakers' fingerprints computed from the audio
 * first (a few seconds each; nothing is transcribed again).
 */
class VoiceMatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val profile = VoiceProfile(applicationContext).load() ?: return Result.success()
        val dao = AppDatabase.get(applicationContext).recordings()

        for (id in dao.recordingsWithVoices()) dao.applyOwnerFromVoice(id, profile)

        val missing = dao.doneWithoutVoices()
        if (missing.isEmpty() || !Models.speechReady(applicationContext)) return Result.success()
        VoiceAnalyzer(applicationContext).use { analyzer ->
            for (rec in missing) {
                if (isStopped) break
                try {
                    if (learnVoicesFromAudio(applicationContext, dao, rec, analyzer).isNotEmpty()) dao.applyOwnerFromVoice(rec.id, profile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The recording may have been deleted or moved; its transcript stays as it is.
                    Log.w(TAG, "voice match skipped ${rec.displayName}: ${e.message}")
                }
            }
        }
        return Result.success()
    }
}
