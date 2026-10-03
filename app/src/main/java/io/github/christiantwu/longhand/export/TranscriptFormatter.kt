package io.github.christiantwu.longhand.export

import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.engine.SegmentLogic
import java.text.DateFormat
import java.util.Date

/** Consecutive lines from the same speaker, shown and exported as one paragraph; [segmentIds] are those lines, in order. */
data class Turn(val startMs: Long, val speaker: Int, val text: String, val endMs: Long = startMs, val segmentIds: List<Long> = emptyList())

/**
 * How the speakers in one transcript are named. A name typed by hand always wins; then the
 * phone's owner is "You", and on a two-person call the other voice is the contact.
 */
data class SpeakerNames(
    val manual: Map<Int, String> = emptyMap(),
    val owner: Int? = null,
    val callerName: String? = null,
    val speakers: Set<Int> = emptySet(),
) {
    fun label(speaker: Int): String {
        manual[speaker]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        if (speaker == owner) return "You"
        if (owner != null && callerName != null && speakers.size == 2 && speaker in speakers) return callerName
        return unnamedLabel(speaker)
    }

    fun isOwner(speaker: Int) = speaker == owner

    /** Shown as "Speaker N": no name typed, not the owner, and not given the caller's name. */
    fun isUnnamed(speaker: Int) = manual[speaker].isNullOrBlank() && label(speaker) == unnamedLabel(speaker)

    private fun unnamedLabel(speaker: Int) = "Speaker ${speaker + 1}"
}

/** The words used for a call around the app: list rows, notifications, exports. */
object CallText {

    /** The other party: contact name, else their number, else null. */
    fun caller(rec: Recording, formatNumber: (String) -> String = { it }): String? =
        rec.contactName?.takeIf { it.isNotBlank() } ?: rec.phoneNumber?.takeIf { it.isNotBlank() }?.let(formatNumber)

    /** List and screen title: who the call was with, or the recording's file name. */
    fun title(rec: Recording, formatNumber: (String) -> String = { it }): String =
        caller(rec, formatNumber) ?: TranscriptFormatter.baseName(rec.displayName)

    /** "Lake cabin booking" for display on its own line. */
    fun topicLine(topic: String): String = topic.replaceFirstChar { it.uppercaseChar() }

    /** "Call with Jordan Ellis regarding Harbor Road building materials", as far as it's known. */
    fun sentence(rec: Recording, formatNumber: (String) -> String = { it }): String {
        val who = caller(rec, formatNumber)
        val topic = rec.topic?.takeIf { it.isNotBlank() }
        return when {
            who != null && topic != null -> "Call with $who regarding $topic"
            who != null -> "Call with $who"
            topic != null -> "Call regarding $topic"
            else -> TranscriptFormatter.baseName(rec.displayName)
        }
    }

    fun direction(rec: Recording): String? = when (rec.callDirection) {
        1 -> "Incoming"
        2 -> "Outgoing"
        7 -> "Answered elsewhere"
        else -> null
    }

    fun followUps(rec: Recording): List<String> =
        rec.followUps?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

object TranscriptFormatter {

    /**
     * Lines grouped into turns, each shown with its start time (and played from it when tapped).
     * A speaker's lines join up when they follow each other closely and nobody else spoke in
     * between: a long turn split at its pauses. Otherwise a line starts a turn of its own, with its
     * own time; for example a reply that comes after an "okay" said over the other person.
     */
    fun turns(segments: List<Segment>): List<Turn> {
        val out = ArrayList<Turn>()
        val lastEnd = HashMap<Int, Long>() // each speaker's latest end so far
        for (s in inOrder(segments)) {
            val last = out.lastOrNull()
            val othersEnd = lastEnd.filterKeys { it != s.speaker }.values.maxOrNull() ?: 0L
            val join = last != null && last.speaker == s.speaker && s.startMs - last.endMs <= JOIN_GAP_MS &&
                // Someone else still talking after this turn ended means they spoke in between
                // (a little overlap from the padding around speech doesn't count).
                minOf(othersEnd, s.startMs) - last.endMs <= OVERLAP_SLACK_MS
            if (join) out[out.lastIndex] = last!!.copy(text = join(last.text, s.text), endMs = maxOf(last.endMs, s.endMs), segmentIds = last.segmentIds + s.id)
            else out += Turn(s.startMs, s.speaker, s.text, s.endMs, listOf(s.id))
            lastEnd[s.speaker] = maxOf(lastEnd[s.speaker] ?: 0L, s.endMs)
        }
        return out
    }

