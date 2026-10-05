package io.github.christiantwu.longhand.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import io.github.christiantwu.longhand.TAG
import java.io.Closeable
import java.util.concurrent.CancellationException

/** A transcript plus each speaker's voice fingerprint (used to recognise the phone's owner). */
class TranscriptResult(val lines: List<TranscriptLine>, val voices: Map<Int, FloatArray>)

/**
 * Turns decoded audio and who spoke when in it ([SpeakerSeparation]) into speaker-labelled, timestamped lines:
 * split long turns at pauses -> recognize each piece -> in English, write its numbers as digits.
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

    /** Parakeet v2 writes many numbers as words; the other models write them as digits themselves, or aren't English. */
    private val spokenNumbers = speech == Models.Set.SPEECH

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

    /**
     * @param separation who spoke when in [audio], from [SpeakerSeparation].
     * @param onProgress [SpeakerSeparation.SHARE]..1: separation is the part before.
     * @param isStopped polled between steps; throws [CancellationException] when true.
     */
    fun transcribe(audio: DecodedAudio, separation: Separation, onProgress: (Float) -> Unit, isStopped: () -> Boolean): TranscriptResult {
        val share = SpeakerSeparation.SHARE
        onProgress(share)
        // Speaker turns, each with the samples it is recognised from (a channel, or the mono mix).
        val turns = separation.turnsIn(audio)

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
                onProgress(share + (1 - share) * done / pieces.size)
            }
        }
        for (group in order.chunked(batch)) {
            if (isStopped()) throw CancellationException()
            val clips = group.mapNotNull { (span, samples) ->
                val from = (span.start * SR).toInt().coerceIn(0, samples.size)
                val to = (span.end * SR).toInt().coerceIn(from, samples.size)
                if (to - from >= MIN_PIECE_SAMPLES) span to samples.copyOfRange(from, to) else null
            }
            val results = streaming?.recognize(clips.map { it.second }, isStopped, step) ?: clips.map { recognize(it.second) }
            clips.zip(results) { (span, _), result ->
                if (result.text.isNotBlank()) {
                    lines += TranscriptLine((span.start * 1000).toLong(), (span.end * 1000).toLong(), span.speaker, result.text, result.words)
                }
            }
            done += group.size
            onProgress(share + (1 - share) * done / pieces.size)
        }
        lines.sortBy { it.startMs }
        return TranscriptResult(lines, separation.voices)
    }

    /** A turn as-is, or cut in the middle of pauses when it is longer than [MAX_PIECE_SEC]. */
    private fun splitTurn(turn: Span, samples: FloatArray): List<Pair<Float, Float>> {
        if (turn.duration <= MAX_PIECE_SEC) return listOf(turn.start to turn.end)
        val speech = speechRanges(samples, (turn.start * SR).toInt(), minOf((turn.end * SR).toInt(), samples.size))
        val pauses = speech.zipWithNext { a, b -> (a.second / SR) to (b.first / SR) }
        return SegmentLogic.splitAtPauses(turn.start, turn.end, pauses, MAX_PIECE_SEC)
    }

    /**
     * The piece's text, with when each word was said (from the tokens' times, and their durations for Parakeet). In
     * English, its numbers are then written as digits ([SpokenNumbers]); the timings are worked out before that, as
     * they must match the tokens' text, and follow the words into digits.
     */
    private fun recognize(samples: FloatArray): Recognized {
        val recognizer = checkNotNull(recognizer)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, MODEL_SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = SegmentLogic.cleanText(result.text)
            val words = try {
                WordTimings.fromTokens(text, result.tokens, result.timestamps, result.durations, lead = 0f, length = samples.size / SR)
            } catch (e: Exception) {
                // Only needed for splitting a line by hand later; the transcript doesn't depend on it.
                Log.w(TAG, "no word timings: ${e.message}")
                null
            }
            val recognized = Recognized(text, words)
            if (!spokenNumbers) return recognized
            return try {
                SpokenNumbers.write(recognized)
            } catch (e: Exception) {
                // Digits are a nicety: a line the converter trips on keeps its words rather than costing the transcript.
                Log.w(TAG, "numbers left as words: ${e.javaClass.simpleName}")
                recognized
            }
        } finally {
            stream.release()
        }
    }

    /** Speech regions within samples[from, to), as absolute sample ranges. */
    private fun speechRanges(samples: FloatArray, from: Int, to: Int): List<Pair<Int, Int>> =
        SpeechRanges.find(vadModel, samples, from, to)

    override fun close() {
        recognizer?.release()
        streaming?.close()
    }

    companion object {
        private const val SR = MODEL_SAMPLE_RATE.toFloat()
        private const val MAX_PIECE_SEC = 25f

        /** The shortest piece recognised (200 ms); speaker separation leaves out shorter pieces of turns too. */
        const val MIN_PIECE_SAMPLES = MODEL_SAMPLE_RATE / 5
    }
}
