package io.github.christiantwu.longhand.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.BuildConfig
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.engine.Models

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onLicences: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val summary by vm.summaryModel.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
    val known by vm.knownVoices.collectAsStateWithLifecycle()
    var confirmForgetVoices by remember { mutableStateOf(false) }
    val s = settings ?: return

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    val askCallerAccess = rememberPermissionRequest(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, onResult = vm::refresh)
    val askPhone = rememberPermissionRequest(Manifest.permission.READ_PHONE_STATE, onResult = vm::refresh)
    val askNotifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS, onResult = vm::refresh)

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        AppBar(navigation = { BackButton(onBack) })
        ScreenTitle("Settings")
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("Callers") {
                SettingRow(
                    "Caller names",
                    if (device.callLog && device.contacts) "On. Names come from your call log and contacts."
                    else "Match recordings to your call log and contacts to show who each call was with.",
                ) {
                    if (!(device.callLog && device.contacts)) OutlinedPillButton("Allow", askCallerAccess)
                }
            }

            Section("Your voice") {
                SettingRow(
                    "Label me as “You”",
                    if (device.voiceSamples > 0) "Learned from ${device.voiceSamples} ${if (device.voiceSamples == 1) "call" else "calls"}. Each “Me” you confirm makes it more accurate."
                    else "Not set up. Open a transcript, tap the name above your own words and choose “Me”." +
                        if (device.redoWaiting > 0) " Calls from before the update count once they've been processed again on the charger." else "",
                ) { if (device.voiceSamples > 0) OutlinedPillButton("Forget", vm::forgetVoice) }
            }

            Section("Recognised voices") {
                val on = s.recogniseVoices
                // The switch, then while on each known voice, or a hint while there are none.
                val count = if (on) 1 + known.size.coerceAtLeast(1) else 1
                Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    GroupRow(
                        groupShape(0, count), minHeight = 72.dp,
                        action = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { turnOn ->
                            if (turnOn || known.isEmpty()) vm.setRecogniseVoices(turnOn) else confirmForgetVoices = true
                        }),
                    ) {
                        RowText("Recognise voices across calls",
                            "Suggests a name when someone you've named speaks in another call. Voice fingerprints stay on this phone and in backups you turn on.")
                        Switch(
                            checked = on, onCheckedChange = null,
                            thumbContent = if (on) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                            } else null,
                        )
                    }
                    if (on && known.isEmpty()) GroupRow(groupShape(1, count)) {
                        Text("Name a speaker in a transcript to start. Only names you choose are learned.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                    }
                    if (on) known.forEachIndexed { i, voice ->
                        SettingRow(voice.name, "From ${voice.calls} ${if (voice.calls == 1) "call" else "calls"}", groupShape(i + 1, count)) {
                            OutlinedPillButton("Forget", { vm.forgetKnownVoice(voice.id) },
                                Modifier.semantics { contentDescription = "Forget ${voice.name}" })
                        }
                    }
                }
            }

            Section("Summaries") {
                SettingRow(
                    "Topic and summary for each call",
                    when {
                        summary.installed -> "On. Written on the phone by Qwen 3.5 4B after each transcription."
                        summary.downloading -> summary.downloadText
                        summary.error != null -> "Download failed: ${summary.error}"
                        else -> "Off. Needs a ${"%.1f".format(Models.Set.SUMMARY.totalBytes / 1e9)} GB download over Wi-Fi."
                    },
                    extra = {
                        summary.runningProgress?.let { ProgressLine(it, Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                        // Under the text, as in the language rows, with its text lined up with the text above.
                        Row(Modifier.offset(x = (-12).dp)) { ModelAction(summary, Models.Set.SUMMARY, vm) }
                    },
                ) { if (summary.installed) OutlinedPillButton("Remove", vm::removeSummaryModel) }
            }

            Section("Processing") {
                val choices = listOf(
                    Triple(false, "Right after each call", "Processing starts about a minute after you hang up, on battery too. Older recordings wait for the charger."),
                    Triple(true, "Only while charging", "Saves battery; new calls wait for the charger."),
                )
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    choices.forEachIndexed { i, (chargingOnly, label, detail) ->
                        val selected = s.chargingOnly == chargingOnly
                        GroupRow(
                            groupShape(i, choices.size), minHeight = 72.dp, verticalAlignment = Alignment.Top,
                            action = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { vm.setChargingOnly(chargingOnly) }),
                        ) {
                            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp))
                            RowText(label, detail)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    SettingRow(
                        "Notice when calls end",
                        if (device.phoneState) "On. Longhand uses the Phone permission only to notice when a call ends."
                        else "Needs the Phone permission. Without it, new calls are found by the regular check every 15 minutes or so.",
                        groupShape(0, 3),
                    ) { if (!device.phoneState) OutlinedPillButton("Allow", askPhone) }
                    SettingRow(
                        "Notifications",
                        if (device.notifications) "On. Shows progress, and the topic of each call when it's ready."
                        else "Off. Allow them to see progress, and the topic of each call when it's ready.",
                        groupShape(1, 3),
                    ) { if (!device.notifications) OutlinedPillButton("Allow", askNotifications) }
                    SettingRow(
                        "Unrestricted battery use",
                        if (device.batteryUnrestricted) "Allowed." else "Needed so long calls aren't cut off in the background.",
                        groupShape(2, 3),
                    ) { if (!device.batteryUnrestricted) OutlinedPillButton("Allow", { requestBatteryExemption(context) }) }
                }
            }

            Section("Recordings") {
                Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    val isPath = s.folderUri != null && device.folderAccessible
                    SettingRow(
                        "Folder",
                        when {
                            s.folderUri == null -> "Not chosen"
                            !device.folderAccessible -> "Access lost. Choose it again."
                            else -> FolderScanner.displayPath(s.folderUri.toUri())
                        },
                        groupShape(0, 2), mono = isPath,
                    ) { OutlinedPillButton("Change", { pickFolder.launch(FolderScanner.defaultFolder) }) }
                    SettingRow("Skipped recordings", "Ones that were in the folder before setup.", groupShape(1, 2)) {
                        OutlinedPillButton("Transcribe", vm::transcribeSkipped)
                    }
                }
            }

            Section("Transcription language") {
                LanguageChoice(s.language, vm)
                val model = when (s.language) {
                    Models.Language.CJK -> "SenseVoice Small"
                    Models.Language.HINDI -> "Nemotron 3.5 ASR Streaming 0.6B"
                    else -> "Parakeet TDT 0.6B"
                }
                Text("Calls are transcribed on the phone, by $model. You can turn off this app's Network permission " +
                    "once the models you want are downloaded.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }

            Section("About") {
                GroupRow(groupShape(0, 1), onClick = onLicences, onClickLabel = "View", minHeight = 72.dp) {
                    RowText("Open-source licences", "Longhand is free software under the GPL, version 3 or later. See what it's built on.")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MonoLabel("Longhand ${BuildConfig.VERSION_NAME} · get it in writing · runs entirely on this phone",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
            }
        }
    }

    if (confirmForgetVoices) {
        val fingerprints = if (known.size == 1) "voice fingerprint" else "${known.size} voice fingerprints"
        AlertDialog(
            onDismissRequest = { confirmForgetVoices = false },
            title = { Text("Forget recognised voices?") },
            text = {
                Text("Longhand will stop suggesting names and delete the $fingerprints it learned. " +
                    "Your transcripts and the names in them stay.")
            },
            confirmButton = {
                // Turning the setting off deletes every known voice, link and rejection with it.
                TextAction("Forget", onClick = click@{
                    if (!confirmForgetVoices) return@click
                    confirmForgetVoices = false
                    vm.setRecogniseVoices(false)
                }, color = MaterialTheme.colorScheme.error)
            },
            dismissButton = { TextAction("Cancel", { confirmForgetVoices = false }) },
        )
    }
}

/** A [SectionHeader] with its rows 10dp below. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title)
        content()
    }
}

/** A setting: its name and state, anything [extra] under them, and an [action] at the end. A [mono] detail is a path. */
@Composable
private fun SettingRow(
    title: String, detail: String, shape: Shape = groupShape(0, 1), mono: Boolean = false,
    extra: @Composable ColumnScope.() -> Unit = {}, action: @Composable () -> Unit = {},
) {
    GroupRow(shape, minHeight = 72.dp) {
        RowText(title, detail.takeUnless { mono }) {
            if (mono) Text(detail, style = EditorialType.time, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
        }
        action()
    }
}
