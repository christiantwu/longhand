package io.github.christiantwu.longhand.ui

import android.content.Intent
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.Pipeline
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.SummaryStatus
import io.github.christiantwu.longhand.engine.SegmentLogic
import io.github.christiantwu.longhand.engine.VoiceProfile
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.export.SpeakerNames
import io.github.christiantwu.longhand.export.TranscriptFormatter
import io.github.christiantwu.longhand.export.Turn
import kotlinx.coroutines.launch

@Composable
fun TranscriptScreen(onBack: () -> Unit) {
    val vm: TranscriptViewModel = viewModel()
    val ink = LocalInk.current
    val context = LocalContext.current
    val rec by vm.recording.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val learning by vm.learningVoice.collectAsStateWithLifecycle()
    val turns = remember(segments) { TranscriptFormatter.turns(segments) }
    val voiceKnown = remember { VoiceProfile(context).samples() > 0 }

    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var shareOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var playingRow by remember { mutableStateOf(-1) } // the row tapped to play
    // Delete outcomes that need the user: a problem to show, or the folder to choose again.
    var deleteProblem by remember { mutableStateOf<String?>(null) }
    var needFolderAccess by remember { mutableStateOf(false) }
    fun handle(result: TranscriptViewModel.DeleteResult) {
        when {
            result.deleted -> {
                result.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                onBack()
            }
            result.needsFolderAccess -> needFolderAccess = true
            else -> deleteProblem = result.message ?: "Couldn't delete the recording."
        }
    }
    val chooseFolderForDelete = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) scope.launch {
            if (vm.allowDeleting(picked)) handle(vm.deleteCall())
            else deleteProblem = "That isn't the folder Longhand watches, so nothing was deleted. Choose the folder shown in Settings."
        }
    }
    // The speaker whose name was tapped, and which transcript that was (a redo renumbers speakers).
    var asking by remember { mutableStateOf<Pair<Int, Long?>?>(null) }
    LaunchedEffect(rec?.transcribedAt) { if (asking != null && asking?.second != rec?.transcribedAt) asking = null }

    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.saveTo(uri, markdown = true)
    }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.saveTo(uri, markdown = false)
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(vm::setCallerFromPick)
    }
    val r = rec
    val done = r?.status == RecordingStatus.DONE
    val base = r?.let { TranscriptFormatter.baseName(it.displayName) } ?: "transcript"
    val showPlayer = playback.playing || playback.positionMs > 0 || playback.error != null

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (showPlayer) 96.dp else 32.dp),
        ) {
            item {
                InkTopBar(
                    left = { InkIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                    actions = {
                        // The recording can be shared before it's transcribed, or if transcribing failed.
                        if (r != null) Box {
                            InkIconButton(Icons.Filled.Share, "Share", onClick = { shareOpen = true })
                            DropdownMenu(expanded = shareOpen, onDismissRequest = { shareOpen = false }) {
                                // Each item checks the menu is still open: during its fade-out a quick second tap
                                // would otherwise run the item again (two share sheets).
                                if (done) DropdownMenuItem(text = { Text("Share transcript") }, onClick = click@{
                                    if (!shareOpen) return@click
                                    shareOpen = false
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, vm.exportTitle())
                                        .putExtra(Intent.EXTRA_TEXT, vm.exportText(markdown = false))
                                    context.startActivity(Intent.createChooser(send, "Share transcript"))
                                })
                                DropdownMenuItem(text = { Text("Share audio") }, onClick = click@{
                                    if (!shareOpen) return@click
                                    shareOpen = false
                                    scope.launch {
                                        when (val share = vm.shareAudio()) {
                                            is TranscriptViewModel.AudioShare.Ready -> context.startActivity(share.chooser)
                                            is TranscriptViewModel.AudioShare.Unavailable -> Toast.makeText(context, share.reason, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                })
                            }
                        }
                        Box {
                            InkIconButton(Icons.Filled.MoreVert, "More", onClick = { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Who was this call with?") }, onClick = click@{
                                    if (!menuOpen) return@click
                                    menuOpen = false
                                    pickContact.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
                                })
                                if (done) {
                                    DropdownMenuItem(text = { Text("Save as Markdown…") }, onClick = { if (menuOpen) { menuOpen = false; saveMarkdown.launch("$base.md") } })
                                    DropdownMenuItem(text = { Text("Save as text…") }, onClick = { if (menuOpen) { menuOpen = false; saveText.launch("$base.txt") } })
                                }
                                DropdownMenuItem(text = { Text("Transcribe again") }, onClick = { menuOpen = false; vm.retranscribe() })
                                if (r != null) DropdownMenuItem(text = { Text("Delete…", color = ink.danger) }, onClick = click@{
                                    if (!menuOpen) return@click
                                    menuOpen = false
                                    confirmDelete = true
                                })
                            }
                        }
                    },
                )
            }
            if (r != null) {
                item { Header(r) }
                if (!done) item { StatusPanel(r, onRetry = vm::retranscribe, onQueue = vm::queue) }
            }
            if (r != null && done) {
                item { SummaryBlock(r) }
                if (learning) item {
                    MonoLabel("Learning your voice…", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = ink.violet)
                } else if (r.redoComing) item {
                    Text(
                        "This call will be processed again on the charger, to tell the voices apart better. " +
                            "Choosing “Me” here labels you until then; choose it again afterwards to teach the app your voice.",
                        style = MaterialTheme.typography.bodySmall, color = ink.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                } else if (!voiceKnown && r.ownerSpeaker == null && turns.isNotEmpty() && r.pipeline >= Pipeline.CURRENT) item {
                    Text(
                        "Tip: tap the name above your own words and choose “Me”. The app will then label you in every call.",
                        style = MaterialTheme.typography.bodySmall, color = ink.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                item { GradientRule(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
                if (turns.isEmpty()) item {
                    Text("No speech was found in this recording.", style = MaterialTheme.typography.bodyMedium,
                        color = ink.muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
                // The row being played: the tapped one while its speech lasts, else the row the position
                // falls in (rows can overlap when people talk over each other), else the last one started.
                val active = if (!showPlayer) -1 else {
                    val pos = playback.positionMs
                    playingRow.takeIf { it in turns.indices && pos >= turns[it].startMs && pos < turns[it].endMs }
                        ?: turns.indexOfLast { pos >= it.startMs && pos < it.endMs }.takeIf { it >= 0 }
                        ?: turns.indexOfLast { it.startMs <= pos }
                }
                itemsIndexed(turns) { i, turn ->
                    TurnItem(turn, names, active = i == active, onSpeaker = { asking = turn.speaker to r.transcribedAt }, onPlay = { playingRow = i; vm.playFrom(turn.startMs) })
                }
            }
        }

        if (showPlayer) {
            PlayerBar(
                playback, r?.durationMs ?: 0,
                onToggle = vm::togglePause, onSeek = vm::seekTo, onClose = vm::stop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (confirmDelete) rec?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ink.paper,
            title = { Text("Delete this call?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text("The recording (${r.displayName}) is deleted from your recordings folder, with its transcript and " +
                    "summary. This can't be undone.", style = MaterialTheme.typography.bodyMedium, color = ink.ink)
            },
            confirmButton = {
                TextButton(onClick = click@{
                    if (!confirmDelete) return@click
                    confirmDelete = false
                    scope.launch { handle(vm.deleteCall()) }
                }) { Text("Delete", color = ink.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = ink.muted) } },
        )
    }

    if (needFolderAccess) AlertDialog(
        onDismissRequest = { needFolderAccess = false },
        containerColor = ink.paper,
        title = { Text("Allow deleting recordings", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text("Longhand can read your recordings folder but not change it. To let it delete recordings, choose the " +
                "same folder once more, then tap \"Use this folder\".", style = MaterialTheme.typography.bodyMedium, color = ink.ink)
        },
        confirmButton = {
            TextButton(onClick = {
                needFolderAccess = false
                scope.launch { chooseFolderForDelete.launch(vm.watchedFolder()) }
            }) { Text("Choose folder", color = ink.ink) }
        },
        dismissButton = { TextButton(onClick = { needFolderAccess = false }) { Text("Cancel", color = ink.muted) } },
    )

    deleteProblem?.let { problem ->
        AlertDialog(
            onDismissRequest = { deleteProblem = null },
            containerColor = ink.paper,
            title = { Text("Not deleted", style = MaterialTheme.typography.titleLarge) },
            text = { Text(problem, style = MaterialTheme.typography.bodyMedium, color = ink.ink) },
            confirmButton = { TextButton(onClick = { deleteProblem = null }) { Text("OK", color = ink.ink) } },
        )
    }

    asking?.let { (speaker, transcript) ->
        SpeakerDialog(
            speaker = speaker, names = names, sample = turns.firstOrNull { it.speaker == speaker }?.text,
            learnsVoice = (rec?.pipeline ?: 0) >= Pipeline.CURRENT,
            onMe = { vm.speakerIsMe(speaker, transcript) }, onNotMe = { vm.notMe(transcript) },
            onName = { vm.nameSpeaker(speaker, it, transcript) },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun Header(r: Recording) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(
        context, r.lastModified,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
    )
    val kicker = listOfNotNull(CallText.direction(r)?.let { "$it call" } ?: "Call", date,
        r.durationMs.takeIf { it > 0 }?.let { SegmentLogic.formatDuration(it) }).joinToString(" · ")
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLabel(kicker)
        Text(CallText.title(r, CallerLookup::formatNumber), style = MaterialTheme.typography.headlineMedium, color = ink.ink)
        if (r.contactName != null && r.phoneNumber != null) {
            Text(CallerLookup.formatNumber(r.phoneNumber), style = InkType.clock, color = ink.faint)
        }
    }
}

@Composable
private fun SummaryBlock(r: Recording) {
    val ink = LocalInk.current
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            r.summary != null -> {
                Text(r.summary, style = MaterialTheme.typography.bodyLarge, color = ink.ink)
                val follow = CallText.followUps(r)
                if (follow.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        follow.forEach { f ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 8.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(ink.violet))
                                Text(f, style = MaterialTheme.typography.bodySmall, color = ink.muted)
                            }
                        }
                    }
                }
            }
            r.summaryStatus == SummaryStatus.PENDING || r.summaryStatus == SummaryStatus.PROCESSING ->
                MonoLabel("Summarizing…", color = ink.violet)
        }
    }
}

@Composable
private fun TurnItem(turn: Turn, names: SpeakerNames, active: Boolean, onSpeaker: () -> Unit, onPlay: () -> Unit) {
    val ink = LocalInk.current
    // The whole row plays from its time, so a one-word line is as easy to tap as a long one; the
    // speaker's name inside it keeps its own tap.
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) ink.raised else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = "Play from here", onClick = onPlay)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(SegmentLogic.formatClock(turn.startMs), style = InkType.clock, color = ink.faint,
            modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                names.label(turn.speaker).uppercase(), style = InkType.speaker,
                color = speakerColor(turn.speaker, names.owner),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onSpeaker).padding(vertical = 2.dp),
            )
            Text(turn.text, style = MaterialTheme.typography.bodyMedium, color = ink.ink,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun StatusPanel(r: Recording, onRetry: () -> Unit, onQueue: () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GradientRule()
        when (r.status) {
            RecordingStatus.PENDING -> {
                Text("Waiting to be transcribed.", style = MaterialTheme.typography.bodyMedium, color = ink.ink)
                InkButton("Transcribe now", onRetry)
            }
            RecordingStatus.PROCESSING -> {
                MonoLabel("Transcribing · ${(r.progress * 100).toInt()}%", color = ink.violet)
                GradientProgress(r.progress)
            }
            RecordingStatus.FAILED -> {
                Text("Couldn't transcribe this call: ${r.error ?: "unknown error"}", style = MaterialTheme.typography.bodyMedium, color = ink.ink)
                InkButton("Try again", onRetry)
            }
            RecordingStatus.SKIPPED -> {
                Text("This recording was already in the folder when you set up the app, so it wasn't transcribed.",
                    style = MaterialTheme.typography.bodyMedium, color = ink.ink)
                InkButton("Transcribe it", onQueue)
            }
            RecordingStatus.DONE -> {}
        }
    }
}

@Composable
private fun PlayerBar(
    state: PlaybackState, durationMs: Long,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    Column(modifier.fillMaxWidth().background(ink.paper).navigationBarsPadding()) {
        HorizontalDivider(color = ink.line)
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.error != null) {
                Text(state.error, style = MaterialTheme.typography.bodySmall, color = ink.danger, modifier = Modifier.weight(1f))
            } else {
                PlayButton(state.playing, onToggle)
                Spacer(Modifier.width(14.dp))
                SeekTrack(state.positionMs, durationMs, onSeek, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(SegmentLogic.formatDuration(state.positionMs), style = InkType.clock, color = ink.muted, maxLines = 1, overflow = TextOverflow.Clip)
            }
            InkIconButton(Icons.Filled.Close, "Close player", onClose, tint = ink.muted)
        }
    }
}

/** A thin gradient track; tap or drag anywhere on it to jump. */
@Composable
private fun SeekTrack(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val ink = LocalInk.current
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        modifier.height(32.dp)
            .pointerInput(durationMs) {
                detectTapGestures { o -> if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong()) }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { change, _ ->
                    if (durationMs > 0) onSeek((change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * durationMs).toLong())
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(ink.line))
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(ink.gradient))
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(ink.ink))
        }
    }
}

