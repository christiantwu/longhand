package io.github.christiantwu.longhand.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christiantwu.longhand.R
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.work.Work
import java.util.Locale

// ---- Editorial pieces -------------------------------------------------------------------------

/** The 2dp coral-violet-sky rule under screen titles. */
@Composable
fun GradientRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(LocalEditorial.current.gradient))
}

/** Uppercase mono caption, e.g. "INCOMING · TODAY, 2:14 PM". */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = EditorialType.label, color = color, modifier = modifier)
}

/**
 * The header above a group of rows: its name in the primary colour and, optionally, a count on the
 * right ("3 CALLS · 24 MIN"). Indented 16dp so it lines up with the text inside the rows.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(title, Modifier.weight(1f).semantics { heading() }, color = MaterialTheme.colorScheme.primary)
        if (trailing != null) MonoLabel(trailing)
    }
}

/** A screen's large title with the gradient rule under it (Calls, Settings, Licences). */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() })
        GradientRule()
    }
}

@Composable
fun Logo(size: Dp = 22.dp) {
    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(size))
}

// ---- App bar ----------------------------------------------------------------------------------

/** The 64dp top bar: an optional navigation button on the left, actions on the right, no title. */
@Composable
fun AppBar(navigation: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        navigation?.invoke()
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A 48dp icon button; actions are drawn in the secondary text colour. */
@Composable
fun AppIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, contentDescription = description, tint = tint) }
}

@Composable
fun BackButton(onClick: () -> Unit) =
    AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick, tint = MaterialTheme.colorScheme.onSurface)

// ---- Grouped rows -----------------------------------------------------------------------------

/** Space between the rows of a group. */
val GroupGap = 2.dp
private val OuterCorner = 16.dp
private val InnerCorner = 4.dp

/** The shape of row [index] of [count]: the group's outer corners are 16dp, the corners between rows 4dp. */
fun groupShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(OuterCorner)
    index == 0 -> RoundedCornerShape(topStart = OuterCorner, topEnd = OuterCorner, bottomStart = InnerCorner, bottomEnd = InnerCorner)
    index == count - 1 -> RoundedCornerShape(topStart = InnerCorner, topEnd = InnerCorner, bottomStart = OuterCorner, bottomEnd = OuterCorner)
    else -> RoundedCornerShape(InnerCorner)
}

/**
 * One row of a group, on the container colour. Give [onClick] for a row that opens something, or a
 * selectable or toggleable modifier as [action] for a choice; either way the ripple stays inside the
 * row's shape. Rows grow with their content above [minHeight].
 */
@Composable
fun GroupRow(
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    action: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(shape).background(color)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .then(action)
            .heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/**
 * A row's text: a 16sp headline over 14sp supporting text, taking the row's free width. [extra] goes
 * under the text, e.g. a progress line or an action.
 */
@Composable
fun RowScope.RowText(
    headline: String,
    supporting: String? = null,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    singleLine: Boolean = false,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val lines = if (singleLine) 1 else Int.MAX_VALUE
    Column(Modifier.weight(1f)) {
        Text(headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            maxLines = lines, overflow = TextOverflow.Ellipsis)
        if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = supportingColor,
            maxLines = lines, overflow = TextOverflow.Ellipsis)
        extra()
    }
}

/** A 40dp circle with a contact's initials on one of the scheme's container colours, or a person for a caller without a name. */
@Composable
fun Avatar(name: String?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val initials = name?.let(::initialsOf)
    val (container, content) = if (initials == null) c.surfaceContainerHighest to c.onSurfaceVariant else listOf(
        c.primaryContainer to c.onPrimaryContainer,
        c.secondaryContainer to c.onSecondaryContainer,
        c.tertiaryContainer to c.onTertiaryContainer,
    )[Math.floorMod(name.hashCode(), 3)]
    Box(modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        if (initials != null) Text(initials, style = MaterialTheme.typography.titleMedium, color = content)
        else Icon(Icons.Filled.Person, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
    }
}

/** "Dana Whitfield" → "DW", "Priya" → "P"; null when the name has no words starting with a letter, like a phone number. */
fun initialsOf(name: String): String? {
    val words = name.trim().split(Regex("\\s+")).filter { it.firstOrNull()?.isLetter() == true }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { null }
}

/** [text] with [style] over each of the [ranges], e.g. where search text occurs. */
fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString =
    if (ranges.isEmpty()) AnnotatedString(text) else buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }

