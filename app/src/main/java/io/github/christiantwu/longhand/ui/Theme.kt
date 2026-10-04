package io.github.christiantwu.longhand.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import io.github.christiantwu.longhand.R

/**
 * Editorial Material: Material You colours from the wallpaper and Material 3 components, set in
 * Geist with mono captions, and the icon's coral-violet-sky gradient kept as a thin rule under titles.
 */
@Immutable
data class Editorial(
    /** The brand gradient: the same on every wallpaper. */
    val gradient: Brush,
)

private val LightEditorial = Editorial(Brush.horizontalGradient(listOf(Color(0xFFF0684A), Color(0xFF9B5CF0), Color(0xFF3D8BFF))))
private val DarkEditorial = Editorial(Brush.horizontalGradient(listOf(Color(0xFFFF8A6B), Color(0xFFC08BFF), Color(0xFF7DB8FF))))

val LocalEditorial = staticCompositionLocalOf { LightEditorial }

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.geist, it) })
val GeistMono = FontFamily(listOf(400, 500).map { variable(R.font.geist_mono, it) })

/** Type styles the Material scale doesn't name. */
object EditorialType {
    /** Mono captions: section headers, kickers, day headers. Set the text uppercase (MonoLabel does). */
    val label = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.06.em)
    /** Times and durations in lists. */
    val time = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    /** Timestamps beside transcript lines and under the player. */
    val clock = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
    /** Speaker names above transcript lines. Set the text uppercase. */
    val speaker = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.08.em)
}

private fun geist(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = Geist, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.em)

/** The Material 3 type scale in Geist; headlines are a little tighter, as in the old design. */
private val EditorialTypography = Typography(
    displaySmall = geist(FontWeight.SemiBold, 36, 44, -0.02),
    headlineLarge = geist(FontWeight.SemiBold, 32, 40, -0.02),
    headlineMedium = geist(FontWeight.SemiBold, 28, 36, -0.02),
    headlineSmall = geist(FontWeight.SemiBold, 24, 32, -0.01),
    titleLarge = geist(FontWeight.SemiBold, 22, 28, -0.01),
    titleMedium = geist(FontWeight.Medium, 16, 24),
    titleSmall = geist(FontWeight.Medium, 14, 20),
    bodyLarge = geist(FontWeight.Normal, 16, 24),
    bodyMedium = geist(FontWeight.Normal, 14, 20),
    bodySmall = geist(FontWeight.Normal, 12, 16),
    labelLarge = geist(FontWeight.Medium, 14, 20),
    labelMedium = geist(FontWeight.Medium, 12, 16),
    labelSmall = geist(FontWeight.Medium, 11, 16),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Wallpaper colours exist on every supported version (minSdk 31, Android 12).
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    CompositionLocalProvider(LocalEditorial provides if (dark) DarkEditorial else LightEditorial) {
        MaterialTheme(colorScheme = scheme, typography = EditorialTypography, content = content)
    }
}

/**
 * Colour for a speaker's name, each voice in its own hue at the same strength: you in the wallpaper's own hue, everyone
 * else in hues turned well away from it ([SpeakerHues]). The wallpaper's secondary and tertiary colours sit too close to
 * its primary to tell people apart. Tone 40 (light) or 80 (dark) keeps every name readable on the page and on the line
 * being played.
 */
@Composable
fun speakerColor(speaker: Int, owner: Int?): Color {
    val c = MaterialTheme.colorScheme
    val dark = c.surface.luminance() < 0.5f
    val primaryHue = remember(c.primary) { FloatArray(3).also { ColorUtils.colorToM3HCT(c.primary.toArgb(), it) }[0] }
    val hue = SpeakerHues.hue(primaryHue, SpeakerHues.slot(speaker, owner))
    return remember(hue, dark) { Color(ColorUtils.M3HCTToColor(hue, SpeakerHues.CHROMA, if (dark) 80f else 40f)) }
}

/** Which hue each speaker's name gets, apart from Compose so it can be tested. */
object SpeakerHues {
    const val CHROMA = 48f

    /** Turns from the wallpaper's hue: you, the next two a third of the way round either side, then between them. */
    private val TURNS = floatArrayOf(0f, 120f, 240f, 60f, 180f, 300f)

    /**
     * 0 for you, then 1, 2… for the other voices in order. Without a known owner, speaker 1 takes your place, as it
     * always has.
     */
    fun slot(speaker: Int, owner: Int?): Int {
        val me = owner ?: 1
        return when {
            speaker == me -> 0
            speaker < me -> speaker + 1
            else -> speaker
        }
    }

    /** The hue for [slot]; past six voices the others' hues come round again, never yours. */
    fun hue(primaryHue: Float, slot: Int): Float {
        val turn = if (slot == 0) 0f else TURNS[1 + (slot - 1) % (TURNS.size - 1)]
        return (primaryHue + turn) % 360f
    }
}
