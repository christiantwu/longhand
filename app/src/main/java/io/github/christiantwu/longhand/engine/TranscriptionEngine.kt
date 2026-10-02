package io.github.christiantwu.longhand.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.christiantwu.longhand.TAG
import java.io.Closeable
import java.util.concurrent.CancellationException

/** A transcript plus each speaker's voice fingerprint (used to recognise the phone's owner). */
class TranscriptResult(val lines: List<TranscriptLine>, val voices: Map<Int, FloatArray>)

/**
 * Turns decoded audio into speaker-labelled, timestamped lines:
 * diarize (who spoke when) -> merge turns -> split long turns at pauses -> Parakeet ASR.
 * [speech] picks the recognizer: English (Parakeet v2) or 25 European languages (v3), both NeMo
 * transducers that sherpa-onnx loads the same way, or Chinese, Japanese and Korean (SenseVoice Small),
 * all run by sherpa-onnx's offline recognizer; or Hindi (Nemotron 3.5 ASR Streaming), a streaming
 * transducer run by its online recognizer ([StreamingRecognizer]).
 *
 * Loading the models takes a few seconds and ~1 GB of native memory, so one engine is
 * created per batch of recordings and closed afterwards.
 */
class TranscriptionEngine(context: Context, speech: Models.Set) : Closeable {

    init {
        requireNotNull(speech.recognizerDir) { "$speech has no recognizer" }
    }

    private val vadModel = Models.file(context, "silero_vad.onnx").absolutePath
    private val threads = 4
    private val voiceAnalyzer = VoiceAnalyzer(context, threads = 2)

    /** Hindi's recognizer; the other languages use [recognizer]. */
    private val streaming = if (speech == Models.Set.HINDI) StreamingRecognizer(context, speech, threads) else null

