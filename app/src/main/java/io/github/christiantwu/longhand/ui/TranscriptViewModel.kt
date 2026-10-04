package io.github.christiantwu.longhand.ui

import android.app.Application
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.christiantwu.longhand.LonghandApp
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.CallSnapshot
import io.github.christiantwu.longhand.data.Correction
import io.github.christiantwu.longhand.data.toCorrections
import io.github.christiantwu.longhand.data.Pipeline
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.KnownVoiceRow
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.data.SpeakerName
import io.github.christiantwu.longhand.data.SummaryStatus
import io.github.christiantwu.longhand.data.detection
import io.github.christiantwu.longhand.engine.CallLanguage
import io.github.christiantwu.longhand.engine.Corrections
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.TranscriptEdits
import io.github.christiantwu.longhand.engine.VoiceAnalyzer
import io.github.christiantwu.longhand.engine.VoiceMath
import io.github.christiantwu.longhand.engine.VoiceProfile
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.export.SpeakerNames
import io.github.christiantwu.longhand.export.TranscriptFormatter
import io.github.christiantwu.longhand.export.Turn
import io.github.christiantwu.longhand.work.CommonCorrections
import io.github.christiantwu.longhand.work.Work
import io.github.christiantwu.longhand.work.learnVoicesFromAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

data class PlaybackState(val playing: Boolean = false, val positionMs: Long = 0, val error: String? = null)

/** Who a line, or part of one, is given to by hand: someone already in the call, or someone new. Kept across a rotation. */
sealed interface SpeakerTarget : java.io.Serializable {
    data class Existing(val speaker: Int) : SpeakerTarget
    /** The phone's owner, in a call where nobody is labelled You yet: a new speaker, set as the owner by hand. */
    data object Me : SpeakerTarget
    /** The caller, when nobody is shown with their name: a new speaker given it. */
    data class Caller(val name: String) : SpeakerTarget
    /** Someone not heard in the call before, shown as "Speaker N". */
    data object New : SpeakerTarget
}

/**
 * What the transcript says after a change by hand: [message], with Undo when [token] is set, then perhaps "Always write
 * “Youvee” for “UV”?" ([suggestion]: what was heard, and how it was written instead). A null [message] takes down
 * whatever is showing: the change can't be undone any more.
 */
class EditNotice(val message: String?, val token: Long? = null, val suggestion: Pair<String, String>? = null)

/** "Recognise voices": a known voice suggested for a speaker, and how many calls it was learned from. */
data class VoiceSuggestion(val voiceId: Long, val name: String, val calls: Int)

/** A known voice with the centroid of its samples, for [voiceSuggestions]. */
class KnownVoiceCentroid(val voice: KnownVoiceRow, val centroid: FloatArray)

/**
 * The known voice to suggest for each speaker of one call who is still shown as "Speaker N" and has
 * a stored [voices] entry ([VoiceMath.suggest]). A name already given to someone in this call is
 * never suggested (the caller's, shown for the [SpeakerNames.caller], included), nor one [rejected]
 * for that speaker.
 */
fun voiceSuggestions(
    names: SpeakerNames, voices: Map<Int, FloatArray>, known: List<KnownVoiceCentroid>, rejected: Map<Int, Set<Long>>,
): Map<Int, VoiceSuggestion> {
    if (known.isEmpty()) return emptyMap()
    val centroids = known.associate { it.voice.id to it.centroid }
    val shown = names.caller?.let { names.label(it) }
    val given = (names.manual.values + listOfNotNull(shown)).map { it.trim() }.filter { it.isNotEmpty() }
    val inThisCall = known.filter { k -> given.any { it.equals(k.voice.name, ignoreCase = true) } }.map { it.voice.id }.toSet()
    class Pick(val speaker: Int, val voiceId: Long, val score: Float)
    val picks = names.speakers.sorted().mapNotNull { speaker ->
        if (!names.isUnnamed(speaker)) return@mapNotNull null
        val voice = voices[speaker] ?: return@mapNotNull null
        val id = VoiceMath.suggest(voice, centroids, inThisCall + rejected[speaker].orEmpty()) ?: return@mapNotNull null
        Pick(speaker, id, VoiceMath.cosine(voice, centroids.getValue(id)))
    }
    // One person can't be two speakers: offer each known voice only to the speaker most like it.
    val best = picks.groupBy { it.voiceId }.mapValues { (_, p) -> p.maxBy { it.score }.speaker }
    val out = LinkedHashMap<Int, VoiceSuggestion>()
    for (p in picks) {
        if (best[p.voiceId] != p.speaker) continue
        val k = known.first { it.voice.id == p.voiceId }.voice
        out[p.speaker] = VoiceSuggestion(p.voiceId, k.name, k.calls)
    }
    return out
}

