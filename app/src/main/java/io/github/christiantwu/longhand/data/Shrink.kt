package io.github.christiantwu.longhand.data

import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Shrinking WAV recordings once they're transcribed (Settings → Shrink WAV recordings, work.ShrinkWorker): which calls,
 * what the compressed copy is called and how it's encoded, when it counts as a faithful copy of the WAV, and what's done
 * about a swap of the two that was cut short.
 *
 * The copy is AAC-LC in an MPEG-4 (.m4a) file, as the GrapheneOS Phone app records with its own AAC option: one channel
 * of 16 kbit/s at 16 kHz, the rate its WAV option records at. Those WAVs (16-bit mono PCM at 16 kHz, 256 kbit/s) come
 * out about 16 times smaller. A stereo WAV keeps both channels (each may be one speaker), at 16 kbit/s each.
 */
object Shrink {

    /** Tries per recording, a crash included; then the WAV is kept as it is. */
    const val MAX_ATTEMPTS = 3

    /** The sample rate of the copy, unless the WAV's is lower and AAC takes it as it is. */
    const val SAMPLE_RATE = 16_000

    /** The copy's bit rate for each channel, as the Phone app's AAC option. */
    const val BITS_PER_CHANNEL = 16_000

    /** Rates AAC-LC codes as they are, up to [SAMPLE_RATE]: a WAV at one of them keeps its rate. */
    private val keptRates = setOf(8_000, 11_025, 12_000, SAMPLE_RATE)

    /** The copy's length may differ from the WAV's by this much (the encoder's start-up delay and last frame). */
    const val MAX_LENGTH_DIFFERENCE_MS = 500L

    /** Quieter than this (RMS of samples in -1..1, about -80 dBFS) counts as silence. */
    const val SILENT_RMS = 1e-4

    /** The copy's loudness may differ from the WAV's by at most this factor (6 dB). */
    const val MAX_LOUDNESS_FACTOR = 2.0

    fun isWav(name: String): Boolean = name.substringAfterLast('.', "").equals("wav", ignoreCase = true)

    /**
     * Whether [rec] is one to shrink: a WAV, transcribed, not shrunk before, and not given up on. One waiting to be redone
     * for a newer pipeline ([redoWaiting]) waits for that, so its new transcript is made from the WAV too. Edited calls
     * are shrunk like any other: the transcript doesn't change.
     */
    fun eligible(rec: Recording): Boolean =
        rec.status == RecordingStatus.DONE && isWav(rec.displayName) && rec.originalBytes == null &&
            rec.shrinkAttempts < MAX_ATTEMPTS && !redoWaiting(rec)

    /**
     * Its transcript will be redone on the charger (RecordingDao.nextRedo has the same rule): made by an older pipeline,
     * not given up on or edited by hand, and not shrunk. A shrunk call's redo would be made from its compressed copy and
     * replace a transcript made from the WAV, so only "Transcribe again" redoes it.
     */
    fun redoWaiting(rec: Recording): Boolean =
        rec.pipeline < Pipeline.CURRENT && rec.attempts < 3 && rec.editedAt == null && rec.originalBytes == null

    /**
     * The compressed copy's name: the WAV's own name as the folder lists it, extension and all replaced, so a name the
     * Phone app's storage changed (a " (1)" added, a character it can't store replaced) stays as it is.
     */
    fun copyName(wavName: String): String = wavName.substringBeforeLast('.') + ".m4a"

    /**
     * What the copy is called while it's written: not an audio file to the folder check, so one left behind by a crash
     * is never taken for a new recording. When a file of that name is already there, the storage numbers the new one
     * before its last extension ("….m4a (1).part"), so leftovers are found by [isPart].
     */
    fun partName(wavName: String): String = copyName(wavName) + ".part"

    /**
     * Whether [name] is a half-written copy ([partName]) of the copy called [copyName]: that name with ".part", or with the
     * number the storage adds when the name is taken ("….m4a (1).part"), in any case.
     */
    fun isPart(name: String, copyName: String): Boolean =
        Regex(Regex.escape(copyName) + """( \(\d+\))?\.part""", RegexOption.IGNORE_CASE).matches(name)

    /** How the copy is encoded: AAC-LC at [sampleRate] Hz, with [channels] channels, at [bitRate] bit/s. */
    data class Format(val sampleRate: Int, val channels: Int, val bitRate: Int)

    /** The copy's format for a WAV at [sampleRate] Hz with [channels] channels; null for more than two channels. */
    fun format(sampleRate: Int, channels: Int): Format? {
        if (channels !in 1..2) return null
        return Format(if (sampleRate in keptRates) sampleRate else SAMPLE_RATE, channels, BITS_PER_CHANNEL * channels)
    }

    /** How many times smaller the copy is than [bitsPerSample]-bit PCM at [sampleRate] Hz, as a whole number. */
    fun ratio(sampleRate: Int, bitsPerSample: Int = 16, channels: Int = 1): Int {
        val copy = format(sampleRate, channels) ?: return 1
        return (sampleRate.toLong() * bitsPerSample * channels / copy.bitRate).toInt().coerceAtLeast(1)
    }

