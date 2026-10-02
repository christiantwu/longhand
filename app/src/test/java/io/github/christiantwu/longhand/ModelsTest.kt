package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.Models.Set.CJK
import io.github.christiantwu.longhand.engine.Models.Set.HINDI
import io.github.christiantwu.longhand.engine.Models.Set.MULTILINGUAL
import io.github.christiantwu.longhand.engine.Models.Set.SPEECH
import io.github.christiantwu.longhand.ui.ModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model folder as the checks see it, without Android: what's on disk, and how big it is. */
class ModelsTest {

    private val encoder = MULTILINGUAL.part("encoder")
    private val old = requireNotNull(encoder.replaces)

    private class Disk(val files: MutableMap<String, Long> = mutableMapOf()) : Models.Sizes {
        override fun of(path: String) = files[path] ?: 0L
        fun add(vararg sets: Models.Set, except: kotlin.collections.Set<String> = emptySet()) = apply {
            for (f in sets.flatMap { it.files }) if (f.path !in except) files[f.path] = f.sizeBytes
        }
    }

    /** What an earlier version left after downloading the European languages: the encoder this one replaces. */
    private fun earlierEuropean() = Disk().add(MULTILINGUAL, except = setOf(encoder.path)).also { it.files[old.path] = old.sizeBytes }

    @Test fun theRepairedEncoderHasItsOwnPath() {
        assertEquals("parakeet-v3/encoder.repaired.int8.onnx", encoder.path)
        assertEquals(665_796_726L, encoder.sizeBytes)
        assertEquals("013290f8001e0434a33bfc1f4ea2a9039107a878d894a51a212c48802b58efac", encoder.sha256)
        assertEquals("parakeet-v3/encoder.int8.onnx", old.path)
        // No earlier file may share a path with a current one, or an old install would pass for the new file.
        val current = Models.Set.entries.flatMap { it.files }.map { it.path }.toSet()
        for (f in Models.Set.entries.flatMap { it.files }) f.replaces?.let { assertFalse(it.path in current) }
        // Only the encoder changed.
        assertEquals(listOf(encoder), Models.Set.entries.flatMap { it.files }.filter { it.replaces != null })
    }

    @Test fun recognizerPartsByKind() {
        assertEquals("parakeet-v3/decoder.int8.onnx", MULTILINGUAL.part("decoder").path)
        assertEquals("parakeet-v3/tokens.txt", MULTILINGUAL.part("tokens").path)
        assertEquals("parakeet/encoder.int8.onnx", SPEECH.part("encoder").path)
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
        assertTrue("parakeet/encoder.int8.onnx" in others)
        assertTrue(HINDI.files.none { it.path in others })
        assertEquals(HINDI.files.map { it.path }.filter { it.startsWith("nemotron/") }, Models.recognizerPaths(listOf(HINDI)))
    }

    @Test fun nothingDownloaded() {
        val disk = Disk()
        assertFalse(Models.isInstalled(MULTILINGUAL, disk))
        assertFalse(Models.needsUpdate(MULTILINGUAL, disk))
        assertEquals(MULTILINGUAL.totalBytes, Models.missingBytes(MULTILINGUAL, disk))
        assertEquals(encoder.path, Models.pathInUse(encoder, disk))
        assertNull(Models.recognizer(MULTILINGUAL, disk))
    }

    @Test fun anEarlierInstallKeepsWorkingUntilTheNewEncoderArrives() {
        val disk = earlierEuropean()
        assertTrue(Models.isInstalled(MULTILINGUAL, disk))
        assertTrue(Models.needsUpdate(MULTILINGUAL, disk))
        assertEquals(MULTILINGUAL, Models.recognizer(MULTILINGUAL, disk))
        assertEquals(MULTILINGUAL, Models.recognizer(SPEECH, disk)) // a new language waits on it too
        assertEquals(old.path, Models.pathInUse(encoder, disk))
        assertEquals(MULTILINGUAL.part("decoder").path, Models.pathInUse(MULTILINGUAL.part("decoder"), disk))
        // Only the new encoder is still to download.
        assertEquals(encoder.sizeBytes, Models.missingBytes(MULTILINGUAL, disk))
        assertEquals(emptyList<String>(), Models.obsoletePaths(MULTILINGUAL, disk))
    }

    @Test fun aPartialDownloadOfTheNewEncoderChangesNothing() {
        val disk = earlierEuropean().also { it.files["${encoder.path}.part"] = 300_000_000 }
        assertTrue(Models.isInstalled(MULTILINGUAL, disk))
        assertTrue(Models.needsUpdate(MULTILINGUAL, disk))
        assertEquals(old.path, Models.pathInUse(encoder, disk))
        assertEquals(emptyList<String>(), Models.obsoletePaths(MULTILINGUAL, disk))
    }

    @Test fun onceTheNewEncoderIsInPlaceTheOldOneGoes() {
        // The download finished but the app stopped before deleting the old encoder.
        val disk = earlierEuropean().also { it.files[encoder.path] = encoder.sizeBytes }
        assertTrue(Models.isInstalled(MULTILINGUAL, disk))
        assertFalse(Models.needsUpdate(MULTILINGUAL, disk))
        assertEquals(0L, Models.missingBytes(MULTILINGUAL, disk))
        assertEquals(encoder.path, Models.pathInUse(encoder, disk))
        assertEquals(listOf(old.path), Models.obsoletePaths(MULTILINGUAL, disk))
        disk.files.remove(old.path)
        assertTrue(Models.isInstalled(MULTILINGUAL, disk))
        assertEquals(emptyList<String>(), Models.obsoletePaths(MULTILINGUAL, disk))
    }

    @Test fun anUnfinishedDownloadOfTheOldEncoderIsDeleted() {
        // Updated in the middle of the first European download: the old encoder will never be finished now.
        val disk = Disk().add(MULTILINGUAL, except = setOf(encoder.path)).also { it.files["${old.path}.part"] = 123_456 }
        assertFalse(Models.isInstalled(MULTILINGUAL, disk))
        assertFalse(Models.needsUpdate(MULTILINGUAL, disk))
        assertEquals(encoder.sizeBytes, Models.missingBytes(MULTILINGUAL, disk))
        assertEquals(listOf("${old.path}.part"), Models.obsoletePaths(MULTILINGUAL, disk))
    }

    @Test fun anOldEncoderDoesNotMakeUpForAnotherMissingFile() {
        val decoder = MULTILINGUAL.part("decoder")
        val disk = earlierEuropean().also { it.files.remove(decoder.path) }
        assertFalse(Models.isInstalled(MULTILINGUAL, disk))
        assertFalse(Models.needsUpdate(MULTILINGUAL, disk))
        // The new encoder downloads with the decoder; the old one doesn't shrink the download.
        assertEquals(encoder.sizeBytes + decoder.sizeBytes, Models.missingBytes(MULTILINGUAL, disk))
        assertEquals(emptyList<String>(), Models.obsoletePaths(MULTILINGUAL, disk))
    }

    @Test fun anOldEncoderOfTheWrongSizeIsNotUsed() {
        val disk = earlierEuropean().also { it.files[old.path] = old.sizeBytes - 1 }
        assertFalse(Models.isInstalled(MULTILINGUAL, disk))
        assertEquals(encoder.path, Models.pathInUse(encoder, disk))
        assertEquals(listOf(old.path), Models.obsoletePaths(MULTILINGUAL, disk))
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
