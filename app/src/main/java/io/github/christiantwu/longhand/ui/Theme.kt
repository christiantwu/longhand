package io.github.christiantwu.longhand.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.christiantwu.longhand.R

/** Paper & Ink: crisp type on paper, with the icon's coral-violet-sky gradient as a thin accent. */
@Immutable
data class Ink(
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val raised: Color,
    val coral: Color,
    val violet: Color,
    val sky: Color,
    /** Speaker label colours: the other person, and you. */
    val them: Color,
    val me: Color,
    val danger: Color,
) {
    val gradient: Brush get() = Brush.horizontalGradient(listOf(coral, violet, sky))
}

private val LightInk = Ink(
    paper = Color(0xFFFFFFFF), ink = Color(0xFF0E1024), muted = Color(0xFF565B73), faint = Color(0xFF8A8FA5),
    line = Color(0xFFE8E9F0), raised = Color(0xFFF5F4FB),
    coral = Color(0xFFF0684A), violet = Color(0xFF9B5CF0), sky = Color(0xFF3D8BFF),
    them = Color(0xFFD4512F), me = Color(0xFF3D6FE0), danger = Color(0xFFC4321C),
)

private val DarkInk = Ink(
    paper = Color(0xFF0D0F1A), ink = Color(0xFFECEEF6), muted = Color(0xFFA3A8BF), faint = Color(0xFF737892),
    line = Color(0xFF242739), raised = Color(0xFF161929),
    coral = Color(0xFFFF8A6B), violet = Color(0xFFC08BFF), sky = Color(0xFF7DB8FF),
    them = Color(0xFFFF9A7E), me = Color(0xFF8FB2FF), danger = Color(0xFFFF8A7A),
)

val LocalInk = staticCompositionLocalOf { LightInk }

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.geist, it) })
val GeistMono = FontFamily(listOf(400, 500).map { variable(R.font.geist_mono, it) })

/** Extra type styles the Material scale doesn't name. */
object InkType {
    /** Uppercase mono captions: kickers, day headers, timestamps. */
    val label = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.06.em)
    val clock = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    /** Speaker names above transcript lines, set in small caps style. */
    val speaker = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.em)
}

private val InkTypography = Typography(
    displaySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.035).em),
    headlineMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.03).em),
    headlineSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.01).em),
    titleSmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.01).em),
    bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) DarkInk else LightInk
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = ink.ink, onPrimary = ink.paper,
        primaryContainer = ink.raised, onPrimaryContainer = ink.ink,
        secondary = ink.violet, onSecondary = ink.paper,
        tertiary = ink.sky,
        background = ink.paper, onBackground = ink.ink,
        surface = ink.paper, onSurface = ink.ink,
        surfaceVariant = ink.raised, onSurfaceVariant = ink.muted,
        surfaceContainerLowest = ink.paper, surfaceContainerLow = ink.paper, surfaceContainer = ink.raised,
        surfaceContainerHigh = ink.raised, surfaceContainerHighest = ink.raised,
        outline = ink.line, outlineVariant = ink.line,
        error = ink.danger,
    )
    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(colorScheme = scheme, typography = InkTypography, content = content)
    }
}

/** Colour for a speaker's name: you, the other person, then extra voices on conference calls. */
@Composable
fun speakerColor(speaker: Int, owner: Int?): Color {
    val ink = LocalInk.current
    return when {
        speaker == owner -> ink.me
        owner == null && speaker == 0 -> ink.them
        owner == null && speaker == 1 -> ink.me
        else -> listOf(ink.them, ink.violet, ink.sky)[(speaker - if (owner != null && speaker > owner) 1 else 0).coerceAtLeast(0) % 3]
    }
}
