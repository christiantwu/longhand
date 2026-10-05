package io.github.christiantwu.longhand.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import io.github.christiantwu.longhand.data.Shrink
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Re-encodes a recording as AAC-LC in an MPEG-4 (.m4a) file, in the format [Shrink.format] gives (the GrapheneOS Phone
 * app's own AAC option: MediaCodec's AAC encoder and MediaMuxer, as it uses them), and decodes such a file back to its end
 * to check it. Both stream, so an hour-long call is never held in memory.
 */
object AacShrinker {

    /** Stopped partway because `keepGoing` turned false. */
    class Stopped : Exception("stopped")

    /** The recording is in a form this doesn't shrink: more than two channels, or an unusual PCM encoding. */
    class Unsupported(message: String) : Exception(message)

    private const val TIMEOUT_US = 10_000L

    /** Waits of [TIMEOUT_US] in a row with nothing from a codec after which it counts as stuck: a second, or five decoding. */
    private const val STALLED_TRIES = 100

    /**
     * Encodes [source] into [out] (replaced), asking [keepGoing] as it goes.
     * @return what decoding [source] found, to check the copy against ([Shrink.problem]).
     */
    fun encode(context: Context, source: Uri, out: File, keepGoing: () -> Boolean): Shrink.Stats {
        out.delete()
        val extractor = MediaExtractor()
        var writer: AacWriter? = null
        try {
            extractor.setDataSource(context, source, null)
            // Each channel goes through its own resampler (passing samples straight through at a kept rate), into a sink
            // emptied into the encoder after every block, so only a block is ever held.
            var channels = 0
            var sinks: List<FloatBuilder> = emptyList()
            var resamplers: List<StreamResampler> = emptyList()
            var pcm = ShortArray(0)
            fun flush() {
                if (sinks.isEmpty()) return
                val frames = sinks.minOf { it.size }
                if (frames == 0) return
                val count = frames * channels
                if (pcm.size < count) pcm = ShortArray(count)
                for (f in 0 until frames) for (c in 0 until channels) pcm[f * channels + c] = toPcm16(sinks[c][f])
                sinks.forEach { it.clear() }
                writer!!.write(pcm, count)
            }
            val stats = decode(
                extractor, keepGoing,
                onFormat = { rate, ch ->
                    val format = Shrink.format(rate, ch) ?: throw Unsupported("$ch channels")
                    channels = ch
                    sinks = List(ch) { FloatBuilder(1 shl 14) }
                    resamplers = sinks.map { StreamResampler(rate, format.sampleRate, it) }
                    writer = AacWriter.open(out, format)
                },
                onSamples = { block, count ->
                    for (i in 0 until count) resamplers[i % channels].push(block[i])
                    flush()
                },
            )
            resamplers.forEach { it.finish() }
            flush()
            writer!!.finish()
            return stats
        } catch (e: Exception) {
            writer?.close()
            writer = null
            out.delete()
            throw e
        } finally {
            writer?.close()
            extractor.release()
        }
    }

    /** The header of the WAV at [source] ([Shrink.wavHeader]), from its first 64 KiB; null when it can't be read. */
    fun wavHeader(context: Context, source: Uri): Shrink.WavHeader? {
        val buf = ByteArray(1 shl 16)
        val n = (context.contentResolver.openInputStream(source) ?: return null).use { input ->
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            n
        }
        return Shrink.wavHeader(buf, n)
    }

