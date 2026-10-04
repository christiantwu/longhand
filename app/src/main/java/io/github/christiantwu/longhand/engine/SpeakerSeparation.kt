package io.github.christiantwu.longhand.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.christiantwu.longhand.TAG
import java.io.Closeable
import java.util.concurrent.CancellationException

/**
 * Who spoke when in a call, without the audio, so it's cheap to keep until the call is transcribed: the speaker turns,
 * each heard on the mono mix or one channel ([Turn]), each speaker's voice fingerprint, and the call's [speech] (sample
 * ranges of the mono mix, in order) for language detection.
 */
class Separation(
    val turns: List<Turn>, val voices: Map<Int, FloatArray>, val speech: List<Pair<Int, Int>>,
    // The audio's length and channels, set on what [SpeakerSeparation.separate] returns.
    private val sampleCount: Int = -1, private val channels: Int = -1,
) {
    /** Found in [audio], as far as its length and channels tell. */
    fun matches(audio: DecodedAudio): Boolean = audio.sampleCount == sampleCount && audio.channels.size == channels

    /** A speaker turn, heard on [channel] of a stereo recording, or on the mono mix when that's null. */
    data class Turn(val span: Span, val channel: Int? = null)

    /** Each turn with the samples it's heard in. */
    fun turnsIn(audio: DecodedAudio): List<Pair<Span, FloatArray>> =
        turns.map { turn -> turn.span to (turn.channel?.let { audio.channels[it] } ?: audio.mono) }

    companion object {
        /** The shortest piece of a turn that's recognised, in seconds. */
        private const val MIN_PIECE_SEC = TranscriptionEngine.MIN_PIECE_SAMPLES.toFloat() / MODEL_SAMPLE_RATE

        /**
         * Turns heard on the mono mix, from who spoke when ([spans]) with each speaker's [voices]: each speaker's
         * speech joined up ([SegmentLogic.merge]), then made not to overlap ([SegmentLogic.withoutOverlaps]), as the
         * mono mix has every voice in it and an overlap would be transcribed twice. Speakers are numbered in the order
         * they're first heard; one left with no turns (everything they said was said over someone else) is gone, voice
         * and all, so it can't be taken for the owner or leave a gap in the numbers.
         */
        fun mono(spans: List<Span>, voices: Map<Int, FloatArray>, speech: List<Pair<Int, Int>>): Separation {
            val turns = SegmentLogic.withoutOverlaps(SegmentLogic.merge(spans), MIN_PIECE_SEC)
            val numbered = SegmentLogic.relabelByFirstAppearance(turns)
            val renumber = turns.zip(numbered).associate { (a, b) -> a.speaker to b.speaker }
            return Separation(
                numbered.map { Turn(it) },
                voices.entries.mapNotNull { (speaker, v) -> renumber[speaker]?.let { it to v } }.toMap(),
                speech,
            )
        }
    }
}

/**
 * Finds who spoke when in a call, whichever language it's in: diarization, deliberately split too finely, then joined up
 * into the people on the call by voice ([SpeakerResolver]); or, when a stereo recording has each side on its own
 * channel, the channels. Its models are small (about 50 MB of files), so it stays loaded while recognizers and language
 * detection come and go, and a call separated for language detection isn't separated again to be transcribed.
 */
class SpeakerSeparation(context: Context) : Closeable {

    private val vadModel = Models.file(context, "silero_vad.onnx").absolutePath
    private val threads = 4
    private val voiceAnalyzer = VoiceAnalyzer(context, threads = 2)

