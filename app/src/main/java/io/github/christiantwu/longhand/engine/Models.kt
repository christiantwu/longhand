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

    /**
     * @param replaces an earlier file this one supersedes, under another path so an install of it is never taken
     *   for this one. Until this file is downloaded, the earlier one stands in for it (see [isInstalled]).
     */
    data class ModelFile(
        val path: String, val url: String, val sizeBytes: Long, val sha256: String, val replaces: Replaced? = null,
    )

    /** A file earlier versions of the app downloaded, recognised by its size. */
    data class Replaced(val path: String, val sizeBytes: Long)

    private const val HF = "https://huggingface.co/csukuangfj"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val PARAKEET = "$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
    private const val PARAKEET_V3 = "$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    private const val SENSE_VOICE = "$HF/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    /** Models Longhand hosts itself, as assets of a GitHub release. */
    private const val LONGHAND = "https://github.com/christiantwu/longhand/releases/download/models-1"
    /** The Hindi model's files in that release, each name followed by "-encoder.int8.onnx" and so on. */
    private const val NEMOTRON = "$LONGHAND/nemotron-3.5-asr-streaming-0.6b-1120ms"
    private const val WHISPER_BASE = "$HF/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc"

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
     * the speaker models and differ in their recognizer; language detection and summaries are optional.
     */
    enum class Set(val files: List<ModelFile>, val recognizerDir: String? = null) {
        /**
         * English: Parakeet TDT 0.6B v2, the most accurate of the two for English. The encoder is Longhand's own int8
         * quantization of sherpa-onnx's fp32 export, made like the European languages' (tools/requantize_parakeet.py v2,
         * see [MULTILINGUAL]); on phone calls in the Phone app's default recording it makes about 11% fewer errors than
         * sherpa-onnx's int8 encoder. Earlier installs have that encoder, which keeps working until this one is in place.
         */
        SPEECH(
            listOf(
                ModelFile("parakeet/encoder.repaired.int8.onnx", "$LONGHAND/parakeet-tdt-0.6b-v2-encoder.int8.onnx",
                    665_796_732, "0db696759cccf970a2fb532948bd9c43af576c51551cfcff71ed955f926d8413",
                    replaces = Replaced("parakeet/encoder.int8.onnx", 652_184_296)),
                ModelFile("parakeet/decoder.int8.onnx", "$PARAKEET/decoder.int8.onnx", 7_257_753,
                    "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                ModelFile("parakeet/joiner.int8.onnx", "$PARAKEET/joiner.int8.onnx", 1_739_080,
                    "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                ModelFile("parakeet/tokens.txt", "$PARAKEET/tokens.txt", 9_384,
                    "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            ) + SPEAKER_MODELS,
            recognizerDir = "parakeet",
        ),

        /**
         * 25 European languages, detected per call: Parakeet TDT 0.6B v3. The encoder is Longhand's own int8
         * quantization of sherpa-onnx's fp32 export (tools/requantize_parakeet.py v3): sherpa-onnx's int8 encoder
         * quantizes the pre-encode (subsampling) stage and the depthwise convolutions too, which costs a lot of accuracy, most on phone
         * audio. Earlier installs have that encoder, which keeps working until this one is in place.
         */
        MULTILINGUAL(
            listOf(
                ModelFile("parakeet-v3/encoder.repaired.int8.onnx", "$LONGHAND/parakeet-tdt-0.6b-v3-encoder.int8.onnx",
                    665_796_726, "013290f8001e0434a33bfc1f4ea2a9039107a878d894a51a212c48802b58efac",
                    replaces = Replaced("parakeet-v3/encoder.int8.onnx", 652_184_281)),
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

        /**
         * Hindi and English, detected per turn, including calls that mix them: NVIDIA Nemotron 3.5 ASR Streaming 0.6B, in
         * sherpa-onnx's int8 export with 1120 ms chunks, unmodified. sherpa-onnx publishes it only inside one archive, so
         * Longhand hosts the four files. A streaming transducer, run by sherpa-onnx's online recognizer ([StreamingRecognizer]).
         */
        HINDI(
            listOf(
                ModelFile("nemotron/encoder.int8.onnx", "$NEMOTRON-encoder.int8.onnx", 657_601_521,
                    "2fff2166acaa535bd969fb223c1f0783d71029f143cb298bc54c2afe85abf772"),
                ModelFile("nemotron/decoder.int8.onnx", "$NEMOTRON-decoder.int8.onnx", 14_978_075,
                    "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0"),
                ModelFile("nemotron/joiner.int8.onnx", "$NEMOTRON-joiner.int8.onnx", 9_504_438,
                    "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce"),
                ModelFile("nemotron/tokens.txt", "$NEMOTRON-tokens.txt", 131_440,
                    "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a"),
            ) + SPEAKER_MODELS,
            recognizerDir = "nemotron",
        ),

        /**
         * Which downloaded language each call is in ([CallLanguage]): Whisper base, multilingual, in sherpa-onnx's int8
         * export (MIT, OpenAI). Not a speech set: wanted only with two or more languages to choose between
         * ([languageIdWanted]).
         */
        LANGUAGE_ID(
            listOf(
                ModelFile("whisper/base-encoder.int8.onnx", "$WHISPER_BASE/base-encoder.int8.onnx", 29_120_534,
                    "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
                ModelFile("whisper/base-decoder.int8.onnx", "$WHISPER_BASE/base-decoder.int8.onnx", 130_672_026,
                    "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
            ),
        ),

        /** Qwen 3.5 4B (Apache 2.0), Q4_0: the quantization llama.cpp runs fastest on phone CPUs. */
        SUMMARY(
            listOf(
                ModelFile(SUMMARY_MODEL, "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf",
                    2_583_221_408, "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb"),
            ),
        );

        val totalBytes: Long get() = files.sumOf { it.sizeBytes }

        /** The recognizer's [kind] file: "encoder", "decoder", "joiner", "model" or "tokens". */
        fun part(kind: String): ModelFile = files.first { it.path.startsWith("$recognizerDir/$kind.") }
    }

    const val SUMMARY_MODEL = "llm/qwen3.5-4b-q4_0.gguf"

    fun dir(context: Context): File = File(context.filesDir, "models")

    fun file(context: Context, path: String) = File(dir(context), path)

    /**
     * The file sizes in the model folder by path, 0 for a missing file: all the checks below look at. A size
     * check is enough, since hashes are verified when a download completes and only then is a file put in place.
     */
    fun interface Sizes {
        fun of(path: String): Long
    }

    fun sizes(context: Context) = Sizes { file(context, it).length() }

    private fun ModelFile.inPlace(sizes: Sizes) = sizes.of(path) == sizeBytes

    /** The earlier file standing in for this one while it isn't downloaded yet, if there is one. */
    private fun ModelFile.standIn(sizes: Sizes): Replaced? =
        replaces?.takeIf { !inPlace(sizes) && sizes.of(it.path) == it.sizeBytes }

    /** [set] can be used: each file is in place, or the earlier file it replaces still is. */
    fun isInstalled(set: Set, sizes: Sizes): Boolean = set.files.all { it.inPlace(sizes) || it.standIn(sizes) != null }

    fun isInstalled(context: Context, set: Set): Boolean = isInstalled(set, sizes(context))

    /** [set] works, but on an earlier file that a newer one replaces: the newer one is still to download. */
    fun needsUpdate(set: Set, sizes: Sizes): Boolean = isInstalled(set, sizes) && set.files.any { !it.inPlace(sizes) }

    fun needsUpdate(context: Context, set: Set): Boolean = needsUpdate(set, sizes(context))

    /**
     * How much of [set] is still to download. Files already in place, such as the shared speaker models, don't
     * count; an earlier file standing in for a newer one doesn't make the newer one any smaller.
     */
    fun missingBytes(set: Set, sizes: Sizes): Long = set.files.filter { !it.inPlace(sizes) }.sumOf { it.sizeBytes }

    fun missingBytes(context: Context, set: Set): Long = missingBytes(set, sizes(context))

    /** The path to load for [f]: its own, or while it downloads, that of the earlier file standing in for it. */
    fun pathInUse(f: ModelFile, sizes: Sizes): String = f.standIn(sizes)?.path ?: f.path

    fun fileInUse(context: Context, f: ModelFile): File = file(context, pathInUse(f, sizes(context)))

    /** Every file transcribing with [set] would load now; a change means its [TranscriptionEngine] should load again. */
    fun filesInUse(set: Set, sizes: Sizes): List<String> = set.files.map { pathInUse(it, sizes) }

    fun filesInUse(context: Context, set: Set): List<String> = filesInUse(set, sizes(context))

    /**
     * Files earlier versions left in [set] that nothing will use: a replaced file once its replacement is in
     * place, and a partial download of a replaced file, which is never finished now.
     */
    fun obsoletePaths(set: Set, sizes: Sizes): List<String> = set.files.mapNotNull { f -> f.replaces?.let { f to it } }
        .flatMap { (f, old) ->
            listOfNotNull(
                old.path.takeIf { sizes.of(it) > 0 && (f.inPlace(sizes) || sizes.of(it) != old.sizeBytes) },
                "${old.path}.part".takeIf { sizes.of(it) > 0 },
            )
        }

    /** Deletes [obsoletePaths]. Call it holding [recognizerLock]. */
    fun deleteObsolete(context: Context, set: Set) {
        obsoletePaths(set, sizes(context)).forEach { file(context, it).delete() }
    }

    /** The transcription languages to choose from, each with the speech set it needs. */
    enum class Language(val set: Set) {
        ENGLISH(Set.SPEECH), EUROPEAN(Set.MULTILINGUAL), CJK(Set.CJK), HINDI(Set.HINDI);

        companion object {
            /** ISO 639-1 codes of Parakeet v3's 25 European languages. */
            private val EUROPEAN_CODES = setOf(
                "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
                "lv", "lt", "mt", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sv", "uk",
            )

            /**
             * The language to start with on a phone set to [code] (ISO 639): English for English, else the
             * model that covers the phone's language, else English. People mostly call in their phone's
             * language, and the English model turns any other language into made-up English.
             */
            fun forPhoneLanguage(code: String): Language = when (code.lowercase()) {
                "en" -> ENGLISH
                in EUROPEAN_CODES -> EUROPEAN
                "zh", "ja", "ko", "yue" -> CJK
                "hi" -> HINDI
                else -> ENGLISH
            }
        }
    }

    // A getter: computed while Models initializes, it would read Language half-built when Language's
    // own initialization is what started it (Language → Set → SPEAKER_MODELS → Models).
    val speechSets: List<Set> get() = Language.entries.map { it.set }

    /** Calls can be transcribed: the speaker models and some recognizer are in place. */
    fun speechReady(context: Context): Boolean = speechSets.any { isInstalled(context, it) }

    /**
     * The speech set to transcribe with: the [chosen] one once it's usable. Until then the [previous] one, the
     * language used before the choice changed, carries on while the new one downloads, or failing that any usable
     * one; null if none is.
     */
    fun recognizer(chosen: Set, previous: Set?, sizes: Sizes): Set? =
        (listOfNotNull(chosen, previous) + speechSets).firstOrNull { isInstalled(it, sizes) }

    fun recognizer(context: Context, chosen: Set, previous: Set?): Set? = recognizer(chosen, previous, sizes(context))

    /**
     * The language to transcribe a call in: the one [pinned] to it by hand while that's usable, else the one its
     * [detection] found ([CallLanguage.languageOf]; pass it only with detection turned on) while that's usable, else the
     * one Settings transcribes in ([recognizer] for the [chosen] and [previous] languages); null if none is usable. A
     * pinned language that was removed isn't forgotten: once it's downloaded again, the call uses it again.
     */
    fun languageFor(
        pinned: Language?, detection: CallLanguage.Detection?, chosen: Language, previous: Language?, sizes: Sizes,
    ): Language? = choose(pinned, detection, chosen, previous, sizes)?.first

    /**
     * [languageFor], and whether [detection] made it other than Settings' language: only then is it worth saying, or
     * every English call under English Settings would say "English".
     */
    private fun choose(
        pinned: Language?, detection: CallLanguage.Detection?, chosen: Language, previous: Language?, sizes: Sizes,
    ): Pair<Language, Boolean>? {
        if (pinned != null && isInstalled(pinned.set, sizes)) return pinned to false
        val settings = recognizer(chosen.set, previous?.set, sizes)?.let { set -> Language.entries.first { it.set == set } }
        val detected = detection?.let(CallLanguage::languageOf)?.takeIf { isInstalled(it.set, sizes) }
        return if (detected != null) detected to (detected != settings) else settings?.let { it to false }
    }

    /**
     * What a call is transcribed with: its [language], and the model [files] for it, to load unless they're loaded.
     * [detected]: detection put the call in that language instead of Settings' (not pinned by hand).
     */
    data class EnginePick(val language: Language, val files: List<String>, val reload: Boolean, val detected: Boolean = false)

    /**
     * The engine for a call ([languageFor]), and whether it must be loaded: only when the files differ from the
     * [loaded] ones, since loading takes seconds and a backlog in one language shouldn't reload for every call.
     */
    fun engineFor(
        pinned: Language?, detection: CallLanguage.Detection?, chosen: Language, previous: Language?, loaded: List<String>?,
        sizes: Sizes,
    ): EnginePick? {
        val (language, detected) = choose(pinned, detection, chosen, previous, sizes) ?: return null
        val files = filesInUse(language.set, sizes)
        return EnginePick(language, files, files != loaded, detected)
    }

    /**
     * Language detection is wanted: turned on in Settings ([detect]), with two or more languages downloaded to choose
     * between. Then its model downloads over Wi-Fi like the others; otherwise it's deleted.
     */
    fun languageIdWanted(detect: Boolean, sizes: Sizes): Boolean = detect && usableLanguages(sizes).size >= 2

    /** Calls can have their language detected now: it's [languageIdWanted] and its model is in place. */
    fun canDetect(detect: Boolean, sizes: Sizes): Boolean = languageIdWanted(detect, sizes) && isInstalled(Set.LANGUAGE_ID, sizes)

    fun canDetect(context: Context, detect: Boolean): Boolean = canDetect(detect, sizes(context))

    /**
     * A call to detect the language of before it's transcribed: detection [canDetect], and the call isn't [pinned] to a
     * language by hand or [detected] already. Detection runs once per call; what it found is decided on afresh at each
     * transcription ([languageFor]), since the languages downloaded and Settings can change.
     */
    fun needsDetection(pinned: Language?, detected: Boolean, detect: Boolean, sizes: Sizes): Boolean =
        pinned == null && !detected && canDetect(detect, sizes)

    /** The language detection model's files on disk, finished or partly downloaded: what goes when it isn't wanted. */
    fun languageIdPaths(sizes: Sizes): List<String> =
        Set.LANGUAGE_ID.files.flatMap { listOf(it.path, "${it.path}.part") }.filter { sizes.of(it) > 0 }

    /** Deletes [languageIdPaths]. Call it holding [recognizerLock], which detection holds while it uses the model. */
    fun removeLanguageId(context: Context) {
        languageIdPaths(sizes(context)).forEach { file(context, it).delete() }
    }

    /** The languages calls can be transcribed in now, in the order Settings lists them. */
    fun usableLanguages(sizes: Sizes): List<Language> = Language.entries.filter { isInstalled(it.set, sizes) }

    fun usableLanguages(context: Context): List<Language> = usableLanguages(sizes(context))

    /**
     * The language to remember as used before, on a switch from [leaving] to [chosen]: [leaving] if it was usable,
     * since it was then the one transcribing; otherwise the one [remembered] so far, which still is.
     */
    fun previousAfter(leaving: Language, chosen: Language, remembered: Language?, sizes: Sizes): Language? =
        leaving.takeIf { it != chosen && isInstalled(it.set, sizes) } ?: remembered

    fun previousAfter(context: Context, leaving: Language, chosen: Language, remembered: Language?): Language? =
        previousAfter(leaving, chosen, remembered, sizes(context))

    /** The speech sets that can be removed: usable, but neither [chosen] nor transcribing in its place. */
    fun removable(chosen: Set, previous: Set?, sizes: Sizes): List<Set> {
        val inUse = recognizer(chosen, previous, sizes)
        return speechSets.filter { it != chosen && it != inUse && isInstalled(it, sizes) }
    }

    fun removable(context: Context, chosen: Set, previous: Set?): List<Set> = removable(chosen, previous, sizes(context))

    /** What [set]'s own files take on disk, the speaker models it shares left out. */
    fun ownBytes(set: Set, sizes: Sizes): Long = recognizerPaths(listOf(set)).sumOf { sizes.of(it) }

    fun ownBytes(context: Context, set: Set): Long = ownBytes(set, sizes(context))

    /**
     * Files of languages no longer [chosen] that nothing can use: everything a first download left unfinished, and
     * the partial files of a usable language (a cancelled update, which starts again when it's chosen). Each file a
     * usable language needs stays, and so do the speaker models every language shares.
     */
    fun abandonedPaths(chosen: Set, sizes: Sizes): List<String> {
        val needed = speechSets.filter { isInstalled(it, sizes) }.flatMap { s -> s.files.map { pathInUse(it, sizes) } }.toSet()
        return (speechSets - chosen).flatMap { set ->
            val usable = isInstalled(set, sizes)
            recognizerPaths(listOf(set)).flatMap { listOfNotNull(it.takeUnless { usable }, "$it.part") }
        }.filter { sizes.of(it) > 0 && it !in needed }
    }

    /** Deletes [abandonedPaths]. Call it holding [recognizerLock]. */
    fun deleteAbandoned(context: Context, chosen: Set) {
        abandonedPaths(chosen, sizes(context)).forEach { file(context, it).delete() }
    }

    /**
     * Held while a recognizer is chosen and loaded, while the language detection model is loaded and used, and while
     * either is deleted, so a model can't disappear halfway through loading (the native loader would abort the app).
     */
    val recognizerLock = Mutex()

    /**
     * The files that go with [sets]' recognizers: their own and the earlier files they replace, but not the
     * speaker models every speech set shares.
     */
    fun recognizerPaths(sets: List<Set>): List<String> {
        val shared = SPEAKER_MODELS.toSet()
        return (sets.flatMap { it.files } - shared).flatMap { listOfNotNull(it.path, it.replaces?.path) }.distinct()
    }

    /**
     * Deletes [sets]' recognizers, and any partial downloads of them, freeing 240–690 MB each: a language the
     * user removes. The speaker models every speech set shares stay. Call it holding [recognizerLock].
     */
    fun removeRecognizers(context: Context, sets: List<Set>) {
        for (path in recognizerPaths(sets)) {
            file(context, path).delete()
            file(context, "$path.part").delete()
        }
    }

    fun remove(context: Context, set: Set) {
        set.files.forEach { f -> listOfNotNull(f.path, f.replaces?.path).forEach { file(context, it).delete() } }
    }
}
