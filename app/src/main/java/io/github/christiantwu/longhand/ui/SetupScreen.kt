package io.github.christiantwu.longhand.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.engine.Models

@Composable
fun SetupScreen(vm: AppViewModel, onDone: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val speechReady by vm.speechReady.collectAsStateWithLifecycle()
    val summary by vm.summaryModel.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
    var includeExisting by rememberSaveable { mutableStateOf(true) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    val askCallerAccess = rememberPermissionRequest(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, onResult = vm::refresh)
    val askNotifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS, onResult = vm::refresh)
    val askPhone = rememberPermissionRequest(Manifest.permission.READ_PHONE_STATE, onResult = vm::refresh)

    val folderChosen = settings?.folderUri != null && device.folderAccessible

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Logo(30.dp)
        Spacer(Modifier.height(18.dp))
        Text("Longhand", style = MaterialTheme.typography.displaySmall, color = ink.ink)
        Spacer(Modifier.height(4.dp))
        MonoLabel("Get it in writing")
        Spacer(Modifier.height(12.dp))
        GradientRule()
        Spacer(Modifier.height(12.dp))
        Text("Calls are transcribed and summarized on this phone. Nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium, color = ink.muted)
        Spacer(Modifier.height(8.dp))

        Step(
            "01", "Recordings folder", done = folderChosen,
            body = if (folderChosen) FolderScanner.displayPath(settings!!.folderUri!!.toUri())
            else "Where your calls are saved. GrapheneOS uses Recordings/CallRecordings.",
        ) { InkLink(if (folderChosen) "Change" else "Choose folder", { pickFolder.launch(FolderScanner.defaultFolder) }) }

        Step(
            "02", "Transcription models", done = speechReady,
            body = "Calls are transcribed on the phone. Choose the language of your calls; you can change it later in Settings.",
            content = { LanguageChoice(settings?.language ?: Models.Language.ENGLISH, vm) },
        ) {}

        Step(
            "03", "Caller names", done = device.callLog && device.contacts, optional = true,
            body = "Matches each recording to your call log and shows the contact's name. Nothing is shared.",
        ) {
            if (!(device.callLog && device.contacts)) InkLink("Allow", askCallerAccess)
        }

        Step(
            "04", "Call summaries", done = summary.installed, optional = true,
            body = when {
                summary.installed -> "Installed. Each call gets a topic, a short summary and follow-ups."
                summary.downloading -> summary.downloadText
                summary.error != null -> "Download failed: ${summary.error}"
                else -> "A topic line, short summary and follow-ups for every call, written on the phone. " +
                    "${"%.1f".format(Models.Set.SUMMARY.totalBytes / 1e9)} GB, downloaded over Wi-Fi."
            },
            progress = summary.runningProgress,
        ) { ModelAction(summary, Models.Set.SUMMARY, vm) }

        Step(
            "05", "Process calls automatically", done = device.phoneState && device.batteryUnrestricted,
            body = "Processing starts about a minute after you hang up. Android will ask to let Longhand \"make and manage phone " +
                "calls\"; it only uses this to notice when a call ends and can't place or answer calls. Unrestricted battery use " +
                "lets the work finish in the background.",
        ) {
            if (!device.phoneState) InkLink("Allow phone access", askPhone)
            if (!device.batteryUnrestricted) InkLink("Allow background use", { requestBatteryExemption(context) })
        }

        Step(
            "06", "Notifications", done = device.notifications, optional = true,
            body = "Shows progress, and the topic of each call when it's ready.",
        ) { if (!device.notifications) InkLink("Allow", askNotifications) }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp)
                .toggleable(value = includeExisting, role = Role.Checkbox, onValueChange = { includeExisting = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = includeExisting, onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = ink.ink, checkmarkColor = ink.paper, uncheckedColor = ink.faint))
            Spacer(Modifier.width(10.dp))
            Text("Also transcribe recordings already in the folder", style = MaterialTheme.typography.bodyMedium, color = ink.ink)
        }

        InkButton("Start", onClick = { vm.finishSetup(includeExisting, onDone) }, enabled = folderChosen && speechReady,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (!(folderChosen && speechReady)) {
            Text("Choose the folder and download the transcription models to start. The other steps can wait.",
                style = MaterialTheme.typography.bodySmall, color = ink.faint)
        }
    }
}

@Composable
private fun Step(
    number: String, title: String, done: Boolean, body: String,
    optional: Boolean = false, progress: Float? = null,
    content: @Composable () -> Unit = {},
    action: @Composable () -> Unit,
) {
    val ink = LocalInk.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Text(number, style = InkType.clock, color = if (done) ink.violet else ink.faint, modifier = Modifier.width(32.dp).padding(top = 3.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = ink.ink, modifier = Modifier.weight(1f))
                    when {
                        done -> MonoLabel("Done", color = ink.violet)
                        optional -> MonoLabel("Optional", color = ink.faint)
                    }
                }
                Text(body, style = MaterialTheme.typography.bodySmall, color = ink.muted)
                content()
                if (progress != null) GradientProgress(progress, Modifier.padding(top = 6.dp))
                // Pull the link back by its own padding so its text lines up with the body text.
                Row(Modifier.padding(top = 2.dp).offset(x = (-8).dp)) { action() }
            }
        }
        HorizontalDivider(color = ink.line)
    }
}

fun requestBatteryExemption(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
    )
}
