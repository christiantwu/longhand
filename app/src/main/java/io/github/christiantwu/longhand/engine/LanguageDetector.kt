package io.github.christiantwu.longhand.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import java.io.Closeable

/**
 * Whisper base's spoken-language identification on a few stretches of a call's speech ([CallLanguage]). It takes about
 * 450 MB at its peak, so it's never loaded while a recognizer is. Create it only while [Models.canDetect] and holding
 * [Models.recognizerLock]: sherpa-onnx ends the whole process when a Whisper file isn't the multilingual one it expects.
 */
class LanguageDetector(context: Context) : Closeable {

    private val lid = Models.Set.LANGUAGE_ID.files.let { (encoder, decoder) ->
        SpokenLanguageIdentification(
            assetManager = null,
            config = SpokenLanguageIdentificationConfig(
                whisper = SpokenLanguageIdentificationWhisperConfig(
                    encoder = Models.file(context, encoder.path).absolutePath,
                    decoder = Models.file(context, decoder.path).absolutePath,
                    tailPaddings = -1,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
    }

    /**
     * What language the call in [samples] (16 kHz mono) is in. Only its [speech] counts (sample ranges in order, as
     * [Separation.speech] has them); with too little of it to go by, no window is identified.
     */
    fun detect(samples: FloatArray, speech: List<Pair<Int, Int>>): CallLanguage.Detection {
        val total = speech.sumOf { (a, b) -> b - a }
        val seconds = total.toFloat() / MODEL_SAMPLE_RATE
        if (seconds < CallLanguage.MIN_SPEECH_SECONDS) return CallLanguage.Detection(emptyList(), seconds)
        // Each window copied out of the call on its own, so the speech is never joined up in memory as a whole.
        val codes = CallLanguage.windows(total).map { (from, to) ->
            val window = FloatArray(to - from)
            var at = 0
            for ((a, b) in CallLanguage.pieces(speech, from, to)) {
                samples.copyInto(window, at, a, b)
                at += b - a
            }
            identify(window)
        }
        return CallLanguage.Detection(codes, seconds)
    }

    /** Whisper's language code for [window], e.g. "ja"; "" when it fails. */
    private fun identify(window: FloatArray): String {
        val stream = lid.createStream()
        try {
            stream.acceptWaveform(window, MODEL_SAMPLE_RATE)
            return lid.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() = lid.release()
}