@Composable
private fun SpeakerDialog(
    speaker: Int, names: SpeakerNames, sample: String?, learnsVoice: Boolean,
    onMe: () -> Unit, onNotMe: () -> Unit, onName: (String) -> Unit, onDismiss: () -> Unit,
) {
    val ink = LocalInk.current
    var typed by remember { mutableStateOf(names.manual[speaker].orEmpty()) }
    val isMe = names.isOwner(speaker)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ink.paper,
        title = { Text("Who is this?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sample != null) Text("“${sample.take(120)}${if (sample.length > 120) "…" else ""}”",
                    style = MaterialTheme.typography.bodySmall, color = ink.muted)
                if (!isMe) {
                    // A transcript from before 0.5.0 may have one speaker for two people, so its voices aren't learned.
                    Choice("Me", if (learnsVoice) "Learns your voice so you're labelled in every call" else "Labels you in this call") { onMe(); onDismiss() }
                } else {
                    Choice("That's not me", "Remove the “You” label from this call") { onNotMe(); onDismiss() }
                }
                names.callerName?.let { caller ->
                    if (!isMe && names.label(speaker) != caller) Choice(caller, "The person you were talking to") { onName(caller); onDismiss() }
                }
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    label = { Text("Another name") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ink.ink, unfocusedBorderColor = ink.line),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onName(typed); onDismiss() }) { Text("Save name", color = ink.ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ink.muted) } },
    )
}

@Composable
private fun Choice(title: String, detail: String, onClick: () -> Unit) {
    val ink = LocalInk.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.raised).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = ink.ink)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = ink.muted)
    }
}

/** Made before 0.5.0 and still to be redone on the charger (a redo that failed isn't tried again). */
private val Recording.redoComing: Boolean
    get() = status == RecordingStatus.DONE && pipeline < Pipeline.CURRENT && attempts < 3
