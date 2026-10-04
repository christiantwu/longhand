package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.CallLanguage
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
        assertEquals(SPEECH, Models.recognizer(HINDI, SPEECH, disk))
        assertFalse(Models.isInstalled(HINDI, disk))
        assertEquals(HINDI.files.filter { it.path.startsWith("nemotron/") }.sumOf { it.sizeBytes }, Models.missingBytes(HINDI, disk))
        disk.add(HINDI)
        assertEquals(HINDI, Models.recognizer(HINDI, SPEECH, disk))
        assertFalse(Models.needsUpdate(HINDI, disk))
        // English stays, for switching back, until it's removed.
        assertEquals(emptyList<String>(), Models.abandonedPaths(HINDI, disk))
        assertEquals(SPEECH, Models.recognizer(SPEECH, HINDI, disk))
        // Removing a language takes only its recognizer; the speaker models stay.
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
            assertNull(Models.recognizer(set, null, disk))
        }
    }

    @Test fun anEarlierInstallKeepsWorkingUntilTheNewEncoderArrives() {
        for (set in repaired) {
            val encoder = set.part("encoder")
            val disk = earlier(set)
            assertTrue(Models.isInstalled(set, disk))
            assertTrue(Models.needsUpdate(set, disk))
            assertEquals(set, Models.recognizer(set, null, disk))
            assertEquals(set, Models.recognizer(CJK, set, disk)) // a new language waits on it too
            assertEquals(encoder.replaces?.path, Models.pathInUse(encoder, disk))
            assertEquals(set.part("decoder").path, Models.pathInUse(set.part("decoder"), disk))
            // Only the new encoder is still to download.
            assertEquals(encoder.sizeBytes, Models.missingBytes(set, disk))
            assertEquals(emptyList<String>(), Models.obsoletePaths(set, disk))
        }
    }

    @Test fun anEarlierEnglishInstallKeepsTranscribingWithTheOldEncoder() {
        val disk = earlier(SPEECH)
        assertEquals(SPEECH, Models.recognizer(SPEECH, null, disk))
        assertEquals(SPEECH, Models.recognizer(MULTILINGUAL, SPEECH, disk)) // and while the European languages download
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
        assertEquals(MULTILINGUAL, Models.recognizer(MULTILINGUAL, SPEECH, disk))
        assertEquals(SPEECH, Models.recognizer(SPEECH, MULTILINGUAL, disk))
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
        val speaker = Models.speechSets.map { s -> s.files.toSet() }.reduce { a, b -> a intersect b }.sumOf { it.sizeBytes }
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

    @Test fun theLanguageUsedBeforeTranscribesUntilTheChosenOneArrives() {
        val disk = Disk().add(SPEECH, MULTILINGUAL)
        // Hindi chosen after the European languages: they carry on, though English comes first.
        assertEquals(MULTILINGUAL, Models.recognizer(HINDI, MULTILINGUAL, disk))
        assertEquals(SPEECH, Models.recognizer(HINDI, SPEECH, disk))
        // None remembered (chosen before this was), or one since removed: any usable language.
        assertEquals(SPEECH, Models.recognizer(HINDI, null, disk))
        assertEquals(SPEECH, Models.recognizer(HINDI, CJK, disk))
        // A language already downloaded takes over at once.
        assertEquals(MULTILINGUAL, Models.recognizer(MULTILINGUAL, SPEECH, disk))
        disk.add(HINDI)
        assertEquals(HINDI, Models.recognizer(HINDI, MULTILINGUAL, disk))
    }

    @Test fun aSecondSwitchDuringADownloadKeepsTheLanguageThatTranscribes() {
        val disk = Disk().add(SPEECH, MULTILINGUAL)
        // The European languages chosen; Hindi picked, so they carry on while it downloads.
        val first = Models.previousAfter(Models.Language.EUROPEAN, Models.Language.HINDI, null, disk)
        assertEquals(Models.Language.EUROPEAN, first)
        // Chinese, Japanese and Korean picked before Hindi arrives: Hindi never transcribed, so the European
        // languages still do, not English, which comes first.
        val second = Models.previousAfter(Models.Language.HINDI, Models.Language.CJK, first, disk)
        assertEquals(Models.Language.EUROPEAN, second)
        assertEquals(MULTILINGUAL, Models.recognizer(CJK, second?.set, disk))
        // Back to English, already downloaded: it takes over, and nothing else needs remembering.
        assertEquals(SPEECH, Models.recognizer(SPEECH, Models.previousAfter(Models.Language.CJK, Models.Language.ENGLISH, second, disk)?.set, disk))
        // Leaving a usable language remembers it.
        assertEquals(Models.Language.ENGLISH, Models.previousAfter(Models.Language.ENGLISH, Models.Language.HINDI, Models.Language.EUROPEAN, disk))
    }

    @Test fun aCallPinnedToALanguageUsesItWhileItIsOnThePhone() {
        val disk = Disk().add(SPEECH, CJK)
        assertEquals(Models.Language.CJK, Models.languageFor(Models.Language.CJK, null, Models.Language.ENGLISH, null, disk))
        assertEquals(Models.Language.ENGLISH, Models.languageFor(Models.Language.ENGLISH, null, Models.Language.CJK, null, disk))
        // Removed: the call follows Settings. The pin stays, so once it's downloaded again the call uses it again.
        Models.recognizerPaths(listOf(CJK)).forEach { disk.files.remove(it) }
        assertEquals(Models.Language.ENGLISH, Models.languageFor(Models.Language.CJK, null, Models.Language.ENGLISH, null, disk))
        disk.add(CJK)
        assertEquals(Models.Language.CJK, Models.languageFor(Models.Language.CJK, null, Models.Language.ENGLISH, null, disk))
        // Half downloaded isn't usable.
        disk.files[HINDI.part("encoder").path] = HINDI.part("encoder").sizeBytes
        assertEquals(Models.Language.ENGLISH, Models.languageFor(Models.Language.HINDI, null, Models.Language.ENGLISH, null, disk))
        // An earlier install, still on the encoder an improved one replaces, is usable.
        val earlierEnglish = earlier(SPEECH).add(MULTILINGUAL)
        assertEquals(Models.Language.ENGLISH, Models.languageFor(Models.Language.ENGLISH, null, Models.Language.EUROPEAN, null, earlierEnglish))
        // Nothing usable: nothing to transcribe with, pinned or not.
        assertNull(Models.languageFor(Models.Language.CJK, null, Models.Language.ENGLISH, null, Disk()))
    }

    @Test fun aPinnedLanguageGoesBeforeTheOneTranscribingWhileSettingsLanguageDownloads() {
        val disk = Disk().add(SPEECH, MULTILINGUAL)
        assertEquals(Models.Language.EUROPEAN, Models.languageFor(null, null, Models.Language.HINDI, Models.Language.EUROPEAN, disk))
        assertEquals(Models.Language.ENGLISH, Models.languageFor(Models.Language.ENGLISH, null, Models.Language.HINDI, Models.Language.EUROPEAN, disk))
        // Once it's in, Settings' language takes over for the calls that follow Settings, but not for a pinned one.
        disk.add(HINDI)
        assertEquals(Models.Language.HINDI, Models.languageFor(null, null, Models.Language.HINDI, Models.Language.EUROPEAN, disk))
        assertEquals(Models.Language.ENGLISH, Models.languageFor(Models.Language.ENGLISH, null, Models.Language.HINDI, Models.Language.EUROPEAN, disk))
    }

    @Test fun aCallWithoutAPinFollowsSettingsAsTheRecognizerDoes() {
        val disks = listOf(Disk(), Disk().add(SPEECH), Disk().add(MULTILINGUAL, HINDI), earlier(SPEECH).add(CJK))
        val previous = listOf(null) + Models.Language.entries
        for (disk in disks) for (chosen in Models.Language.entries) for (before in previous) {
            assertEquals("$chosen after $before", Models.recognizer(chosen.set, before?.set, disk),
                Models.languageFor(null, null, chosen, before, disk)?.set)
        }
    }

    @Test fun usableLanguagesInSettingsOrder() {
        assertEquals(emptyList<Models.Language>(), Models.usableLanguages(Disk()))
        assertEquals(listOf(Models.Language.ENGLISH, Models.Language.CJK), Models.usableLanguages(Disk().add(CJK, SPEECH)))
        assertEquals(listOf(Models.Language.ENGLISH), Models.usableLanguages(earlier(SPEECH)))
        // A download still going isn't one.
        val disk = Disk().add(MULTILINGUAL).also { it.files[HINDI.part("encoder").path] = HINDI.part("encoder").sizeBytes }
        assertEquals(listOf(Models.Language.EUROPEAN), Models.usableLanguages(disk))
    }

    @Test fun onlyALanguageNeitherChosenNorInUseCanBeRemoved() {
        val disk = Disk().add(SPEECH, MULTILINGUAL, CJK)
        // Hindi downloading after the European languages, which transcribe meanwhile.
        assertEquals(listOf(SPEECH, CJK), Models.removable(HINDI, MULTILINGUAL, disk))
        assertEquals(listOf(SPEECH, MULTILINGUAL), Models.removable(CJK, MULTILINGUAL, disk))
        assertEquals(emptyList<Models.Set>(), Models.removable(SPEECH, null, Disk().add(SPEECH)))
        assertEquals(emptyList<Models.Set>(), Models.removable(HINDI, SPEECH, Disk().add(SPEECH)))
    }

    @Test fun aDownloadedLanguageCountsOnlyItsOwnFiles() {
        val disk = Disk().add(SPEECH, MULTILINGUAL)
        assertEquals(MULTILINGUAL.files.filter { it.path.startsWith("parakeet-v3/") }.sumOf { it.sizeBytes },
            Models.ownBytes(MULTILINGUAL, disk))
        assertEquals(0L, Models.ownBytes(CJK, disk))
        // On the old encoder, that's what it takes.
        val english = SPEECH.files.filter { it.path.startsWith("parakeet/") }.sumOf { it.sizeBytes }
        assertEquals(english - englishEncoder.sizeBytes + englishOld.sizeBytes, Models.ownBytes(SPEECH, earlier(SPEECH)))
    }

    @Test fun anUnfinishedFirstDownloadOfALanguageNoLongerChosenGoes() {
        // Hindi chosen while English transcribed, half downloaded, then English chosen again.
        val hindiEncoder = HINDI.part("encoder")
        val hindiDecoder = HINDI.part("decoder")
        val disk = Disk().add(SPEECH).also {
            it.files[hindiEncoder.path] = hindiEncoder.sizeBytes
            it.files["${hindiDecoder.path}.part"] = 4_000_000
        }
        assertEquals(listOf(hindiEncoder.path, "${hindiDecoder.path}.part"), Models.abandonedPaths(SPEECH, disk))
        assertEquals(listOf(hindiEncoder.path, "${hindiDecoder.path}.part"), Models.abandonedPaths(CJK, disk))
        // While Hindi is still chosen, it's left to finish.
        assertEquals(emptyList<String>(), Models.abandonedPaths(HINDI, disk))
    }

    @Test fun usableLanguagesStayWithTheFilesStandingIn() {
        // English from an earlier version, still on its old encoder, and the European languages.
        val disk = earlier(SPEECH).add(MULTILINGUAL)
        for (chosen in Models.speechSets) assertEquals(emptyList<String>(), Models.abandonedPaths(chosen, disk))
        // An old encoder that no longer makes its language usable goes with the rest of it.
        disk.files.remove(SPEECH.part("decoder").path)
        assertEquals(listOf(englishOld.path, "parakeet/joiner.int8.onnx", "parakeet/tokens.txt"), Models.abandonedPaths(MULTILINGUAL, disk))
    }

    @Test fun aCancelledUpdateOfALanguageNoLongerChosenGoes() {
        // English's new encoder was downloading when the European languages were chosen.
        val disk = earlier(SPEECH).add(MULTILINGUAL).also { it.files["${englishEncoder.path}.part"] = 300_000_000 }
        assertEquals(listOf("${englishEncoder.path}.part"), Models.abandonedPaths(MULTILINGUAL, disk))
        assertEquals(listOf("${englishEncoder.path}.part"), Models.abandonedPaths(HINDI, disk))
        // English itself stays, on the old encoder; chosen again, it carries on with the update.
        assertTrue(Models.isInstalled(SPEECH, disk))
        assertEquals(emptyList<String>(), Models.abandonedPaths(SPEECH, disk))
    }

    @Test fun theSharedSpeakerModelsAreNeverAbandoned() {
        // English's first download stopped in the speaker models, which come after its recognizer; then Hindi was chosen.
        val speaker = setOf("segmentation.onnx", "embedding.onnx", "silero_vad.onnx")
        val disk = Disk().add(SPEECH, except = speaker - "segmentation.onnx").also { it.files["embedding.onnx.part"] = 1_000_000 }
        val paths = Models.abandonedPaths(HINDI, disk)
        assertEquals(listOf(englishEncoder.path, "parakeet/decoder.int8.onnx", "parakeet/joiner.int8.onnx", "parakeet/tokens.txt"), paths)
        assertTrue(paths.none { p -> speaker.any { p.startsWith(it) } })
        // Nor with every language usable, or none.
        val everything = Disk().add(*Models.speechSets.toTypedArray()).also { d -> speaker.forEach { d.files["$it.part"] = 1 } }
        assertEquals(emptyList<String>(), Models.abandonedPaths(SPEECH, everything))
        assertEquals(emptyList<String>(), Models.abandonedPaths(SPEECH, Disk(speaker.associateWith { 1_000L }.toMutableMap())))
    }

    @Test fun notEnoughStorageSaysWhenALanguageCouldMakeRoom() {
        assertEquals("Not enough storage: 413 MB more needed", ModelDownloadWorker.notEnoughStorage(412_345_678, removable = false))
        assertEquals("Not enough storage: 413 MB more needed. Remove a language you don't use to make room.",
            ModelDownloadWorker.notEnoughStorage(412_345_678, removable = true))
    }

    @Test fun removingALanguageMakesRoomForDetectionOnlyWithMoreThanTwo() {
        val languageId = Models.Set.LANGUAGE_ID
        val two = Disk().add(SPEECH, CJK)
        // Hindi downloading while English transcribes: Chinese, Japanese and Korean could go.
        assertTrue(ModelDownloadWorker.removingMakesRoom(HINDI, HINDI, SPEECH, two))
        // Of two, removing one would delete the detection model too.
        assertFalse(ModelDownloadWorker.removingMakesRoom(languageId, SPEECH, null, two))
        assertTrue(ModelDownloadWorker.removingMakesRoom(languageId, SPEECH, null, Disk().add(SPEECH, CJK, HINDI)))
        // Nothing to remove.
        assertFalse(ModelDownloadWorker.removingMakesRoom(MULTILINGUAL, MULTILINGUAL, SPEECH, Disk().add(SPEECH)))
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

    private val languageId = Models.Set.LANGUAGE_ID

    @Test fun languageDetectionIsWhisperBaseFromHuggingFace() {
        assertEquals(listOf("whisper/base-encoder.int8.onnx", "whisper/base-decoder.int8.onnx"), languageId.files.map { it.path })
        for (f in languageId.files) {
            assertEquals("https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc/" +
                f.path.removePrefix("whisper/"), f.url)
            assertNull(f.replaces)
        }
        assertEquals(listOf(29_120_534L, 130_672_026L), languageId.files.map { it.sizeBytes })
        assertEquals("0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11", languageId.files[0].sha256)
        assertEquals("9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d", languageId.files[1].sha256)
        assertEquals(160, Math.round(languageId.totalBytes / 1e6).toInt())
        // Not a language to transcribe in, and nothing of it is shared with them.
        assertFalse(languageId in Models.speechSets)
        assertTrue(Models.speechSets.flatMap { it.files }.none { it in languageId.files })
        assertEquals("Downloading the language detection model", ModelDownloadWorker.title(languageId, update = false))
    }

    @Test fun detectionIsWantedWithTwoLanguagesToChooseBetween() {
        assertFalse(Models.languageIdWanted(true, Disk()))
        assertFalse(Models.languageIdWanted(true, Disk().add(SPEECH)))
        assertTrue(Models.languageIdWanted(true, Disk().add(SPEECH, CJK)))
        assertTrue(Models.languageIdWanted(true, earlier(SPEECH).add(HINDI)))
        // Turned off, or the second language still downloading.
        assertFalse(Models.languageIdWanted(false, Disk().add(SPEECH, CJK)))
        assertFalse(Models.languageIdWanted(true, Disk().add(SPEECH).also { it.files[CJK.part("model").path] = 1_000 }))
    }

    @Test fun callsAreDetectedOnceTheModelIsIn() {
        val disk = Disk().add(SPEECH, MULTILINGUAL)
        assertFalse(Models.canDetect(true, disk))
        assertFalse(Models.needsDetection(null, detected = false, detect = true, disk))
        // Half of it isn't enough: a wrong Whisper file would end the app.
        disk.files[languageId.files[0].path] = languageId.files[0].sizeBytes
        disk.files[languageId.files[1].path] = languageId.files[1].sizeBytes - 1
        assertFalse(Models.canDetect(true, disk))
        disk.add(languageId)
        assertTrue(Models.canDetect(true, disk))
        assertTrue(Models.needsDetection(null, detected = false, detect = true, disk))
        // Once each, never for a call pinned to a language by hand, and not with detection off or one language left.
        assertFalse(Models.needsDetection(null, detected = true, detect = true, disk))
        assertFalse(Models.needsDetection(Models.Language.ENGLISH, detected = false, detect = true, disk))
        assertFalse(Models.needsDetection(null, detected = false, detect = false, disk))
        Models.recognizerPaths(listOf(MULTILINGUAL)).forEach { disk.files.remove(it) }
        assertFalse(Models.needsDetection(null, detected = false, detect = true, disk))
    }

    @Test fun whatGoesWhenDetectionIsNotWanted() {
        assertEquals(emptyList<String>(), Models.languageIdPaths(Disk().add(SPEECH, CJK)))
        val disk = Disk().add(SPEECH).also {
            it.files[languageId.files[0].path] = languageId.files[0].sizeBytes
            it.files["${languageId.files[1].path}.part"] = 5_000_000
        }
        assertEquals(listOf("whisper/base-encoder.int8.onnx", "whisper/base-decoder.int8.onnx.part"), Models.languageIdPaths(disk))
        // It's no language's: removing or abandoning one never touches it.
        assertTrue(Models.recognizerPaths(Models.speechSets).none { it.startsWith("whisper/") })
        assertEquals(emptyList<String>(), Models.abandonedPaths(SPEECH, disk.add(languageId)))
    }

    private fun detection(codes: String, speechSeconds: Float = 40f) = CallLanguage.Detection(codes.split(","), speechSeconds)

    @Test fun aCallIsTranscribedInTheLanguageDetectedWhileItIsDownloaded() {
        val disk = Disk().add(SPEECH, CJK)
        val japanese = detection("ja,ja,en")
        assertEquals(Models.Language.CJK, Models.languageFor(null, japanese, Models.Language.ENGLISH, null, disk))
        // Not downloaded: the call follows Settings.
        assertEquals(Models.Language.ENGLISH, Models.languageFor(null, detection("de,de,de"), Models.Language.ENGLISH, null, disk))
        assertEquals(Models.Language.CJK, Models.languageFor(null, detection("de,de,de"), Models.Language.CJK, null, disk))
        // Too little speech, or no clear answer: Settings too.
        assertEquals(Models.Language.ENGLISH, Models.languageFor(null, detection("ja", 8f), Models.Language.ENGLISH, null, disk))
        assertEquals(Models.Language.ENGLISH, Models.languageFor(null, detection("ja,ar,ar"), Models.Language.ENGLISH, null, disk))
        // A language chosen by hand goes first; one removed since doesn't.
        assertEquals(Models.Language.ENGLISH, Models.languageFor(Models.Language.ENGLISH, japanese, Models.Language.CJK, null, disk))
        assertEquals(Models.Language.CJK, Models.languageFor(Models.Language.HINDI, japanese, Models.Language.ENGLISH, null, disk))
    }

    @Test fun anEnglishCallGoesToTheEnglishModel() {
        val disk = Disk().add(SPEECH, CJK)
        assertEquals(Models.Language.ENGLISH, Models.languageFor(null, detection("en,en,en"), Models.Language.CJK, null, disk))
        // Only when every window says English.
        assertEquals(Models.Language.CJK, Models.languageFor(null, detection("en,en,ar"), Models.Language.CJK, null, disk))
        // Without the English model, it stays with Settings', which transcribes English too.
        val noEnglish = Disk().add(MULTILINGUAL, CJK)
        assertEquals(Models.Language.CJK, Models.languageFor(null, detection("en,en,en"), Models.Language.CJK, null, noEnglish))
    }

    @Test fun theEngineSaysWhetherDetectionChoseTheLanguage() {
        val disk = Disk().add(SPEECH, CJK)
        val detected = Models.engineFor(null, detection("ko,ko,ko"), Models.Language.ENGLISH, null, loaded = null, disk)!!
        assertEquals(Models.Language.CJK, detected.language)
        assertTrue(detected.detected)
        // The same language, already loaded, for the next call.
        assertFalse(Models.engineFor(null, detection("ja,ja,ja"), Models.Language.ENGLISH, null, detected.files, disk)!!.reload)
        // Detection agreeing with Settings made no difference, so there's nothing to say: an English call under
        // English Settings isn't marked English.
        assertFalse(Models.engineFor(null, detection("ja,ja,ja"), Models.Language.CJK, null, detected.files, disk)!!.detected)
        val english = Models.engineFor(null, detection("en,en,en"), Models.Language.ENGLISH, null, null, disk)!!
        assertEquals(Models.Language.ENGLISH, english.language)
        assertFalse(english.detected)
        // Nor while Settings' language downloads and the one used before, which detection found, transcribes.
        assertFalse(Models.engineFor(null, detection("ja,ja,ja"), Models.Language.HINDI, Models.Language.CJK, null, disk)!!.detected)
        assertTrue(Models.engineFor(null, detection("ja,ja,ja"), Models.Language.HINDI, Models.Language.ENGLISH, null, disk)!!.detected)
        val pinned = Models.engineFor(Models.Language.CJK, detection("ja,ja,ja"), Models.Language.ENGLISH, null, detected.files, disk)!!
        assertEquals(Models.Language.CJK, pinned.language)
        assertFalse(pinned.detected)
        val stays = Models.engineFor(null, detection("de,de,de"), Models.Language.ENGLISH, null, detected.files, disk)!!
        assertEquals(Models.Language.ENGLISH, stays.language)
        assertFalse(stays.detected)
        assertTrue(stays.reload)
        // Detection off: no detection is passed, and the call follows Settings.
        assertFalse(Models.engineFor(null, null, Models.Language.ENGLISH, null, stays.files, disk)!!.detected)
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
