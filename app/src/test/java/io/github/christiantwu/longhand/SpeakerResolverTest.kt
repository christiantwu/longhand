package io.github.christiantwu.longhand

import io.github.christiantwu.longhand.engine.SpeakerResolver
import io.github.christiantwu.longhand.engine.Span
import io.github.christiantwu.longhand.engine.VoiceMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.sqrt

class SpeakerResolverTest {

    // Synthetic fingerprints. A person is a direction made of a part every voice on a phone line
    // shares plus one of their own, so two people score [shared]² (0.36 by default, as on real
    // calls). A cluster scores [CLUSTER] against its person, plus noise of its own: two clusters
    // of one person score about 0.8, and a cluster scores about 0.3 against someone else's.
    private val dim = 64
    private var nextNoise = 16

    private fun person(index: Int, shared: Float = 0.6f) =
        FloatArray(dim).also { it[15] = shared; it[index] = sqrt(1 - shared * shared) }

    private fun cluster(person: FloatArray, toPerson: Float = CLUSTER): FloatArray {
        val noise = nextNoise++
        return FloatArray(dim) { toPerson * person[it] + if (it == noise) sqrt(1 - toPerson * toPerson) else 0f }
    }

    /** Three people whose voices score [ab], [ac] and [bc] against each other. */
    private fun threePeople(ab: Float, ac: Float, bc: Float): Triple<FloatArray, FloatArray, FloatArray> =
        withScores(floatArrayOf(1f, ab, ac), floatArrayOf(ab, 1f, bc), floatArrayOf(ac, bc, 1f)).let { Triple(it[0], it[1], it[2]) }

