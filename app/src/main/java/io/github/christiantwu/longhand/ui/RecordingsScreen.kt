package io.github.christiantwu.longhand.ui

import android.Manifest
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.data.CallRow
import io.github.christiantwu.longhand.data.CallerLookup
import io.github.christiantwu.longhand.data.Recording
import io.github.christiantwu.longhand.data.RecordingStatus
import io.github.christiantwu.longhand.data.SummaryStatus
import io.github.christiantwu.longhand.engine.SegmentLogic
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.work.Work
import java.util.Calendar
import java.util.Date

@Composable
fun RecordingsScreen(vm: AppViewModel, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val speechReady by vm.speechReady.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
    val askPhone = rememberPermissionRequest(Manifest.permission.READ_PHONE_STATE, onResult = vm::refresh)

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        vm.scanNow()
        onPauseOrDispose {}
    }

    val groups = remember(rows) { DayGroups.group(rows) }
    // Matches TranscribeWorker: on battery, automatic runs take only the last day's calls.
    val cutoff = System.currentTimeMillis() - Work.RECENT_WINDOW_MS
    // ...or none with "Only while charging"; calls the user asked for always go ahead.
    fun waitsForCharger(rec: Recording) = device.loaded && speechReady && !device.charging && !rec.requested &&
        rec.status == RecordingStatus.PENDING && (settings?.chargingOnly == true || rec.lastModified < cutoff)

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { AppBar(actions = { AppIconButton(Icons.Filled.Settings, "Settings", onSettings) }) }
        item { ScreenTitle("Calls") }
        item { SearchField(query, onChange = { vm.query.value = it }) }

        // Things that stop transcription first, then the queue.
        item {
            // Each notice brings its own space above, so there's no gap when there are none.
            val above = Modifier.padding(top = 16.dp)
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (!speechReady) Notice("Setup", "Transcription models are missing.", "Settings", onSettings, modifier = above)
                // device.loaded: nothing is reported missing before the first check has run.
                if (device.loaded && settings?.folderUri != null && !device.folderAccessible) {
                    Notice("Folder", "Access to the recordings folder was lost.", "Settings", onSettings, modifier = above)
                }
                // For anyone who skipped the Phone step in setup. Without it, the 15-minute check still runs.
                if (device.loaded && settings?.chargingOnly == false && settings?.phoneNoticeHidden == false && !device.phoneState) {
                    Notice(
                        "Right after each call",
                        "Allow the Phone permission so Longhand can start about a minute after you hang up.",
                        "Allow", askPhone, dismiss = "Hide", onDismiss = vm::hidePhoneNotice, modifier = above,
                    )
                }
                val pending = rows.filter { it.rec.status == RecordingStatus.PENDING }
                val busy = rows.any { it.rec.status == RecordingStatus.PROCESSING || it.rec.summaryStatus == SummaryStatus.PROCESSING }
                if (device.loaded && pending.isNotEmpty() && !busy && speechReady) {
                    val older = if (device.charging) 0 else pending.count { it.rec.lastModified < cutoff }
                    Notice(
                        "${pending.size} waiting",
                        when {
                            settings?.chargingOnly == true -> "These will be transcribed while charging."
                            older == pending.size -> "These are more than a day old, so they wait until the phone is charging."
                            older > 0 -> "Calls from the last day will be transcribed shortly; older ones wait until the phone is charging."
                            else -> "These will be transcribed shortly."
                        },
                        "Transcribe now", vm::transcribeNow, modifier = above,
                    )
                }
            }
        }

        if (rows.isEmpty()) item { EmptyState(query.isNotBlank()) }

        groups.forEach { group ->
            item(key = "day-${group.label}-${group.rows.first().rec.id}") {
                val minutes = group.rows.sumOf { it.rec.durationMs } / 60_000
                SectionHeader(
                    group.label, Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 10.dp),
                    trailing = "${group.rows.size} ${if (group.rows.size == 1) "call" else "calls"}" + if (minutes > 0) " · $minutes min" else "",
                )
            }
            itemsIndexed(group.rows, key = { _, row -> row.rec.id }) { i, row ->
                CallRowItem(
                    row, groupShape(i, group.rows.size), waitsForCharger(row.rec), onClick = { onOpen(row.rec.id) },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = if (i > 0) GroupGap else 0.dp),
                )
            }
        }
    }
}