/** "Transcribe again" with more than one language on the phone: the call can be transcribed in any of them. */
object TranscribeAgain {
    /**
     * The [languages] to choose from, starting on [preselected]. With [automatic], "Automatic" comes first (null in
     * [preselected] and in the choice), saying [automaticDetail].
     */
    class Offer(
        val languages: List<Models.Language>, val preselected: Models.Language?, val automatic: Boolean = false,
        val automaticDetail: String = "",
    )

    /**
     * The language the choice starts on, of the [usable] ones: the call's [pinned] language; else, with [automatic]
     * detection, Automatic (null); else the one its transcript was [madeWith], else the one Settings transcribes in (the
     * [chosen] language, or while it downloads the [previous] one). Null without [automatic] only when nothing is usable.
     */
    fun preselected(
        pinned: Models.Language?, madeWith: Models.Language?, chosen: Models.Language, previous: Models.Language?,
        usable: List<Models.Language>, automatic: Boolean = false,
    ): Models.Language? = pinned?.takeIf { it in usable }
        ?: if (automatic) null else (listOfNotNull(madeWith, chosen, previous) + usable).firstOrNull { it in usable }

    /**
     * The call's pinned language once [picked] is chosen. Automatic (null) clears the pin. With [automatic] detection
     * on offer, any language pins the call, Settings' too, or detection would override the pick. Without it, the
     * language the call gets unpinned clears the pin: Settings' now (the [chosen] one, or while it downloads the
     * [previous] one, since the chosen one isn't offered yet), or with detection on but its model not in, the one its
     * stored [detection] found (pass it only with detection on).
     */
    fun pin(
        picked: Models.Language?, chosen: Models.Language, previous: Models.Language?, sizes: Models.Sizes,
        automatic: Boolean = false, detection: CallLanguage.Detection? = null,
    ): Models.Language? = when {
        picked == null || automatic -> picked
        else -> picked.takeUnless { it == (Models.languageFor(null, detection, chosen, previous, sizes) ?: chosen) }
    }

