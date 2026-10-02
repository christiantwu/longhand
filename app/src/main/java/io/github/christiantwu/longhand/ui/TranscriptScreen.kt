package io.github.christiantwu.longhand.ui

import android.content.Intent
import android.provider.ContactsContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import io.github.christiantwu.longhand.export.SearchMatch
import io.github.christiantwu.longhand.export.SpeakerNames
import io.github.christiantwu.longhand.export.TranscriptFormatter
import io.github.christiantwu.longhand.export.Turn
import kotlinx.coroutines.launch

/** [onCallsWith]: "Calls with …" was chosen, for the list to show that person's calls. */
@Composable
fun TranscriptScreen(onBack: () -> Unit, onCallsWith: (PersonFilter) -> Unit) {
    val vm: TranscriptViewModel = viewModel()
    val context = LocalContext.current
    val rec by vm.recording.collectAsStateWithLifecycle()
    val segments by vm.segments.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val learning by vm.learningVoice.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recognising by vm.recogniseVoices.collectAsStateWithLifecycle()
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
    // Room for "1:02:03" beside every line once a call runs past the hour, so the text stays in one column.
    val clockWidth = if ((turns.lastOrNull()?.startMs ?: 0) >= 3_600_000) 50.dp else 38.dp

    // From a search result: once the transcript is in, the turn with the match goes to the top, once.
    val listState = rememberLazyListState()
    var jumped by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(turns, done) {
        val at = vm.matchAt
        if (jumped || at == null || !done || turns.isEmpty()) return@LaunchedEffect
        withFrameNanos {} // by the next frame, the list has laid these turns out
        // The turns are the list's last items.
        val turn = turns.indexOfLast { it.startMs <= at }.coerceAtLeast(0)
        listState.scrollToItem(listState.layoutInfo.totalItemsCount - turns.size + turn)
        jumped = true
    }

    // Only the list is inset: the player bar paints its colour under the navigation bar.
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            state = listState,
            contentPadding = PaddingValues(bottom = if (showPlayer) 96.dp else 32.dp),
        ) {
            item {
                AppBar(
                    navigation = { BackButton(onBack) },
                    actions = {
                        // The recording can be shared before it's transcribed, or if transcribing failed.
                        if (r != null) Box {
                            AppIconButton(Icons.Filled.Share, "Share", onClick = { shareOpen = true })
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
                            AppIconButton(Icons.Filled.MoreVert, "More", onClick = { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Who was this call with?") }, onClick = click@{
                                    if (!menuOpen) return@click
                                    menuOpen = false
                                    pickContact.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
                                })
                                r?.let(PersonFilter::of)?.let { person ->
                                    DropdownMenuItem(text = { Text("Calls with ${person.name}") }, onClick = click@{
                                        if (!menuOpen) return@click
                                        menuOpen = false
                                        onCallsWith(person)
                                    })
                                }
                                if (done) {
                                    DropdownMenuItem(text = { Text("Save as Markdown…") }, onClick = { if (menuOpen) { menuOpen = false; saveMarkdown.launch("$base.md") } })
                                    DropdownMenuItem(text = { Text("Save as text…") }, onClick = { if (menuOpen) { menuOpen = false; saveText.launch("$base.txt") } })
                                }
                                DropdownMenuItem(text = { Text("Transcribe again") }, onClick = { menuOpen = false; vm.retranscribe() })
                                if (r != null) DropdownMenuItem(text = { Text("Delete…", color = MaterialTheme.colorScheme.error) }, onClick = click@{
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
                val note = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                if (learning) item {
                    MonoLabel("Learning your voice…", note, color = MaterialTheme.colorScheme.primary)
                } else if (r.redoComing) item {
                    Text(
                        "This call will be processed again on the charger, to tell the voices apart better. " +
                            "Choosing “Me” here labels you until then; choose it again afterwards to teach the app your voice.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = note,
                    )
                } else if (!voiceKnown && r.ownerSpeaker == null && turns.isNotEmpty() && r.pipeline >= Pipeline.CURRENT) item {
                    Text(
                        "Tip: tap the name above your own words and choose “Me”. The app will then label you in every call.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = note,
                    )
                }
                // Recognise voices. A name just chosen reaches the labels a moment before the suggestions catch up.
                suggestions.filterKeys { names.isUnnamed(it) }.forEach { (speaker, suggestion) ->
                    item {
                        Notice(
                            "Recognised voice", "${names.label(speaker)} sounds like ${suggestion.name}.",
                            action = "That's them", onAction = { vm.confirmSuggestion(speaker, r.transcribedAt) },
                            dismiss = "Not them", onDismiss = { vm.rejectSuggestion(speaker, r.transcribedAt) },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        )
                    }
                }
                // With each row's own 2dp, the transcript starts 14dp below what's above it.
                item { Spacer(Modifier.height(12.dp)) }
                if (turns.isEmpty()) item {
                    Text("No speech was found in this recording.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                // The row being played: the tapped one while its speech lasts, else the row the position
                // falls in (rows can overlap when people talk over each other), else the last one started.
                val active = if (!showPlayer) -1 else {
                    val pos = playback.positionMs
                    playingRow.takeIf { it in turns.indices && pos >= turns[it].startMs && pos < turns[it].endMs }
                        ?: turns.indexOfLast { pos >= it.startMs && pos < it.endMs }.takeIf { it >= 0 }
                        ?: turns.indexOfLast { it.startMs <= pos }
                }
                // Keyed, so a notice appearing above (a suggestion, say) doesn't move the lines being read.
                itemsIndexed(turns, key = { i, _ -> "turn-$i" }) { i, turn ->
                    TurnItem(turn, names, active = i == active, clockWidth = clockWidth, highlight = vm.highlight,
                        onSpeaker = { asking = turn.speaker to r.transcribedAt }, onPlay = { playingRow = i; vm.playFrom(turn.startMs) })
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
            title = { Text("Delete this call?") },
            text = {
                Text("The recording (${r.displayName}) is deleted from your recordings folder, with its transcript and " +
                    "summary. This can't be undone.")
            },
            confirmButton = {
                TextAction("Delete", onClick = click@{
                    if (!confirmDelete) return@click
                    confirmDelete = false
                    scope.launch { handle(vm.deleteCall()) }
                }, color = MaterialTheme.colorScheme.error)
            },
            dismissButton = { TextAction("Cancel", { confirmDelete = false }) },
        )
    }

    if (needFolderAccess) AlertDialog(
        onDismissRequest = { needFolderAccess = false },
        title = { Text("Allow deleting recordings") },
        text = {
            Text("Longhand can read your recordings folder but not change it. To let it delete recordings, choose the " +
                "same folder once more, then tap \"Use this folder\".")
        },
        confirmButton = {
            TextAction("Choose folder", {
                needFolderAccess = false
                scope.launch { chooseFolderForDelete.launch(vm.watchedFolder()) }
            })
        },
        dismissButton = { TextAction("Cancel", { needFolderAccess = false }) },
    )

    deleteProblem?.let { problem ->
        AlertDialog(
            onDismissRequest = { deleteProblem = null },
            title = { Text("Not deleted") },
            text = { Text(problem) },
            confirmButton = { TextAction("OK", { deleteProblem = null }) },
        )
    }

    asking?.let { (speaker, transcript) ->
        val current = (rec?.pipeline ?: 0) >= Pipeline.CURRENT
        SpeakerDialog(
            speaker = speaker, names = names, sample = turns.firstOrNull { it.speaker == speaker }?.text,
            learnsVoice = current, suggestion = suggestions[speaker]?.takeIf { names.isUnnamed(speaker) },
            learnsNames = recognising && current && !names.isOwner(speaker),
            onMe = { vm.speakerIsMe(speaker, transcript) }, onNotMe = { vm.notMe(transcript) },
            onName = { vm.nameSpeaker(speaker, it, transcript) },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun Header(r: Recording) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(
        context, r.lastModified,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
    )
    val kicker = listOfNotNull(CallText.direction(r)?.let { "$it call" } ?: "Call", date,
        r.durationMs.takeIf { it > 0 }?.let { SegmentLogic.formatDuration(it) }).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        MonoLabel(kicker)
        Text(CallText.title(r, CallerLookup::formatNumber), style = MaterialTheme.typography.headlineMedium, color = c.onSurface,
            modifier = Modifier.padding(top = 6.dp).semantics { heading() })
        r.topic?.takeIf { it.isNotBlank() }?.let {
            Text(CallText.topicLine(it), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
        if (r.contactName != null && r.phoneNumber != null) {
            Text(CallerLookup.formatNumber(r.phoneNumber), style = EditorialType.clock, color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        GradientRule(Modifier.padding(top = 16.dp))
    }
}

/** The tonal card that holds the summary, or the state of the call when there's no transcript yet. */
@Composable
private fun TonalCard(content: @Composable () -> Unit) {
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun SummaryBlock(r: Recording) {
    val c = MaterialTheme.colorScheme
    when {
        r.summary != null -> TonalCard {
            Text(r.summary, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
            val follow = CallText.followUps(r)
            if (follow.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    follow.forEach { FollowUpChip(it) }
                }
            }
        }
        r.summaryStatus == SummaryStatus.PENDING || r.summaryStatus == SummaryStatus.PROCESSING ->
            TonalCard { MonoLabel("Summarizing…", color = c.primary) }
    }
}

/** A follow-up as a chip that only shows it; long ones wrap. */
@Composable
private fun FollowUpChip(text: String) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceContainerLow)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.FollowUp, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.onSurface)
    }
}

@Composable
private fun TurnItem(
    turn: Turn, names: SpeakerNames, active: Boolean, clockWidth: Dp, highlight: String?, onSpeaker: () -> Unit, onPlay: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    // The text searched for; the row being played is already secondaryContainer, so its marks take another colour.
    // On the playing row (already secondaryContainer) a container tone wouldn't show: use the strong tertiary.
    // SemiBold too, so the highlight doesn't rest on colour alone.
    val mark = if (active) SpanStyle(color = c.onTertiary, background = c.tertiary, fontWeight = FontWeight.SemiBold)
        else SpanStyle(color = c.onSecondaryContainer, background = c.secondaryContainer, fontWeight = FontWeight.SemiBold)
    val text = remember(turn.text, highlight, mark) {
        highlighted(turn.text, highlight?.let { SearchMatch.occurrences(turn.text, it) }.orEmpty(), mark)
    }
    // The whole row plays from its time, so a one-word line is as easy to tap as a long one; the
    // speaker's name inside it keeps its own tap, whose 2dp padding counts towards the row's top padding.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) c.secondaryContainer else Color.Transparent)
            .clickable(onClickLabel = "Play from here", onClick = onPlay)
            .padding(start = 8.dp, end = 8.dp, top = if (active) 8.dp else 4.dp, bottom = if (active) 10.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(SegmentLogic.formatClock(turn.startMs), style = EditorialType.clock,
            color = if (active) c.onSecondaryContainer else c.onSurfaceVariant,
            modifier = Modifier.width(clockWidth).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    names.label(turn.speaker).uppercase(), style = EditorialType.speaker,
                    color = speakerColor(turn.speaker, names.owner),
                    modifier = Modifier.weight(1f, fill = false)
                        .clip(RoundedCornerShape(4.dp)).clickable(onClick = onSpeaker).padding(vertical = 2.dp),
                )
                if (active) Icon(AppIcons.Playing, contentDescription = "Playing", tint = c.primary,
                    modifier = Modifier.padding(start = 8.dp).size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
        }
    }
}

@Composable
private fun StatusPanel(r: Recording, onRetry: () -> Unit, onQueue: () -> Unit) {
    val c = MaterialTheme.colorScheme
    if (r.status == RecordingStatus.DONE) return
    TonalCard {
        when (r.status) {
            RecordingStatus.PENDING -> {
                Text("Waiting to be transcribed.", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                PrimaryButton("Transcribe now", onRetry)
            }
            RecordingStatus.PROCESSING -> {
                MonoLabel("Transcribing · ${(r.progress * 100).toInt()}%", color = c.primary)
                ProgressLine(r.progress)
            }
            RecordingStatus.FAILED -> {
                Text("Couldn't transcribe this call: ${r.error ?: "unknown error"}", style = MaterialTheme.typography.bodyMedium, color = c.error)
                PrimaryButton("Try again", onRetry)
            }
            RecordingStatus.SKIPPED -> {
                Text("This recording was already in the folder when you set up the app, so it wasn't transcribed.",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                PrimaryButton("Transcribe it", onQueue)
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
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().background(c.surfaceContainer)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .height(80.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.error != null) {
            Text(state.error, style = MaterialTheme.typography.bodyMedium, color = c.error, modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                val fraction = if (durationMs > 0) (state.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Slider(
                    value = fraction, onValueChange = { onSeek((it * durationMs).toLong()) }, enabled = durationMs > 0,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(SegmentLogic.formatDuration(state.positionMs), style = EditorialType.clock, color = c.onSurface, maxLines = 1)
                    if (durationMs > 0) Text(SegmentLogic.formatDuration(durationMs), style = EditorialType.clock, color = c.onSurfaceVariant, maxLines = 1)
                }
            }
            Spacer(Modifier.width(16.dp))
            PlayButton(state.playing, onToggle)
            Spacer(Modifier.width(4.dp))
        }
        AppIconButton(Icons.Filled.Close, "Close player", onClose)
    }
}

@Composable
private fun SpeakerDialog(
    speaker: Int, names: SpeakerNames, sample: String?, learnsVoice: Boolean,
    suggestion: VoiceSuggestion?, learnsNames: Boolean,
    onMe: () -> Unit, onNotMe: () -> Unit, onName: (String) -> Unit, onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(names.manual[speaker].orEmpty()) }
    val isMe = names.isOwner(speaker)
    val choices = buildList {
        suggestion?.let { s ->
            add(SpeakerChoice(s.name, "Sounds like them · from ${s.calls} ${if (s.calls == 1) "call" else "calls"}") { onName(s.name); onDismiss() })
        }
        // A transcript from before 0.5.0 may have one speaker for two people, so its voices aren't learned.
        if (!isMe) add(SpeakerChoice("Me", if (learnsVoice) "Learns your voice so you're labelled in every call" else "Labels you in this call") { onMe(); onDismiss() })
        else add(SpeakerChoice("That's not me", "Remove the “You” label from this call") { onNotMe(); onDismiss() })
        names.callerName?.let { caller ->
            if (isMe || caller.equals(suggestion?.name, ignoreCase = true)) return@let
            when {
                names.label(speaker) != caller -> add(SpeakerChoice(caller, "The person you were talking to") { onName(caller); onDismiss() })
                // Named only automatically, from the number: confirming it is what lets their voice be learned.
                learnsNames && names.manual[speaker] == null ->
                    add(SpeakerChoice(caller, "Confirm, so Longhand recognises their voice in other calls") { onName(caller); onDismiss() })
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who is this?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (sample != null) Text("“${sample.take(120)}${if (sample.length > 120) "…" else ""}”",
                    style = MaterialTheme.typography.bodyMedium)
                // The dialog sits on surfaceContainerHigh, so the rows take the next tone up to stand out.
                Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    choices.forEachIndexed { i, choice ->
                        GroupRow(groupShape(i, choices.size), onClick = choice.onClick, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            RowText(choice.title, choice.detail)
                        }
                    }
                }
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    label = { Text("Another name") }, modifier = Modifier.fillMaxWidth(),
                    // With Recognise voices on, any name chosen here is learned, the choices above included.
                    supportingText = if (learnsNames) {
                        { Text("Longhand will suggest this name when it hears this voice in other calls.") }
                    } else null,
                )
            }
        },
        confirmButton = { TextAction("Save name", { onName(typed); onDismiss() }) },
        dismissButton = { TextAction("Cancel", onDismiss) },
    )
}

private class SpeakerChoice(val title: String, val detail: String, val onClick: () -> Unit)

/** Made before 0.5.0 and still to be redone on the charger (a redo that failed isn't tried again). */
private val Recording.redoComing: Boolean
    get() = status == RecordingStatus.DONE && pipeline < Pipeline.CURRENT && attempts < 3
