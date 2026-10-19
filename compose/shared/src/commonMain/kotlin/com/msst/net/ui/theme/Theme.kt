package com.msst.net.ui.theme

// Light-blue minimal theme (3.1.x), ported from the Svelte frontend's tokens.
//
// Colour rules:
//  - Light blue (primaryLighter / primarySoftBorder) is for surfaces,
//    selection and tags only.  Text, icons and filled buttons use primary or
//    primaryDark, which keep ≥ 4.5:1 contrast against white and the page
//    background.
//  - Every text colour is ≥ 4.5:1 on bgPrimary and bgSecondary.
//  - Success / warning / error keep their own hues so status stays
//    readable; the *Dot variants are for non-text indicators only.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msst.net.resources.JetBrainsMono_Regular
import com.msst.net.resources.Res
import com.msst.net.resources.chillround_bold
import com.msst.net.resources.chillround_regular
import org.jetbrains.compose.resources.Font

@Immutable
data class MsstColors(
    val primaryDark: Color = Color(0xFF185DA3),
    val primary: Color = Color(0xFF1D6FC2),
    val primaryLight: Color = Color(0xFF6EB6FF),
    val primaryLighter: Color = Color(0xFFEAF3FC),
    val primarySoftBorder: Color = Color(0xFFBCD8F3),

    val bgPrimary: Color = Color(0xFFFFFFFF),
    val bgSecondary: Color = Color(0xFFF4F8FC),
    val bgTertiary: Color = Color(0xFFEAF3FC),
    val bgHover: Color = Color(0xFFEEF5FC),

    val textPrimary: Color = Color(0xFF17212C),
    val textSecondary: Color = Color(0xFF3F4D5C),
    val textTertiary: Color = Color(0xFF56636F),
    val textMuted: Color = Color(0xFF616D79),
    val textInverse: Color = Color(0xFFFFFFFF),

    val borderLight: Color = Color(0xFFDCE6F0),
    val borderMedium: Color = Color(0xFFC3D3E3),
    val borderPrimary: Color = Color(0xFF8FBBE6),

    val success: Color = Color(0xFF1B7347),
    val successDot: Color = Color(0xFF2F9E63),
    val successLight: Color = Color(0xFFE4F4EA),
    val warning: Color = Color(0xFF8A5300),
    val warningDot: Color = Color(0xFFE0921F),
    val warningLight: Color = Color(0xFFFDF0D9),
    val error: Color = Color(0xFFB42F28),
    val errorDark: Color = Color(0xFF8F241F),
    val errorDot: Color = Color(0xFFD9473F),
    val errorLight: Color = Color(0xFFFCE9E7),
)

val LocalMsstColors = staticCompositionLocalOf { MsstColors() }

/** Theme tokens: `Msst.colors.primary`, … */
object Msst {
    val colors: MsstColors
        @Composable get() = LocalMsstColors.current
}

object Radius {
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(8.dp)
    val lg = RoundedCornerShape(10.dp)
    val xl = RoundedCornerShape(12.dp)
    val full = RoundedCornerShape(percent = 50)
}

/** The Tauri/WebUI font: 寒蝉全圆体 (ChillRound, SIL OFL 1.1). */
@Composable
fun chillRound(): FontFamily = FontFamily(
    Font(Res.font.chillround_regular, FontWeight.Normal),
    Font(Res.font.chillround_regular, FontWeight.Medium),
    Font(Res.font.chillround_bold, FontWeight.SemiBold),
    Font(Res.font.chillround_bold, FontWeight.Bold),
)

/** Monospace for IDs, addresses and logs: JetBrains Mono (SIL OFL 1.1).
 *  Only the regular weight is bundled.  It has no Chinese glyphs: those fall
 *  back to the system fonts, or in the browser (no system fonts) to
 *  ChillRound, which [MsstTheme] preloads for that purpose. */
val MonoFamily: FontFamily
    @Composable get() = FontFamily(Font(Res.font.JetBrainsMono_Regular, FontWeight.Normal))

@Composable
fun MsstTheme(content: @Composable () -> Unit) {
    val c = MsstColors()
    val font = chillRound()
    // Preloaded families serve as glyph fallback where the platform has no
    // fonts of its own (web): Chinese inside monospace text.
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(font) { runCatching { resolver.preload(font) } }
    fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.5).toInt()) =
        TextStyle(fontFamily = font, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

    val typography = Typography(
        displaySmall = style(28, FontWeight.Bold, 34),
        headlineSmall = style(24, FontWeight.Bold, 30),
        titleLarge = style(20, FontWeight.SemiBold, 28),
        titleMedium = style(16, FontWeight.SemiBold, 24),
        titleSmall = style(15, FontWeight.SemiBold, 22),
        bodyLarge = style(15),
        bodyMedium = style(14),
        bodySmall = style(13),
        labelLarge = style(14, FontWeight.Medium, 20),
        labelMedium = style(13, FontWeight.Medium, 18),
        labelSmall = style(12, FontWeight.Normal, 16),
    )
    val scheme = lightColorScheme(
        primary = c.primary,
        onPrimary = c.textInverse,
        primaryContainer = c.primaryLighter,
        onPrimaryContainer = c.primaryDark,
        secondary = c.primaryDark,
        onSecondary = c.textInverse,
        secondaryContainer = c.primaryLighter,
        onSecondaryContainer = c.primaryDark,
        tertiary = c.warning,
        background = c.bgSecondary,
        onBackground = c.textPrimary,
        surface = c.bgPrimary,
        onSurface = c.textPrimary,
        surfaceVariant = c.bgTertiary,
        onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.bgPrimary,
        surfaceContainerLow = c.bgPrimary,
        surfaceContainerHigh = c.bgPrimary,
        surfaceContainerHighest = c.bgTertiary,
        surfaceContainerLowest = c.bgPrimary,
        outline = c.borderMedium,
        outlineVariant = c.borderLight,
        error = c.error,
        onError = c.textInverse,
        errorContainer = c.errorLight,
        onErrorContainer = c.errorDark,
    )
    CompositionLocalProvider(LocalMsstColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = Shapes(
                extraSmall = Radius.sm,
                small = Radius.md,
                medium = Radius.lg,
                large = Radius.xl,
                extraLarge = Radius.xl,
            ),
            content = content,
        )
    }
}
