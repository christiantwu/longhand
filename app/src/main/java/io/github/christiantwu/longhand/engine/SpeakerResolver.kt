package io.github.christiantwu.longhand.engine

/**
 * Turns over-split diarization clusters into the people on the call.
 *
 * Diarization runs with a loose clustering threshold, so one person often comes back as several
 * clusters, plus fragments ("Mm.", a cough) that no voice fingerprint can place. Forcing exactly
 * two clusters instead lets such a fragment take one of the two slots, which put both people
 * under "Speaker 1", and it can't represent a transferred call with three voices.
 *
 * Only speech that nobody talks over counts, in pieces of at least [MIN_PART_SECONDS]: that is
 * what a fingerprint can be made from.
 * - **Anchors**: clusters with enough of it ([Config.anchorSeconds]) get a voice fingerprint and
 *   can become speakers. Speakers whose anchors match on average are merged (average linkage).
 *   On sample calls, one person's anchors scored 0.72 or more against each other and different
 *   people's 0.53 or less, so [Config.mergeAt] sits between the two. At 0.64 rather than 0.62, a
 *   person whose voice changes partway through a call keeps the changed voice as a speaker of its
 *   own, instead of it going to the owner; no other sample call changed.
 * - **Probes**: shorter clusters get a fingerprint too, mostly just to place them with the closest
 *   speaker. They never merge with each other, so a fragment that sounds halfway between two
 *   people can't join them together. One becomes a speaker of its own only when its voice is
 *   clearly unlike every speaker found so far (someone who mostly listens).
 * - **Fragments**: too short to fingerprint, so each segment is placed by context: talking over
 *   someone, it's the listener (another voice); otherwise it goes with the voice nearest in time.
 * - **The owner**: with a voiceprint, the cluster that sounds most like the owner, clearly more
 *   than anyone else on the call, stays a speaker of its own, however short (one stretch of half
 *   a second that nobody talks over is enough).
 */
object SpeakerResolver {

    data class Config(
        /** Clean speech (seconds) a cluster needs before it can be a speaker. */
        val anchorSeconds: Float = 8f,
        /** Clean speech (seconds) a cluster needs for its fingerprint to place it. Even half a second
         *  placed short replies better than their timing did on sample calls. */
        val probeSeconds: Float = 0.5f,
        /** Speakers whose anchors score at least this on average merge into one. */
        val mergeAt: Float = 0.64f,
        /** A probe scoring below this against every speaker is someone else... */
        val distinctBelow: Float = 0.5f,
        /** ...if it has at least this much clean speech (seconds). */
        val distinctSeconds: Float = 3f,
        /** The cluster that sounds most like the owner must score at least this against the voiceprint... */
        val ownerAt: Float = 0.5f,
        /** ...and this much more than any anchor that doesn't sound like it (as VoiceMath.pickOwner). */
        val ownerMargin: Float = 0.15f,
        /** At most this much clean speech (seconds) goes into a fingerprint. */
        val fingerprintSeconds: Float = 15f,
        /** At most this many clusters are fingerprinted, largest first. */
        val maxFingerprints: Int = 20,
    )

    /**
     * The people on the call: [spans] relabelled with speaker ids, and for each speaker made from
     * fingerprinted clusters, their average fingerprint (unit length).
     */
    class Result(val spans: List<Span>, val voices: Map<Int, FloatArray>)

    /** Which clusters to fingerprint and from which clean stretches (start, end in seconds). */
    fun fingerprintPlan(spans: List<Span>, config: Config = Config()): Map<Int, List<Pair<Float, Float>>> =
        usableParts(spans).entries
            .map { (cluster, parts) -> Triple(cluster, parts, seconds(parts)) }
            .filter { it.second.isNotEmpty() && it.third >= config.probeSeconds }
            .sortedWith(compareByDescending<Triple<Int, List<Pair<Float, Float>>, Float>> { it.third }.thenBy { it.first })
            .take(config.maxFingerprints)
            .associate { (cluster, parts, _) -> cluster to longestFirst(parts, config.fingerprintSeconds) }

