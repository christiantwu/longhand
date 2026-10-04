package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.Models.Set.CJK
import io.github.christiantwu.longhand.engine.Models.Set.HINDI
import io.github.christiantwu.longhand.engine.Models.Set.MULTILINGUAL
import io.github.christiantwu.longhand.engine.Models.Set.SPEECH
import io.github.christiantwu.longhand.ui.ModelState
import io.github.christiantwu.longhand.work.ModelDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model folder as the checks see it, without Android: what's on disk, and how big it is. */
class ModelsTest {

    private val encoder = MULTILINGUAL.part("encoder")
    private val old = requireNotNull(encoder.replaces)
    private val englishEncoder = SPEECH.part("encoder")
    private val englishOld = requireNotNull(englishEncoder.replaces)

    /** The sets whose encoder Longhand re-quantized, replacing sherpa-onnx's that earlier versions downloaded. */
    private val repaired = listOf(SPEECH, MULTILINGUAL)

    private class Disk(val files: MutableMap<String, Long> = mutableMapOf()) : Models.Sizes {
        override fun of(path: String) = files[path] ?: 0L
        fun add(vararg sets: Models.Set, except: kotlin.collections.Set<String> = emptySet()) = apply {
            for (f in sets.flatMap { it.files }) if (f.path !in except) files[f.path] = f.sizeBytes
        }
    }

    /** What an earlier version left after downloading [set]: the encoder the current one replaces. */
    private fun earlier(set: Models.Set): Disk {
        val e = set.part("encoder")
        val o = requireNotNull(e.replaces)
        return Disk().add(set, except = setOf(e.path)).also { it.files[o.path] = o.sizeBytes }
    }

    private fun earlierEuropean() = earlier(MULTILINGUAL)

    @Test fun theRepairedEncoderHasItsOwnPath() {
        assertEquals("parakeet-v3/encoder.repaired.int8.onnx", encoder.path)
        assertEquals(665_796_726L, encoder.sizeBytes)
        assertEquals("013290f8001e0434a33bfc1f4ea2a9039107a878d894a51a212c48802b58efac", encoder.sha256)
        assertEquals("parakeet-v3/encoder.int8.onnx", old.path)
        // No earlier file may share a path with a current one, or an old install would pass for the new file.
        val current = Models.Set.entries.flatMap { it.files }.map { it.path }.toSet()
        for (f in Models.Set.entries.flatMap { it.files }) f.replaces?.let { assertFalse(it.path in current) }
        // Only the encoders changed.
        assertEquals(listOf(englishEncoder, encoder), Models.Set.entries.flatMap { it.files }.filter { it.replaces != null })
    }

    @Test fun theRepairedEnglishEncoderHasItsOwnPath() {
        assertEquals("parakeet/encoder.repaired.int8.onnx", englishEncoder.path)
        assertEquals("https://github.com/christiantwu/longhand/releases/download/models-1/parakeet-tdt-0.6b-v2-encoder.int8.onnx",
            englishEncoder.url)
        assertEquals(665_796_732L, englishEncoder.sizeBytes)
        assertEquals("0db696759cccf970a2fb532948bd9c43af576c51551cfcff71ed955f926d8413", englishEncoder.sha256)
        // sherpa-onnx's int8 encoder, which every earlier version downloaded for English.
        assertEquals(Models.Replaced("parakeet/encoder.int8.onnx", 652_184_296), englishOld)
        // The decoder, joiner and tokens are still sherpa-onnx's, where they always were.
        for (kind in listOf("decoder", "joiner", "tokens")) {
            val f = SPEECH.part(kind)
            assertNull(f.replaces)
            assertTrue(f.url, f.url.startsWith("https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/"))
        }
        // About 720 MB with the speaker models.
        assertEquals(721, (SPEECH.totalBytes / 1_000_000).toInt())
    }

    @Test fun recognizerPartsByKind() {
        assertEquals("parakeet-v3/decoder.int8.onnx", MULTILINGUAL.part("decoder").path)
        assertEquals("parakeet-v3/tokens.txt", MULTILINGUAL.part("tokens").path)
        assertEquals("parakeet/encoder.repaired.int8.onnx", SPEECH.part("encoder").path)
        assertEquals("parakeet/decoder.int8.onnx", SPEECH.part("decoder").path)
        assertEquals("sensevoice/model.int8.onnx", CJK.part("model").path)
        assertEquals("nemotron/encoder.int8.onnx", HINDI.part("encoder").path)
        assertEquals("nemotron/joiner.int8.onnx", HINDI.part("joiner").path)
        assertEquals("nemotron/tokens.txt", HINDI.part("tokens").path)
    }

