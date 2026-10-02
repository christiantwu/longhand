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
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.AppDatabase
import io.github.christiantwu.longhand.data.Pipeline
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.Segment
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.data.SpeakerName
import io.github.christiantwu.longhand.data.SummaryStatus
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.engine.VoiceAnalyzer
import io.github.christiantwu.longhand.engine.VoiceMath
import io.github.christiantwu.longhand.engine.VoiceProfile
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.export.SpeakerNames
import io.github.christiantwu.longhand.export.TranscriptFormatter
import io.github.christiantwu.longhand.work.Work
import io.github.christiantwu.longhand.work.learnVoicesFromAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

data class PlaybackState(val playing: Boolean = false, val positionMs: Long = 0, val error: String? = null)

class TranscriptViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    val id: Long = checkNotNull(savedState["id"])
    private val dao = AppDatabase.get(app).recordings()
    private val format: (String) -> String = CallerLookup::formatNumber

    val recording: StateFlow<Recording?> = dao.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val segments: StateFlow<List<Segment>> = dao.observeSegments(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** How each speaker is named, from hand-typed names, the owner's voice and the caller. */
    val names: StateFlow<SpeakerNames> = combine(recording, segments, dao.observeSpeakerNames(id)) { rec, segs, manual ->
        SpeakerNames(
            manual = manual.associate { it.speaker to it.name },
            owner = rec?.ownerSpeaker,
            callerName = rec?.let { CallText.caller(it, format) },
            speakers = segs.map { it.speaker }.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SpeakerNames())

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
        dao.setOwnerByHand(id, speaker)
        dao.deleteSpeakerName(id, speaker)
        // A transcript from an older pipeline may have put two people under one speaker, so its
        // voice isn't learned: the call is labelled, and its redo on the charger separates them.
        if ((dao.get(id)?.pipeline ?: 0) >= Pipeline.CURRENT) {
            val voice = dao.voices(id).firstOrNull { it.speaker == speaker }?.let { VoiceMath.fromBytes(it.embedding) }
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
        dao.setOwnerByHand(id, null)
        resummarize()
    }

    fun nameSpeaker(speaker: Int, name: String, transcript: Long?) = viewModelScope.launch(Dispatchers.IO) {
        if (!stillShowing(transcript)) return@launch
        if (name.isBlank()) dao.deleteSpeakerName(id, speaker)
        else dao.upsertSpeakerNames(listOf(SpeakerName(id, speaker, name.trim())))
        resummarize()
    }

    /** Older transcripts have no stored voices; compute this call's from its audio once. */
    private suspend fun learnVoiceFromAudio(speaker: Int): FloatArray? {
        val rec = dao.get(id) ?: return null
        if (!Models.speechReady(getApplication())) return null
        learningVoice.value = true
        return try {
            VoiceAnalyzer(getApplication()).use { learnVoicesFromAudio(getApplication(), dao, rec, it) }[speaker]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't learn the voice from ${rec.displayName}: ${e.message}")
            null
        } finally {
            learningVoice.value = false
        }
    }

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

    /** Queues this recording again and starts right away, regardless of the charging setting. */
    fun retranscribe() = viewModelScope.launch(Dispatchers.IO) {
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
    }
}