    /**
     * Relabels [spans] (cluster ids from diarization) with speaker ids. [fingerprints] are unit or
     * raw embeddings for the clusters [fingerprintPlan] chose; [owner] is the owner's voiceprint.
     */
    fun resolve(
        spans: List<Span>,
        fingerprints: Map<Int, FloatArray>,
        owner: FloatArray? = null,
        config: Config = Config(),
    ): Result {
        if (spans.isEmpty()) return Result(spans, emptyMap())
        val clean = usableParts(spans).mapValues { seconds(it.value) }
        val unit = fingerprints.mapValues { VoiceMath.normalize(it.value) }
        fun weight(id: Int) = (clean[id] ?: 0f).coerceAtLeast(MIN_WEIGHT)

        // Each anchor starts as its own group.
        val anchorIds = unit.keys.filter { (clean[it] ?: 0f) >= config.anchorSeconds }.sorted()
        val groups = anchorIds.map { Group(mutableListOf(it), weight(it), unit.getValue(it)) }.toMutableList()

        // The cluster that sounds most like the owner is theirs, with any that sound like it, if it
        // stands out from everyone else on the call: someone who merely resembles the owner scores
        // nearly as well, and then the voiceprint can't tell who the owner is. Only anchors count
        // as someone else: a short cluster's fingerprint is too rough, and may be the owner's own.
        if (owner != null && unit.isNotEmpty()) {
            val profile = VoiceMath.normalize(owner)
            val score = unit.mapValues { VoiceMath.cosine(it.value, profile) }
            val best = score.keys.maxWith(compareBy<Int> { score.getValue(it) }.thenByDescending { it })
            fun like(id: Int) = id == best || VoiceMath.cosine(unit.getValue(id), unit.getValue(best)) >= config.mergeAt
            val runnerUp = score.filterKeys { !like(it) && (clean[it] ?: 0f) >= config.anchorSeconds }.values.maxOrNull()
            val top = score.getValue(best)
            if (top >= config.ownerAt && (runnerUp == null || top - runnerUp >= config.ownerMargin)) {
                val ownerIds = score.keys.filter { like(it) && score.getValue(it) >= config.ownerAt }.sorted()
                groups.removeAll { g -> g.members.any { it in ownerIds } }
                groups += Group(ownerIds.toMutableList(), ownerIds.sumOf { weight(it).toDouble() }.toFloat(),
                    mean(ownerIds.map { unit.getValue(it) to weight(it) }))
            }
        }

        mergeWhileSimilar(groups, config.mergeAt)

        // A probe whose voice is unlike every speaker so far is someone else (often a person who mostly
        // listens); with no anchor at all, the largest probe starts the first speaker.
        val placedIds = groups.flatMap { it.members }.toHashSet()
        for (id in unit.keys.sortedWith(compareByDescending<Int> { clean[it] ?: 0f }.thenBy { it })) {
            val tooShort = (clean[id] ?: 0f) < minOf(config.distinctSeconds, config.anchorSeconds)
            if (id in placedIds || (tooShort && groups.isNotEmpty())) continue
            val v = unit.getValue(id)
            if (groups.all { VoiceMath.cosine(v, it.mean) < config.distinctBelow }) {
                groups += Group(mutableListOf(id), weight(id), v)
                placedIds += id
            }
        }

        // Every other fingerprinted cluster joins the closest speaker.
        val speakerOf = HashMap<Int, Int>()
        groups.sortWith(compareByDescending<Group> { it.seconds }.thenBy { it.members.min() })
        groups.forEachIndexed { index, g -> g.members.forEach { speakerOf[it] = index } }
        for (id in unit.keys.sorted()) {
            if (id in speakerOf || groups.isEmpty()) continue
            speakerOf[id] = groups.indices.maxWith(
                compareBy<Int> { VoiceMath.cosine(unit.getValue(id), groups[it].mean) }.thenByDescending { it },
            )
        }
        if (groups.isEmpty()) return Result(spans.map { it.copy(speaker = 0) }, emptyMap()) // nothing to fingerprint: one voice

        // A speaker's voice is the average of all the clusters placed with it.
        val voices = speakerOf.entries.groupBy({ it.value }, { it.key }).mapValues { (_, ids) ->
            VoiceMath.normalize(mean(ids.map { unit.getValue(it) to weight(it) }))
        }

        // Fragments: decided segment by segment from the placed speech around them.
        val placed = spans.filter { it.speaker in speakerOf }.map { it.copy(speaker = speakerOf.getValue(it.speaker)) }
        val out = spans.map { s ->
            speakerOf[s.speaker]?.let { s.copy(speaker = it) } ?: s.copy(speaker = fragmentSpeaker(s, placed, groups.size))
        }
        return Result(out, voices)
    }

