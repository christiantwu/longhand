package io.github.christiantwu.longhand.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.christiantwu.longhand.R
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.work.Work

/** The 2dp coral-violet-sky line used under titles and above transcripts. */
@Composable
fun GradientRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(LocalInk.current.gradient))
}

/** Uppercase mono caption, e.g. "TODAY" or "INCOMING CALL · 2:14 PM". */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalInk.current.muted) {
    Text(text.uppercase(), style = InkType.label, color = color, modifier = modifier)
}

@Composable
fun Logo(size: Dp = 22.dp) {
    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(size))
}

/** A plain top row: one thing on the left, actions on the right. */
@Composable
fun InkTopBar(left: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { left() }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun InkIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = LocalInk.current.ink) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description, tint = tint) }
}

/** Solid ink pill for the one main action on a screen. */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ink = LocalInk.current
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.paper,
            disabledContainerColor = ink.line, disabledContentColor = ink.faint),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Underlined text action, for secondary choices next to body text. */
@Composable
fun InkLink(text: String, onClick: () -> Unit, color: Color = LocalInk.current.ink) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        // TextButton is at least 58 dp wide and centres anything shorter, which pushed "Allow" out of line
        // with the links above it. Filling that minimum keeps short labels flush left.
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, textDecoration = TextDecoration.Underline,
            modifier = Modifier.widthIn(min = ButtonDefaults.MinWidth - 16.dp))
    }
}

/** The link beside a model set: Download or Retry, or a way round the Wi-Fi wait. */
@Composable
fun ModelAction(state: ModelState, set: Models.Set, vm: AppViewModel) {
    when {
        state.installed -> {}
        state.offer != null ->
            InkLink(if (state.offer == Work.DownloadNetwork.WIFI) "Download anyway" else "Use mobile data", { vm.downloadModels(set, state.offer) })
        !state.downloading -> InkLink(if (state.error != null) "Retry" else "Download", { vm.downloadModels(set) })
    }
}

/** The languages Parakeet TDT 0.6B v3 transcribes, as its model card lists them. */
const val EUROPEAN_LANGUAGES = "Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, " +
    "Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, " +
    "Slovenian, Spanish, Swedish and Ukrainian"

/**
 * The transcription language: English only, 25 European languages, or Chinese, Japanese and Korean,
 * the last two detected per call. The chosen one shows its download; until it's in, the installed
 * one keeps transcribing.
 */
@Composable
fun LanguageChoice(language: Models.Language, vm: AppViewModel) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val states = mapOf(
        Models.Language.ENGLISH to vm.speechModels.collectAsStateWithLifecycle().value,
        Models.Language.EUROPEAN to vm.multilingualModels.collectAsStateWithLifecycle().value,
        Models.Language.CJK to vm.cjkModels.collectAsStateWithLifecycle().value,
    )
    val chosenReady = states.getValue(language).installed
    for (option in Models.Language.entries) {
        val selected = language == option
        val state = states.getValue(option)
        val set = option.set
        val (name, about) = when (option) {
            Models.Language.ENGLISH -> "English" to "The most accurate for English, and the only language it transcribes."
            Models.Language.EUROPEAN -> "25 European languages" to
                "$EUROPEAN_LANGUAGES, detected for each call. A little less accurate in English."
            Models.Language.CJK -> "Chinese, Japanese and Korean" to
                "Mandarin and Cantonese Chinese, Japanese, Korean and English, detected for each call. " +
                "Less accurate in English."
        }
        val detail = when {
            selected && state.installed -> about
            selected && state.downloading -> state.downloadText
            selected && state.error != null -> "Download failed: ${state.error}"
            !selected && state.installed && !chosenReady -> "In use until the chosen language has downloaded."
            else -> "$about About ${Models.missingBytes(context, set) / 1_000_000} MB, downloaded over Wi-Fi."
        }
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) vm.setLanguage(option) })
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 4.dp),
                colors = RadioButtonDefaults.colors(selectedColor = ink.ink, unselectedColor = ink.faint))
            Column(Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = ink.ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = ink.muted)
                if (selected) state.runningProgress?.let { GradientProgress(it, Modifier.padding(top = 6.dp)) }
                // Pull the link back by its own padding so its text lines up with the text above.
                if (selected && !state.installed) Row(Modifier.offset(x = (-8).dp)) { ModelAction(state, set, vm) }
            }
        }
    }
}

/** A hairline-bordered notice: a mono label, a sentence, an optional action and an optional way to dismiss it. */
@Composable
fun Notice(
    label: String, text: String, action: String? = null, onAction: () -> Unit = {},
    dismiss: String? = null, onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    Column(
        modifier.fillMaxWidth().border(1.dp, ink.line, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MonoLabel(label, color = ink.violet)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = ink.ink, modifier = Modifier.weight(1f).padding(bottom = 6.dp))
            if (dismiss != null) InkLink(dismiss, onDismiss, color = ink.muted)
            if (action != null) InkLink(action, onAction)
        }
    }
}

/** A thin progress line in the accent gradient. */
@Composable
fun GradientProgress(progress: Float, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Box(modifier.fillMaxWidth().height(2.dp).background(ink.line)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(ink.gradient))
    }
}

/** Round ink play/pause button for the player bar. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(ink.ink).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) InkIcons.Pause else InkIcons.Play, contentDescription = if (playing) "Pause" else "Play",
            tint = ink.paper, modifier = Modifier.size(18.dp))
    }
}

/** Line icons in the same 1.8-stroke style, for what material-icons-core doesn't have. */
object InkIcons {
    private fun line(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
        ).build()

    private fun solid(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

    val Play = solid("play") { moveTo(8f, 5.5f); lineTo(8f, 18.5f); lineTo(18.5f, 12f); close() }
    val Pause = solid("pause") {
        moveTo(7f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(7f, 19f); close()
        moveTo(14f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(14f, 19f); close()
    }
    val Incoming = line("incoming") { moveTo(17f, 7f); lineTo(7f, 17f); moveTo(7f, 9f); lineTo(7f, 17f); lineTo(15f, 17f) }
    val Outgoing = line("outgoing") { moveTo(7f, 17f); lineTo(17f, 7f); moveTo(9f, 7f); lineTo(17f, 7f); lineTo(17f, 15f) }
    val Tune = line("tune") {
        moveTo(4f, 7f); lineTo(14f, 7f); moveTo(18f, 7f); lineTo(20f, 7f); moveTo(4f, 17f); lineTo(8f, 17f)
        moveTo(12f, 17f); lineTo(20f, 17f); moveTo(14f, 4f); lineTo(14f, 10f); moveTo(8f, 14f); lineTo(8f, 20f)
    }
}