    /** About how many megabytes an hour of audio at [bitRate] bit/s takes. */
    fun megabytesPerHour(bitRate: Int): Long = Math.round(bitRate / 8.0 * 3600 / 1e6)

    /** What decoding a recording to its end found: its length, its channels, and its loudness (RMS of samples in -1..1). */
    data class Stats(val durationMs: Long, val channels: Int, val rms: Double)

    /**
     * Why [copy], decoded, isn't a faithful copy of the [original] it was made from, or null when it is: as long as the
     * WAV within [MAX_LENGTH_DIFFERENCE_MS], with as many channels, not silent, and (unless the WAV is silent too) about
     * as loud, within [MAX_LOUDNESS_FACTOR].
     */
    fun problem(original: Stats, copy: Stats): String? = when {
        abs(copy.durationMs - original.durationMs) > MAX_LENGTH_DIFFERENCE_MS ->
            "the copy lasts ${copy.durationMs} ms, the WAV ${original.durationMs} ms"
        copy.channels != original.channels -> "the copy has ${copy.channels} channels, the WAV ${original.channels}"
        copy.rms <= SILENT_RMS -> "the copy is silent"
        original.rms > SILENT_RMS && (copy.rms < original.rms / MAX_LOUDNESS_FACTOR || copy.rms > original.rms * MAX_LOUDNESS_FACTOR) ->
            "the copy's loudness (RMS %.4f) is far from the WAV's (%.4f)".format(copy.rms, original.rms)
        else -> null
    }

    /**
     * A file's length and the SHA-256 of its bytes: noted for the copy before it's written into the folder, and compared
     * with what reads back from there, so a copy is never trusted for its size alone ([fingerprint]).
     */
    data class Fingerprint(val size: Long, val sha256: String)

