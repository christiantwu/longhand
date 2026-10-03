package io.github.christiantwu.longhand.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.data.Correction
import io.github.christiantwu.longhand.engine.Corrections
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A rule as TalkBack reads it, without the arrow ("UV, written as Youvee"). */
private fun spoken(rule: Correction) = "${rule.heard}, written as ${rule.written}"

/** Settings → Common corrections: words the recogniser gets wrong, written the user's way in every transcript. */
@Composable
fun CorrectionsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val allRules by vm.corrections.collectAsStateWithLifecycle()
    val removing by vm.removingCorrections.collectAsStateWithLifecycle()
    val rules = allRules.filter { it.id !in removing }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /**
     * Removing a rule puts its words in earlier transcripts back as recognised, so it only happens once Undo is no
     * longer offered; Undo keeps the rule as it was.
     */
    fun remove(rule: Correction) {
        vm.removeCorrection(rule)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Correction for “${rule.heard}” removed", actionLabel = "Undo",
                duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) vm.undoRemoveCorrection(rule)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            AppBar(navigation = { BackButton(onBack) })
            ScreenTitle("Corrections")
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Words the recogniser keeps getting wrong, such as a name, written your way in every new transcript. " +
                    "Each matches whole words in any case, so one for “UV” changes “UV index” too. In Chinese and Japanese, " +
                    "which have no spaces between words, it matches anywhere, inside longer words as well. Removing one puts " +
                    "its words in earlier transcripts back to what the recogniser wrote.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    if (rules.isEmpty()) GroupRow(groupShape(0, 1)) {
                        Text("No corrections yet. Add a word as it appears in your transcripts, and how it should be written.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                    }
                    rules.forEachIndexed { i, rule ->
                        GroupRow(groupShape(i, rules.size)) {
                            Text("${rule.heard} → ${rule.written}", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).semantics { contentDescription = spoken(rule) })
                            OutlinedPillButton("Remove", { remove(rule) }, Modifier.semantics { contentDescription = "Remove ${spoken(rule)}" })
                        }
                    }
                }
                PrimaryButton("Add correction", { adding = true })
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) { Snackbar(it, Modifier.padding(12.dp)) }
    }

    if (adding) CorrectionDialog(
        rules = rules,
        earlierCalls = vm::earlierCallsSaying,
        onSave = { heard, written, earlierCalls, _ -> vm.addCorrection(heard, written, earlierCalls) },
        onDismiss = { adding = false },
    )
}

/**
 * Adding a common correction: what the recogniser writes ([heard], perhaps filled in from an edit) and
 * how to write it instead ([written]). A rule for the same words in [rules] is replaced, which the dialog
 * says. When other transcripts say it, "Also correct N earlier calls" ([earlierCalls] counts them)
 * passes true to [onSave]. Opened from a transcript, [inThisCall] counts that call's other lines saying
 * it, which are corrected too unless unticked; lines edited by hand are left alone either way.
 */
@Composable
fun CorrectionDialog(
    rules: List<Correction>,
    earlierCalls: suspend (heard: String) -> Int,
    onSave: (heard: String, written: String, earlierCalls: Boolean, thisCall: Boolean) -> Unit,
    onDismiss: () -> Unit,
    heard: String = "",
    written: String = "",
    inThisCall: (suspend (heard: String) -> Int)? = null,
) {
    var heardText by rememberSaveable { mutableStateOf(heard) }
    var writtenText by rememberSaveable { mutableStateOf(written) }
    var alsoEarlier by rememberSaveable { mutableStateOf(false) }
    // The call the rule was made from: the rest of it is what the user expects corrected.
    var alsoHere by rememberSaveable { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }
    val key = Corrections.keyOf(heardText)
    // Counted a moment after typing stops, from the database.
    var count by remember { mutableStateOf(0) }
    var here by remember { mutableStateOf(0) }
    LaunchedEffect(key) {
        if (key.isEmpty()) {
            count = 0
            here = 0
            return@LaunchedEffect
        }
        delay(300)
        here = inThisCall?.invoke(heardText) ?: 0
        count = earlierCalls(heardText)
    }
    val replaces = rules.firstOrNull { it.heardKey == key }
    val valid = key.isNotEmpty() && writtenText.isNotBlank() && Corrections.normalize(heardText) != writtenText.trim()
    val save = save@{
        if (!valid || saved) return@save
        saved = true
        // "This call" as ticked, whether or not its count has come in yet (correcting it changes nothing if no line says it).
        onSave(heardText, writtenText, alsoEarlier && count > 0, alsoHere)
        onDismiss()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (heard.isEmpty()) focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add correction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = heardText, onValueChange = { heardText = it }, singleLine = true,
                    label = { Text("Heard as") }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    supportingText = replaces?.takeIf { it.written != writtenText.trim() }?.let { old ->
                        { Text("Replaces “${old.heard}”, written as “${old.written}”.") }
                    },
                )
                OutlinedTextField(
                    value = writtenText, onValueChange = { writtenText = it }, singleLine = true,
                    label = { Text("Write as") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                // The dialog sits on surfaceContainerHigh, so the rows take the next tone up to stand out.
                val options = buildList {
                    if (here > 0) add(Triple("Also correct $here other ${if (here == 1) "line" else "lines"} in this call", alsoHere) { v: Boolean -> alsoHere = v })
                    // From a transcript, the count leaves that call out: it's the line above.
                    val calls = if (inThisCall != null) "other" else "earlier"
                    if (count > 0) add(Triple("Also correct $count $calls ${if (count == 1) "call" else "calls"}", alsoEarlier) { v: Boolean -> alsoEarlier = v })
                }
                if (options.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    options.forEachIndexed { i, (label, checked, onChange) ->
                        GroupRow(
                            groupShape(i, options.size), color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            action = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            RowText(label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextAction("Save", save, enabled = valid) },
        dismissButton = { TextAction("Cancel", onDismiss) },
    )
}
