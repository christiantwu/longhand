package io.github.christiantwu.longhand.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Speaker-embedding arithmetic: voices are compared by cosine similarity. */
object VoiceMath {

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / sqrt(na * nb)).toFloat()
    }

    /**
     * Which speaker is the phone's owner: the one whose voice is most similar to [profile],
     * if it is similar enough and clearly closer than everyone else on the call.
     */
    fun pickOwner(profile: FloatArray, voices: Map<Int, FloatArray>, threshold: Float = 0.5f, margin: Float = 0.15f): Int? {
        val scored = voices.map { (speaker, v) -> speaker to cosine(profile, v) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < threshold) return null
        val second = scored.getOrNull(1)?.second ?: return best.first
        return if (best.second - second >= margin) best.first else null
    }

    // "Recognise voices": in the private sample calls the same person scored 0.59–0.82 across calls
    // and different people at most 0.53. Both are set to keep wrong suggestions rare.
    const val SUGGEST_THRESHOLD = 0.56f
    const val SUGGEST_MARGIN = 0.10f

    /**
     * Which known voice (by id, from their [centroid]s) a speaker's [voice] sounds like: the closest
     * one, if it is close enough and clearly closer than the next. Null if that one is [excluded]
     * (turned down for this speaker, or already someone else's name in this call): the next best
     * isn't offered instead, since this voice sounds more like someone it isn't.
     */
    fun suggest(
        voice: FloatArray, known: Map<Long, FloatArray>, excluded: Set<Long> = emptySet(),
        threshold: Float = SUGGEST_THRESHOLD, margin: Float = SUGGEST_MARGIN,
    ): Long? {
        val scored = known.map { (id, v) -> id to cosine(voice, v) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < threshold || best.first in excluded) return null
        val second = scored.getOrNull(1)?.second ?: return best.first
        return if (best.second - second >= margin) best.first else null
    }

    /** A known voice's centroid: the normalised mean of its samples (unit length), or null without any. */
    fun centroid(samples: List<FloatArray>): FloatArray? {
        val size = samples.firstOrNull()?.size ?: return null
        val sum = FloatArray(size)
        for (s in samples) {
            if (s.size != size) continue
            val unit = normalize(s)
            for (i in sum.indices) sum[i] += unit[i]
        }
        return normalize(sum)
    }

    fun toBytes(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()

    fun fromBytes(b: ByteArray): FloatArray =
        FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
}

/**
 * The owner's voiceprint: the average of every voice they've confirmed as theirs, kept in
 * the app's private storage. Each confirmation makes later matches more reliable.
 */
class VoiceProfile(context: Context) {
    // Version 2 of the file: a voiceprint learned before 0.5.0 may mix the owner's voice with the
    // other person's (the speakers weren't kept apart), so it's dropped and learned again.
    private val file = File(context.filesDir, "voiceprint-2.bin").also {
        File(context.filesDir, "voiceprint.bin").delete()
    }

    /** The current voiceprint (unit length), or null before the owner first chooses "Me". */
    fun load(): FloatArray? = read()?.second

    fun samples(): Int = read()?.first ?: 0

    fun add(embedding: FloatArray) {
        val unit = VoiceMath.normalize(embedding)
        val (count, current) = read() ?: (0 to FloatArray(unit.size))
        val merged = if (current.size != unit.size) unit
        else FloatArray(unit.size) { (current[it] * count + unit[it]) / (count + 1) }
        write(count + 1, VoiceMath.normalize(merged))
    }

    fun clear() {
        file.delete()
    }

    private fun read(): Pair<Int, FloatArray>? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 8) return null
        val count = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return count to VoiceMath.fromBytes(bytes.copyOfRange(4, bytes.size))
    }

    private fun write(count: Int, v: FloatArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array() + VoiceMath.toBytes(v))
        tmp.renameTo(file)
    }
}

/** Computes a voice fingerprint per speaker from the parts of a call where they talk. */
class VoiceAnalyzer(context: Context, threads: Int = 2) : Closeable {

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = null,
        config = SpeakerEmbeddingExtractorConfig(
            model = Models.file(context, "embedding.onnx").absolutePath,
            numThreads = threads,
        ),
    )

    /**
     * @param turns each speaker's stretches of speech, with the samples they come from.
     * Uses up to [maxSeconds] of each speaker's longest stretches that nobody talks over.
     */
    fun voices(turns: List<Pair<Span, FloatArray>>, maxSeconds: Float = 30f): Map<Int, FloatArray> {
        val out = HashMap<Int, FloatArray>()
        for ((speaker, theirs) in turns.groupBy { it.first.speaker }) {
            val samples = theirs.first().second
            // Only what nobody else says over it on the same audio: overlap would mix in their voice.
            val sameAudio = turns.filter { it.second === samples }.map { it.first }
            val parts = SpeakerResolver.longestFirst(SpeakerResolver.cleanParts(sameAudio)[speaker].orEmpty(), maxSeconds)
            if (parts.sumOf { (a, b) -> (b - a).toDouble() } < 1.5) continue // too little speech for a dependable fingerprint
            fingerprint(samples, parts)?.let { out[speaker] = it }
        }
        return out
    }

    /** The voice fingerprint (unit length) of [parts] (start, end in seconds) of [samples], or null if too short. */
    fun fingerprint(samples: FloatArray, parts: List<Pair<Float, Float>>): FloatArray? {
        val pieces = parts.mapNotNull { (a, b) ->
            val from = (a * MODEL_SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val to = (b * MODEL_SAMPLE_RATE).toInt().coerceIn(from, samples.size)
            if (to > from) samples.copyOfRange(from, to) else null
        }
        if (pieces.isEmpty()) return null
        // One piece (a window splitting turns by voice, say) is already a copy of its own.
        val joined = pieces.singleOrNull() ?: FloatArray(pieces.sumOf { it.size }).also { all ->
            var pos = 0
            for (p in pieces) {
                p.copyInto(all, pos)
                pos += p.size
            }
        }
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(joined, MODEL_SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) VoiceMath.normalize(extractor.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()
}
