package io.github.christiantwu.longhand.engine

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import java.io.File

/**
 * The on-device models, downloaded once into the app's internal storage
 * (/data/data/<package>/files/models). See the README for copying them in with adb
 * during development instead of downloading.
 */
object Models {

    data class ModelFile(val path: String, val url: String, val sizeBytes: Long, val sha256: String)

    private const val HF = "https://huggingface.co/csukuangfj"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val PARAKEET = "$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
    private const val PARAKEET_V3 = "$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    private const val SENSE_VOICE = "$HF/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    /** Speaker separation and pause detection, the same whichever language is transcribed. */
    private val SPEAKER_MODELS = listOf(
        ModelFile("segmentation.onnx", "$HF/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx", 5_992_913,
            "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"),
        ModelFile("embedding.onnx", "$GH/speaker-recongition-models/nemo_en_titanet_small.onnx", 40_257_283,
            "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"),
        ModelFile("silero_vad.onnx", "$GH/asr-models/silero_vad.onnx", 643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )

    /**
     * The groups the app downloads separately. Transcription needs one of the speech sets, which share
     * the speaker models and differ in their recognizer; summaries are optional.
     */
    enum class Set(val files: List<ModelFile>, val recognizerDir: String? = null) {
        /** English: Parakeet TDT 0.6B v2, the most accurate of the two for English. */
        SPEECH(
            listOf(
                ModelFile("parakeet/encoder.int8.onnx", "$PARAKEET/encoder.int8.onnx", 652_184_296,
                    "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                ModelFile("parakeet/decoder.int8.onnx", "$PARAKEET/decoder.int8.onnx", 7_257_753,
                    "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                ModelFile("parakeet/joiner.int8.onnx", "$PARAKEET/joiner.int8.onnx", 1_739_080,
                    "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                ModelFile("parakeet/tokens.txt", "$PARAKEET/tokens.txt", 9_384,
                    "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            ) + SPEAKER_MODELS,
            recognizerDir = "parakeet",
        ),

        /** 25 European languages, detected per call: Parakeet TDT 0.6B v3. */
        MULTILINGUAL(
            listOf(
                ModelFile("parakeet-v3/encoder.int8.onnx", "$PARAKEET_V3/encoder.int8.onnx", 652_184_281,
                    "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                ModelFile("parakeet-v3/decoder.int8.onnx", "$PARAKEET_V3/decoder.int8.onnx", 11_845_275,
                    "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                ModelFile("parakeet-v3/joiner.int8.onnx", "$PARAKEET_V3/joiner.int8.onnx", 6_355_277,
                    "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                ModelFile("parakeet-v3/tokens.txt", "$PARAKEET_V3/tokens.txt", 93_939,
                    "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            ) + SPEAKER_MODELS,
            recognizerDir = "parakeet-v3",
        ),

        /** Chinese (Mandarin), Cantonese, Japanese, Korean and English, detected per call: SenseVoice Small. */
        CJK(
            listOf(
                ModelFile("sensevoice/model.int8.onnx", "$SENSE_VOICE/model.int8.onnx", 239_233_841,
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
                ModelFile("sensevoice/tokens.txt", "$SENSE_VOICE/tokens.txt", 315_894,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            ) + SPEAKER_MODELS,
            recognizerDir = "sensevoice",
        ),

        /** Qwen 3.5 4B (Apache 2.0), Q4_0: the quantization llama.cpp runs fastest on phone CPUs. */
        SUMMARY(
            listOf(
                ModelFile(SUMMARY_MODEL, "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf",
                    2_583_221_408, "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb"),
            ),
        );

        val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    }

    const val SUMMARY_MODEL = "llm/qwen3.5-4b-q4_0.gguf"

    fun dir(context: Context): File = File(context.filesDir, "models")

    fun file(context: Context, path: String) = File(dir(context), path)

    /** A size check is enough here; hashes are verified when the download completes. */
    fun isInstalled(context: Context, set: Set): Boolean =
        set.files.all { file(context, it.path).length() == it.sizeBytes }

    /** The transcription languages to choose from, each with the speech set it needs. */
    enum class Language(val set: Set) { ENGLISH(Set.SPEECH), EUROPEAN(Set.MULTILINGUAL), CJK(Set.CJK) }

    // A getter: computed while Models initializes, it would read Language half-built when Language's
    // own initialization is what started it (Language → Set → SPEAKER_MODELS → Models).
    val speechSets: List<Set> get() = Language.entries.map { it.set }

    /** Calls can be transcribed: the speaker models and some recognizer are in place. */
    fun speechReady(context: Context): Boolean = speechSets.any { isInstalled(context, it) }

    /**
     * The speech set to transcribe with: the [chosen] one once it's downloaded, until then whichever
     * is installed (a new language keeps the old one working while it downloads); null if none is.
     */
    fun recognizer(context: Context, chosen: Set): Set? =
        chosen.takeIf { isInstalled(context, it) } ?: speechSets.firstOrNull { isInstalled(context, it) }

    /**
     * Held while a recognizer is chosen and loaded, and while one is deleted, so a model can't
     * disappear halfway through loading (the native loader would abort the app).
     */
    val recognizerLock = Mutex()

    /**
     * Deletes [sets]' recognizers, and any partial downloads of them, freeing 240–670 MB each. The
     * speaker models every speech set shares stay. Call it holding [recognizerLock].
     */
    fun removeRecognizers(context: Context, sets: List<Set>) {
        val shared = SPEAKER_MODELS.toSet()
        for (f in sets.flatMap { it.files } - shared) {
            file(context, f.path).delete()
            File(file(context, f.path).path + ".part").delete()
        }
    }

    /** How much of [set] is still to download (files already in place, such as shared ones, don't count). */
    fun missingBytes(context: Context, set: Set): Long =
        set.files.filter { file(context, it.path).length() != it.sizeBytes }.sumOf { it.sizeBytes }

    fun remove(context: Context, set: Set) {
        set.files.forEach { file(context, it.path).delete() }
    }
}
