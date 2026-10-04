package io.github.christiantwu.longhand.engine

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Speech detection (Silero VAD): finds the pauses long turns are cut at, and the speech of a call that diarization finds
 * nobody in, or of each side of a stereo recording with one on each channel.
 */
object SpeechRanges {
    const val WINDOW = 512
    /** Padding around detected speech: detection reacts slightly after a word starts. */
    const val PAD_BEFORE = MODEL_SAMPLE_RATE / 5 // 200 ms
    const val PAD_AFTER = MODEL_SAMPLE_RATE / 10 // 100 ms

    /** Speech regions within samples[from, to), as absolute sample ranges, unpadded. [vadModel] is silero_vad.onnx. */
    fun find(vadModel: String, samples: FloatArray, from: Int, to: Int): List<Pair<Int, Int>> {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel,
                    threshold = 0.5f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW,
                    // Only used to find pauses; long speech is split by the engine, not here.
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
            while (i + WINDOW <= to) {
                vad.acceptWaveform(samples.copyOfRange(i, i + WINDOW))
                drain()
                i += WINDOW
            }
            vad.flush()
            drain()
            return out
        } finally {
            vad.release()
        }
    }
}