    /** Lines in the order turns are made from them: by start, then as stored (a line split by hand comes after its first part). */
    fun inOrder(segments: List<Segment>): List<Segment> = segments.sortedWith(compareBy({ it.startMs }, { it.id }))

    /**
     * Two pieces of one turn as one text. Chinese and Japanese put no spaces between words or around
     * their own punctuation; Korean and the rest get a space.
     */
    fun join(a: String, b: String): String {
        if (a.isEmpty() || b.isEmpty()) return a + b
        val end = a.codePointBefore(a.length)
        val start = b.codePointAt(0)
        val noSpace = punctuation(end) || punctuation(start) || (hanOrKana(end) && hanOrKana(start))
        return if (noSpace) a + b else "$a $b"
    }

    /** Chinese and Japanese punctuation (。、「」) and full-width forms (，！？), which carry their own spacing. */
    private fun punctuation(cp: Int) = cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF

    /** Kana, and the Chinese characters that Japanese writes as kanji. */
    private fun hanOrKana(cp: Int) = cp in 0x3040..0x30FF || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    /** Lines of one speaker this close (ms) are one turn; the engine already joins anything within 1 s. */
    private const val JOIN_GAP_MS = 2_000L

    /** Another speaker's speech running this far (ms) past a turn's end doesn't separate it from what follows. */
    private const val OVERLAP_SLACK_MS = 500L

    /** Base name for exported files: the recording's name without its extension. */
    fun baseName(displayName: String): String = displayName.substringBeforeLast('.').ifBlank { "transcript" }

    fun format(
        rec: Recording,
        segments: List<Segment>,
        names: SpeakerNames,
        markdown: Boolean,
        formatNumber: (String) -> String = { it },
    ): String = buildString {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(rec.lastModified))
        // Changed by hand, so no longer just what the recogniser heard.
        val edited = if (rec.editedAt != null) "Edited" else null
        val meta = listOfNotNull(CallText.direction(rec), date, SegmentLogic.formatDuration(rec.durationMs), edited).joinToString(" · ")
        val title = CallText.sentence(rec, formatNumber)
        if (markdown) append("# ").append(title).append("\n\n*").append(meta).append("*\n\n")
        else append(title).append("\n").append(meta).append("\n\n")

        rec.summary?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
        val follow = CallText.followUps(rec)
        if (follow.isNotEmpty()) {
            append(if (markdown) "**Follow-ups**\n\n" else "Follow-ups\n")
            follow.forEach { append("- ").append(it).append("\n") }
            append("\n")
        }
        if (rec.summary != null || follow.isNotEmpty()) append(if (markdown) "---\n\n" else "\n")

        for (t in turns(segments)) {
            val ts = SegmentLogic.formatTimestamp(t.startMs)
            val who = names.label(t.speaker)
            if (markdown) append("**[").append(ts).append("] ").append(who).append(":** ")
            else append("[").append(ts).append("] ").append(who).append(": ")
            append(t.text).append("\n\n")
        }
    }.trimEnd() + "\n"

    /** Transcript lines in the form the summary model was evaluated with: "[01:23] You: ...". */
    fun summaryLines(segments: List<Segment>, names: SpeakerNames): List<String> =
        turns(segments).map { "[${SegmentLogic.formatClock(it.startMs)}] ${names.label(it.speaker)}: ${it.text}" }

    /** "caller: Jordan Ellis | direction: incoming | duration: 3:05". */
    fun summaryHeader(rec: Recording, formatNumber: (String) -> String = { it }): String {
        val who = CallText.caller(rec, formatNumber) ?: "unknown"
        val dir = CallText.direction(rec)?.lowercase() ?: "unknown"
        return "caller: $who | direction: $dir | duration: ${SegmentLogic.formatDuration(rec.durationMs)}"
    }
}