    /** Talking over someone, a fragment is usually the listener; otherwise it's the voice nearest in time. */
    private fun fragmentSpeaker(s: Span, placed: List<Span>, speakers: Int): Int {
        if (placed.isEmpty()) return 0
        val over = placed.filter { it.start < s.end && s.start < it.end }
        if (over.isNotEmpty() && speakers > 1) {
            val talking = over.maxWith(compareBy<Span> { minOf(it.end, s.end) - maxOf(it.start, s.start) }.thenBy { it.start }).speaker
            val others = placed.filter { it.speaker != talking }
            if (others.isNotEmpty()) return others.minWith(compareBy<Span> { gap(it, s) }.thenBy { it.start }).speaker
        }
        return placed.minWith(compareBy<Span> { gap(it, s) }.thenBy { it.start }).speaker
    }

    private fun gap(a: Span, b: Span): Float = maxOf(0f, maxOf(a.start, b.start) - minOf(a.end, b.end))

    /**
     * [mean] is the members' unit fingerprints averaged, weighted by clean speech, and not normalised:
     * the dot product of two groups' means is then the average score between their members.
     */
    private class Group(val members: MutableList<Int>, var seconds: Float, var mean: FloatArray)

    /** Average linkage: the pair of speakers whose members score highest on average merges first. */
    private fun mergeWhileSimilar(groups: MutableList<Group>, at: Float) {
        while (groups.size > 1) {
            var best: Triple<Float, Int, Int>? = null
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val sim = dot(groups[i].mean, groups[j].mean)
                if (best == null || sim > best.first) best = Triple(sim, i, j)
            }
            val (sim, i, j) = best ?: return
            if (sim < at) return
            val a = groups[i]
            val b = groups.removeAt(j)
            a.mean = mean(listOf(a.mean to a.seconds, b.mean to b.seconds))
            a.members += b.members
            a.seconds += b.seconds
        }
    }

    private fun mean(weighted: List<Pair<FloatArray, Float>>): FloatArray {
        val size = weighted.first().first.size
        val total = weighted.sumOf { it.second.toDouble() }
        val out = DoubleArray(size)
        for ((v, w) in weighted) for (k in 0 until size) out[k] += v[k] * w.toDouble()
        return FloatArray(size) { (out[it] / total).toFloat() }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0.0
        for (k in a.indices) sum += a[k] * b[k].toDouble()
        return sum.toFloat()
    }

    /** Each cluster's speech that no other cluster talks over. */
    internal fun cleanParts(spans: List<Span>): Map<Int, List<Pair<Float, Float>>> =
        spans.groupBy { it.speaker }.mapValues { (cluster, own) ->
            val others = spans.filter { it.speaker != cluster }.sortedBy { it.start }
            own.sortedBy { it.start }.flatMap { s ->
                var parts = listOf(s.start to s.end)
                for (o in others) {
                    if (o.end <= s.start || o.start >= s.end) continue
                    parts = parts.flatMap { (a, b) ->
                        listOfNotNull((a to minOf(b, o.start)).takeIf { it.second > it.first }, (maxOf(a, o.end) to b).takeIf { it.second > it.first })
                    }
                }
                parts
            }
        }

    /** The clean pieces long enough to say something about a voice. */
    private fun usableParts(spans: List<Span>): Map<Int, List<Pair<Float, Float>>> =
        cleanParts(spans).mapValues { (_, parts) -> parts.filter { (a, b) -> b - a >= MIN_PART_SECONDS } }

    private fun seconds(parts: List<Pair<Float, Float>>): Float = parts.sumOf { (a, b) -> (b - a).toDouble() }.toFloat()

    /** The longest stretches first, up to [limit] seconds in total. */
    internal fun longestFirst(parts: List<Pair<Float, Float>>, limit: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var total = 0f
        for ((a, b) in parts.sortedWith(compareByDescending<Pair<Float, Float>> { it.second - it.first }.thenBy { it.first })) {
            if (total >= limit) break
            if (b - a < MIN_PART_SECONDS) continue
            out += a to minOf(b, a + (limit - total))
            total += minOf(b - a, limit - total)
        }
        return out
    }

    /** Pieces shorter than this say little about a voice. */
    const val MIN_PART_SECONDS = 0.5f

    /** Weights stay positive, so averages are defined even for a cluster with no usable speech. */
    private const val MIN_WEIGHT = 0.01f
}
