package io.github.christiantwu.longhand.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.christiantwu.longhand.TAG
import java.io.Closeable
import java.util.concurrent.CancellationException

/**
 * Hindi's recognizer, NVIDIA Nemotron 3.5 ASR Streaming: a streaming transducer, which sherpa-onnx runs with its online
 * recognizer instead of the offline one the other models use. Each piece of a call goes in whole, followed by silence
 * that flushes its last chunk through the model, and is decoded to the end. Pieces are decoded [BATCH] at a time, all
 * in the same runs of the model, which takes much less time than one after another.
 */
class StreamingRecognizer(context: Context, speech: Models.Set, threads: Int) : Closeable {

    private val recognizer = OnlineRecognizer(
        assetManager = null,
        config = OnlineRecognizerConfig(
            modelConfig = run {
                fun path(kind: String) = Models.fileInUse(context, speech.part(kind)).absolutePath
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = path("encoder"),
                        decoder = path("decoder"),
                        joiner = path("joiner"),
                    ),
                    tokens = path("tokens"),
                    numThreads = threads,
                )
            },
            // Each piece is decoded to its end: endpoints, the pauses that end an utterance in live audio, don't apply.
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        ),
    )

    /**
     * The text of each of [clips] (16 kHz audio), cleaned up as the other models' is. The model detects the language of
     * each piece, which keeps turns spoken in English in Latin letters; a piece it writes in another script (some short
     * ones come out in Cyrillic, say) is decoded again as Hindi.
     * @param isStopped polled between decoding steps; throws [CancellationException] when true.
     * @param onStep called after each decoding step.
     */
    fun recognize(clips: List<FloatArray>, isStopped: () -> Boolean, onStep: () -> Unit = {}): List<String> {
        val texts = decode(clips, AUTO, isStopped, onStep).toMutableList()
        val again = texts.indices.filter { Scripts.hasOtherScript(texts[it]) }
        if (again.isNotEmpty()) {
            decode(again.map { clips[it] }, HINDI, isStopped, onStep).forEachIndexed { k, text -> texts[again[k]] = text }
        }
        return texts.map(SegmentLogic::cleanText)
    }

    private fun decode(clips: List<FloatArray>, language: String, isStopped: () -> Boolean, onStep: () -> Unit): List<String> {
        val streams = ArrayList<OnlineStream>(clips.size)
        try {
            for (samples in clips) {
                streams += recognizer.createStream().apply {
                    setOption("language", language)
                    acceptWaveform(LEAD_PADDING, MODEL_SAMPLE_RATE)
                    acceptWaveform(samples, MODEL_SAMPLE_RATE)
                    acceptWaveform(TAIL_PADDING, MODEL_SAMPLE_RATE)
                    inputFinished()
                }
            }
            // Pieces of different lengths run out at different steps; each step decodes those with audio left.
            while (true) {
                if (isStopped()) throw CancellationException()
                val ready = streams.filter { recognizer.isReady(it) }
                if (ready.isEmpty()) break
                if (ready.size == 1 || !OnlineBatch.decode(recognizer, ready)) ready.forEach { recognizer.decode(it) }
                onStep()
            }
            return streams.map { recognizer.getResult(it).text }
        } finally {
            streams.forEach { it.release() }
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /**
         * Pieces decoded together. In desktop tests of the model, batches of 4 to 8 took about 1.8 times Parakeet v3's
         * time, against 2.5 one by one.
         */
        const val BATCH = 6
        /** The language option: detected per piece, or Hindi for a second try. */
        private const val AUTO = "auto"
        private const val HINDI = "hi"
        /**
         * 0.3 s of silence before each piece: a turn starts right at the speech, and without a moment of lead-in the
         * model drops the first word (Hindi FLEURS through the default recording: first word right in 15 of 40
         * pieces without it, 31 with it).
         */
        private val LEAD_PADDING = FloatArray(MODEL_SAMPLE_RATE * 3 / 10)
        /** 1.5 s of silence after each piece, so its last chunk is decoded. */
        private val TAIL_PADDING = FloatArray(MODEL_SAMPLE_RATE * 3 / 2)
    }
}

/**
 * sherpa-onnx's batched decoding: one step for several streams in a single run of the model. libsherpa-onnx-jni.so has
 * it, but the Kotlin class in the 1.13.8 AAR doesn't declare it, so nothing in Kotlin can reach it;
 * libonline-batch.so (cpp/online_batch.c) calls it instead.
 */
private object OnlineBatch {
    private val loaded = runCatching { System.loadLibrary("online-batch") }
        .onFailure { Log.w(TAG, "batched decoding isn't available; decoding one piece at a time", it) }
        .isSuccess

    @Volatile private var warned = false

    /** One decoding step for each of [streams], together; false, with nothing decoded, when that isn't available. */
    fun decode(recognizer: OnlineRecognizer, streams: List<OnlineStream>): Boolean {
        if (!loaded) return false
        val done = nativeDecode(recognizer, LongArray(streams.size) { streams[it].ptr })
        if (!done && !warned) {
            warned = true
            Log.w(TAG, "batched decoding isn't in this sherpa-onnx; decoding one piece at a time")
        }
        return done
    }

    @JvmStatic private external fun nativeDecode(recognizer: OnlineRecognizer, streams: LongArray): Boolean
}
