package io.github.christiantwu.longhand.work

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.Pipeline
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.engine.AudioDecoder
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.SegmentLogic
import io.github.christiantwu.longhand.engine.Span
import io.github.christiantwu.longhand.engine.VoiceAnalyzer
import io.github.christiantwu.longhand.engine.VoiceProfile
import java.util.concurrent.CancellationException

/**
 * After lines were given to another speaker by hand (moved, split or merged), the call's voice fingerprints no longer
 * match whose speech is whose. They're worked out again from the audio and the lines as they are now (the old ones are
 * replaced, so a speaker left with too little speech has none), then the owner is looked for again unless it was set
 * by hand, and with Recognise voices on the named speakers are linked to their known voices again. A known voice's
 * centroid follows on its own, being made from the fingerprints. Nothing is transcribed again.
 */
class VoiceRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(RECORDING, -1L)
        val db = AppDatabase.get(applicationContext)
        val dao = db.recordings()
        val rec = dao.get(id) ?: return Result.success()
        if (rec.status != RecordingStatus.DONE || !Models.speechReady(applicationContext)) return Result.success()
        try {
            val audio = AudioDecoder.decode(applicationContext, rec.documentUri.toUri())
            val saved = VoiceAnalyzer(applicationContext).use { analyzer ->
                // Another edit while the voices were worked out: work them out again from the new lines.
                (1..3).any {
                    if (isStopped) return Result.success()
                    val lines = dao.segments(id)
                    // The stored lines no longer overlap (a short "yeah" said over someone is left in their line), so a
                    // voice re-learned here can hold a moment of someone else; the ones learned when transcribing don't.
                    // Up to the end of the call: the samples end in silence after it.
                    val spans = SegmentLogic.upTo(lines.map { Span(it.startMs / 1000f, it.endMs / 1000f, it.speaker) }, audio.seconds)
                    val voices = analyzer.voices(spans.map { it to audio.mono })
                    dao.replaceVoices(id, rec.transcribedAt, lines, voices.toSpeakerVoices(id))
                }
            }
            if (!saved) return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The recording was moved or deleted: the old fingerprints stay.
            Log.w(TAG, "voices not worked out again for ${rec.displayName}: ${e.message}")
            return Result.success()
        }
        VoiceProfile(applicationContext).load()?.let { dao.applyOwnerFromVoice(id, it) }
        val now = dao.get(id) ?: return Result.success()
        if (Settings(applicationContext).current().recogniseVoices && now.pipeline >= Pipeline.CURRENT) {
            for (name in dao.speakerNames(id)) {
                if (name.speaker != now.ownerSpeaker && name.name.isNotBlank()) {
                    db.voices().link(id, name.speaker, name.name, rec.transcribedAt)
                }
            }
        }
        Log.i(TAG, "voices worked out again for ${rec.displayName} after an edit")
        return Result.success()
    }

    companion object {
        const val RECORDING = "recording"
    }
}
