package io.github.christiantwu.longhand.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

const val MODEL_SAMPLE_RATE = 16_000

/**
 * Decoded audio at 16 kHz. [channels] has one entry (mono) or two (stereo), each the call's
 * [sampleCount] samples followed by silence: from [AudioDecoder], at least [TAIL] of it. Only
 * diarization hears that ([monoWithTail]); nothing else goes past [sampleCount].
 */
class DecodedAudio(val channels: List<FloatArray>, val sampleCount: Int = channels[0].size) {
    val durationMs: Long get() = sampleCount * 1000L / MODEL_SAMPLE_RATE

    /** The call's length in seconds. */
    val seconds: Float get() = sampleCount.toFloat() / MODEL_SAMPLE_RATE

    /** Both channels averaged; the input to diarization when the channels aren't separate speakers. */
    val mono: FloatArray by lazy {
        if (channels.size == 1) channels[0]
        else {
            val l = channels[0]
            val r = channels[1]
            FloatArray(minOf(l.size, r.size)) { (l[it] + r[it]) * 0.5f }
        }
    }

    /**
     * [mono] followed by at least [TAIL] samples of silence, as diarization hears it. It's [mono]
     * itself when the arrays have that room, as [AudioDecoder]'s do, so the call isn't copied.
     */
    fun monoWithTail(): FloatArray = mono.let { if (it.size - sampleCount >= TAIL) it else it.copyOf(sampleCount + TAIL) }

    companion object {
        /**
         * The least silence diarization hears after the call (half a second; usually about 1.5 s, the
         * room the decoder's array has left). Without it, sherpa-onnx's
         * diarization ends the whole app on some calls ("This segment is too short"): a speaker it
         * hears at the very end of its last window, which runs past the end of the call, can have
         * too little of the call left to fingerprint. On desktop, two calls with someone talking
         * to the very end failed at 44 of 320 lengths near their end without it, and at none with it.
         * It makes the crash much less likely, not impossible: a speaker heard starting well into the
         * silence would still trigger it.
         */
        const val TAIL = MODEL_SAMPLE_RATE / 2
    }
}

/**
 * Decodes any format Android's built-in codecs support (MP3 from the GrapheneOS dialer,
 * plus AAC/M4A, AMR, Opus, WAV...) and converts it to 16 kHz float PCM while streaming,
 * so the original-rate audio is never held in memory.
 */
object AudioDecoder {

    /** The recording's length from its metadata, without decoding it (0 if unknown). */
    fun probeDurationMs(context: Context, uri: Uri): Long = try {
        android.media.MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    } catch (e: Exception) {
        0L
    }

    fun decode(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in file")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            // A second more than the stated length, in case it's short, then the silence after the call.
            val estimate = (durationUs / 1_000_000.0 * MODEL_SAMPLE_RATE).toInt() + MODEL_SAMPLE_RATE + DecodedAudio.TAIL

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inFormat, null, null, 0)
                codec.start()
                return drain(extractor, codec, inFormat, estimate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inFormat: MediaFormat, estimate: Int): DecodedAudio {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        var channelCount = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatPcm = false
        var builders: List<FloatBuilder> = emptyList()
        var resamplers: List<StreamResampler> = emptyList()

        fun setUp() {
            val kept = if (channelCount == 2) 2 else 1 // >2 channels are downmixed to mono
            builders = List(kept) { FloatBuilder(estimate) }
            resamplers = builders.map { StreamResampler(sampleRate, MODEL_SAMPLE_RATE, it) }
        }

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    when {
                        builders.isEmpty() -> { sampleRate = rate; setUp() }
                        // The rate changed partway (rare): what's decoded so far keeps its rate, the rest uses the new one.
                        rate != sampleRate -> {
                            resamplers.forEach { it.finish() }
                            sampleRate = rate
                            resamplers = builders.map { StreamResampler(sampleRate, MODEL_SAMPLE_RATE, it) }
                        }
                    }
                }
                outIndex >= 0 -> {
                    if (builders.isEmpty()) setUp()
                    val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = if (floatPcm) info.size / 4 / channelCount else info.size / 2 / channelCount
                    val floats = if (floatPcm) buf.asFloatBuffer() else null
                    val shorts = if (floatPcm) null else buf.asShortBuffer()
                    // The channel layout can change partway too: mono after stereo goes to both channels.
                    for (i in 0 until frames) {
                        if (resamplers.size == 2 && channelCount == 2) {
                            resamplers[0].push(sample(floats, shorts))
                            resamplers[1].push(sample(floats, shorts))
                        } else {
                            var sum = 0f
                            repeat(channelCount) { sum += sample(floats, shorts) }
                            val mono = sum / channelCount
                            resamplers.forEach { it.push(mono) }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        if (builders.isEmpty()) error("Decoder produced no audio")
        resamplers.forEach { it.finish() }
        // The buffers themselves, unless the stated length was over a second out: their unused room is the silence after
        // the call.
        val tail = DecodedAudio.TAIL
        val channels = builders.map { it.toArray(minPadding = tail, maxPadding = 2 * MODEL_SAMPLE_RATE + tail) }
        return DecodedAudio(channels, sampleCount = builders[0].size)
    }

    private fun sample(floats: java.nio.FloatBuffer?, shorts: java.nio.ShortBuffer?): Float =
        floats?.get() ?: (shorts!!.get() / 32768f)
}
