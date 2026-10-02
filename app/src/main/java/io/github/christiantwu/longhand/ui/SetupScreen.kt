package io.github.christiantwu.longhand.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.data.FolderScanner
import io.github.christiantwu.longhand.engine.Models

@Composable
fun SetupScreen(vm: AppViewModel, onDone: () -> Unit) {
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
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(bottom = 12.dp)) {
            Logo(40.dp)
            Text("Longhand", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 20.dp).semantics { heading() })
            MonoLabel("Get it in writing", Modifier.padding(top = 4.dp))
            GradientRule(Modifier.padding(top = 14.dp))
            Text("Calls are transcribed and summarized on this phone. Nothing is uploaded.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp))
        }

        Step(
            "01", "Recordings folder", done = folderChosen, mono = folderChosen,
            body = if (folderChosen) FolderScanner.displayPath(settings!!.folderUri!!.toUri())
            else "Where your calls are saved. GrapheneOS uses Recordings/CallRecordings.",
        ) { TextAction(if (folderChosen) "Change" else "Choose folder", { pickFolder.launch(FolderScanner.defaultFolder) }) }

        Step(
            "02", "Transcription models", done = speechReady,
            body = "Calls are transcribed on the phone. Choose the language of your calls; you can change it later in Settings.",
            content = {
                // The language rows sit on the card's colour, so lift them a tone to keep the rows and their gaps visible.
                val c = MaterialTheme.colorScheme
                // Start from the phone's language: a Swedish phone starts on 25 European languages, not English.
                val phone = LocalConfiguration.current.locales[0].language
                LaunchedEffect(settings?.languageChosen) {
                    if (settings?.languageChosen == false) vm.preselectLanguage(Models.Language.forPhoneLanguage(phone))
                }
                MaterialTheme(colorScheme = c.copy(surfaceContainer = c.surfaceContainerHighest)) {
                    LanguageChoice(settings?.language ?: Models.Language.ENGLISH, vm, Modifier.padding(top = 8.dp))
                }
            },
        ) {}

        Step(
            "03", "Caller names", done = device.callLog && device.contacts, optional = true,
            body = "Matches each recording to your call log and shows the contact's name. Nothing is shared.",
        ) {
            if (!(device.callLog && device.contacts)) TextAction("Allow", askCallerAccess)
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
            if (!device.phoneState) TextAction("Allow phone access", askPhone)
            if (!device.batteryUnrestricted) TextAction("Allow background use", { requestBatteryExemption(context) })
        }

        Step(
            "06", "Notifications", done = device.notifications, optional = true,
            body = "Shows progress, and the topic of each call when it's ready.",
        ) { if (!device.notifications) TextAction("Allow", askNotifications) }

        GroupRow(
            groupShape(0, 1),
            action = Modifier.toggleable(value = includeExisting, role = Role.Checkbox, onValueChange = { includeExisting = it }),
        ) {
            Checkbox(checked = includeExisting, onCheckedChange = null)
            RowText("Also transcribe recordings already in the folder")
        }

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Start", onClick = { vm.finishSetup(includeExisting, onDone) }, enabled = folderChosen && speechReady,
                modifier = Modifier.fillMaxWidth())
            if (!(folderChosen && speechReady)) {
                Text("Choose the folder and download the transcription models to start. The other steps can wait.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

/** A setup step as a card: its number and state, what it's for, [content] such as a choice, and its actions. */
@Composable
private fun Step(
    number: String, title: String, done: Boolean, body: String,
    optional: Boolean = false, mono: Boolean = false, progress: Float? = null,
    content: @Composable () -> Unit = {},
    action: @Composable () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(number, style = EditorialType.label, color = c.primary, modifier = Modifier.weight(1f))
            when {
                done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = c.primary, modifier = Modifier.size(16.dp))
                    MonoLabel("Done", color = c.primary)
                }
                optional -> MonoLabel("Optional")
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.semantics { heading() })
        Text(body, style = if (mono) EditorialType.time else MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        content()
        if (progress != null) ProgressLine(progress, Modifier.padding(top = 8.dp, bottom = 4.dp))
        // Pull the actions back by their own padding so their text lines up with the body text.
        FlowRow(Modifier.offset(x = (-12).dp)) { action() }
    }
}

fun requestBatteryExemption(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
    )
}
