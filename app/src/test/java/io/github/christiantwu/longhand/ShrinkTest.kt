package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.data.Pipeline
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.ScanDiff
import io.github.christiantwu.longhand.data.Shrink
import io.github.christiantwu.longhand.data.Shrink.FileState.Gone
import io.github.christiantwu.longhand.data.Shrink.FileState.Present
import io.github.christiantwu.longhand.data.Shrink.FileState.Unknown
import io.github.christiantwu.longhand.data.Shrink.Recovery
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShrinkTest {
    // Fictional calls: the names follow the GrapheneOS Phone app's pattern, with a 555 number.
    private val wavName = "CallRecord_20261004-101500_+15555550123.wav"

    private fun rec(
        name: String = wavName, status: RecordingStatus = RecordingStatus.DONE, pipeline: Int = Pipeline.CURRENT,
    ) = Recording(id = 1, documentUri = "u", displayName = name, sizeBytes = 115_200_000, lastModified = 1_000,
        status = status, pipeline = pipeline)

    @Test fun onlyTranscribedWavsAreShrunk() {
        assertTrue(Shrink.eligible(rec()))
        for (status in RecordingStatus.entries - RecordingStatus.DONE) assertFalse(status.name, Shrink.eligible(rec(status = status)))
        assertFalse(Shrink.eligible(rec(name = "CallRecord_20261004-101500_+15555550123.m4a")))
        assertFalse(Shrink.eligible(rec(name = "CallRecord_20261004-101500_+15555550123.amr")))
        // Whatever the case of the extension, and wherever else "wav" appears in the name.
        assertTrue(Shrink.eligible(rec(name = "Call.WAV")))
        assertFalse(Shrink.eligible(rec(name = "wav.m4a")))
        assertFalse(Shrink.eligible(rec(name = "wav")))
    }

    @Test fun aCallIsShrunkOnceAndGivenUpOnAfterThreeTries() {
        assertFalse(Shrink.eligible(rec().copy(originalBytes = 115_200_000, fileModified = 2_000)))
        assertTrue(Shrink.eligible(rec().copy(shrinkAttempts = Shrink.MAX_ATTEMPTS - 1)))
        assertFalse(Shrink.eligible(rec().copy(shrinkAttempts = Shrink.MAX_ATTEMPTS)))
    }

    @Test fun editedCallsAreShrunkLikeOthers() {
        assertTrue(Shrink.eligible(rec().copy(editedAt = 5_000)))
    }

    @Test fun aCallWaitingToBeRedoneIsRedoneFromTheWavFirst() {
        val old = rec(pipeline = Pipeline.CURRENT - 1)
        assertFalse(Shrink.eligible(old))
        // Unless its redo won't come: edited by hand (redos leave those alone), or given up after three tries.
        assertTrue(Shrink.eligible(old.copy(editedAt = 5_000)))
        assertTrue(Shrink.eligible(old.copy(attempts = 3)))
    }

    @Test fun aShrunkCallIsntRedoneForANewerPipeline() {
        val old = rec(pipeline = Pipeline.CURRENT - 1)
        assertTrue(Shrink.redoWaiting(old))
        // Its redo would be made from the compressed copy and replace the transcript made from the WAV: only "Transcribe
        // again" redoes it.
        assertFalse(Shrink.redoWaiting(old.copy(originalBytes = 115_200_000, fileModified = 2_000)))
    }

    @Test fun theCopyKeepsTheWavsNameAsListed() {
        assertEquals("CallRecord_20261004-101500_+15555550123.m4a", Shrink.copyName(wavName))
        // A " (1)" the storage added, other dots and the extension's case are kept as they are; only the extension changes.
        assertEquals("CallRecord_20261004-101500_+15555550123 (1).m4a", Shrink.copyName("CallRecord_20261004-101500_+15555550123 (1).wav"))
        assertEquals("call.with.dots.m4a", Shrink.copyName("call.with.dots.WAV"))
        assertEquals("CallRecord_20261004-101500_+15555550123.m4a.part", Shrink.partName(wavName))
    }

    @Test fun theCopyIsNeverTakenForANewRecordingWhileItsWritten() {
        // The part file isn't audio to the folder check, by its name or the type the storage gives it, numbered or not.
        assertFalse(ScanDiff.isAudio(Shrink.partName(wavName), "application/octet-stream"))
        assertFalse(ScanDiff.isAudio("CallRecord_20261004-101500_+15555550123.m4a (1).part", "application/octet-stream"))
        assertTrue(ScanDiff.isAudio(Shrink.copyName(wavName), null))
    }

    @Test fun halfWrittenCopiesAreFoundHoweverTheStorageNumbersThem() {
        val copy = Shrink.copyName(wavName)
        assertTrue(Shrink.isPart(Shrink.partName(wavName), copy))
        // A name that's taken is numbered before its last extension, as ExternalStorageProvider does; in any case.
        assertTrue(Shrink.isPart("CallRecord_20261004-101500_+15555550123.m4a (1).part", copy))
        assertTrue(Shrink.isPart("callrecord_20261004-101500_+15555550123.M4A (12).PART", copy))
        // Not the copy itself, another call's, or another file that merely starts alike.
        assertFalse(Shrink.isPart(copy, copy))
        assertFalse(Shrink.isPart("CallRecord_20261004-101500_+15555550124.m4a.part", copy))
        assertFalse(Shrink.isPart("CallRecord_20261004-101500_+15555550123.m4a.part (1)", copy))
        assertFalse(Shrink.isPart("CallRecord_20261004-101500_+15555550123.m4a backup.part", copy))
        assertFalse(Shrink.isPart("CallRecord_20261004-101500_+15555550123.m4a (1).part.m4a", copy))
        // The name's "+" and "." are taken as they are, not as a pattern.
        assertFalse(Shrink.isPart("CallRecord_20261004-101500_15555550123.m4a.part", copy))
        assertFalse(Shrink.isPart("CallRecord_20261004-101500_+15555550123xm4a.part", copy))
    }

    @Test fun thePhoneAppsWavsBecomeItsOwnAacFormat() {
        // The Phone app's WAV is 16-bit mono at 16 kHz; its AAC option is AAC-LC, mono, 16 kHz, 16 kbit/s.
        assertEquals(Shrink.Format(16_000, 1, 16_000), Shrink.format(16_000, 1))
        assertEquals(16, Shrink.ratio(16_000))
        assertEquals(115L, Shrink.megabytesPerHour(16_000 * 16))
        assertEquals(7L, Shrink.megabytesPerHour(Shrink.BITS_PER_CHANNEL))
    }

    @Test fun otherWavsKeepTheirChannelsAndALowRate() {
        // Each channel may be one speaker: both are kept, at the same bit rate each.
        assertEquals(Shrink.Format(16_000, 2, 32_000), Shrink.format(16_000, 2))
        assertEquals(Shrink.Format(8_000, 1, 16_000), Shrink.format(8_000, 1))
        assertEquals(Shrink.Format(11_025, 1, 16_000), Shrink.format(11_025, 1))
        // Higher rates come down to 16 kHz, what calls are transcribed at; a rate AAC doesn't code goes up to it.
        assertEquals(16_000, Shrink.format(48_000, 1)!!.sampleRate)
        assertEquals(16_000, Shrink.format(44_100, 2)!!.sampleRate)
        assertEquals(16_000, Shrink.format(9_600, 1)!!.sampleRate)
        assertNull(Shrink.format(16_000, 3))
        assertNull(Shrink.format(16_000, 0))
        assertEquals(48, Shrink.ratio(48_000))
    }

    private val wav = Shrink.Stats(durationMs = 600_000, channels = 1, rms = 0.05)

    @Test fun aFaithfulCopyPassesItsCheck() {
        assertNull(Shrink.problem(wav, wav))
        // The encoder's start-up delay and padded last frame make it a little longer.
        assertNull(Shrink.problem(wav, wav.copy(durationMs = 600_128, rms = 0.048)))
        assertNull(Shrink.problem(wav, wav.copy(durationMs = 600_500)))
        assertNull(Shrink.problem(wav, wav.copy(durationMs = 599_500)))
    }

    @Test fun aCopyOfTheWrongLengthFails() {
        assertNotNull(Shrink.problem(wav, wav.copy(durationMs = 600_501)))
        assertNotNull(Shrink.problem(wav, wav.copy(durationMs = 599_499)))
        assertNotNull(Shrink.problem(wav, wav.copy(durationMs = 0)))
    }

    @Test fun aCopyWithOtherChannelsFails() {
        assertNotNull(Shrink.problem(wav, wav.copy(channels = 2)))
        assertNotNull(Shrink.problem(wav.copy(channels = 2), wav))
    }

    @Test fun aSilentCopyFails() {
        assertNotNull(Shrink.problem(wav, wav.copy(rms = 0.0)))
        assertNotNull(Shrink.problem(wav, wav.copy(rms = Shrink.SILENT_RMS)))
        // Even of a silent WAV: the copy has to be heard to be trusted.
        assertNotNull(Shrink.problem(wav.copy(rms = 0.0), wav.copy(rms = 0.0)))
    }

    @Test fun aCopyFarLouderOrQuieterThanTheWavFails() {
        assertNull(Shrink.problem(wav, wav.copy(rms = 0.026)))
        assertNull(Shrink.problem(wav, wav.copy(rms = 0.099)))
        assertNotNull(Shrink.problem(wav, wav.copy(rms = 0.024)))
        assertNotNull(Shrink.problem(wav, wav.copy(rms = 0.101)))
        // A near-silent WAV has no loudness to keep: only silence fails.
        assertNull(Shrink.problem(wav.copy(rms = 0.0), wav.copy(rms = 0.01)))
    }

    // ---- the WAV's own length

    /**
     * A WAV file's first bytes, up to the start of its audio: the RIFF header, any [before] chunks (id to size, written
     * with that many zero bytes and padding), "fmt " for [formatTag] (in WAVE_FORMAT_EXTENSIBLE if [extensible]), and the
     * "data" chunk's header saying [dataBytes].
     */
    private fun wavStart(
        dataBytes: Long, formatTag: Int = 1, channels: Int = 1, rate: Int = 16_000, bits: Int = 16,
        before: List<Pair<String, Int>> = emptyList(), extensible: Boolean = false,
    ): ByteArray {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun id(s: String) = b.put(s.toByteArray(Charsets.US_ASCII))
        id("RIFF").putInt((36 + dataBytes).toInt()); id("WAVE")
        for ((chunk, size) in before) {
            id(chunk).putInt(size)
            b.put(ByteArray(size + size % 2))
        }
        val block = channels * bits / 8
        id("fmt ").putInt(if (extensible) 40 else 16)
        b.putShort((if (extensible) 0xFFFE else formatTag).toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * block).putShort(block.toShort()).putShort(bits.toShort())
        if (extensible) {
            // cbSize, valid bits, channel mask, then the sub-format GUID, which starts with the format tag.
            b.putShort(22).putShort(bits.toShort()).putInt(4).putShort(formatTag.toShort())
            b.put(byteArrayOf(0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }
        id("data").putInt(dataBytes.toInt())
        return b.array().copyOf(b.position())
    }

    @Test fun thePhoneAppsWavHeaderIsRead() {
        val header = Shrink.wavHeader(wavStart(dataBytes = 19_200_000))!!
        assertEquals(Shrink.WavHeader(1, 1, 16_000, 16, dataOffset = 44, dataBytes = 19_200_000), header)
        // Followed by audio, as it is in a file.
        assertEquals(header, Shrink.wavHeader(wavStart(dataBytes = 19_200_000) + ByteArray(1000)))
    }

    @Test fun otherChunksAndExtensibleWavsAreReadToo() {
        // A chunk of odd size is padded to an even one.
        val listed = Shrink.wavHeader(wavStart(dataBytes = 64_000, before = listOf("LIST" to 25)))!!
        assertEquals(44L + 8 + 26, listed.dataOffset)
        val extensible = Shrink.wavHeader(wavStart(dataBytes = 64_000, channels = 2, bits = 24, extensible = true))!!
        assertEquals(Shrink.WavHeader(1, 2, 16_000, 24, dataOffset = 68, dataBytes = 64_000), extensible)
    }

    @Test fun whatIsntAWavHeaderIsNotRead() {
        val good = wavStart(dataBytes = 64_000)
        assertNull(Shrink.wavHeader(ByteArray(0)))
        assertNull(Shrink.wavHeader(good.copyOf().also { "RIFX".toByteArray().copyInto(it) }))
        assertNull(Shrink.wavHeader(good.copyOf().also { "AVI ".toByteArray().copyInto(it, 8) }))
        // Cut off before the start of its audio, or with no "fmt " before it.
        assertNull(Shrink.wavHeader(good, length = good.size - 1))
        assertNull(Shrink.wavHeader(good.copyOf(30)))
        assertNull(Shrink.wavHeader(good.copyOf().also { "junk".toByteArray().copyInto(it, 12) }))
    }

    @Test fun aWholeWavPassesTheLengthCheck() {
        // The Phone app's ten-minute WAV: 16-bit mono at 16 kHz, 32,000 bytes a second.
        val header = Shrink.wavHeader(wavStart(dataBytes = 19_200_000))!!
        val size = 44L + 19_200_000
        assertEquals(600_000L, Shrink.wavLengthMs(header, size))
        assertNull(Shrink.wavProblem(header, size, decodedMs = 600_000))
        assertNull(Shrink.wavProblem(header, size, decodedMs = 599_000))
        assertNull(Shrink.wavProblem(header, size, decodedMs = 601_000))
        // A little metadata after the audio (a LIST chunk of a few hundred bytes) is within the second allowed.
        assertNull(Shrink.wavProblem(header, size + 600, decodedMs = 600_000))
        // Stereo at 8 kHz, and A-law at a byte a sample.
        val stereo = Shrink.wavHeader(wavStart(dataBytes = 1_920_000, channels = 2, rate = 8_000))!!
        assertEquals(60_000L, Shrink.wavLengthMs(stereo, 44L + 1_920_000))
        val aLaw = Shrink.wavHeader(wavStart(dataBytes = 480_000, formatTag = 6, rate = 8_000, bits = 8))!!
        assertEquals(60_000L, Shrink.wavLengthMs(aLaw, 44L + 480_000))
    }

    @Test fun aWavWhoseHeaderSaysLessThanItHoldsIsntShrunk() {
        // A recorder stopped partway: the header says ten seconds, the file holds ten minutes, and ten seconds decode.
        val stale = Shrink.wavHeader(wavStart(dataBytes = 320_000))!!
        val size = 44L + 19_200_000
        assertNotNull(Shrink.wavProblem(stale, size, decodedMs = 10_000))
        assertNotNull(Shrink.wavProblem(stale, size, decodedMs = 598_999))
        // Or never wrote its sizes at all.
        assertNotNull(Shrink.wavProblem(Shrink.wavHeader(wavStart(dataBytes = 0))!!, size, decodedMs = 0))
        // Decoding more than the file holds is as wrong.
        assertNotNull(Shrink.wavProblem(stale, size, decodedMs = 601_001))
    }

    @Test fun aWavWhoseLengthCantBeToldIsntShrunk() {
        assertNotNull(Shrink.wavProblem(null, 1_000_000, decodedMs = 30_000))
        // IMA ADPCM, compressed: its length doesn't follow from its size.
        val adpcm = Shrink.wavHeader(wavStart(dataBytes = 64_000, formatTag = 0x11, bits = 4))!!
        assertNull(Shrink.wavLengthMs(adpcm, 44L + 64_000))
        assertNotNull(Shrink.wavProblem(adpcm, 44L + 64_000, decodedMs = 8_000))
    }

    // ---- the copy read back

    @Test fun aCopysFingerprintIsItsLengthAndSha256() {
        assertEquals(
            Shrink.Fingerprint(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            Shrink.fingerprint(ByteArrayInputStream(ByteArray(0))),
        )
        assertEquals(
            Shrink.Fingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            Shrink.fingerprint(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test fun aCopyReadBackInPiecesHasTheSameFingerprint() {
        // Longer than one read, as an hour's copy is; the storage may give it a little at a time.
        val bytes = ByteArray(200_003) { (it * 31 + it / 7).toByte() }
        val whole = Shrink.fingerprint(ByteArrayInputStream(bytes))
        assertEquals(200_003L, whole.size)
        val trickle = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 1000))
        }
        assertEquals(whole, Shrink.fingerprint(trickle))
        // A copy of the same size whose data didn't all reach the storage (zeros in its place) doesn't match.
        val lost = bytes.copyOf().also { it.fill(0, 100_000, 200_003) }
        val damaged = Shrink.fingerprint(ByteArrayInputStream(lost))
        assertEquals(whole.size, damaged.size)
        assertFalse(whole == damaged)
        assertFalse(whole == Shrink.fingerprint(ByteArrayInputStream(bytes.copyOf(200_002))))
    }

    // ---- a swap cut short

    private val wavUri = "content://storage/tree/CallRecordings/document/$wavName"
    private val onWav = rec().copy(documentUri = wavUri)
    private val onCopy = onWav.copy(
        documentUri = "content://storage/tree/CallRecordings/document/CallRecord_20261004-101500_+15555550123.m4a",
        displayName = "CallRecord_20261004-101500_+15555550123.m4a", sizeBytes = 7_200_000,
        originalBytes = 115_200_000, fileModified = 2_000,
    )
    private val wavAsCopied = Present(115_200_000)
    private val copyAsSaved = Present(7_200_000)

    @Test fun aSwapCutShortOnceTheRowNamedTheCopyIsFinished() {
        assertEquals(Recovery.DELETE_WAV, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved))
        // Already gone, or added by a folder check meanwhile as a recording of its own: nothing more to do.
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, Gone, copyAsSaved))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved, wavNamedElsewhere = true))
    }

    @Test fun aWavIsOnlyDeletedJustAsItWasCopiedWithItsCopyThere() {
        // Changed since it was copied, or the copy changed: both are kept, for the folder check to take as recordings.
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, Present(115_200_002), copyAsSaved))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, wavAsCopied, Present(7_100_000)))
        // Not known whether the WAV or the copy is there: looked at again later.
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, onCopy, Unknown, copyAsSaved))
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, onCopy, wavAsCopied, Unknown))
        // The copy is gone, or the WAV can't be deleted: the row names the WAV again.
        assertEquals(Recovery.UNDO, Shrink.recovery(wavUri, onCopy, wavAsCopied, Gone))
        assertEquals(Recovery.UNDO, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved, wavUndeletable = true))
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, onCopy, Unknown, copyAsSaved, wavUndeletable = true))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, Gone, copyAsSaved, wavUndeletable = true))
    }

    @Test fun aWavIsOnlyDeletedOnceItsCopyReadsBackAsWritten() {
        // Its name and size kept by a crash, but not all of its bytes: the row names the WAV again, and the copy goes.
        assertEquals(Recovery.UNDO, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved, copyIntact = { false }))
        // It can't be read back now: looked at again later.
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved, copyIntact = { null }))
        assertEquals(Recovery.DELETE_WAV, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved, copyIntact = { true }))
        // Read through only when the WAV would go: never for a copy that's undone or kept anyway.
        val notAsked = { throw AssertionError("the copy was read back") }
        assertEquals(Recovery.UNDO, Shrink.recovery(wavUri, onCopy, wavAsCopied, Gone, copyIntact = notAsked))
        assertEquals(Recovery.UNDO, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved, wavUndeletable = true, copyIntact = notAsked))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, Gone, copyAsSaved, copyIntact = notAsked))
        assertEquals(Recovery.DELETE_COPY, Shrink.recovery(wavUri, onWav, wavAsCopied, copyAsSaved, copyIntact = notAsked))
    }

    @Test fun aCopyAnotherCallsRowNamesIsKept() {
        // The folder check added it meanwhile, as a recording of its own: never deleted, whatever became of this call.
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onWav, wavAsCopied, copyAsSaved, copyNamedElsewhere = true))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, null, wavAsCopied, copyAsSaved, copyNamedElsewhere = true))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, wavAsCopied, Gone, copyNamedElsewhere = true))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onCopy, wavAsCopied, copyAsSaved, copyNamedElsewhere = true, copyIntact = { false }))
        // What can't be told still waits.
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, onWav, Unknown, copyAsSaved, copyNamedElsewhere = true))
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, null, wavAsCopied, Unknown, copyNamedElsewhere = true))
    }

    @Test fun aSwapCutShortBeforeTheRowNamedTheCopyIsUndone() {
        assertEquals(Recovery.DELETE_COPY, Shrink.recovery(wavUri, onWav, Present(115_200_000), copyAsSaved))
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onWav, Present(115_200_000), Gone))
        // Not known whether the WAV is there: the copy may be all that's left of the call.
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, onWav, Unknown, copyAsSaved))
        assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, onWav, Present(115_200_000), Unknown))
        // The WAV is gone: the copy is all that's left of the call, and stays.
        assertEquals(Recovery.DONE, Shrink.recovery(wavUri, onWav, Gone, copyAsSaved))
    }

    @Test fun aDeletedCallsCopyGoesButNeverItsWav() {
        for (wav in listOf(wavAsCopied, Gone, Unknown)) {
            assertEquals(Recovery.DELETE_COPY, Shrink.recovery(wavUri, null, wav, copyAsSaved))
            assertEquals(Recovery.DONE, Shrink.recovery(wavUri, null, wav, Gone))
            assertEquals(Recovery.WAIT, Shrink.recovery(wavUri, null, wav, Unknown))
        }
    }

    @Test fun nothingIsDeletedThatMayBeAllThatsLeftOfACall() {
        val files = listOf(wavAsCopied, Present(115_200_002), copyAsSaved, Present(7_100_000), Gone, Unknown)
        val flags = listOf(false, true)
        for (row in listOf(onWav, onCopy, null)) for (wav in files) for (copy in files)
            for (elsewhere in flags) for (undeletable in flags) for (copyElsewhere in flags) for (intact in listOf(true, false, null)) {
                val action = Shrink.recovery(wavUri, row, wav, copy, elsewhere, undeletable, copyElsewhere, copyIntact = { intact })
                val case = "row ${row?.documentUri}, wav $wav, copy $copy, elsewhere $elsewhere, undeletable $undeletable, " +
                    "copy elsewhere $copyElsewhere, intact $intact: $action"
                // The WAV goes only for a row naming a copy that's there as saved and reads back as written, and only as
                // it was copied.
                if (action == Recovery.DELETE_WAV) {
                    assertTrue(case, row == onCopy && wav == wavAsCopied && copy == copyAsSaved && intact == true &&
                        !elsewhere && !undeletable && !copyElsewhere)
                }
                // The copy goes only while the row names a WAV that's there, or the call was deleted; or once the row
                // names the WAV again, as it was; and never while another call's row names it.
                if (action == Recovery.DELETE_COPY) {
                    assertTrue(case, copy is Present && !copyElsewhere && (row == null || (row == onWav && wav is Present)))
                }
                if (action == Recovery.UNDO) assertTrue(case, row == onCopy && wav == wavAsCopied && !elsewhere && !copyElsewhere)
            }
    }
}