    /** The [Fingerprint] of what [input] holds, read to its end (it's left open). */
    fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            size += n
        }
        return Fingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** What RecordingDao.saveShrunk did. */
    enum class Saved {
        /** The call names its copy now. */
        DONE,
        /** The call was deleted, queued again or its file changed meanwhile: nothing was changed. */
        CHANGED,
        /**
         * Another call's row names the copy's URI (a transcript kept for a file of that name that disappeared): nothing
         * was changed, and won't be on a later try. Normally found before the copy is written (RecordingDao.copyNameTaken).
         */
        URI_TAKEN,
    }

    /** A WAV, decoded, may last this much more or less than the audio its file holds ([wavProblem]). */
    const val MAX_WAV_LENGTH_DIFFERENCE_MS = 1_000L

    /**
     * What a WAV file's header says: its encoding ([formatTag]; for WAVE_FORMAT_EXTENSIBLE, its sub-format's), channels,
     * sample rate and bits per sample, where its audio (the "data" chunk) starts, and how many bytes of audio it says
     * follow.
     */
    data class WavHeader(
        val formatTag: Int, val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val dataOffset: Long, val dataBytes: Long,
    )

    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

    /** Encodings whose samples all have one size, so a length follows from a size: PCM, IEEE float, A-law and µ-law. */
    private val fixedSizeFormats = setOf(1, 3, 6, 7)

    /**
     * The header of a WAV file from its first [length] [bytes]; null when they don't hold a RIFF/WAVE header with its
     * "fmt " chunk, up to the start of its "data" chunk.
     */
    fun wavHeader(bytes: ByteArray, length: Int = bytes.size): WavHeader? {
        val n = minOf(length, bytes.size)
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        fun id(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        if (n < 12 || id(0) != "RIFF" || id(8) != "WAVE") return null
        var format: WavHeader? = null
        var at = 12L
        while (at + 8 <= n) {
            val chunk = id(at.toInt())
            val size = u32(at.toInt() + 4)
            val body = at.toInt() + 8
            when (chunk) {
                "fmt " -> {
                    if (size < 16 || body + 16 > n) return null
                    // WAVE_FORMAT_EXTENSIBLE names the encoding in the first two bytes of its sub-format, 24 bytes in.
                    val tag = u16(body).let { if (it == WAVE_FORMAT_EXTENSIBLE && size >= 40 && body + 26 <= n) u16(body + 24) else it }
                    format = WavHeader(tag, u16(body + 2), u32(body + 4).toInt(), u16(body + 14), 0, 0)
                }
                "data" -> return format?.copy(dataOffset = body.toLong(), dataBytes = size)
            }
            // Chunks are padded to an even size.
            at = body + size + (size and 1)
        }
        return null
    }

    /**
     * How long the audio in a WAV of [fileSize] bytes lasts when everything after its [header] is audio; null for a
     * compressed encoding, whose length can't be told from its size.
     */
    fun wavLengthMs(header: WavHeader, fileSize: Long): Long? {
        if (header.formatTag !in fixedSizeFormats || header.channels <= 0 || header.sampleRate <= 0 || header.bitsPerSample <= 0) {
            return null
        }
        val bytesPerSecond = header.sampleRate.toLong() * header.channels * ((header.bitsPerSample + 7) / 8)
        return (fileSize - header.dataOffset).coerceAtLeast(0) * 1000 / bytesPerSecond
    }

    /**
     * Why a WAV of [fileSize] bytes that decoded to [decodedMs] of audio can't be shrunk whole, or null when it can: its
     * [header] must be read, its samples have one size, and what decoded must last as long as the file holds after the
     * header, within [MAX_WAV_LENGTH_DIFFERENCE_MS]. A recorder stopped partway may leave a header that says less than
     * the file holds (or nothing): decoding stops where the header says, so a copy checked against what decoded would
     * lose the rest of the call.
     */
    fun wavProblem(header: WavHeader?, fileSize: Long, decodedMs: Long): String? {
        if (header == null) return "its WAV header can't be read"
        val held = wavLengthMs(header, fileSize) ?: return "its WAV encoding (%#06x) is compressed".format(header.formatTag)
        if (abs(decodedMs - held) <= MAX_WAV_LENGTH_DIFFERENCE_MS) return null
        return "the file holds $held ms of audio, but $decodedMs ms decode (its header says ${header.dataBytes} bytes of audio)"
    }

    /** A file as the folder shows it: there, of [Present.size] bytes; certainly gone; or not known (it can't be read). */
    sealed interface FileState {
        data class Present(val size: Long) : FileState
        data object Gone : FileState
        data object Unknown : FileState
    }

    /** What's done about a swap of a WAV for its copy that was cut short ([recovery]). */
    enum class Recovery {
        /** Nothing is left to do, and the swap is forgotten. */
        DONE,
        /** The call names its checked copy, and the WAV, just as it was copied, is still to go. */
        DELETE_WAV,
        /** The call still names the WAV (or was deleted): the copy written beside it goes. */
        DELETE_COPY,
        /**
         * The call names a copy that's gone or no longer holds what was written, or the WAV couldn't be deleted, while the
         * WAV is there just as it was copied: the call names the WAV again, and the copy, if it's there, goes.
         */
        UNDO,
        /** Whether a file is there can't be told now: nothing is touched, and it's looked at again later. */
        WAIT,
    }

    /**
     * What's to be done about a swap of the WAV [wavUri] for its copy that was cut short, given the call's [row] now (null
     * when the call was deleted), the WAV ([wav]), the copy ([copy]: the file the row names when it names one, otherwise
     * the file of the copy's name in the folder), whether another call's row names the WAV ([wavNamedElsewhere]: the folder
     * check added it meanwhile, as a recording of its own) or the copy ([copyNamedElsewhere], likewise), whether deleting
     * the WAV was tried and failed ([wavUndeletable]), and whether the copy still holds what was written ([copyIntact]: its
     * length and SHA-256 as noted before it was written; null when it can't be read), asked only when the WAV would go.
     *
     * A WAV is only deleted while its call names a copy that's there, of the size it was saved with and with the bytes it
     * was written with, and the WAV is still the size it was copied at; a copy only while the call names a WAV that's
     * certainly there, or after the call was deleted, and never while another call's row names it. A WAV or copy that
     * changed is kept, for the folder check to take as a recording of its own; a copy of the size saved whose bytes
     * aren't those written (a crash kept its name and size but not all of its data) is undone. Anything that can't be
     * told waits.
     */
    fun recovery(
        wavUri: String, row: Recording?, wav: FileState, copy: FileState,
        wavNamedElsewhere: Boolean = false, wavUndeletable: Boolean = false,
        copyNamedElsewhere: Boolean = false, copyIntact: () -> Boolean? = { true },
    ): Recovery = when {
        // The call was deleted (TranscriptViewModel.deleteCall, which deleted the file its row named): its copy goes too,
        // unless it's another call's recording now, but never the WAV, which no row names now.
        row == null -> when (copy) {
            is FileState.Present -> if (copyNamedElsewhere) Recovery.DONE else Recovery.DELETE_COPY
            FileState.Gone -> Recovery.DONE
            FileState.Unknown -> Recovery.WAIT
        }
        // The swap never reached the row, or was undone: the copy goes while the WAV is there, and is kept, as all that's
        // left of the call, when it isn't, or as another call's recording when that call's row names it.
        row.documentUri == wavUri -> when {
            copy == FileState.Gone -> Recovery.DONE
            copy == FileState.Unknown || wav == FileState.Unknown -> Recovery.WAIT
            wav is FileState.Present && !copyNamedElsewhere -> Recovery.DELETE_COPY
            else -> Recovery.DONE
        }
        // The row names the copy.
        wavNamedElsewhere || copyNamedElsewhere || wav == FileState.Gone -> Recovery.DONE
        wav == FileState.Unknown -> Recovery.WAIT
        (wav as FileState.Present).size != row.originalBytes -> Recovery.DONE
        copy == FileState.Unknown -> Recovery.WAIT
        copy == FileState.Gone -> Recovery.UNDO
        (copy as FileState.Present).size != row.sizeBytes -> Recovery.DONE
        wavUndeletable -> Recovery.UNDO
        else -> when (copyIntact()) {
            true -> Recovery.DELETE_WAV
            false -> Recovery.UNDO
            null -> Recovery.WAIT
        }
    }
}