@Composable
private fun CallRowItem(row: CallRow, shape: Shape, waitsForCharger: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val rec = row.rec
    val title = CallText.title(rec, CallerLookup::formatNumber)
    val time = DateFormat.getTimeFormat(context).format(Date(rec.lastModified))
    val (line, color) = subtitle(row)
    GroupRow(shape, modifier, onClick = onClick, minHeight = 72.dp) {
        // Initials only for a contact; a number or a file name gets the plain person.
        Avatar(rec.contactName?.takeIf { it.isNotBlank() })
        // The progress line runs under the times too, so it sits outside the text's row.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (waitsForCharger) RowText(title, singleLine = true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(AppIcons.Bolt, "Waiting for the charger", tint = color, modifier = Modifier.size(16.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else RowText(title, line, supportingColor = color, singleLine = true)
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(time, style = EditorialType.time, color = c.onSurfaceVariant)
                    val direction = when (rec.callDirection) {
                        1 -> AppIcons.Incoming to "Incoming"
                        2 -> AppIcons.Outgoing to "Outgoing"
                        else -> null
                    }
                    if (direction != null || rec.durationMs > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            direction?.let { (icon, label) -> Icon(icon, label, tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
                            if (rec.durationMs > 0) Text(SegmentLogic.formatDuration(rec.durationMs), style = EditorialType.time, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
            if (rec.status == RecordingStatus.PROCESSING) ProgressLine(rec.progress)
        }
    }
}

@Composable
private fun subtitle(row: CallRow): Pair<String, Color> {
    val c = MaterialTheme.colorScheme
    val rec = row.rec
    return when {
        rec.status == RecordingStatus.PROCESSING -> "Transcribing · ${(rec.progress * 100).toInt()}%" to c.primary
        rec.status == RecordingStatus.PENDING -> "Waiting to transcribe" to c.onSurfaceVariant
        rec.status == RecordingStatus.FAILED -> "Couldn't transcribe" to c.error
        rec.status == RecordingStatus.SKIPPED -> "Not transcribed" to c.onSurfaceVariant
        rec.topic != null -> CallText.topicLine(rec.topic) to c.onSurfaceVariant
        rec.summaryStatus == SummaryStatus.PENDING || rec.summaryStatus == SummaryStatus.PROCESSING -> "Summarizing…" to c.primary
        row.snippet != null -> row.snippet to c.onSurfaceVariant
        else -> "No speech found" to c.onSurfaceVariant
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    BasicTextField(
        value = query, onValueChange = onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
        cursorBrush = SolidColor(c.primary),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().height(56.dp)
            .clip(CircleShape).background(c.surfaceContainerHigh),
        decorationBox = { field ->
            Row(
                // The clear button's own 48dp box pads its icon, so the end padding shrinks when it shows.
                Modifier.fillMaxSize().padding(start = 16.dp, end = if (query.isEmpty()) 20.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = c.onSurface)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search calls", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    field()
                }
                if (query.isNotEmpty()) AppIconButton(Icons.Filled.Close, "Clear search", { onChange("") })
            }
        },
    )
}

@Composable
private fun EmptyState(searching: Boolean) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!searching) Box(Modifier.padding(bottom = 8.dp)) { Logo(40.dp) }
        Text(if (searching) "No matches" else "No calls yet", style = MaterialTheme.typography.titleLarge, color = c.onSurface,
            textAlign = TextAlign.Center)
        Text(
            if (searching) "Try a name, a topic, or a word from the call."
            else "New recordings in your call recordings folder show up here and are transcribed automatically.",
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
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