    private val diarizer = OfflineSpeakerDiarization(
        assetManager = null,
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = Models.file(context, "segmentation.onnx").absolutePath,
                ),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = Models.file(context, "embedding.onnx").absolutePath,
                numThreads = threads,
            ),
            // Deliberately loose: one person may come back as several clusters, which SpeakerResolver
            // then joins up by voice. A fixed count let a stray fragment take a speaker's place.
            clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    )

    /**
     * @param owner the phone owner's voiceprint, if they've set one: keeps them a speaker of their own.
     * @param onProgress 0..[SHARE]: separating a call is that much of transcribing it.
     * @param isStopped polled after diarization; throws [CancellationException] when true.
     */
    fun separate(audio: DecodedAudio, owner: FloatArray?, onProgress: (Float) -> Unit, isStopped: () -> Boolean): Separation {
        val stereo = audio.channels.size == 2 &&
            StereoAnalysis.channelsAreDistinct(audio.channels[0], audio.channels[1])
        Log.i(TAG, "transcribe: ${audio.durationMs} ms, channels=${audio.channels.size}, stereoSpeakers=$stereo")
        val found = if (stereo) stereoTurns(audio) else monoTurns(audio, owner, onProgress, isStopped)
        return Separation(found.turns, found.voices, found.speech, audio.sampleCount, audio.channels.size)
    }

    /** Who spoke when, from voice clustering over the whole call. */
    private fun monoTurns(audio: DecodedAudio, owner: FloatArray?, onProgress: (Float) -> Unit, isStopped: () -> Boolean): Separation {
        val samples = audio.mono
        val raw = diarizer.processWithCallback(samples, DiarizationProgress(onProgress), 0L)
            .map { Span(it.start, it.end, it.speaker) }
        if (isStopped()) throw CancellationException()
        // Very short or single-voice clips can come back empty; fall back to plain speech detection.
        if (raw.isEmpty()) {
            return ofTurns(SegmentLogic.merge(speechSpans(samples, 0, samples.size, speaker = 0)).map { Separation.Turn(it) }, audio)
        }

        val fingerprints = SpeakerResolver.fingerprintPlan(raw)
            .mapNotNull { (cluster, parts) -> voiceAnalyzer.fingerprint(samples, parts)?.let { cluster to it } }
            .toMap()
        val resolved = SpeakerResolver.resolve(raw, fingerprints, owner)
        // A speaker with too little clean speech for a fingerprint of their own keeps the one speaker separation made,
        // so the owner can still be recognised among them. Taken from the turns before they're made not to overlap,
        // which still show where someone else talks over a speaker (their voice would be mixed in).
        val voices = resolved.voices + voiceAnalyzer.voices(SegmentLogic.merge(resolved.spans).map { it to samples })
        val found = Separation.mono(resolved.spans, voices,
            // Everything diarization heard anyone say, padded as recognition pads speech: it hears quieter and noisier
            // voices than speech detection does.
            CallLanguage.speech(raw, samples.size, SpeechRanges.PAD_BEFORE, SpeechRanges.PAD_AFTER))
        Log.i(TAG, "speakers: ${raw.map { it.speaker }.distinct().size} clusters, " +
            "${fingerprints.size} fingerprinted, ${found.turns.map { it.span.speaker }.distinct().size} people")
        return found
    }

    /** Each side of the call on its own channel: the channel is the speaker. */
    private fun stereoTurns(audio: DecodedAudio): Separation {
        val out = ArrayList<Separation.Turn>()
        for (ch in 0..1) {
            val own = audio.channels[ch]
            val other = audio.channels[1 - ch]
            val spans = speechSpans(own, 0, audio.sampleCount, speaker = ch).filter { span ->
                // Drop echo/bleed: speech in this channel that is much louder in the other one.
                val a = (span.start * SR).toInt()
                val b = (span.end * SR).toInt()
                StereoAnalysis.rms(own, a, b) * 2 >= StereoAnalysis.rms(other, a, b)
            }
            SegmentLogic.merge(spans).forEach { out += Separation.Turn(it, channel = ch) }
        }
        return ofTurns(out.sortedBy { it.span.start }, audio)
    }

    /**
     * [turns] of [audio] found without diarization, whose speech is where they are, with each speaker's voice from what
     * they say in them that nobody says over.
     */
    private fun ofTurns(turns: List<Separation.Turn>, audio: DecodedAudio): Separation {
        val found = Separation(turns, emptyMap(), CallLanguage.speech(turns.map { it.span }, audio.channels.minOf { it.size }))
        return Separation(turns, voiceAnalyzer.voices(found.turnsIn(audio)), found.speech)
    }

    /**
     * Speech regions as spans, padded a little: speech detection reacts slightly after a word
     * starts, and a clipped first syllable often loses the whole word.
     */
    private fun speechSpans(samples: FloatArray, from: Int, to: Int, speaker: Int): List<Span> =
        SpeechRanges.find(vadModel, samples, from, to).map { (a, b) ->
            Span(maxOf(a - SpeechRanges.PAD_BEFORE, from) / SR, minOf(b + SpeechRanges.PAD_AFTER, to) / SR, speaker)
        }

    override fun close() {
        diarizer.release()
        voiceAnalyzer.close()
    }

    companion object {
        /** How much of transcribing a call is separating it, in progress reported: recognition is the rest. */
        const val SHARE = 0.3f

        /** Diarization's clustering threshold: larger merges more; see SpeakerResolver for the rest. */
        private const val CLUSTER_THRESHOLD = 0.8f
        private const val SR = MODEL_SAMPLE_RATE.toFloat()
    }
}

/**
 * Progress callback for diarization. sherpa-onnx's native code looks up exactly
 * `invoke(int, int, long): Integer` on the callback's class, which a Kotlin lambda (compiled
 * to a generic invokedynamic lambda) doesn't have, so this must be a real class. The
 * signature is also kept from R8 in proguard-rules.pro.
 */
class DiarizationProgress(private val onProgress: (Float) -> Unit) : (Int, Int, Long) -> Int {
    override fun invoke(processedChunks: Int, totalChunks: Int, arg: Long): Int {
        if (totalChunks > 0) onProgress(SpeakerSeparation.SHARE * processedChunks / totalChunks)
        return 0
    }
}