    /**
     * What "Automatic" says, by the [detection] stored for the call: what it would be transcribed in, of the [usable]
     * languages, or why it would follow Settings.
     */
    fun automaticDetail(detection: CallLanguage.Detection?, usable: List<Models.Language>): String {
        if (detection == null) return "Detects the language spoken"
        if (detection.speechSeconds < CallLanguage.MIN_SPEECH_SECONDS) return "Too little speech to tell"
        val language = CallLanguage.languageOf(detection)
            ?: return if (detection.codes.none { CallLanguage.family(it) != CallLanguage.Family.OTHER }) "No downloaded language detected"
                else "No single language detected"
        // Whisper's stand-ins for European languages aren't named (CallLanguage.spoken).
        val name = CallLanguage.spokenName(detection)
            ?: if (language == Models.Language.EUROPEAN) "a European language" else languageName(language)
        return "Detected: $name" + if (language in usable) "" else ", not downloaded"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    val id: Long = checkNotNull(savedState["id"])

    /** Opened from a search result: when the first matching line starts, and the text searched for. */
    val matchAt: Long? = savedState.get<Long>("at")?.takeIf { it >= 0 }
    val highlight: String? = savedState.get<String>("q")?.trim()?.ifEmpty { null }
    private val dao = AppDatabase.get(app).recordings()
    private val voiceDao = AppDatabase.get(app).voices()
    private val correctionDao = AppDatabase.get(app).corrections()
    private val settingsStore = Settings(app)
    private val format: (String) -> String = CallerLookup::formatNumber

    val recording: StateFlow<Recording?> = dao.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val segments: StateFlow<List<Segment>> = dao.observeSegments(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The recording with its speakers' names, from the same moment (the owner and caller come from it). */
    private val named: StateFlow<Pair<Recording?, SpeakerNames>> = combine(recording, segments, dao.observeSpeakerNames(id)) { rec, segs, manual ->
        rec to SpeakerNames(
            manual = manual.associate { it.speaker to it.name },
            owner = rec?.ownerSpeaker,
            callerName = rec?.let { CallText.caller(it, format) },
            speech = SpeakerNames.speechOf(segs),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null to SpeakerNames())

    /** How each speaker is named, from hand-typed names, the owner's voice and the caller. */
    val names: StateFlow<SpeakerNames> = named.map { it.second }.stateIn(viewModelScope, SharingStarted.Eagerly, SpeakerNames())

    /** Settings → Recognise voices. */
    val recogniseVoices: StateFlow<Boolean> = settingsStore.flow.map { it.recogniseVoices }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val knownVoices: Flow<List<KnownVoiceCentroid>> =
        combine(voiceDao.observeKnown(), voiceDao.observeSampleEmbeddings()) { known, samples ->
            val byVoice = samples.groupBy({ it.voiceId }, { VoiceMath.fromBytes(it.embedding) })
            known.mapNotNull { k -> VoiceMath.centroid(byVoice[k.id].orEmpty())?.let { KnownVoiceCentroid(k, it) } }
        }

    /**
     * "Recognise voices": by speaker, the known voice that someone still shown as "Speaker N" sounds
     * like. Empty while the setting is off, and in a transcript from an older pipeline (one speaker
     * there may be two people).
     */
    val suggestions: StateFlow<Map<Int, VoiceSuggestion>> = recogniseVoices.flatMapLatest { on ->
        if (!on) flowOf(emptyMap())
        else combine(named, voiceDao.observeSpeakerVoices(id), knownVoices, voiceDao.observeRejections(id)) { (rec, speakerNames), voices, known, rejections ->
            if (rec == null || rec.pipeline < Pipeline.CURRENT) emptyMap()
            else voiceSuggestions(
                speakerNames, voices.associate { it.speaker to VoiceMath.fromBytes(it.embedding) }, known,
                rejections.groupBy({ it.speaker }, { it.voiceId }).mapValues { it.value.toSet() },
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** True while the app learns the owner's voice from an older transcript. */
    val learningVoice = MutableStateFlow(false)

    val playback = MutableStateFlow(PlaybackState())
    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    // ---------------------------------------------------------------- playback

    /** Plays the recording from [ms]; the player is created on first use. */
    fun playFrom(ms: Long) {
        val rec = recording.value ?: return
        val p = player ?: ExoPlayer.Builder(getApplication()).build().also { p ->
            p.setMediaItem(MediaItem.fromUri(rec.documentUri))
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playback.value = playback.value.copy(playing = isPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    playback.value = PlaybackState(error = "Can't play the recording. " + unreadable(error))
                }
            })
            p.prepare()
            player = p
        }
        p.seekTo(ms)
        p.play()
        playback.value = PlaybackState(playing = true, positionMs = ms)
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                playback.value = playback.value.copy(positionMs = p.currentPosition)
                delay(200)
            }
        }
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms) ?: playFrom(ms)
        playback.value = playback.value.copy(positionMs = ms)
    }

    fun stop() {
        ticker?.cancel()
        player?.pause()
        playback.value = PlaybackState()
    }

    // ---------------------------------------------------------------- speakers

    /**
     * The speaker actions below apply to the [transcript] they were chosen in (its transcribedAt):
     * if it has been replaced since, by a redo that numbered the speakers afresh, they do nothing.
     */
    private suspend fun stillShowing(transcript: Long?) = dao.get(id)?.transcribedAt == transcript

    /**
     * "Me": label this voice You. In a transcript made by this version, also learn the voice and
     * look for it in every other call.
     */
    fun speakerIsMe(speaker: Int, transcript: Long?) = viewModelScope.launch(Dispatchers.IO) {
        if (!stillShowing(transcript)) return@launch
        forgetUndo()
        dao.setOwnerByHand(id, speaker)
        dao.deleteSpeakerName(id, speaker)
        // The owner's voice is learned into their own voiceprint, not as a known voice.
        voiceDao.unlink(id, speaker)
        // A transcript from an older pipeline may have put two people under one speaker, so its
        // voice isn't learned: the call is labelled, and its redo on the charger separates them.
        if ((dao.get(id)?.pipeline ?: 0) >= Pipeline.CURRENT) {
            // Lines given to someone else by hand leave the stored fingerprints out of date until they're worked out
            // again (VoiceRefreshWorker); the voiceprint can't have a voice taken out, so the speaker's voice is worked
            // out from the lines as they are now instead.
            val stored = editing.withLock { dao.voices(id).takeUnless { Work.refreshingVoices(getApplication(), id) } }
            val voice = stored?.firstOrNull { it.speaker == speaker }?.let { VoiceMath.fromBytes(it.embedding) }
                ?: learnVoiceFromAudio(speaker)
            if (voice != null) {
                VoiceProfile(getApplication()).add(voice)
                Work.matchVoices(getApplication())
            }
        }
        resummarize()
    }

    /** "That's not me": this call's owner label was wrong. */
    fun notMe(transcript: Long?) = viewModelScope.launch(Dispatchers.IO) {
        if (!stillShowing(transcript)) return@launch
        forgetUndo()
        dao.setOwnerByHand(id, null)
        resummarize()
    }

    /** A name chosen in "Who is this?" (blank clears it). With Recognise voices on, the voice is learned under it. */
    fun nameSpeaker(speaker: Int, name: String, transcript: Long?) = viewModelScope.launch(Dispatchers.IO) {
        if (!stillShowing(transcript)) return@launch
        forgetUndo()
        if (name.isBlank()) dao.deleteSpeakerName(id, speaker)
        else dao.upsertSpeakerNames(listOf(SpeakerName(id, speaker, name.trim())))
        resummarize()
        learnName(speaker, name, transcript)
    }

    /** "That's them": exactly as if the suggested name were chosen in "Who is this?". */
    fun confirmSuggestion(speaker: Int, transcript: Long?) {
        val suggestion = suggestions.value[speaker] ?: return
        nameSpeaker(speaker, suggestion.name, transcript)
    }

    /** "Not them": that known voice isn't suggested for this speaker again. */
    fun rejectSuggestion(speaker: Int, transcript: Long?) {
        val suggestion = suggestions.value[speaker] ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!stillShowing(transcript)) return@launch
            forgetUndo()
            voiceDao.reject(id, speaker, suggestion.voiceId)
        }
    }

    /**
     * Recognise voices: the speaker's voice becomes a sample of the known voice with the [name] the
     * user chose (a cleared name removes the link). Only while the setting is on, and only in a
     * transcript made by this version: an older one may have put two people under one speaker.
     */
    private suspend fun learnName(speaker: Int, name: String, transcript: Long?) {
        val rec = dao.get(id)
        // The owner's voice goes into their own voiceprint, never a known voice.
        val learns = name.isNotBlank() && settingsStore.current().recogniseVoices && rec != null &&
            rec.pipeline >= Pipeline.CURRENT && rec.ownerSpeaker != speaker
        if (!learns) {
            voiceDao.unlink(id, speaker)
            return
        }
        // Only a call with no stored voices at all needs its audio analysed. In one transcribed with
        // voices, a speaker without one said too little to fingerprint, and the audio won't change that.
        if (dao.voices(id).isEmpty()) learnVoiceFromAudio(speaker, mine = false)
        voiceDao.link(id, speaker, name, transcript)
    }

    /**
     * Older transcripts have no stored voices; compute this call's from its audio once. For the
     * owner's voice ([mine]), [learningVoice] is on meanwhile.
     */
    private suspend fun learnVoiceFromAudio(speaker: Int, mine: Boolean = true): FloatArray? {
        val rec = dao.get(id) ?: return null
        if (!Models.speechReady(getApplication())) return null
        if (mine) learningVoice.value = true
        return try {
            VoiceAnalyzer(getApplication()).use { learnVoicesFromAudio(getApplication(), dao, rec, it) }[speaker]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't learn the voice from ${rec.displayName}: ${e.message}")
            null
        } finally {
            if (mine) learningVoice.value = false
        }
    }

    // ---------------------------------------------------------------- editing

    /** Settings: the tip about long-pressing a line has been seen (true until settings are read, so it doesn't flash). */
    val editTipSeen: StateFlow<Boolean> = settingsStore.flow.map { it.editTipSeen }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun markEditTipSeen() {
        if (!editTipSeen.value) viewModelScope.launch { settingsStore.setEditTipSeen(true) }
    }

    /** Common corrections, for "Always correct this?". */
    val corrections: StateFlow<List<Correction>> = correctionDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val notices = Channel<EditNotice>(Channel.BUFFERED)
    val editNotices: Flow<EditNotice> = notices.receiveAsFlow()

    /** The last change by hand, which Undo puts back: one level, kept while this screen is open. */
    private class Change(val token: Long, val snapshot: CallSnapshot)

    @Volatile private var lastChange: Change? = null
    private var changes = 0L

    /** One change at a time, so a quick second tap works from the first one's result. */
    private val editing = Mutex()

    /** A change queued the summary to be written again: it's asked for once the user leaves the transcript. */
    @Volatile private var summaryQueued = false

    /** Naming a speaker, say, after a change: Undo would put the old names back too, so it's no longer offered. */
    private suspend fun forgetUndo() {
        if (lastChange == null) return
        lastChange = null
        notices.send(EditNotice(null))
    }

    /** The turn's lines as stored, or null if they changed since it was shown. */
    private fun linesOf(turn: Turn): List<Segment>? {
        val byId = segments.value.associateBy { it.id }
        val lines = turn.segmentIds.map { byId[it] ?: return null }
        if (lines.isEmpty() || lines.any { it.speaker != turn.speaker } || TranscriptEdits.text(lines) != turn.text) return null
        return lines
    }

    /** Edits mark the summary to be written again, when there is one and the model is installed. */
    private fun resummarizes() = Models.isInstalled(getApplication(), Models.Set.SUMMARY)

    /**
     * Runs one change by hand: [change] returns the call as it was before, for Undo, and what to say about it; null
     * when the transcript changed meanwhile and nothing was done. With [speakers], the call's voice fingerprints are
     * worked out again afterwards.
     */
    private fun change(speakers: Boolean, suggestion: Pair<String, String>? = null, change: suspend () -> Pair<CallSnapshot, String>?) =
        viewModelScope.launch(Dispatchers.IO) {
            editing.withLock {
                // Finished even if the screen closes meanwhile.
                val done = withContext(NonCancellable) {
                    change()?.also { if (speakers) Work.refreshVoices(getApplication(), id) }
                }
                val (before, message) = done ?: run {
                    notices.send(EditNotice("The transcript changed meanwhile, so nothing was changed. Try again."))
                    return@withLock
                }
                val token = ++changes
                lastChange = Change(token, before)
                if (before.recording.summaryStatus != SummaryStatus.NONE && resummarizes()) summaryQueued = true
                notices.send(EditNotice(message, token, suggestion))
            }
        }

    /** Speaker numbers for [targets]: someone new gets the next number not used in the call, one each. */
    private class Resolved(val speakers: List<Int>, val owner: Int?, val names: List<SpeakerName>, val labels: List<String>)

    private suspend fun resolve(targets: List<SpeakerTarget>): Resolved? {
        val call = dao.snapshot(id) ?: return null
        val used = call.segments.map { it.speaker } + call.names.map { it.speaker } + call.voices.map { it.speaker } +
            call.samples.map { it.speaker } + listOfNotNull(call.recording.ownerSpeaker)
        var next = (used.maxOrNull() ?: -1) + 1
        var owner: Int? = null
        val speakers = ArrayList<Int>()
        val names = ArrayList<SpeakerName>()
        val labels = ArrayList<String>()
        for (target in targets) {
            val speaker = when (target) {
                is SpeakerTarget.Existing -> target.speaker
                else -> next++
            }
            when (target) {
                is SpeakerTarget.Me -> owner = speaker
                is SpeakerTarget.Caller -> names += SpeakerName(id, speaker, target.name)
                else -> {}
            }
            speakers += speaker
            labels += when (target) {
                is SpeakerTarget.Existing -> if (this.names.value.isOwner(speaker)) "you" else this.names.value.label(speaker)
                is SpeakerTarget.Me -> "you"
                is SpeakerTarget.Caller -> target.name
                is SpeakerTarget.New -> "Speaker ${speaker + 1}"
            }
        }
        return Resolved(speakers, owner, names, labels)
    }

    /**
     * "Edit text": the turn becomes one line saying [typed], kept from common corrections. When one phrase of a few
     * words was replaced ("UV" by "Youvee"), Undo is followed by the offer to always write it that way.
     */
    fun editText(turn: Turn, transcript: Long?, typed: String) {
        val text = TranscriptEdits.clean(typed)
        if (text.isEmpty() || text == turn.text) return
        // Worked out against what the recogniser wrote, which rules apply to: a word a rule wrote gives a rule for its words.
        val recognized = linesOf(turn)?.let(TranscriptEdits::recognized) ?: turn.text
        val suggestion = TranscriptEdits.learnedPhrase(turn.text, text, recognized, corrections.value.toCorrections())?.takeUnless { (heard, written) ->
            corrections.value.any { it.heardKey == Corrections.keyOf(heard) && it.written == written.trim() }
        }
        change(speakers = false, suggestion) {
            val lines = linesOf(turn) ?: return@change null
            dao.changeLines(id, transcript, lines, listOf(TranscriptEdits.edit(lines, text)), resummarize = resummarizes())
                ?.let { it to "Line edited" }
        }
    }

    /** "Someone else said this…": the whole turn goes to [target]. */
    fun reassign(turn: Turn, transcript: Long?, target: SpeakerTarget) = change(speakers = true) {
        val lines = linesOf(turn) ?: return@change null
        val r = resolve(listOf(target)) ?: return@change null
        if (r.speakers[0] == turn.speaker) return@change null
        dao.changeLines(id, transcript, lines, TranscriptEdits.reassign(lines, r.speakers[0]), r.owner, r.names, resummarizes())
            ?.let { it to "Moved to ${r.labels[0]}" }
    }

    /** "Split line…": the turn's words before word [at] go to [before], the rest to [after]. */
    fun split(turn: Turn, transcript: Long?, at: Int, before: SpeakerTarget, after: SpeakerTarget) = change(speakers = true) {
        val lines = linesOf(turn) ?: return@change null
        if (at !in 1 until TranscriptEdits.words(lines).size) return@change null
        val r = resolve(listOf(before, after)) ?: return@change null
        if (r.speakers[0] == r.speakers[1]) return@change null
        val new = TranscriptEdits.split(lines, at, r.speakers[0], r.speakers[1])
        dao.changeLines(id, transcript, lines, new, r.owner, r.names, resummarizes())?.let { it to "Line split" }
    }

    /**
     * "Same person as…": everything [from] said is [into]'s. The message names nobody: a merge can change the name
     * shown (a name or the You label moves with the lines, and the caller's name goes to whoever else now speaks longest).
     */
    fun mergeSpeakers(from: Int, into: Int, transcript: Long?) = change(speakers = true) {
        dao.mergeSpeakers(id, transcript, from, into, resummarizes())?.let { it to "Speakers merged" }
    }

    /** Puts back the call as it was before change [token], if that's still the last one. */
    fun undo(token: Long) = viewModelScope.launch(Dispatchers.IO) {
        editing.withLock {
            val change = lastChange?.takeIf { it.token == token } ?: return@withLock
            lastChange = null
            // The fingerprints come back with the lines; a refresh queued by the change works from the lines as they are then.
            val restored = withContext(NonCancellable) { dao.restore(change.snapshot) }
            if (!restored) notices.send(EditNotice("Couldn't undo: the call was transcribed again meanwhile."))
            // The snapshot's fingerprints may predate an earlier change whose refresh has finished since; work them out
            // again from the restored lines, so "Me" never learns from out-of-date ones.
            else Work.refreshVoices(getApplication(), id)
        }
    }

    /**
     * "Always": the rule, from the correction dialog. With [thisCall], the rest of this transcript is corrected at once;
     * with [earlierCalls], other calls are in the background.
     */
    fun addCorrection(heard: String, written: String, earlierCalls: Boolean, thisCall: Boolean) =
        getApplication<LonghandApp>().appScope.launch(Dispatchers.IO) {
            CommonCorrections.add(getApplication(), heard, written, earlierCalls, call = id.takeIf { thisCall }, except = id.takeUnless { thisCall })
        }

    /** For "Also correct N other calls": calls other than this one. */
    suspend fun otherCallsSaying(heard: String): Int =
        withContext(Dispatchers.Default) { CommonCorrections.earlierCalls(getApplication(), heard, except = id) }

    /** For "Also correct N other lines in this call". */
    suspend fun linesHereSaying(heard: String): Int =
        withContext(Dispatchers.Default) { CommonCorrections.linesInCall(getApplication(), id, heard) }

    // ---------------------------------------------------------------- caller

    /** The contact the user picked for this call (ACTION_PICK on phone numbers). */
    fun setCallerFromPick(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val c = CallerLookup.fromPickedPhone(getApplication(), uri) ?: return@launch
        dao.setCaller(id, c.number, c.name, c.lookupKey, recording.value?.callDirection, manual = true)
        resummarize()
    }

    /** Names feed the summary prompt, so a correction re-runs the summary when there is one. */
    private suspend fun resummarize() {
        val rec = dao.get(id) ?: return
        if (rec.summaryStatus == SummaryStatus.NONE || !Models.isInstalled(getApplication(), Models.Set.SUMMARY)) return
        dao.setSummaryStatus(id, SummaryStatus.PENDING)
        requestNowIfAllowed()
    }

    // ---------------------------------------------------------------- queue & export

    /**
     * What "Transcribe again" offers; null with fewer than two languages on the phone, when there's nothing to choose.
     * With language detection on and its model in, it offers Automatic too.
     */
    suspend fun transcribeAgainOffer(): TranscribeAgain.Offer? = withContext(Dispatchers.IO) {
        val rec = dao.get(id) ?: return@withContext null
        val sizes = Models.sizes(getApplication())
        val usable = Models.usableLanguages(sizes)
        if (usable.size < 2) return@withContext null
        val s = settingsStore.current()
        val automatic = Models.canDetect(s.detectLanguage, sizes)
        TranscribeAgain.Offer(
            usable, TranscribeAgain.preselected(rec.pinnedLanguage, rec.language, s.language, s.previousLanguage, usable, automatic),
            automatic, TranscribeAgain.automaticDetail(rec.detection, usable),
        )
    }

    /**
     * Queues this recording again and starts right away, regardless of the charging setting. Edits by hand are replaced
     * (the screen asks first), so the call stops counting as edited. Its language stays as it is.
     */
    fun retranscribe() = requeue { }

    /**
     * [retranscribe] in the language [picked] in "Transcribe again", or with Automatic (null) in the one detected: it
     * stays with the call for its later transcriptions too ([TranscribeAgain.pin], with [automatic] on offer or not).
     */
    fun retranscribe(picked: Models.Language?, automatic: Boolean) = requeue {
        // Automatic asked for: a call left undecided by a stop mid-detection ([RecordingDao.startDetection]) gets one more try.
        if (picked == null && automatic) dao.interruptDetection(id)
        val s = settingsStore.current()
        val detection = dao.get(id)?.detection?.takeIf { s.detectLanguage }
        val sizes = Models.sizes(getApplication())
        dao.setPinnedLanguage(id, TranscribeAgain.pin(picked, s.language, s.previousLanguage, sizes, automatic, detection))
    }

    private fun requeue(before: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        forgetUndo()
        before()
        dao.forgetEdits(id)
        dao.requeue(listOf(id))
        dao.request(listOf(id))
        Work.enqueueTranscribe(getApplication(), followUp = true)
    }

    /** Queues it respecting the charging setting (used for a skipped recording). */
    fun queue() = viewModelScope.launch(Dispatchers.IO) {
        dao.requeue(listOf(id))
        requestNowIfAllowed()
    }

    /** Respects "Only while charging": on battery with it on, the recording waits for the charger. */
    private suspend fun requestNowIfAllowed() {
        if (!Work.mayRunNow(getApplication())) return
        dao.request(listOf(id))
        Work.enqueueTranscribe(getApplication(), followUp = true)
    }

    fun exportText(markdown: Boolean): String {
        val rec = recording.value ?: return ""
        return TranscriptFormatter.format(rec, segments.value, names.value, markdown, format)
    }

    fun exportTitle(): String = recording.value?.let { CallText.sentence(it, format) } ?: "Transcript"

    fun saveTo(uri: Uri, markdown: Boolean) = viewModelScope.launch {
        val text = exportText(markdown)
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }

    /**
     * What [deleteCall] did. [deleted]: the call is gone from Longhand. [message]: something to tell
     * the user. [needsFolderAccess]: nothing was deleted, because Longhand may only read the
     * recordings folder; choosing the folder again (see [allowDeleting]) lets it delete files there.
     */
    class DeleteResult(val deleted: Boolean, val message: String? = null, val needsFolderAccess: Boolean = false)

    /**
     * Deletes this call: its recording file, then its transcript, summary and voices. A recording
     * already gone from the folder just loses its transcript too. One in a folder Longhand no
     * longer watches (another was chosen in Settings) and can't reach either loses only its
     * transcript: nothing will add it back.
     */
    suspend fun deleteCall(): DeleteResult {
        val rec = recording.value ?: return DeleteResult(deleted = true)
        stop()
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val uri = rec.documentUri.toUri()
        val watched = Settings(app).current().folderUri?.toUri()
        // The file and the transcript go together, even if the screen closes in between.
        return withContext(Dispatchers.IO + NonCancellable) {
            suspend fun gone(message: String? = null): DeleteResult {
                dao.delete(rec.id)
                return DeleteResult(deleted = true, message = message)
            }
            if (documentGone(resolver, uri)) return@withContext gone()
            try {
                // Write access, which deleting needs, comes with the folder (chosen since 0.6.0) and is
                // taken up here in case only read access was kept.
                val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
                resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (DocumentsContract.deleteDocument(resolver, uri)) gone()
                else DeleteResult(deleted = false, message = "Couldn't delete the recording.")
            } catch (e: Exception) {
                Log.w(TAG, "can't delete ${rec.displayName}: ${e.javaClass.simpleName}")
                val noAccess = generateSequence<Throwable>(e) { it.cause }.any { it is SecurityException }
                when {
                    documentGone(resolver, uri) -> gone()
                    noAccess && !inFolder(uri, watched) ->
                        gone("Transcript deleted. The recording stays in its old folder.")
                    noAccess -> DeleteResult(deleted = false, needsFolderAccess = true)
                    else -> DeleteResult(deleted = false, message = "Couldn't delete the recording.")
                }
            }
        }
    }

    /** Where the folder picker should open: the folder Longhand watches. */
    suspend fun watchedFolder(): Uri? = Settings(getApplication()).current().folderUri?.toUri()?.let { tree ->
        runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()
    }

    /**
     * After the folder was chosen again for deleting: keeps write access to it, if it's the folder
     * Longhand watches. False if another folder was chosen (nothing changes then).
     */
    suspend fun allowDeleting(picked: Uri): Boolean {
        val app = getApplication<Application>()
        val watched = Settings(app).current().folderUri?.toUri() ?: return false
        if (!sameTree(picked, watched)) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
        }
    }

    /**
     * Whether [uri]'s document no longer exists. For a tree URI, ExternalStorageProvider reports a
     * missing file as IllegalArgumentException rather than FileNotFoundException.
     */
    private fun documentGone(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { !it.moveToFirst() } ?: true
    } catch (e: IllegalArgumentException) {
        true
    } catch (e: java.io.FileNotFoundException) {
        true
    } catch (e: Exception) {
        false // no access, for example: not known to be gone
    }

    private fun sameTree(a: Uri, b: Uri): Boolean = runCatching {
        a.authority == b.authority && DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)
    }.getOrDefault(false)

    /** Whether [document] lies in the [folder] Longhand watches (both from the folder picker). */
    private fun inFolder(document: Uri, folder: Uri?): Boolean = folder != null && sameTree(document, folder)

    /** What [shareAudio] found: a chooser to start, or why the recording can't be shared. */
    sealed interface AudioShare {
        class Ready(val chooser: Intent) : AudioShare
        class Unavailable(val reason: String) : AudioShare
    }

    /**
     * A chooser for sharing the call's recording itself. Nothing is copied: the app picked in
     * the chooser may read just this one file, while it sends it. Only the file goes: no subject
     * line, which some apps (Telegram) would send unseen as a caption with the contact's name.
     */
    suspend fun shareAudio(): AudioShare {
        val rec = recording.value ?: return AudioShare.Unavailable("Can't share the recording.")
        val resolver = getApplication<Application>().contentResolver
        val uri = rec.documentUri.toUri()
        return withContext(Dispatchers.IO) {
            try {
                resolver.openAssetFileDescriptor(uri, "r")?.close() ?: throw java.io.FileNotFoundException(rec.displayName)
                // The provider types by extension and calls .3gp video; it's always audio here.
                val type = resolver.getType(uri)?.takeIf { it.startsWith("audio/") }
                    ?: if (rec.displayName.endsWith(".3gp", ignoreCase = true)) "audio/3gpp" else "audio/*"
                val send = Intent(Intent.ACTION_SEND)
                    .setType(type)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData(rec.displayName, arrayOf(type), ClipData.Item(uri)) }
                AudioShare.Ready(Intent.createChooser(send, "Share audio"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "can't share ${rec.displayName}: ${e.javaClass.simpleName}")
                AudioShare.Unavailable("Can't share the recording. " + unreadable(e))
            }
        }
    }

    /**
     * Why a recording couldn't be read. A permission error means the folder it's in is no longer
     * the one Longhand may read (another folder was chosen in Settings, or access was taken away);
     * anything else, that the file was moved or deleted.
     */
    private fun unreadable(e: Throwable): String =
        if (generateSequence(e) { it.cause }.any { it is SecurityException }) "Longhand no longer has access to the folder it's in."
        else "Was it moved or deleted?"

    override fun onCleared() {
        player?.release()
        // Leaving the transcript: a summary queued by edits is asked for now, once (respecting "Only while charging").
        if (summaryQueued) {
            val app = getApplication<LonghandApp>()
            app.appScope.launch(Dispatchers.IO) {
                if (dao.get(id)?.summaryStatus == SummaryStatus.PENDING && Work.mayRunNow(app)) {
                    dao.request(listOf(id))
                    Work.enqueueTranscribe(app, followUp = true)
                }
            }
        }
    }
}