// ---- Buttons ----------------------------------------------------------------------------------

/** The filled button for the one main action on a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A 40dp outlined pill, for an action at the end of a row ("Change", "Forget"). */
@Composable
fun OutlinedPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 40.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A text button in the primary colour, for secondary choices next to body text. */
@Composable
fun TextAction(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        // TextButton is at least 58 dp wide and centres anything shorter, which pushes short labels out
        // of line with the actions above them. Filling that minimum keeps them flush left.
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.widthIn(min = ButtonDefaults.MinWidth - 24.dp))
    }
}

/** The actions beside a model set: Download, Update or Retry, or a way round the Wi-Fi wait. */
@Composable
fun ModelAction(state: ModelState, set: Models.Set, vm: AppViewModel) {
    when {
        state.installed && !state.update -> {}
        state.offer != null ->
            TextAction(if (state.offer == Work.DownloadNetwork.WIFI) "Download anyway" else "Use mobile data", { vm.downloadModels(set, state.offer) })
        !state.downloading -> TextAction(
            when {
                state.error != null -> "Retry"
                state.update -> "Update"
                else -> "Download"
            },
            { vm.downloadModels(set) },
        )
    }
}

// ---- Progress and playback --------------------------------------------------------------------

/** Material's linear progress indicator: determinate with a [progress], else indeterminate. */
@Composable
fun ProgressLine(progress: Float?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    if (progress == null) LinearProgressIndicator(modifier.fillMaxWidth(), color = c.primary, trackColor = c.secondaryContainer)
    else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth(),
        color = c.primary, trackColor = c.secondaryContainer)
}

/** The player's 56dp play/pause button on the primary container colour. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = onClick, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer),
    ) {
        Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = if (playing) "Pause" else "Play")
    }
}

// ---- Notices ----------------------------------------------------------------------------------

/** A tonal card: a mono label, a sentence, and optional actions to act on it or dismiss it. */
@Composable
fun Notice(
    label: String, text: String, action: String? = null, onAction: () -> Unit = {},
    dismiss: String? = null, onDismiss: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHighest)
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = if (action != null || dismiss != null) 4.dp else 16.dp),
    ) {
        MonoLabel(label, color = c.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurface, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
        if (action != null || dismiss != null) {
            Row(Modifier.align(Alignment.End)) {
                if (dismiss != null) TextAction(dismiss, onDismiss, color = c.onSurfaceVariant)
                if (action != null) TextAction(action, onAction)
            }
        }
    }
}

// ---- Language choice --------------------------------------------------------------------------

/** How a language choice is named in Setup and Settings. */
fun languageName(language: Models.Language): String = when (language) {
    Models.Language.ENGLISH -> "English"
    Models.Language.EUROPEAN -> "25 European languages"
    Models.Language.CJK -> "Chinese, Japanese and Korean"
    Models.Language.HINDI -> "Hindi"
}

/** The languages Parakeet TDT 0.6B v3 transcribes, as its model card lists them. */
const val EUROPEAN_LANGUAGES = "Bulgarian, Croatian, Czech, Danish, Dutch, English, Estonian, Finnish, French, German, " +
    "Greek, Hungarian, Italian, Latvian, Lithuanian, Maltese, Polish, Portuguese, Romanian, Russian, Slovak, " +
    "Slovenian, Spanish, Swedish and Ukrainian"

/**
 * The transcription language as a group of radio rows: English only, 25 European languages,
 * Chinese, Japanese and Korean, or Hindi, the last three detected per call. The chosen one shows its
 * download; until it's in, the installed one keeps transcribing.
 */
