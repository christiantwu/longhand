package io.github.christiantwu.longhand.ui

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
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
import io.github.christiantwu.longhand.data.detecting
import io.github.christiantwu.longhand.engine.SegmentLogic
import io.github.christiantwu.longhand.export.CallText
import io.github.christiantwu.longhand.export.SearchMatch
import io.github.christiantwu.longhand.work.Work
import java.util.Calendar
import java.util.Date

/** [onOpen] takes the call, and from a search, when its first matching line starts and the text searched for. */
@Composable
fun RecordingsScreen(vm: AppViewModel, onOpen: (id: Long, at: Long?, q: String?) -> Unit, onSettings: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val allRows by vm.allRows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val person by vm.person.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val speechReady by vm.speechReady.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
    val askPhone = rememberPermissionRequest(Manifest.permission.READ_PHONE_STATE, onResult = vm::refresh)

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        vm.scanNow()
        onPauseOrDispose {}
    }

    // Back clears "Calls with …" before it leaves the app.
    BackHandler(enabled = person != null) { vm.person.value = null }
    // A filter set or cleared since the list was last shown starts at the top, where its chip is.
    val listState = rememberLazyListState()
    var listed by rememberSaveable { mutableStateOf(person?.toString()) }
    LaunchedEffect(person) {
        if (person?.toString() == listed) return@LaunchedEffect
        listed = person?.toString()
        listState.scrollToItem(0)
    }

    val search = query.trim().ifEmpty { null }
    val groups = remember(rows) { DayGroups.group(rows) }
    // Matches TranscribeWorker: on battery, automatic runs take only the last day's calls.
    val cutoff = System.currentTimeMillis() - Work.RECENT_WINDOW_MS
    // ...or none with "Only while charging"; calls the user asked for always go ahead.
    fun waitsForCharger(rec: Recording) = device.loaded && speechReady && !device.charging && !rec.requested &&
        rec.status == RecordingStatus.PENDING && (settings?.chargingOnly == true || rec.lastModified < cutoff)

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { AppBar(actions = { AppIconButton(Icons.Filled.Settings, "Settings", onSettings) }) }
        item { ScreenTitle("Calls") }
        item { SearchField(query, onChange = { vm.query.value = it }) }
        person?.let { p ->
            item {
                InputChip(
                    selected = true, onClick = { vm.person.value = null },
                    label = { Text("Calls with ${p.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(InputChipDefaults.IconSize)) },
                    // Read as what it is, announced when it appears: the stock chip reads as a checked checkbox.
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).clearAndSetSemantics {
                        contentDescription = "Calls with ${p.name}"
                        liveRegion = LiveRegionMode.Polite
                        role = Role.Button
                        onClick(label = "Clear filter") { vm.person.value = null; true }
                    },
                )
            }
        }

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
                // From every call: "Transcribe now" acts on all waiting calls, whatever is searched or filtered.
                val pending = allRows.filter { it.rec.status == RecordingStatus.PENDING }
                val busy = allRows.any {
                    it.rec.status == RecordingStatus.PROCESSING || it.rec.detecting || it.rec.summaryStatus == SummaryStatus.PROCESSING
                }
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

        if (rows.isEmpty()) item { EmptyState(search != null, person) }

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
                    row, groupShape(i, group.rows.size), waitsForCharger(row.rec), search,
                    person = PersonFilter.of(row.rec), onPerson = { vm.person.value = it },
                    onClick = { onOpen(row.rec.id, row.matchMs, row.matchQuery ?: search) },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = if (i > 0) GroupGap else 0.dp),
                )
            }
        }
    }
}

/**
 * [search]: the text searched for, whose first matching line shows under the row. [person]: the
 * call's contact or number, for "Calls with …" from the avatar; null when it has neither.
 */
@Composable
private fun CallRowItem(
    row: CallRow, shape: Shape, waitsForCharger: Boolean, search: String?,
    person: PersonFilter?, onPerson: (PersonFilter) -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val rec = row.rec
    val title = CallText.title(rec, CallerLookup::formatNumber)
    val time = DateFormat.getTimeFormat(context).format(Date(rec.lastModified))
    val (line, color) = subtitle(row)
    val match = remember(row.matchText, row.matchQuery) { row.matchQuery?.let { q -> row.matchText?.let { SearchMatch.excerpt(it, q) } } }
    // A call shown by its first line, when that's also the line that matched, shows it once: as the match.
    val supporting = line.takeUnless { match != null && line == row.snippet && row.snippet == row.matchText }
    GroupRow(shape, modifier, onClick = onClick, minHeight = 72.dp) {
        // Initials only for a contact; a number or a file name gets the plain person.
        val name = rec.contactName?.takeIf { it.isNotBlank() }
        if (person == null) Avatar(name) else PersonAvatar(name, person, onPerson)
        // The progress line and the match run under the times too, so they sit outside the text's row.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (waitsForCharger) RowText(title, singleLine = true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(AppIcons.Bolt, "Waiting for the charger", tint = color, modifier = Modifier.size(16.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else RowText(title, supporting, supportingColor = color, singleLine = true)
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
                if (match != null) {
                    Text(
                        highlighted(match.text, match.matches, SpanStyle(color = c.primary, fontWeight = FontWeight.SemiBold)),
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (rec.status == RecordingStatus.PROCESSING) ProgressLine(rec.progress)
        }
    }
}

/** The avatar as its own button, for "Calls with …" this row's [person]. */
@Composable
private fun PersonAvatar(name: String?, person: PersonFilter, onPerson: (PersonFilter) -> Unit) {
    val label = "Calls with ${person.name}"
    // A 48dp touch target around the 40dp avatar, reaching into the row's padding so the text stays put.
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.requiredSize(48.dp).clip(CircleShape)
                .clickable(role = Role.Button) { onPerson(person) }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Avatar(name, Modifier.clearAndSetSemantics {}) }
    }
}

@Composable
private fun subtitle(row: CallRow): Pair<String, Color> {
    val c = MaterialTheme.colorScheme
    val rec = row.rec
    return when {
        rec.status == RecordingStatus.PROCESSING -> "Transcribing · ${(rec.progress * 100).toInt()}%" to c.primary
        rec.detecting -> "Detecting the language · ${(rec.progress * 100).toInt()}%" to c.primary
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
private fun EmptyState(searching: Boolean, person: PersonFilter?) {
    val c = MaterialTheme.colorScheme
    val tryWords = "Try a name, a topic, or a word from the call."
    val (title, text) = when {
        person != null && searching -> "No matches in calls with ${person.name}" to tryWords
        person != null -> "No calls with ${person.name}" to "Calls with them, and calls where a speaker was given their name, show up here."
        searching -> "No matches" to tryWords
        else -> "No calls yet" to "New recordings in your call recordings folder show up here and are transcribed automatically."
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!searching && person == null) Box(Modifier.padding(bottom = 8.dp)) { Logo(40.dp) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.onSurface, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
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
