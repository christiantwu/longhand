package io.github.christiantwu.longhand.ui

import android.Manifest
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.data.CallRow
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.SummaryStatus
import io.github.christiantwu.longhand.engine.SegmentLogic
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.work.Work
import java.util.Calendar
import java.util.Date

@Composable
fun RecordingsScreen(vm: AppViewModel, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val ink = LocalInk.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val speechReady by vm.speechReady.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val askPhone = rememberPermissionRequest(Manifest.permission.READ_PHONE_STATE, onResult = vm::refresh)

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        vm.scanNow()
        onPauseOrDispose {}
    }

    val groups = remember(rows) { DayGroups.group(rows) }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            InkTopBar(
                left = { Box(Modifier.padding(start = 12.dp)) { Logo(22.dp) } },
                actions = {
                    InkIconButton(Icons.Filled.Search, "Search calls", onClick = {
                        searching = !searching
                        if (!searching) vm.query.value = ""
                    })
                    InkIconButton(InkIcons.Tune, "Settings", onClick = onSettings)
                },
            )
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 4.dp)) {
                Text("Calls", style = MaterialTheme.typography.displaySmall, color = ink.ink)
                Spacer(Modifier.height(10.dp))
                GradientRule()
            }
        }
        if (searching) item { SearchField(query, onChange = { vm.query.value = it }, onClose = { searching = false; vm.query.value = "" }) }

        // Things that stop transcription first, then the queue.
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!speechReady) Notice("Setup", "Transcription models are missing.", "Settings", onSettings)
                // device.loaded: nothing is reported missing before the first check has run.
                if (device.loaded && settings?.folderUri != null && !device.folderAccessible) {
                    Notice("Folder", "Access to the recordings folder was lost.", "Settings", onSettings)
                }
                // For anyone who skipped the Phone step in setup. Without it, the 15-minute check still runs.
                if (device.loaded && settings?.chargingOnly == false && settings?.phoneNoticeHidden == false && !device.phoneState) {
                    Notice(
                        "Right after each call",
                        "Allow the Phone permission so Longhand can start about a minute after you hang up.",
                        "Allow", askPhone, dismiss = "Hide", onDismiss = vm::hidePhoneNotice,
                    )
                }
                val pending = rows.filter { it.rec.status == RecordingStatus.PENDING }
                val busy = rows.any { it.rec.status == RecordingStatus.PROCESSING || it.rec.summaryStatus == SummaryStatus.PROCESSING }
                if (device.loaded && pending.isNotEmpty() && !busy && speechReady) {
                    // Matches TranscribeWorker: on battery, automatic runs take only the last day's calls.
                    val cutoff = System.currentTimeMillis() - Work.RECENT_WINDOW_MS
                    val older = if (device.charging) 0 else pending.count { it.rec.lastModified < cutoff }
                    Notice(
                        "${pending.size} waiting",
                        when {
                            settings?.chargingOnly == true -> "These will be transcribed while charging."
                            older == pending.size -> "These are more than a day old, so they wait until the phone is charging."
                            older > 0 -> "Calls from the last day will be transcribed shortly; older ones wait until the phone is charging."
                            else -> "These will be transcribed shortly."
                        },
                        "Transcribe now", vm::transcribeNow,
                    )
                }
            }
        }

        if (rows.isEmpty()) item { EmptyState(query.isNotBlank()) }

        groups.forEach { group ->
            item(key = "day-${group.label}-${group.rows.first().rec.id}") {
                val minutes = group.rows.sumOf { it.rec.durationMs } / 60_000
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp)) {
                    MonoLabel(group.label, Modifier.weight(1f))
                    MonoLabel("${group.rows.size} ${if (group.rows.size == 1) "call" else "calls"}" + if (minutes > 0) " · $minutes min" else "")
                }
            }
            items(group.rows, key = { it.rec.id }) { row -> CallRowItem(row, onClick = { onOpen(row.rec.id) }) }
        }
    }
}