    @Test fun hindiIsNemotronFromLonghandsRelease() {
        assertEquals(HINDI, Models.Language.HINDI.set)
        assertEquals(listOf(SPEECH, MULTILINGUAL, CJK, HINDI), Models.speechSets)
        val own = HINDI.files.filter { it.path.startsWith("nemotron/") }
        assertEquals(4, own.size)
        for (f in own) {
            assertTrue(f.url, f.url.startsWith("https://github.com/christiantwu/longhand/releases/download/models-1/nemotron-3.5-asr-streaming-0.6b-1120ms-"))
            assertTrue(f.url, f.url.endsWith("-" + f.path.removePrefix("nemotron/")))
            assertEquals(64, f.sha256.length)
        }
        assertEquals(657_601_521L, HINDI.part("encoder").sizeBytes)
        // About 730 MB with the speaker models, which every language shares.
        for (path in listOf("segmentation.onnx", "embedding.onnx", "silero_vad.onnx")) assertTrue(path, HINDI.files.any { it.path == path })
        assertEquals(729, (HINDI.totalBytes / 1_000_000).toInt())
    }

    @Test fun hindiTakesOverFromTheInstalledLanguage() {
        val disk = Disk().add(SPEECH)
        // English keeps transcribing while Hindi downloads, and only Hindi's own files are still to come.
        assertEquals(SPEECH, Models.recognizer(HINDI, disk))
        assertFalse(Models.isInstalled(HINDI, disk))
        assertEquals(HINDI.files.filter { it.path.startsWith("nemotron/") }.sumOf { it.sizeBytes }, Models.missingBytes(HINDI, disk))
        disk.add(HINDI)
        assertEquals(HINDI, Models.recognizer(HINDI, disk))
        assertFalse(Models.needsUpdate(HINDI, disk))
        // Then the other recognizers go, and the speaker models stay.
        val others = Models.recognizerPaths(Models.speechSets - HINDI)
        assertTrue("parakeet/encoder.repaired.int8.onnx" in others)
        assertTrue("parakeet/encoder.int8.onnx" in others)
        assertTrue(HINDI.files.none { it.path in others })
        assertEquals(HINDI.files.map { it.path }.filter { it.startsWith("nemotron/") }, Models.recognizerPaths(listOf(HINDI)))
    }

    @Test fun nothingDownloaded() {
        val disk = Disk()
        for (set in repaired) {
            val encoder = set.part("encoder")
            assertFalse(Models.isInstalled(set, disk))
            assertFalse(Models.needsUpdate(set, disk))
            assertEquals(set.totalBytes, Models.missingBytes(set, disk))
            assertEquals(encoder.path, Models.pathInUse(encoder, disk))
            assertNull(Models.recognizer(set, disk))
        }
    }

    @Test fun anEarlierInstallKeepsWorkingUntilTheNewEncoderArrives() {
        for (set in repaired) {
            val encoder = set.part("encoder")
            val disk = earlier(set)
            assertTrue(Models.isInstalled(set, disk))
            assertTrue(Models.needsUpdate(set, disk))
            assertEquals(set, Models.recognizer(set, disk))
            assertEquals(set, Models.recognizer(CJK, disk)) // a new language waits on it too
            assertEquals(encoder.replaces?.path, Models.pathInUse(encoder, disk))
            assertEquals(set.part("decoder").path, Models.pathInUse(set.part("decoder"), disk))
            // Only the new encoder is still to download.
            assertEquals(encoder.sizeBytes, Models.missingBytes(set, disk))
            assertEquals(emptyList<String>(), Models.obsoletePaths(set, disk))
        }
    }

