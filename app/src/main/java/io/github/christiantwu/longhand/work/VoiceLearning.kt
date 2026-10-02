package io.github.christiantwu.longhand.work

import android.content.Context
import androidx.core.net.toUri
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingDao
import io.github.christiantwu.longhand.data.SpeakerVoice
import io.github.christiantwu.longhand.engine.AudioDecoder
import io.github.christiantwu.longhand.engine.Span
import io.github.christiantwu.longhand.engine.VoiceAnalyzer
import io.github.christiantwu.longhand.engine.VoiceMath

/** Database rows for a recording's speaker voice fingerprints. */
fun Map<Int, FloatArray>.toSpeakerVoices(recordingId: Long): List<SpeakerVoice> =
    map { (speaker, v) -> SpeakerVoice(recordingId, speaker, VoiceMath.toBytes(v)) }

/**
 * Computes and stores the speakers' voice fingerprints for a call transcribed before voices
 * were stored, from its audio and saved transcript (nothing is transcribed again).
 * Returns empty when the transcript has no speech; throws when the audio can't be read
 * (moved or deleted).
 */
suspend fun learnVoicesFromAudio(context: Context, dao: RecordingDao, rec: Recording, analyzer: VoiceAnalyzer): Map<Int, FloatArray> {
    val segments = dao.segments(rec.id)
    if (segments.isEmpty()) return emptyMap()
    val audio = AudioDecoder.decode(context, rec.documentUri.toUri())
    val turns = segments.map { Span(it.startMs / 1000f, it.endMs / 1000f, it.speaker) to audio.mono }
    val voices = analyzer.voices(turns)
    dao.insertVoices(voices.toSpeakerVoices(rec.id))
    return voices
}