@Composable
private fun CallRowItem(row: CallRow, onClick: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val rec = row.rec
    val time = DateFormat.getTimeFormat(context).format(Date(rec.lastModified))
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(CallText.title(rec, CallerLookup::formatNumber), style = MaterialTheme.typography.titleSmall,
                    color = ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (line, color) = subtitle(row)
                Text(line, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (rec.status == RecordingStatus.PROCESSING) GradientProgress(rec.progress, Modifier.padding(top = 6.dp))
            }
            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(time, style = InkType.clock, color = ink.muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (rec.callDirection) {
                        1 -> Icon(InkIcons.Incoming, "Incoming", tint = ink.faint, modifier = Modifier.size(12.dp))
                        2 -> Icon(InkIcons.Outgoing, "Outgoing", tint = ink.faint, modifier = Modifier.size(12.dp))
                    }
                    if (rec.durationMs > 0) Text(" " + SegmentLogic.formatDuration(rec.durationMs), style = InkType.clock, color = ink.faint)
                }
            }
        }
        HorizontalDivider(color = ink.line)
    }
}

@Composable
private fun subtitle(row: CallRow): Pair<String, Color> {
    val ink = LocalInk.current
    val rec = row.rec
    return when {
        rec.status == RecordingStatus.PROCESSING -> "Transcribing · ${(rec.progress * 100).toInt()}%" to ink.violet
        rec.status == RecordingStatus.PENDING -> "Waiting to transcribe" to ink.muted
        rec.status == RecordingStatus.FAILED -> "Couldn't transcribe" to ink.danger
        rec.status == RecordingStatus.SKIPPED -> "Not transcribed" to ink.faint
        rec.topic != null -> CallText.topicLine(rec.topic) to ink.muted
        rec.summaryStatus == SummaryStatus.PENDING || rec.summaryStatus == SummaryStatus.PROCESSING -> "Summarizing…" to ink.violet
        row.snippet != null -> row.snippet to ink.muted
        else -> "No speech found" to ink.faint
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val ink = LocalInk.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) MonoLabel("Search names, topics, transcripts", color = ink.faint)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink.ink),
                cursorBrush = SolidColor(ink.violet),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        InkIconButton(Icons.Filled.Close, "Close search", onClose, tint = ink.muted)
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    val ink = LocalInk.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!searching) Logo(40.dp)
        Text(if (searching) "No matches" else "No calls yet", style = MaterialTheme.typography.titleMedium, color = ink.ink)
        Text(
            if (searching) "Try a name, a topic, or a word from the call."
            else "New recordings in your call recordings folder show up here and are transcribed automatically.",
            style = MaterialTheme.typography.bodySmall, color = ink.muted,
        )
    }
}

/** Groups calls under Today, Yesterday, a weekday for this week, then dates. */
object DayGroups {
    data class Group(val label: String, val rows: List<CallRow>)

    fun group(rows: List<CallRow>, now: Long = System.currentTimeMillis()): List<Group> =
        rows.groupBy { label(it.rec.lastModified, now) }.map { (label, r) -> Group(label, r) }

    fun label(time: Long, now: Long): String {
        val day = Calendar.getInstance().apply { timeInMillis = time; startOfDay() }
        val today = Calendar.getInstance().apply { timeInMillis = now; startOfDay() }
        // Rounded, because a day with a daylight-saving change is 23 or 25 hours long.
        val days = Math.round((today.timeInMillis - day.timeInMillis) / 86_400_000.0).toInt()
        return when {
            days <= 0 -> "Today"
            days == 1 -> "Yesterday"
            days < 7 -> DateFormat.format("EEEE", day).toString()
            day.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> DateFormat.format("MMMM d", day).toString()
            else -> DateFormat.format("MMMM d, yyyy", day).toString()
        }
    }

    private fun Calendar.startOfDay() {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
