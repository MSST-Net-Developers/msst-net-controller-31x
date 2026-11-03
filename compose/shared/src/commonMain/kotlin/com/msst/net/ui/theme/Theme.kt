package com.msst.net.ui.theme

// Material Design theme (3.1.x) with light and dark colour sets.
//
// Colour rules:
//  - Light mode is built on the Material palette's Light Blue, dark mode on
//    its Blue (materialui.co/colors).  The palette 500 tone is the brand
//    accent ([MsstColors.brand]): indicators and decoration only.
//  - Text, icons and filled buttons use primary or primaryDark, which keep
//    ≥ 4.5:1 contrast against the surfaces of their mode.
//  - Every text colour is ≥ 4.5:1 on bgPrimary and bgSecondary.
//  - Success / warning / error keep their own hues so status stays
//    readable; the *Dot variants are for non-text indicators only.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
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

/** Theme preference, stored per device by [com.msst.net.platform.LocalPreferences]. */
enum class ThemeMode(val key: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色");

    companion object {
        fun parse(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

/** The current [ThemeMode] and how to change it (settings screen). */
@Immutable
class ThemeSetting(val mode: ThemeMode, val set: (ThemeMode) -> Unit)

val LocalThemeSetting = staticCompositionLocalOf { ThemeSetting(ThemeMode.System) {} }

@Immutable
data class MsstColors(
    val dark: Boolean,
    /** Brand accent (palette 500): navigation indicators and decoration, never text. */
    val brand: Color,
    /** Text, icons and filled buttons. */
    val primary: Color,
    /** Text on [primaryLighter] surfaces, emphasised text. */
    val primaryDark: Color,
    val primaryLight: Color,
    /** Tonal container: selection, notices, tags. */
    val primaryLighter: Color,
    val primarySoftBorder: Color,
    /** Active indicator behind the selected navigation item. */
    val navIndicator: Color,

    /** Cards, bars and dialogs. */
    val bgPrimary: Color,
    /** Page background. */
    val bgSecondary: Color,
    val bgTertiary: Color,
    val bgHover: Color,
    /** Dialogs and menus (one tone above cards). */
    val bgElevated: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,
    /** Text and icons on filled [primary] / [error] / [errorDot] backgrounds. */
    val textInverse: Color,

    val borderLight: Color,
    val borderMedium: Color,
    val borderPrimary: Color,

    val success: Color,
    val successDot: Color,
    val successLight: Color,
    val warning: Color,
    val warningDot: Color,
    val warningLight: Color,
    val error: Color,
    val errorDark: Color,
    val errorDot: Color,
    val errorLight: Color,
)

/**
 * Light mode: Material palette Light Blue (materialui.co/colors).  Light Blue
 * 500 is the brand accent; text and filled buttons use 800 / 900, which keep
 * ≥ 4.5:1 against white and the Light Blue 50 containers.
 */
val LightColors = MsstColors(
    dark = false,
    brand = Color(0xFF03A9F4),            // Light Blue 500
    primary = Color(0xFF0277BD),          // Light Blue 800 — 4.8:1 on white
    primaryDark = Color(0xFF01579B),      // Light Blue 900 — 6.7:1 on Light Blue 50
    primaryLight = Color(0xFF4FC3F7),     // Light Blue 300
    primaryLighter = Color(0xFFE1F5FE),   // Light Blue 50
    primarySoftBorder = Color(0xFFB3E5FC),// Light Blue 100
    navIndicator = Color(0xFFB3E5FC),     // Light Blue 100

    bgPrimary = Color(0xFFFFFFFF),
    bgSecondary = Color(0xFFF5F9FC),
    bgTertiary = Color(0xFFE8F1F7),
    bgHover = Color(0xFFEDF5FA),
    bgElevated = Color(0xFFFFFFFF),

    textPrimary = Color(0xFF191C1F),
    textSecondary = Color(0xFF40484F),
    textTertiary = Color(0xFF545D66),
    textMuted = Color(0xFF5F6A74),
    textInverse = Color(0xFFFFFFFF),

    borderLight = Color(0xFFDDE5EC),
    borderMedium = Color(0xFFBFCBD5),
    borderPrimary = Color(0xFF81D4FA),    // Light Blue 200

    success = Color(0xFF2E7D32),          // Green 800
    successDot = Color(0xFF43A047),       // Green 600
    successLight = Color(0xFFE8F5E9),     // Green 50
    warning = Color(0xFF8A5300),
    warningDot = Color(0xFFFB8C00),       // Orange 600
    warningLight = Color(0xFFFFF3E0),     // Orange 50
    error = Color(0xFFC62828),            // Red 800
    errorDark = Color(0xFFB71C1C),        // Red 900
    errorDot = Color(0xFFE53935),         // Red 600
    errorLight = Color(0xFFFFEBEE),       // Red 50
)

/**
 * Dark mode: Material palette Blue.  Following Material's dark-theme
 * guidance, text and filled buttons use the desaturated Blue 200 with dark
 * content; Blue 500 is the accent and Blue 900 the tonal container.
 */
val DarkColors = MsstColors(
    dark = true,
    brand = Color(0xFF2196F3),            // Blue 500
    primary = Color(0xFF90CAF9),          // Blue 200
    primaryDark = Color(0xFFBBDEFB),      // Blue 100 — 6.3:1 on Blue 900
    primaryLight = Color(0xFF64B5F6),     // Blue 300
    primaryLighter = Color(0xFF0D3C78),   // between Blue 900 and the surface
    primarySoftBorder = Color(0xFF1565C0),// Blue 800
    navIndicator = Color(0xFF0D3C78),

    bgPrimary = Color(0xFF1B2026),
    bgSecondary = Color(0xFF111418),
    bgTertiary = Color(0xFF252B33),
    bgHover = Color(0xFF2A313A),
    bgElevated = Color(0xFF232930),

    textPrimary = Color(0xFFE2E6EB),
    textSecondary = Color(0xFFBFC7D0),
    textTertiary = Color(0xFFA3AEB9),
    textMuted = Color(0xFF95A1AD),
    textInverse = Color(0xFF0A1A2B),

    borderLight = Color(0xFF2C343D),
    borderMedium = Color(0xFF414B56),
    borderPrimary = Color(0xFF1976D2),    // Blue 700

    success = Color(0xFF81C784),          // Green 300
    successDot = Color(0xFF66BB6A),       // Green 400
    successLight = Color(0xFF1C3321),
    warning = Color(0xFFFFCC80),          // Orange 200
    warningDot = Color(0xFFFFA726),       // Orange 400
    warningLight = Color(0xFF3A2B14),
    error = Color(0xFFEF9A9A),            // Red 200
    errorDark = Color(0xFFFFCDD2),        // Red 100
    errorDot = Color(0xFFEF5350),         // Red 400
    errorLight = Color(0xFF3D1E21),
)

val LocalMsstColors = staticCompositionLocalOf { LightColors }

/** Theme tokens: `Msst.colors.primary`, … */
object Msst {
    val colors: MsstColors
        @Composable get() = LocalMsstColors.current
}

/** Material 3 shape scale. */
object Radius {
    val xs = RoundedCornerShape(4.dp)
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(12.dp)
    val lg = RoundedCornerShape(16.dp)
    val xl = RoundedCornerShape(28.dp)
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
fun MsstTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
    val font = chillRound()
    // Preloaded families serve as glyph fallback where the platform has no
    // fonts of its own (web): Chinese inside monospace text.
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(font) { runCatching { resolver.preload(font) } }
    fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.5).toInt()) =
        TextStyle(fontFamily = font, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

    val typography = Typography(
        displaySmall = style(28, FontWeight.Bold, 34),
        headlineSmall = style(22, FontWeight.SemiBold, 30),
        titleLarge = style(22, FontWeight.SemiBold, 28),
        titleMedium = style(16, FontWeight.SemiBold, 24),
        titleSmall = style(15, FontWeight.SemiBold, 22),
        bodyLarge = style(15),
        bodyMedium = style(14),
        bodySmall = style(13),
        labelLarge = style(14, FontWeight.Medium, 20),
        labelMedium = style(13, FontWeight.Medium, 18),
        labelSmall = style(12, FontWeight.Normal, 16),
    )
    val scheme = (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary,
        onPrimary = c.textInverse,
        primaryContainer = c.primaryLighter,
        onPrimaryContainer = c.primaryDark,
        inversePrimary = c.brand,
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
        surfaceTint = c.brand,
        surfaceContainerLowest = c.bgPrimary,
        surfaceContainerLow = c.bgPrimary,
        surfaceContainer = c.bgPrimary,
        surfaceContainerHigh = c.bgElevated,
        surfaceContainerHighest = c.bgTertiary,
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
                extraSmall = Radius.xs,
                small = Radius.sm,
                medium = Radius.md,
                large = Radius.lg,
                extraLarge = Radius.xl,
            ),
            content = content,
        )
    }
}
