package io.github.christiantwu.longhand.engine

import android.content.Context
import android.os.SystemClock
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
         * mono mix has every voice in it and an overlap would be transcribed twice, then [split] by voice
         * ([VoiceSplit.resplit]), given the voices of the speakers who have turns. Speakers are numbered in the order
         * they're first heard; one left with no turns (everything they said was said over someone else, or sounded
         * like others) is gone, voice and all, so it can't be taken for the owner or leave a gap in the numbers.
         */
        fun mono(
            spans: List<Span>,
            voices: Map<Int, FloatArray>,
            speech: List<Pair<Int, Int>>,
            split: (List<Span>, Map<Int, FloatArray>) -> List<Span> = { turns, _ -> turns },
        ): Separation {
            val joined = SegmentLogic.withoutOverlaps(SegmentLogic.merge(spans), MIN_PIECE_SEC)
            val heard = joined.mapTo(HashSet()) { it.speaker }
            val turns = split(joined, voices.filterKeys { it in heard })
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
     * @param isStopped polled after diarization and between the windows fingerprinted to split turns by voice; throws
     * [CancellationException] when true.
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
        // Diarized with the silence after the call (DecodedAudio.TAIL); what it finds there is cut off.
        val progress = DiarizationProgress(SHARE * DIARIZATION_SHARE, onProgress)
        val heard = diarizer.processWithCallback(audio.monoWithTail(), progress, 0L).map { Span(it.start, it.end, it.speaker) }
        val raw = SegmentLogic.upTo(heard, audio.seconds)
        if (isStopped()) throw CancellationException()
        // Very short or single-voice clips can come back empty; fall back to plain speech detection.
        if (raw.isEmpty()) {
            return ofTurns(SegmentLogic.merge(speechSpans(samples, 0, audio.sampleCount, speaker = 0)).map { Separation.Turn(it) }, audio)
        }

        val fingerprints = SpeakerResolver.fingerprintPlan(raw)
            .mapNotNull { (cluster, parts) -> voiceAnalyzer.fingerprint(samples, parts)?.let { cluster to it } }
            .toMap()
        val resolved = SpeakerResolver.resolve(raw, fingerprints, owner)
        // A speaker with too little clean speech for a fingerprint of their own keeps the one speaker separation made,
        // so the owner can still be recognised among them. Taken from the turns before they're made not to overlap,
        // which still show where someone else talks over a speaker (their voice would be mixed in).
        val voices = resolved.voices + voiceAnalyzer.voices(SegmentLogic.merge(resolved.spans).map { it to samples })
        // Everything diarization heard anyone say, padded as recognition pads speech: it hears quieter and noisier
        // voices than speech detection does.
        val speech = CallLanguage.speech(raw, audio.sampleCount, SpeechRanges.PAD_BEFORE, SpeechRanges.PAD_AFTER)
        val found = Separation.mono(resolved.spans, voices, speech) { turns, theirs ->
            splitByVoice(samples, turns, theirs, onProgress, isStopped)
        }
        Log.i(TAG, "speakers: ${raw.map { it.speaker }.distinct().size} clusters, " +
            "${fingerprints.size} fingerprinted, ${found.turns.map { it.span.speaker }.distinct().size} people")
        return found
    }

    /**
     * [turns] split where another speaker's voice takes over ([VoiceSplit]), by fingerprinting each window on its own
     * and scoring it against the speakers' [voices]: the rest of separation's progress after diarization.
     */
    private fun splitByVoice(
        samples: FloatArray, turns: List<Span>, voices: Map<Int, FloatArray>, onProgress: (Float) -> Unit, isStopped: () -> Boolean,
    ): List<Span> {
        val speakers = voices.keys.sorted()
        val theirs = speakers.map { voices.getValue(it) }
        val started = SystemClock.elapsedRealtime()
        var windows = 0
        val split = VoiceSplit.resplit(turns, speakers, onWindow = { done, total ->
            windows = total
            onProgress(SHARE * (DIARIZATION_SHARE + (1 - DIARIZATION_SHARE) * done / total))
        }) { start, end ->
            if (isStopped()) throw CancellationException()
            voiceAnalyzer.fingerprint(samples, listOf(start to end))?.let { v ->
                FloatArray(theirs.size) { VoiceMath.cosine(v, theirs[it]) }
            }
        }
        Log.i(TAG, "split by voice: $windows windows in ${SystemClock.elapsedRealtime() - started} ms, " +
            "${turns.size} -> ${split.size} turns")
        return split
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
        val found = Separation(turns, emptyMap(), CallLanguage.speech(turns.map { it.span }, audio.sampleCount))
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

        /**
         * How much of separating a call is diarization, in progress reported: splitting turns by voice is the rest
         * (11–18% of the time on sample calls, on desktop).
         */
        private const val DIARIZATION_SHARE = 0.85f

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
class DiarizationProgress(private val share: Float, private val onProgress: (Float) -> Unit) : (Int, Int, Long) -> Int {
    override fun invoke(processedChunks: Int, totalChunks: Int, arg: Long): Int {
        if (totalChunks > 0) onProgress(share * processedChunks / totalChunks)
        return 0
    }
}