@Composable
fun LanguageChoice(language: Models.Language, vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val states = mapOf(
        Models.Language.ENGLISH to vm.speechModels.collectAsStateWithLifecycle().value,
        Models.Language.EUROPEAN to vm.multilingualModels.collectAsStateWithLifecycle().value,
        Models.Language.CJK to vm.cjkModels.collectAsStateWithLifecycle().value,
        Models.Language.HINDI to vm.hindiModels.collectAsStateWithLifecycle().value,
    )
    val chosenReady = states.getValue(language).installed
    val options = Models.Language.entries
    // A phone set to a language the chosen model doesn't transcribe: most calls are probably in it.
    val phone = LocalConfiguration.current.locales[0]
    val forPhone = Models.Language.forPhoneLanguage(phone.language)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        if (forPhone != language && forPhone != Models.Language.ENGLISH) {
            val name = phone.getDisplayLanguage(Locale.ENGLISH)
            Text("Your phone is set to $name. To transcribe calls in $name, choose ${languageName(forPhone)}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
        }
        options.forEachIndexed { i, option ->
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
                Models.Language.HINDI -> "Hindi" to
                    "Hindi and English, detected for each call, including calls that mix them. " +
                    "English words in Hindi sentences are written in Devanagari."
            }
            val detail = when {
                selected && state.installed -> about
                selected && state.downloading -> state.downloadText
                selected && state.error != null -> "Download failed: ${state.error}"
                !selected && state.installed && !chosenReady -> "In use until the chosen language has downloaded."
                else -> "$about About ${Models.missingBytes(context, set) / 1_000_000} MB, downloaded over Wi-Fi."
            }
            GroupRow(
                groupShape(i, options.size), minHeight = 72.dp, verticalAlignment = Alignment.Top,
                action = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) vm.setLanguage(option) }),
            ) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp))
                RowText(name, detail) {
                    if (selected && !state.update) state.runningProgress?.let { ProgressLine(it, Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                    // The language keeps working while an improved version downloads, so that's one quiet line.
                    if (selected) state.updateText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    // Pull the action back by its own padding so its text lines up with the text above.
                    if (selected && (!state.installed || state.update)) Row(Modifier.offset(x = (-12).dp)) { ModelAction(state, set, vm) }
                }
            }
        }
    }
}

// ---- Icons ------------------------------------------------------------------------------------

/** Line icons in one stroke style, for what material-icons-core doesn't have. */
object AppIcons {
    private fun line(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
        ).build()

    private fun solid(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

    val Play = solid("play") { moveTo(8f, 5.5f); lineTo(8f, 18.5f); lineTo(18.5f, 12f); close() }
    val Pause = solid("pause") {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }
    val Incoming = line("incoming") { moveTo(18.5f, 5.5f); lineTo(6.6f, 17.4f); moveTo(6f, 9.5f); lineTo(6f, 18f); lineTo(14.5f, 18f) }
    val Outgoing = line("outgoing") { moveTo(5.5f, 18.5f); lineTo(17.4f, 6.6f); moveTo(9.5f, 6f); lineTo(18f, 6f); lineTo(18f, 14.5f) }
    /** Waiting for the charger. */
    val Bolt = line("bolt") {
        moveTo(13.5f, 2.8f); lineTo(5.6f, 13.4f); lineTo(11.5f, 13.4f); lineTo(10.5f, 21.2f); lineTo(18.4f, 10.6f); lineTo(12.5f, 10.6f); close()
    }
    /** Marks the transcript line being played. */
    val Playing = solid("playing") {
        moveTo(3f, 10f); lineTo(5.5f, 10f); lineTo(5.5f, 14f); lineTo(3f, 14f); close()
        moveTo(8f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 19f); lineTo(8f, 19f); close()
        moveTo(13f, 8f); lineTo(15.5f, 8f); lineTo(15.5f, 16f); lineTo(13f, 16f); close()
        moveTo(18f, 11f); lineTo(20.5f, 11f); lineTo(20.5f, 13f); lineTo(18f, 13f); close()
    }
    /** A follow-up still to happen. */
    val FollowUp = line("follow-up") { moveTo(5f, 5f); lineTo(5f, 13f); lineTo(19f, 13f); moveTo(15f, 9f); lineTo(19f, 13f); lineTo(15f, 17f) }
}