    /** Decodes [file] to its end, asking [keepGoing] as it goes. */
    fun stats(file: File, keepGoing: () -> Boolean): Shrink.Stats {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return decode(extractor, keepGoing, onFormat = { _, _ -> }, onSamples = { _, _ -> })
        } finally {
            extractor.release()
        }
    }

    private fun toPcm16(x: Float): Short = (x * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()

    /**
     * Decodes the first audio track of [extractor] to its end, giving [onFormat] its sample rate and channels once the
     * decoder says, then each block of interleaved samples (in -1..1, whole frames) to [onSamples].
     * @return the track's length, its channels as the file states them (a decoder may give a mono track as two), and
     * its loudness.
     */
    private fun decode(
        extractor: MediaExtractor, keepGoing: () -> Boolean,
        onFormat: (sampleRate: Int, channels: Int) -> Unit, onSamples: (FloatArray, Int) -> Unit,
    ): Shrink.Stats {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track in file")
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(inFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var rate = 0
            var channels = 0
            var floatPcm = false
            var block = FloatArray(0)
            var samples = 0L
            var sumSquares = 0.0

            fun setFormat(f: MediaFormat) {
                val r = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    else AudioFormat.ENCODING_PCM_16BIT
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    throw Unsupported("PCM encoding $encoding")
                }
                floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                when {
                    rate == 0 -> {
                        rate = r
                        channels = ch
                        onFormat(r, ch)
                    }
                    r != rate || ch != channels -> throw IOException("The audio format changed partway")
                }
            }

            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                if (!keepGoing()) throw Stopped()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idle > 5 * STALLED_TRIES) {
                    throw IOException("The decoder stopped responding")
                }
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> setFormat(codec.outputFormat)
                    outIndex >= 0 -> {
                        idle = 0
                        if (rate == 0) setFormat(codec.getOutputFormat(outIndex))
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val count = (if (floatPcm) info.size / 4 else info.size / 2).let { it - it % channels }
                        if (block.size < count) block = FloatArray(count)
                        if (floatPcm) {
                            buf.asFloatBuffer().get(block, 0, count)
                        } else {
                            val shorts = buf.asShortBuffer()
                            for (i in 0 until count) block[i] = shorts.get() / 32768f
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        for (i in 0 until count) sumSquares += block[i] * block[i]
                        samples += count
                        if (count > 0) onSamples(block, count)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (rate == 0) throw IOException("Decoder produced no audio")
            val stated = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
            return Shrink.Stats(
                durationMs = samples / channels * 1000 / rate,
                channels = stated,
                rms = if (samples == 0L) 0.0 else sqrt(sumSquares / samples),
            )
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** MediaCodec's AAC encoder writing into an MPEG-4 file through MediaMuxer, fed interleaved 16-bit PCM. */
    private class AacWriter private constructor(
        private val codec: MediaCodec, private val muxer: MediaMuxer, private val format: Shrink.Format,
    ) : AutoCloseable {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var frames = 0L
        private var ended = false

        private fun timeUs() = frames * 1_000_000L / format.sampleRate

        fun write(samples: ShortArray, count: Int) {
            var off = 0
            var idle = 0
            while (off < count) {
                val drained = drain(0)
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    // A second with no room and nothing out means it's stuck.
                    if (!drained && ++idle > STALLED_TRIES) throw IOException("The encoder stopped responding")
                    continue
                }
                idle = 0
                val buf = codec.getInputBuffer(index)!!
                buf.clear()
                val n = minOf(count - off, buf.remaining() / 2 / format.channels * format.channels)
                if (n <= 0) throw IOException("Encoder input buffer too small")
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, off, n)
                codec.queueInputBuffer(index, 0, n * 2, timeUs(), 0)
                frames += n / format.channels
                off += n
            }
        }

        /** Ends the stream and writes out the file. */
        fun finish() {
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, timeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(0)
            }
            // A second with nothing from the encoder means it's stuck.
            var idle = 0
            while (!ended) if (drain(TIMEOUT_US)) idle = 0 else if (++idle > STALLED_TRIES) throw IOException("The encoder stopped responding")
            if (track < 0) throw IOException("No audio was encoded")
            muxer.stop()
        }

        /** Writes out what the encoder has ready, waiting up to [timeoutUs] for the first of it. @return whether there was any. */
        private fun drain(timeoutUs: Long): Boolean {
            var any = false
            while (!ended) {
                val index = codec.dequeueOutputBuffer(info, if (any) 0 else timeoutUs)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (track >= 0) throw IOException("The encoder's format changed partway")
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        any = true
                    }
                    index >= 0 -> {
                        any = true
                        // The codec config (AudioSpecificConfig) is in the format the muxer was given.
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            if (track < 0) throw IOException("Encoded audio before its format")
                            val buf = codec.getOutputBuffer(index)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                    }
                    else -> return any
                }
            }
            return any
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.release() }
        }

        companion object {
            /** The Phone app's AAC settings: [format]'s rate, channels and bit rate, the AAC-LC profile, an MPEG-4 file. */
            fun open(out: File, format: Shrink.Format): AacWriter {
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                try {
                    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, format.bitRate)
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    return AacWriter(codec, MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), format)
                } catch (e: Exception) {
                    runCatching { codec.stop() }
                    codec.release()
                    throw e
                }
            }
        }
    }
}