    private val recognizer = if (streaming != null) null else OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            modelConfig = run {
                // A file that replaces an earlier one is loaded once downloaded; until then the earlier one is.
                fun path(kind: String) = Models.fileInUse(context, speech.part(kind)).absolutePath
                if (speech == Models.Set.CJK) {
                    OfflineModelConfig(
                        // The language is detected for each piece. Inverse text normalization adds
                        // punctuation and writes numbers as digits.
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = path("model"), language = "auto", useInverseTextNormalization = true,
                        ),
                        tokens = path("tokens"),
                        numThreads = threads,
                    )
                } else {
                    OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = path("encoder"),
                            decoder = path("decoder"),
                            joiner = path("joiner"),
                        ),
                        tokens = path("tokens"),
                        modelType = "nemo_transducer",
                        numThreads = threads,
                    )
                }
            },
            decodingMethod = "greedy_search",
        ),
    )

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
     * @param onProgress 0..1; diarization is the first 30%, recognition the rest.
     * @param isStopped polled between steps; throws [CancellationException] when true.
     */
    fun transcribe(audio: DecodedAudio, owner: FloatArray?, onProgress: (Float) -> Unit, isStopped: () -> Boolean): TranscriptResult {
        val stereo = audio.channels.size == 2 &&
            StereoAnalysis.channelsAreDistinct(audio.channels[0], audio.channels[1])
        Log.i(TAG, "transcribe: ${audio.durationMs} ms, channels=${audio.channels.size}, stereoSpeakers=$stereo")

        // Speaker turns, each with the samples it is recognised from (a channel, or the mono mix).
        val separated = if (stereo) Separated(stereoTurns(audio), emptyMap()) else monoTurns(audio.mono, owner, onProgress, isStopped)
        val turns = separated.turns
        // A speaker with too little clean speech for a fingerprint of their own keeps the one
        // speaker separation made, so the owner can still be recognised among them.
        val voices = separated.voices + voiceAnalyzer.voices(turns)
        onProgress(0.3f)

        // Long turns are cut at pauses into contiguous pieces, so recognition memory stays
        // bounded without dropping any audio between pieces.
        val pieces = turns.flatMap { (turn, samples) ->
            splitTurn(turn, samples).map { (a, b) -> Span(a, b, turn.speaker) to samples }
        }

        val lines = ArrayList<TranscriptLine>()
        // The streaming recognizer decodes several pieces together; the offline one takes them one at a time. A batch
        // runs until its longest piece is done, so pieces of similar length go together (lines are put back in order).
        val batch = if (streaming != null) StreamingRecognizer.BATCH else 1
        val order = if (streaming != null) pieces.sortedBy { (span, _) -> span.end - span.start } else pieces
        var done = 0
        var reported = 0L
        // Also reported while a batch decodes, at most every 2 s: the worker learns there that the call was deleted.
        val step = {
            val now = SystemClock.elapsedRealtime()
            if (now - reported >= 2_000) {
                reported = now
                onProgress(0.3f + 0.7f * done / pieces.size)
            }
        }
        for (group in order.chunked(batch)) {
            if (isStopped()) throw CancellationException()
            val clips = group.mapNotNull { (span, samples) ->
                val from = (span.start * SR).toInt().coerceIn(0, samples.size)
                val to = (span.end * SR).toInt().coerceIn(from, samples.size)
                if (to - from >= MIN_PIECE_SAMPLES) span to samples.copyOfRange(from, to) else null
            }
            val texts = streaming?.recognize(clips.map { it.second }, isStopped, step) ?: clips.map { recognize(it.second) }
            clips.zip(texts) { (span, _), text ->
                if (text.isNotBlank()) {
                    lines += TranscriptLine((span.start * 1000).toLong(), (span.end * 1000).toLong(), span.speaker, text)
                }
            }
            done += group.size
            onProgress(0.3f + 0.7f * done / pieces.size)
        }
        lines.sortBy { it.startMs }
        return TranscriptResult(lines, voices)
    }

    /** Speaker turns with the samples they come from, and the voices speaker separation found. */
    private class Separated(val turns: List<Pair<Span, FloatArray>>, val voices: Map<Int, FloatArray>)

    /** Who spoke when, from voice clustering over the whole call. */
    private fun monoTurns(samples: FloatArray, owner: FloatArray?, onProgress: (Float) -> Unit, isStopped: () -> Boolean): Separated {
        val raw = diarizer.processWithCallback(samples, DiarizationProgress(onProgress), 0L)
            .map { Span(it.start, it.end, it.speaker) }
        if (isStopped()) throw CancellationException()
        // Very short or single-voice clips can come back empty; fall back to plain speech detection.
        if (raw.isEmpty()) return Separated(SegmentLogic.merge(speechSpans(samples, 0, samples.size, speaker = 0)).map { it to samples }, emptyMap())

        val fingerprints = SpeakerResolver.fingerprintPlan(raw)
            .mapNotNull { (cluster, parts) -> voiceAnalyzer.fingerprint(samples, parts)?.let { cluster to it } }
            .toMap()
        val resolved = SpeakerResolver.resolve(raw, fingerprints, owner)
        val numbered = SegmentLogic.relabelByFirstAppearance(resolved.spans)
        val renumber = resolved.spans.zip(numbered).associate { (a, b) -> a.speaker to b.speaker }
        Log.i(TAG, "speakers: ${raw.map { it.speaker }.distinct().size} clusters, " +
            "${fingerprints.size} fingerprinted, ${renumber.size} people")
        return Separated(
            SegmentLogic.merge(numbered).map { it to samples },
            resolved.voices.entries.mapNotNull { (speaker, v) -> renumber[speaker]?.let { it to v } }.toMap(),
        )
    }

    /** Each side of the call on its own channel: the channel is the speaker. */
    private fun stereoTurns(audio: DecodedAudio): List<Pair<Span, FloatArray>> {
        val out = ArrayList<Pair<Span, FloatArray>>()
        for (ch in 0..1) {
            val own = audio.channels[ch]
            val other = audio.channels[1 - ch]
            val spans = speechSpans(own, 0, audio.sampleCount, speaker = ch).filter { span ->
                // Drop echo/bleed: speech in this channel that is much louder in the other one.
                val a = (span.start * SR).toInt()
                val b = (span.end * SR).toInt()
                StereoAnalysis.rms(own, a, b) * 2 >= StereoAnalysis.rms(other, a, b)
            }
            SegmentLogic.merge(spans).forEach { out += it to own }
        }
        return out.sortedBy { it.first.start }
    }

    /**
     * Speech regions as spans, padded a little: speech detection reacts slightly after a word
     * starts, and a clipped first syllable often loses the whole word.
     */
    private fun speechSpans(samples: FloatArray, from: Int, to: Int, speaker: Int): List<Span> =
        speechRanges(samples, from, to).map { (a, b) ->
            Span(maxOf(a - PAD_BEFORE, from) / SR, minOf(b + PAD_AFTER, to) / SR, speaker)
        }

    /** A turn as-is, or cut in the middle of pauses when it is longer than [MAX_PIECE_SEC]. */
    private fun splitTurn(turn: Span, samples: FloatArray): List<Pair<Float, Float>> {
        if (turn.duration <= MAX_PIECE_SEC) return listOf(turn.start to turn.end)
        val speech = speechRanges(samples, (turn.start * SR).toInt(), minOf((turn.end * SR).toInt(), samples.size))
        val pauses = speech.zipWithNext { a, b -> (a.second / SR) to (b.first / SR) }
        return SegmentLogic.splitAtPauses(turn.start, turn.end, pauses, MAX_PIECE_SEC)
    }

    private fun recognize(samples: FloatArray): String {
        val recognizer = checkNotNull(recognizer)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, MODEL_SAMPLE_RATE)
            recognizer.decode(stream)
            return SegmentLogic.cleanText(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    /** Speech regions within samples[from, to), as absolute sample ranges. */
    private fun speechRanges(samples: FloatArray, from: Int, to: Int): List<Pair<Int, Int>> {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel,
                    threshold = 0.5f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    // Only used to find pauses; long speech is split by splitTurn, not here.
                    maxSpeechDuration = 120f,
                ),
                sampleRate = MODEL_SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        try {
            val out = ArrayList<Pair<Int, Int>>()
            fun drain() {
                while (!vad.empty()) {
                    val seg = vad.front()
                    out += (from + seg.start) to (from + seg.start + seg.samples.size)
                    vad.pop()
                }
            }
            var i = from
            while (i + VAD_WINDOW <= to) {
                vad.acceptWaveform(samples.copyOfRange(i, i + VAD_WINDOW))
                drain()
                i += VAD_WINDOW
            }
            vad.flush()
            drain()
            return out
        } finally {
            vad.release()
        }
    }

    override fun close() {
        recognizer?.release()
        streaming?.close()
        diarizer.release()
        voiceAnalyzer.close()
    }

    private companion object {
        /** Diarization's clustering threshold: larger merges more; see SpeakerResolver for the rest. */
        const val CLUSTER_THRESHOLD = 0.8f
        const val SR = MODEL_SAMPLE_RATE.toFloat()
        const val VAD_WINDOW = 512
        const val MAX_PIECE_SEC = 25f
        const val MIN_PIECE_SAMPLES = MODEL_SAMPLE_RATE / 5 // 200 ms
        const val PAD_BEFORE = MODEL_SAMPLE_RATE / 5 // 200 ms
        const val PAD_AFTER = MODEL_SAMPLE_RATE / 10 // 100 ms
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
        if (totalChunks > 0) onProgress(0.3f * processedChunks / totalChunks)
        return 0
    }
}