    @Test fun anEarlierEnglishInstallKeepsTranscribingWithTheOldEncoder() {
        val disk = earlier(SPEECH)
        assertEquals(SPEECH, Models.recognizer(SPEECH, disk))
        assertEquals(SPEECH, Models.recognizer(MULTILINGUAL, disk)) // and while the European languages download
        assertEquals("parakeet/encoder.int8.onnx", Models.pathInUse(englishEncoder, disk))
        assertEquals(englishEncoder.sizeBytes, Models.missingBytes(SPEECH, disk))
        // The European languages need only their recognizer: the old English encoder is no part of them.
        assertEquals(MULTILINGUAL.files.filter { it.path.startsWith("parakeet-v3/") }.sumOf { it.sizeBytes },
            Models.missingBytes(MULTILINGUAL, disk))
        assertFalse(Models.needsUpdate(MULTILINGUAL, disk))
        // Once the new English encoder is in, the old one is the only thing left to delete.
        disk.files[englishEncoder.path] = englishEncoder.sizeBytes
        assertFalse(Models.needsUpdate(SPEECH, disk))
        assertEquals(englishEncoder.path, Models.pathInUse(englishEncoder, disk))
        assertEquals(listOf("parakeet/encoder.int8.onnx"), Models.obsoletePaths(SPEECH, disk))
        assertEquals(emptyList<String>(), Models.obsoletePaths(MULTILINGUAL, disk))
    }

    @Test fun bothEncodersCanBeUpdatingAtOnce() {
        // English installed by an earlier version, then the European languages chosen: both stand in until updated.
        val disk = earlier(SPEECH).also { it.files.putAll(earlierEuropean().files) }
        for (set in repaired) {
            assertTrue(Models.isInstalled(set, disk))
            assertTrue(Models.needsUpdate(set, disk))
            assertEquals(set.part("encoder").replaces?.path, Models.pathInUse(set.part("encoder"), disk))
        }
        assertEquals(MULTILINGUAL, Models.recognizer(MULTILINGUAL, disk))
        assertEquals(SPEECH, Models.recognizer(SPEECH, disk))
    }

    @Test fun aPartialDownloadOfTheNewEncoderChangesNothing() {
        for (set in repaired) {
            val encoder = set.part("encoder")
            val disk = earlier(set).also { it.files["${encoder.path}.part"] = 300_000_000 }
            assertTrue(Models.isInstalled(set, disk))
            assertTrue(Models.needsUpdate(set, disk))
            assertEquals(encoder.replaces?.path, Models.pathInUse(encoder, disk))
            assertEquals(emptyList<String>(), Models.obsoletePaths(set, disk))
        }
    }

    @Test fun onceTheNewEncoderIsInPlaceTheOldOneGoes() {
        for (set in repaired) {
            val encoder = set.part("encoder")
            val old = requireNotNull(encoder.replaces)
            // The download finished but the app stopped before deleting the old encoder.
            val disk = earlier(set).also { it.files[encoder.path] = encoder.sizeBytes }
            assertTrue(Models.isInstalled(set, disk))
            assertFalse(Models.needsUpdate(set, disk))
            assertEquals(0L, Models.missingBytes(set, disk))
            assertEquals(encoder.path, Models.pathInUse(encoder, disk))
            assertEquals(listOf(old.path), Models.obsoletePaths(set, disk))
            disk.files.remove(old.path)
            assertTrue(Models.isInstalled(set, disk))
            assertEquals(emptyList<String>(), Models.obsoletePaths(set, disk))
        }
    }

    @Test fun anUnfinishedDownloadOfTheOldEncoderIsDeleted() {
        for (set in repaired) {
            val encoder = set.part("encoder")
            val old = requireNotNull(encoder.replaces)
            // Updated in the middle of the first download: the old encoder will never be finished now.
            val disk = Disk().add(set, except = setOf(encoder.path)).also { it.files["${old.path}.part"] = 123_456 }
            assertFalse(Models.isInstalled(set, disk))
            assertFalse(Models.needsUpdate(set, disk))
            assertEquals(encoder.sizeBytes, Models.missingBytes(set, disk))
            assertEquals(listOf("${old.path}.part"), Models.obsoletePaths(set, disk))
        }
    }

    @Test fun anOldEncoderDoesNotMakeUpForAnotherMissingFile() {
        for (set in repaired) {
            val encoder = set.part("encoder")
            val decoder = set.part("decoder")
            val disk = earlier(set).also { it.files.remove(decoder.path) }
            assertFalse(Models.isInstalled(set, disk))
            assertFalse(Models.needsUpdate(set, disk))
            // The new encoder downloads with the decoder; the old one doesn't shrink the download.
            assertEquals(encoder.sizeBytes + decoder.sizeBytes, Models.missingBytes(set, disk))
            assertEquals(emptyList<String>(), Models.obsoletePaths(set, disk))
        }
    }

