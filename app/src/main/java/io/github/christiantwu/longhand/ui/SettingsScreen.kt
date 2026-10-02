package io.github.christiantwu.longhand.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.BuildConfig
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.engine.Models

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onLicences: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val summary by vm.summaryModel.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
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
        InkTopBar(left = { InkIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) })
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Settings", style = MaterialTheme.typography.displaySmall, color = ink.ink)
            Spacer(Modifier.height(10.dp))
            GradientRule()
        }

        Section("Callers")
        SettingRow(
            "Caller names",
            if (device.callLog && device.contacts) "On. Names come from your call log and contacts."
            else "Match recordings to your call log and contacts to show who each call was with.",
        ) {
            if (!(device.callLog && device.contacts)) InkLink("Allow", askCallerAccess)
        }

        Section("Your voice")
        SettingRow(
            "Label me as “You”",
            if (device.voiceSamples > 0) "Learned from ${device.voiceSamples} ${if (device.voiceSamples == 1) "call" else "calls"}. Each “Me” you confirm makes it more accurate."
            else "Not set up. Open a transcript, tap the name above your own words and choose “Me”." +
                if (device.redoWaiting > 0) " Calls from before the update count once they've been processed again on the charger." else "",
        ) { if (device.voiceSamples > 0) InkLink("Forget", vm::forgetVoice, color = ink.danger) }

        Section("Summaries")
        SettingRow(
            "Topic and summary for each call",
            when {
                summary.installed -> "On. Written on the phone by Qwen 3.5 4B after each transcription."
                summary.downloading -> summary.downloadText
                summary.error != null -> "Download failed: ${summary.error}"
                else -> "Off. Needs a ${"%.1f".format(Models.Set.SUMMARY.totalBytes / 1e9)} GB download over Wi-Fi."
            },
            progress = summary.runningProgress,
        ) {
            if (summary.installed) InkLink("Remove", vm::removeSummaryModel, color = ink.danger)
            else ModelAction(summary, Models.Set.SUMMARY, vm)
        }

        Section("Processing")
        listOf(
            Triple(false, "Right after each call", "Processing starts about a minute after you hang up, on battery too. Older recordings wait for the charger."),
            Triple(true, "Only while charging", "Saves battery; new calls wait for the charger."),
        ).forEach { (chargingOnly, label, detail) ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = s.chargingOnly == chargingOnly, role = Role.RadioButton, onClick = { vm.setChargingOnly(chargingOnly) })
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.chargingOnly == chargingOnly, onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = ink.ink, unselectedColor = ink.faint))
                Column(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = ink.ink)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = ink.muted)
                }
            }
        }
        SettingRow(
            "Notice when calls end",
            if (device.phoneState) "On. Longhand uses the Phone permission only to notice when a call ends."
            else "Needs the Phone permission. Without it, new calls are found by the regular check every 15 minutes or so.",
        ) { if (!device.phoneState) InkLink("Allow", askPhone) }
        SettingRow(
            "Notifications",
            if (device.notifications) "On. Shows progress, and the topic of each call when it's ready."
            else "Off. Allow them to see progress, and the topic of each call when it's ready.",
        ) { if (!device.notifications) InkLink("Allow", askNotifications) }
        SettingRow(
            "Unrestricted battery use",
            if (device.batteryUnrestricted) "Allowed." else "Needed so long calls aren't cut off in the background.",
        ) { if (!device.batteryUnrestricted) InkLink("Allow", { requestBatteryExemption(context) }) }

        Section("Recordings")
        SettingRow(
            "Folder",
            when {
                s.folderUri == null -> "Not chosen"
                !device.folderAccessible -> "Access lost. Choose it again."
                else -> FolderScanner.displayPath(s.folderUri.toUri())
            },
        ) { InkLink("Change", { pickFolder.launch(FolderScanner.defaultFolder) }) }
        SettingRow("Skipped recordings", "Ones that were in the folder before setup.") { InkLink("Transcribe", vm::transcribeSkipped) }

        Section("Transcription language")
        LanguageChoice(s.language, vm)
        val model = if (s.language == Models.Language.CJK) "SenseVoice Small" else "Parakeet TDT 0.6B"
        Text("Calls are transcribed on the phone, by $model. You can turn off this app's Network permission " +
            "once the models you want are downloaded.",
            style = MaterialTheme.typography.bodySmall, color = ink.faint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        Section("About")
        SettingRow("Open-source licences", "Longhand is free software under the GPL, version 3 or later. See what it's built on.") {
            InkLink("View", onLicences)
        }

        Spacer(Modifier.height(20.dp))
        MonoLabel("Longhand ${BuildConfig.VERSION_NAME} · get it in writing · runs entirely on this phone", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = ink.faint)
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 4.dp)) { MonoLabel(title, color = LocalInk.current.violet) }
}

@Composable
private fun SettingRow(title: String, detail: String, progress: Float? = null, action: @Composable () -> Unit = {}) {
    val ink = LocalInk.current
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = ink.ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = ink.muted)
                if (progress != null) GradientProgress(progress, Modifier.padding(top = 6.dp))
            }
            action()
        }
        HorizontalDivider(Modifier.padding(start = 20.dp), color = ink.line)
    }
}