    /** Unit vectors whose scores against each other are the given rows (by Cholesky decomposition). */
    private fun withScores(vararg rows: FloatArray): List<FloatArray> {
        val n = rows.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            val sum = (0 until j).sumOf { l[i][it] * l[j][it] }
            l[i][j] = if (i == j) sqrt(rows[i][i] - sum) else (rows[i][j] - sum) / l[j][j]
        }
        return List(n) { i -> FloatArray(dim) { k -> if (k < n) l[i][k].toFloat() else 0f } }
    }

    /** Turns of [seconds] each, one after another with short gaps, by the given clusters. */
    private fun turns(vararg clusters: Int, seconds: Float = 9f): List<Span> =
        clusters.mapIndexed { i, c -> Span(i * (seconds + 0.5f), i * (seconds + 0.5f) + seconds, c) }

    /** Who each input cluster became; every cluster with a fingerprint must map to one speaker. */
    private fun speakers(spans: List<Span>, out: List<Span>): Map<Int, Int> {
        assertEquals(spans.size, out.size)
        val byCluster = spans.zip(out).groupBy({ it.first.speaker }, { it.second.speaker })
        return byCluster.mapValues { (cluster, ids) ->
            assertEquals("cluster $cluster split between speakers", 1, ids.distinct().size)
            ids.first()
        }
    }

    @Test fun mergesOnePersonSplitIntoSeveralClusters() {
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 3, 1, 4, 2, 3, 0, 4)
        val fp = mapOf(0 to cluster(a), 1 to cluster(a), 2 to cluster(a), 3 to cluster(b), 4 to cluster(b))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(setOf(who[0]), setOf(who[1], who[2]))
        assertEquals(who[3], who[4])
        assertNotEquals(who[0], who[3])
    }

    @Test fun oneVoiceStaysOne() {
        val a = person(0)
        val spans = turns(0, 1, 2, 0)
        val fp = mapOf(0 to cluster(a), 1 to cluster(a), 2 to cluster(a))
        assertEquals(setOf(0), SpeakerResolver.resolve(spans, fp).spans.map { it.speaker }.toSet())
    }

    @Test fun transferredCallKeepsThreeVoices() {
        // Two reps, the second after a transfer, who sound less alike (0.31 between their clusters)
        // than either does to the caller (0.53, 0.50): forced to two speakers, the caller would
        // merge with a rep.
        val (you, rep1, rep2) = threePeople(ab = 0.654f, ac = 0.617f, bc = 0.383f)
        val spans = turns(0, 1, 0, 1, 0, 2, 0, 2)
        val fp = mapOf(0 to cluster(you), 1 to cluster(rep1), 2 to cluster(rep2))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(3, who.values.toSet().size)
    }

    @Test fun lessTypicalStretchOfAVoiceStillMerges() {
        // A stretch of A's speech (laughing, say) that scores only 0.63 against A's other speech.
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 3, 1, 3, 2, 3, 0)
        val fp = mapOf(0 to cluster(a), 1 to cluster(a), 2 to cluster(a, toPerson = 0.7f), 3 to cluster(b))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(2, who.values.toSet().size)
        assertEquals(who[0], who[2])
    }

    @Test fun manyStretchesOfTwoSimilarVoicesStayTwoPeople() {
        // Each person's clusters score 0.81 against each other and 0.53 against the other person's.
        // Averaged fingerprints would score higher as clusters pile up (0.64 with 8 each); the
        // average score between the clusters stays 0.53.
        val a = person(0, shared = 0.81f)
        val b = person(1, shared = 0.81f)
        val spans = turns(*IntArray(16) { it })
        val fp = (0 until 16).associateWith { cluster(if (it % 2 == 0) a else b) }
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(2, who.values.toSet().size)
        assertEquals(who[0], who[14])
        assertEquals(who[1], who[15])
    }

    @Test fun twoRepsWhoSoundAlikeStayApart() {
        // On a sample call the two reps of a transferred call scored 0.53, the closest of any two people.
        val you = person(0)
        val rep1 = person(1, shared = 0.81f)
        val rep2 = person(2, shared = 0.81f)
        val spans = turns(0, 1, 0, 2, 1, 0, 3, 0, 4, 3)
        val fp = mapOf(0 to cluster(you), 1 to cluster(rep1), 2 to cluster(rep1), 3 to cluster(rep2), 4 to cluster(rep2))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(3, who.values.toSet().size)
        assertEquals(who[1], who[2])
        assertEquals(who[3], who[4])
    }

    @Test fun keepsSomeoneWhoMostlyListens() {
        // B says only 4 s (not enough to be an anchor), but sounds unlike A.
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 1, 0, 1) + Span(40f, 44f, 2)
        val fp = mapOf(0 to cluster(a), 1 to cluster(a), 2 to cluster(b))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(who[0], who[1])
        assertNotEquals(who[0], who[2])
    }

    @Test fun shortClusterOfAKnownVoiceJoinsIt() {
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 1, 0, 1) + Span(40f, 44f, 2)
        val fp = mapOf(0 to cluster(a), 1 to cluster(b), 2 to cluster(a))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(2, who.values.toSet().size)
        assertEquals(who[0], who[2])
    }

    @Test fun ownerStaysASpeakerHoweverLittleTheySay() {
        // The owner's only clean speech is 0.6 s: without a voiceprint it's placed with the other voice.
        val you = person(0)
        val rep = person(1)
        val spans = turns(1, 1, 1) + Span(30f, 30.6f, 0)
        val fp = mapOf(0 to cluster(you), 1 to cluster(rep))
        assertEquals(1, SpeakerResolver.resolve(spans, fp).spans.map { it.speaker }.toSet().size)
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp, owner = cluster(you)).spans)
        assertNotEquals(who[0], who[1])
    }

    @Test fun voiceThatResemblesTheOwnersIsNotTakenForThem() {
        // The other person's clusters score 0.53 against the voiceprint, above the 0.5 bar, but they
        // don't sound like the owner's cluster (0.81), so they aren't the owner's.
        val you = person(0, shared = 0.81f)
        val rep = person(1, shared = 0.81f)
        val spans = turns(1, 0, 1, 2, 1, 0)
        val fp = mapOf(0 to cluster(you), 1 to cluster(rep), 2 to cluster(rep))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp, owner = cluster(you)).spans)
        assertNotEquals(who[0], who[1])
        assertEquals(who[1], who[2])
    }

    @Test fun voiceprintThatCantTellTheOwnerApartDecidesNothing() {
        // On speakerphone the owner's one short reply scores only 0.66 against their voiceprint and
        // the caller 0.53: too close to tell who's who, so the voiceprint changes nothing.
        val (print, yours, theirs) = threePeople(ab = 0.66f, ac = 0.53f, bc = 0.30f)
        val spans = turns(1, 1, 1) + Span(30f, 30.6f, 0)
        val fp = mapOf(0 to yours, 1 to theirs)
        assertEquals(SpeakerResolver.resolve(spans, fp).spans, SpeakerResolver.resolve(spans, fp, owner = print).spans)
    }

    @Test fun quietOwnerSplitInTwoStaysOneSpeaker() {
        // Two short replies landed in clusters that score only 0.53 against each other (rough
        // fingerprints), 0.68 and 0.63 against the voiceprint. The caller's anchor, the only
        // dependable rival, scores 0.29, so the voiceprint decides: both replies are the owner's.
        val (print, reply1, reply2, caller) = withScores(
            floatArrayOf(1f, 0.675f, 0.63f, 0.29f),
            floatArrayOf(0.675f, 1f, 0.525f, 0.25f),
            floatArrayOf(0.63f, 0.525f, 1f, 0.25f),
            floatArrayOf(0.29f, 0.25f, 0.25f, 1f),
        )
        val spans = turns(9, 9, 9) + Span(30f, 30.7f, 5) + Span(32f, 33.2f, 6)
        val fp = mapOf(5 to reply1, 6 to reply2, 9 to caller)
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp, owner = print).spans)
        assertEquals(who[5], who[6])
        assertNotEquals(who[5], who[9])
    }

    @Test fun everySpeakerGetsAVoice() {
        // Including an owner too quiet for a fingerprint of their turns: their cluster's is used.
        val you = person(0)
        val rep = person(1)
        val spans = turns(1, 1, 1) + Span(30f, 30.6f, 0)
        val fp = mapOf(0 to cluster(you), 1 to cluster(rep))
        val result = SpeakerResolver.resolve(spans, fp, owner = cluster(you))
        val who = speakers(spans, result.spans)
        assertEquals(setOf(who[0], who[1]), result.voices.keys)
        assertEquals(1f, VoiceMath.cosine(result.voices.getValue(who.getValue(0)), fp.getValue(0)), 1e-5f)
    }

    @Test fun manyTinyPiecesDontMakeASpeaker() {
        // 20 clean "mm-hm"s of 0.45 s and one 0.6 s "yeah, right": 9.6 s of clean speech, but only the
        // 0.6 s is long enough to fingerprint, so the cluster is placed rather than made a speaker.
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 1, 0, 1) + List(20) { Span(40f + it, 40.45f + it, 2) } + Span(61f, 61.6f, 2)
        val fp = mapOf(0 to cluster(a), 1 to cluster(b), 2 to cluster(b, toPerson = 0.6f))
        assertEquals(listOf(61f to 61.6f), SpeakerResolver.fingerprintPlan(spans)[2])
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertEquals(2, who.values.toSet().size)
        assertEquals(who[1], who[2])
    }

    @Test fun backchannelOverTheSpeakerGoesToTheListener() {
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 1, 0, 1) + Span(3f, 3.4f, 7) // "Mm." while A talks
        val fp = mapOf(0 to cluster(a), 1 to cluster(b))
        val out = SpeakerResolver.resolve(spans, fp).spans
        assertEquals(out[1].speaker, out[4].speaker)
    }

    @Test fun fragmentInAPauseGoesToTheNearestVoice() {
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 1, 0, 1) + Span(9.1f, 9.3f, 7) // just after A's first turn
        val fp = mapOf(0 to cluster(a), 1 to cluster(b))
        val out = SpeakerResolver.resolve(spans, fp).spans
        assertEquals(out[0].speaker, out[4].speaker)
    }

    @Test fun shortCallOfProbesStillFindsTwoVoices() {
        val a = person(0)
        val b = person(1)
        val spans = listOf(Span(0f, 5f, 0), Span(5.5f, 9.5f, 1), Span(10f, 11f, 2))
        val fp = mapOf(0 to cluster(a), 1 to cluster(b))
        val who = speakers(spans, SpeakerResolver.resolve(spans, fp).spans)
        assertNotEquals(who[0], who[1])
    }

    @Test fun withoutFingerprintsEverythingIsOneSpeaker() {
        val spans = listOf(Span(0f, 1f, 3), Span(1.2f, 2f, 5))
        assertEquals(listOf(0, 0), SpeakerResolver.resolve(spans, emptyMap()).spans.map { it.speaker })
    }

    @Test fun fingerprintsUseOnlySpeechNobodyTalksOver() {
        val spans = listOf(Span(0f, 10f, 0), Span(5f, 25f, 1), Span(30f, 30.4f, 2), Span(40f, 41f, 3), Span(40.6f, 42f, 4))
        val plan = SpeakerResolver.fingerprintPlan(spans)
        assertEquals(listOf(10f to 25f), plan[1])
        assertEquals(listOf(0f to 5f), plan[0])
        assertEquals(null, plan[2]) // 0.4 s: too little to fingerprint
        assertEquals(listOf(41f to 42f), plan[4]) // what's left of 1.4 s once the overlap is taken out
        assertEquals(listOf(40f to 40.6f), plan[3])
    }

    @Test fun fingerprintTakesTheLongestStretchesUpToTheLimit() {
        val spans = listOf(Span(0f, 4f, 0), Span(10f, 40f, 0), Span(50f, 50.3f, 0))
        assertEquals(listOf(10f to 25f), SpeakerResolver.fingerprintPlan(spans)[0])
    }

    @Test fun fingerprintsTheLargestClustersFirst() {
        val spans = (0 until 25).map { Span(it * 10f, it * 10f + 2f + it * 0.1f, it) }
        assertEquals((5 until 25).toSet(), SpeakerResolver.fingerprintPlan(spans).keys)
    }

    @Test fun cleanPartsSubtractOverlaps() {
        val spans = listOf(Span(0f, 10f, 0), Span(2f, 3f, 1), Span(6f, 7f, 1))
        assertEquals(listOf(0f to 2f, 3f to 6f, 7f to 10f), SpeakerResolver.cleanParts(spans)[0])
    }

    @Test fun resultIsTheSameWhicheverOrderTheFingerprintsComeIn() {
        val a = person(0)
        val b = person(1)
        val spans = turns(0, 2, 1, 3, 0, 2)
        val fp = mapOf(0 to cluster(a), 1 to cluster(a), 2 to cluster(b), 3 to cluster(b))
        val forward = SpeakerResolver.resolve(spans, fp).spans
        val backward = SpeakerResolver.resolve(spans, fp.entries.reversed().associate { it.key to it.value }).spans
        assertEquals(forward, backward)
        assertEquals(VoiceMath.cosine(fp.getValue(0), fp.getValue(1)), CLUSTER * CLUSTER, 1e-5f)
    }

    private companion object {
        const val CLUSTER = 0.9f
    }
}