    @Test fun anOldEncoderOfTheWrongSizeIsNotUsed() {
        for (set in repaired) {
            val encoder = set.part("encoder")
            val old = requireNotNull(encoder.replaces)
            val disk = earlier(set).also { it.files[old.path] = old.sizeBytes - 1 }
            assertFalse(Models.isInstalled(set, disk))
            assertEquals(encoder.path, Models.pathInUse(encoder, disk))
            assertEquals(listOf(old.path), Models.obsoletePaths(set, disk))
        }
    }

    @Test fun sizesCountOnlyWhatIsMissing() {
        val english = Disk().add(SPEECH)
        val speaker = Models.Set.entries.filter { it != Models.Set.SUMMARY }
            .map { s -> s.files.toSet() }.reduce { a, b -> a intersect b }.sumOf { it.sizeBytes }
        // The speaker models are shared, so another language needs only its recognizer.
        assertEquals(MULTILINGUAL.totalBytes - speaker, Models.missingBytes(MULTILINGUAL, english))
        assertEquals(CJK.totalBytes - speaker, Models.missingBytes(CJK, english))
        assertEquals(HINDI.totalBytes - speaker, Models.missingBytes(HINDI, english))
        assertEquals(0L, Models.missingBytes(SPEECH, english))
        assertFalse(Models.needsUpdate(SPEECH, english))
    }

    @Test fun removingTheEuropeanLanguagesDeletesBothEncoders() {
        val paths = Models.recognizerPaths(listOf(MULTILINGUAL))
        assertTrue(encoder.path in paths)
        assertTrue(old.path in paths)
        assertTrue(MULTILINGUAL.part("decoder").path in paths)
        // The speaker models stay for the other languages.
        assertFalse("embedding.onnx" in paths)
        assertFalse("silero_vad.onnx" in paths)
        assertEquals(paths.distinct(), paths)
        assertFalse(old.path in Models.recognizerPaths(listOf(SPEECH, CJK)))
    }

    @Test fun removingEnglishDeletesBothEncoders() {
        val paths = Models.recognizerPaths(listOf(SPEECH))
        assertEquals(listOf("parakeet/encoder.repaired.int8.onnx", "parakeet/encoder.int8.onnx", "parakeet/decoder.int8.onnx",
            "parakeet/joiner.int8.onnx", "parakeet/tokens.txt"), paths)
        // The European languages' files are another folder's.
        assertTrue(Models.recognizerPaths(listOf(MULTILINGUAL)).none { it.startsWith("parakeet/") })
    }

    @Test fun updateLine() {
        assertNull(ModelState(installed = true).updateText)
        assertNull(ModelState(installed = false, downloading = true).updateText)
        val update = ModelState(installed = true, update = true)
        assertEquals("An improved version is ready to download.", update.updateText)
        assertEquals("Updating to an improved version… 34%", update.copy(downloading = true, progress = 0.345f).updateText)
        assertEquals("Updating to an improved version. Waiting for Wi-Fi…",
            update.copy(downloading = true, waiting = "Waiting for Wi-Fi…").updateText)
        assertEquals("Update failed: HTTP 404", update.copy(error = "HTTP 404").updateText)
    }

    @Test fun downloadNotificationSaysWhenItIsAnUpdate() {
        assertEquals("Updating the English model", ModelDownloadWorker.title(SPEECH, update = true))
        assertEquals("Downloading transcription models", ModelDownloadWorker.title(SPEECH, update = false))
        assertEquals("Updating the European languages model", ModelDownloadWorker.title(MULTILINGUAL, update = true))
        assertEquals("Downloading the European languages model", ModelDownloadWorker.title(MULTILINGUAL, update = false))
        assertEquals("Downloading the Hindi model", ModelDownloadWorker.title(HINDI, update = false))
    }

    @Test fun startsFromThePhonesLanguage() {
        assertEquals(Models.Language.ENGLISH, Models.Language.forPhoneLanguage("en"))
        assertEquals(Models.Language.EUROPEAN, Models.Language.forPhoneLanguage("sv"))
        assertEquals(Models.Language.EUROPEAN, Models.Language.forPhoneLanguage("DE"))
        assertEquals(Models.Language.CJK, Models.Language.forPhoneLanguage("ja"))
        assertEquals(Models.Language.HINDI, Models.Language.forPhoneLanguage("hi"))
        // Not covered by any model (Norwegian, Arabic): English, as before.
        assertEquals(Models.Language.ENGLISH, Models.Language.forPhoneLanguage("nb"))
        assertEquals(Models.Language.ENGLISH, Models.Language.forPhoneLanguage("ar"))
    }
}
