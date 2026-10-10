package com.msst.net.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

fun getLightColorScheme(option: ColorSchemeOption): ColorScheme {
    return when (option) {
        ColorSchemeOption.RED -> lightColorScheme(
            primary = Red700, onPrimary = Color.White,
            primaryContainer = Red100, onPrimaryContainer = Red900,
            secondary = Red500, onSecondary = Color.White,
            secondaryContainer = Red50, onSecondaryContainer = Red900,
            tertiary = Red400, onTertiary = Color.White,
            tertiaryContainer = Red100, onTertiaryContainer = Red900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Red50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Red200,
        )
        ColorSchemeOption.PINK -> lightColorScheme(
            primary = Pink700, onPrimary = Color.White,
            primaryContainer = Pink100, onPrimaryContainer = Pink900,
            secondary = Pink500, onSecondary = Color.White,
            secondaryContainer = Pink50, onSecondaryContainer = Pink900,
            tertiary = Pink400, onTertiary = Color.White,
            tertiaryContainer = Pink100, onTertiaryContainer = Pink900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Pink50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Pink200,
        )
        ColorSchemeOption.PURPLE -> lightColorScheme(
            primary = Purple700, onPrimary = Color.White,
            primaryContainer = Purple100, onPrimaryContainer = Purple900,
            secondary = Purple500, onSecondary = Color.White,
            secondaryContainer = Purple50, onSecondaryContainer = Purple900,
            tertiary = Purple400, onTertiary = Color.White,
            tertiaryContainer = Purple100, onTertiaryContainer = Purple900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Purple50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Purple200,
        )
        ColorSchemeOption.DEEP_PURPLE -> lightColorScheme(
            primary = DeepPurple700, onPrimary = Color.White,
            primaryContainer = DeepPurple100, onPrimaryContainer = DeepPurple900,
            secondary = DeepPurple500, onSecondary = Color.White,
            secondaryContainer = DeepPurple50, onSecondaryContainer = DeepPurple900,
            tertiary = DeepPurple400, onTertiary = Color.White,
            tertiaryContainer = DeepPurple100, onTertiaryContainer = DeepPurple900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = DeepPurple50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = DeepPurple200,
        )
        ColorSchemeOption.INDIGO -> lightColorScheme(
            primary = Indigo700, onPrimary = Color.White,
            primaryContainer = Indigo100, onPrimaryContainer = Indigo900,
            secondary = Indigo500, onSecondary = Color.White,
            secondaryContainer = Indigo50, onSecondaryContainer = Indigo900,
            tertiary = Indigo400, onTertiary = Color.White,
            tertiaryContainer = Indigo100, onTertiaryContainer = Indigo900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Indigo50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Indigo200,
        )
        ColorSchemeOption.BLUE -> lightColorScheme(
            primary = Blue700, onPrimary = Color.White,
            primaryContainer = Blue100, onPrimaryContainer = Blue900,
            secondary = Blue500, onSecondary = Color.White,
            secondaryContainer = Blue50, onSecondaryContainer = Blue900,
            tertiary = Blue400, onTertiary = Color.White,
            tertiaryContainer = Blue100, onTertiaryContainer = Blue900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Blue50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Blue200,
        )
        ColorSchemeOption.LIGHT_BLUE -> lightColorScheme(
            primary = LightBlue700, onPrimary = Color.White,
            primaryContainer = LightBlue100, onPrimaryContainer = LightBlue900,
            secondary = LightBlue500, onSecondary = Color.White,
            secondaryContainer = LightBlue50, onSecondaryContainer = LightBlue900,
            tertiary = LightBlue400, onTertiary = Color.White,
            tertiaryContainer = LightBlue100, onTertiaryContainer = LightBlue900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = LightBlue50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = LightBlue200,
        )
        ColorSchemeOption.CYAN -> lightColorScheme(
            primary = Cyan700, onPrimary = Color.White,
            primaryContainer = Cyan100, onPrimaryContainer = Cyan900,
            secondary = Cyan500, onSecondary = Color.White,
            secondaryContainer = Cyan50, onSecondaryContainer = Cyan900,
            tertiary = Cyan400, onTertiary = Color.White,
            tertiaryContainer = Cyan100, onTertiaryContainer = Cyan900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Cyan50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Cyan200,
        )
        ColorSchemeOption.TEAL -> lightColorScheme(
            primary = Teal700, onPrimary = Color.White,
            primaryContainer = Teal100, onPrimaryContainer = Teal900,
            secondary = Teal500, onSecondary = Color.White,
            secondaryContainer = Teal50, onSecondaryContainer = Teal900,
            tertiary = Teal400, onTertiary = Color.White,
            tertiaryContainer = Teal100, onTertiaryContainer = Teal900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Teal50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Teal200,
        )
        ColorSchemeOption.GREEN -> lightColorScheme(
            primary = Green700, onPrimary = Color.White,
            primaryContainer = Green100, onPrimaryContainer = Green900,
            secondary = Green500, onSecondary = Color.White,
            secondaryContainer = Green50, onSecondaryContainer = Green900,
            tertiary = Green400, onTertiary = Color.White,
            tertiaryContainer = Green100, onTertiaryContainer = Green900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Green50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Green200,
        )
        ColorSchemeOption.LIGHT_GREEN -> lightColorScheme(
            primary = LightGreen700, onPrimary = Color.White,
            primaryContainer = LightGreen100, onPrimaryContainer = LightGreen900,
            secondary = LightGreen500, onSecondary = Color.White,
            secondaryContainer = LightGreen50, onSecondaryContainer = LightGreen900,
            tertiary = LightGreen400, onTertiary = Color.White,
            tertiaryContainer = LightGreen100, onTertiaryContainer = LightGreen900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = LightGreen50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = LightGreen200,
        )
        ColorSchemeOption.LIME -> lightColorScheme(
            primary = Lime700, onPrimary = Color.White,
            primaryContainer = Lime100, onPrimaryContainer = Lime900,
            secondary = Lime500, onSecondary = Grey900,
            secondaryContainer = Lime50, onSecondaryContainer = Lime900,
            tertiary = Lime400, onTertiary = Grey900,
            tertiaryContainer = Lime100, onTertiaryContainer = Lime900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Lime50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Lime200,
        )
        ColorSchemeOption.YELLOW -> lightColorScheme(
            primary = Yellow700, onPrimary = Grey900,
            primaryContainer = Yellow100, onPrimaryContainer = Yellow900,
            secondary = Yellow500, onSecondary = Grey900,
            secondaryContainer = Yellow50, onSecondaryContainer = Yellow900,
            tertiary = Yellow400, onTertiary = Grey900,
            tertiaryContainer = Yellow100, onTertiaryContainer = Yellow900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Yellow50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Yellow200,
        )
        ColorSchemeOption.AMBER -> lightColorScheme(
            primary = Amber700, onPrimary = Grey900,
            primaryContainer = Amber100, onPrimaryContainer = Amber900,
            secondary = Amber500, onSecondary = Grey900,
            secondaryContainer = Amber50, onSecondaryContainer = Amber900,
            tertiary = Amber400, onTertiary = Grey900,
            tertiaryContainer = Amber100, onTertiaryContainer = Amber900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Amber50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Amber200,
        )
        ColorSchemeOption.ORANGE -> lightColorScheme(
            primary = Orange700, onPrimary = Color.White,
            primaryContainer = Orange100, onPrimaryContainer = Orange900,
            secondary = Orange500, onSecondary = Color.White,
            secondaryContainer = Orange50, onSecondaryContainer = Orange900,
            tertiary = Orange400, onTertiary = Grey900,
            tertiaryContainer = Orange100, onTertiaryContainer = Orange900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Orange50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Orange200,
        )
        ColorSchemeOption.DEEP_ORANGE -> lightColorScheme(
            primary = DeepOrange700, onPrimary = Color.White,
            primaryContainer = DeepOrange100, onPrimaryContainer = DeepOrange900,
            secondary = DeepOrange500, onSecondary = Color.White,
            secondaryContainer = DeepOrange50, onSecondaryContainer = DeepOrange900,
            tertiary = DeepOrange400, onTertiary = Color.White,
            tertiaryContainer = DeepOrange100, onTertiaryContainer = DeepOrange900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = DeepOrange50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = DeepOrange200,
        )
        ColorSchemeOption.BROWN -> lightColorScheme(
            primary = Brown700, onPrimary = Color.White,
            primaryContainer = Brown100, onPrimaryContainer = Brown900,
            secondary = Brown500, onSecondary = Color.White,
            secondaryContainer = Brown50, onSecondaryContainer = Brown900,
            tertiary = Brown400, onTertiary = Color.White,
            tertiaryContainer = Brown100, onTertiaryContainer = Brown900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Brown50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Brown200,
        )
        ColorSchemeOption.GREY -> lightColorScheme(
            primary = Grey700, onPrimary = Color.White,
            primaryContainer = Grey200, onPrimaryContainer = Grey900,
            secondary = Grey500, onSecondary = Color.White,
            secondaryContainer = Grey100, onSecondaryContainer = Grey900,
            tertiary = Grey400, onTertiary = Color.White,
            tertiaryContainer = Grey200, onTertiaryContainer = Grey900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = Grey100, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = Grey300,
        )
        ColorSchemeOption.BLUE_GREY -> lightColorScheme(
            primary = BlueGrey700, onPrimary = Color.White,
            primaryContainer = BlueGrey100, onPrimaryContainer = BlueGrey900,
            secondary = BlueGrey500, onSecondary = Color.White,
            secondaryContainer = BlueGrey50, onSecondaryContainer = BlueGrey900,
            tertiary = BlueGrey400, onTertiary = Color.White,
            tertiaryContainer = BlueGrey100, onTertiaryContainer = BlueGrey900,
            error = ErrorLight, onError = OnErrorLight,
            errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
            background = Grey50, onBackground = Grey900,
            surface = Grey50, onSurface = Grey900,
            surfaceVariant = BlueGrey50, onSurfaceVariant = Grey700,
            outline = Grey500, outlineVariant = Grey300,
            inverseSurface = Grey900, inverseOnSurface = Grey100, inversePrimary = BlueGrey200,
        )
    }
}

fun getDarkColorScheme(option: ColorSchemeOption): ColorScheme {
    return when (option) {
        ColorSchemeOption.RED -> darkColorScheme(
            primary = Red200, onPrimary = Red900,
            primaryContainer = Red700, onPrimaryContainer = Red100,
            secondary = Red300, onSecondary = Red900,
            secondaryContainer = Red800, onSecondaryContainer = Red100,
            tertiary = Red400, onTertiary = Red900,
            tertiaryContainer = Red700, onTertiaryContainer = Red100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Red700,
        )
        ColorSchemeOption.PINK -> darkColorScheme(
            primary = Pink200, onPrimary = Pink900,
            primaryContainer = Pink700, onPrimaryContainer = Pink100,
            secondary = Pink300, onSecondary = Pink900,
            secondaryContainer = Pink800, onSecondaryContainer = Pink100,
            tertiary = Pink400, onTertiary = Pink900,
            tertiaryContainer = Pink700, onTertiaryContainer = Pink100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Pink700,
        )
        ColorSchemeOption.PURPLE -> darkColorScheme(
            primary = Purple200, onPrimary = Purple900,
            primaryContainer = Purple700, onPrimaryContainer = Purple100,
            secondary = Purple300, onSecondary = Purple900,
            secondaryContainer = Purple800, onSecondaryContainer = Purple100,
            tertiary = Purple400, onTertiary = Purple900,
            tertiaryContainer = Purple700, onTertiaryContainer = Purple100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Purple700,
        )
        ColorSchemeOption.DEEP_PURPLE -> darkColorScheme(
            primary = DeepPurple200, onPrimary = DeepPurple900,
            primaryContainer = DeepPurple700, onPrimaryContainer = DeepPurple100,
            secondary = DeepPurple300, onSecondary = DeepPurple900,
            secondaryContainer = DeepPurple800, onSecondaryContainer = DeepPurple100,
            tertiary = DeepPurple400, onTertiary = DeepPurple900,
            tertiaryContainer = DeepPurple700, onTertiaryContainer = DeepPurple100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = DeepPurple700,
        )
        ColorSchemeOption.INDIGO -> darkColorScheme(
            primary = Indigo200, onPrimary = Indigo900,
            primaryContainer = Indigo700, onPrimaryContainer = Indigo100,
            secondary = Indigo300, onSecondary = Indigo900,
            secondaryContainer = Indigo800, onSecondaryContainer = Indigo100,
            tertiary = Indigo400, onTertiary = Indigo900,
            tertiaryContainer = Indigo700, onTertiaryContainer = Indigo100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Indigo700,
        )
        ColorSchemeOption.BLUE -> darkColorScheme(
            primary = Blue200, onPrimary = Blue900,
            primaryContainer = Blue700, onPrimaryContainer = Blue100,
            secondary = Blue300, onSecondary = Blue900,
            secondaryContainer = Blue800, onSecondaryContainer = Blue100,
            tertiary = Blue400, onTertiary = Blue900,
            tertiaryContainer = Blue700, onTertiaryContainer = Blue100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Blue700,
        )
        ColorSchemeOption.LIGHT_BLUE -> darkColorScheme(
            primary = LightBlue200, onPrimary = LightBlue900,
            primaryContainer = LightBlue700, onPrimaryContainer = LightBlue100,
            secondary = LightBlue300, onSecondary = LightBlue900,
            secondaryContainer = LightBlue800, onSecondaryContainer = LightBlue100,
            tertiary = LightBlue400, onTertiary = LightBlue900,
            tertiaryContainer = LightBlue700, onTertiaryContainer = LightBlue100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = LightBlue700,
        )
        ColorSchemeOption.CYAN -> darkColorScheme(
            primary = Cyan200, onPrimary = Cyan900,
            primaryContainer = Cyan700, onPrimaryContainer = Cyan100,
            secondary = Cyan300, onSecondary = Cyan900,
            secondaryContainer = Cyan800, onSecondaryContainer = Cyan100,
            tertiary = Cyan400, onTertiary = Cyan900,
            tertiaryContainer = Cyan700, onTertiaryContainer = Cyan100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Cyan700,
        )
        ColorSchemeOption.TEAL -> darkColorScheme(
            primary = Teal200, onPrimary = Teal900,
            primaryContainer = Teal700, onPrimaryContainer = Teal100,
            secondary = Teal300, onSecondary = Teal900,
            secondaryContainer = Teal800, onSecondaryContainer = Teal100,
            tertiary = Teal400, onTertiary = Teal900,
            tertiaryContainer = Teal700, onTertiaryContainer = Teal100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Teal700,
        )
        ColorSchemeOption.GREEN -> darkColorScheme(
            primary = Green200, onPrimary = Green900,
            primaryContainer = Green700, onPrimaryContainer = Green100,
            secondary = Green300, onSecondary = Green900,
            secondaryContainer = Green800, onSecondaryContainer = Green100,
            tertiary = Green400, onTertiary = Green900,
            tertiaryContainer = Green700, onTertiaryContainer = Green100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Green700,
        )
        ColorSchemeOption.LIGHT_GREEN -> darkColorScheme(
            primary = LightGreen200, onPrimary = LightGreen900,
            primaryContainer = LightGreen700, onPrimaryContainer = LightGreen100,
            secondary = LightGreen300, onSecondary = LightGreen900,
            secondaryContainer = LightGreen800, onSecondaryContainer = LightGreen100,
            tertiary = LightGreen400, onTertiary = LightGreen900,
            tertiaryContainer = LightGreen700, onTertiaryContainer = LightGreen100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = LightGreen700,
        )
        ColorSchemeOption.LIME -> darkColorScheme(
            primary = Lime200, onPrimary = Lime900,
            primaryContainer = Lime700, onPrimaryContainer = Lime100,
            secondary = Lime300, onSecondary = Lime900,
            secondaryContainer = Lime800, onSecondaryContainer = Lime100,
            tertiary = Lime400, onTertiary = Lime900,
            tertiaryContainer = Lime700, onTertiaryContainer = Lime100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Lime700,
        )
        ColorSchemeOption.YELLOW -> darkColorScheme(
            primary = Yellow200, onPrimary = Yellow900,
            primaryContainer = Yellow700, onPrimaryContainer = Yellow100,
            secondary = Yellow300, onSecondary = Yellow900,
            secondaryContainer = Yellow800, onSecondaryContainer = Yellow100,
            tertiary = Yellow400, onTertiary = Yellow900,
            tertiaryContainer = Yellow700, onTertiaryContainer = Yellow100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Yellow700,
        )
        ColorSchemeOption.AMBER -> darkColorScheme(
            primary = Amber200, onPrimary = Amber900,
            primaryContainer = Amber700, onPrimaryContainer = Amber100,
            secondary = Amber300, onSecondary = Amber900,
            secondaryContainer = Amber800, onSecondaryContainer = Amber100,
            tertiary = Amber400, onTertiary = Amber900,
            tertiaryContainer = Amber700, onTertiaryContainer = Amber100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Amber700,
        )
        ColorSchemeOption.ORANGE -> darkColorScheme(
            primary = Orange200, onPrimary = Orange900,
            primaryContainer = Orange700, onPrimaryContainer = Orange100,
            secondary = Orange300, onSecondary = Orange900,
            secondaryContainer = Orange800, onSecondaryContainer = Orange100,
            tertiary = Orange400, onTertiary = Orange900,
            tertiaryContainer = Orange700, onTertiaryContainer = Orange100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Orange700,
        )
        ColorSchemeOption.DEEP_ORANGE -> darkColorScheme(
            primary = DeepOrange200, onPrimary = DeepOrange900,
            primaryContainer = DeepOrange700, onPrimaryContainer = DeepOrange100,
            secondary = DeepOrange300, onSecondary = DeepOrange900,
            secondaryContainer = DeepOrange800, onSecondaryContainer = DeepOrange100,
            tertiary = DeepOrange400, onTertiary = DeepOrange900,
            tertiaryContainer = DeepOrange700, onTertiaryContainer = DeepOrange100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = DeepOrange700,
        )
        ColorSchemeOption.BROWN -> darkColorScheme(
            primary = Brown200, onPrimary = Brown900,
            primaryContainer = Brown700, onPrimaryContainer = Brown100,
            secondary = Brown300, onSecondary = Brown900,
            secondaryContainer = Brown800, onSecondaryContainer = Brown100,
            tertiary = Brown400, onTertiary = Brown900,
            tertiaryContainer = Brown700, onTertiaryContainer = Brown100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Brown700,
        )
        ColorSchemeOption.GREY -> darkColorScheme(
            primary = Grey300, onPrimary = Grey900,
            primaryContainer = Grey700, onPrimaryContainer = Grey200,
            secondary = Grey400, onSecondary = Grey900,
            secondaryContainer = Grey700, onSecondaryContainer = Grey200,
            tertiary = Grey500, onTertiary = Grey900,
            tertiaryContainer = Grey700, onTertiaryContainer = Grey200,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = Grey600,
        )
        ColorSchemeOption.BLUE_GREY -> darkColorScheme(
            primary = BlueGrey200, onPrimary = BlueGrey900,
            primaryContainer = BlueGrey700, onPrimaryContainer = BlueGrey100,
            secondary = BlueGrey300, onSecondary = BlueGrey900,
            secondaryContainer = BlueGrey800, onSecondaryContainer = BlueGrey100,
            tertiary = BlueGrey400, onTertiary = BlueGrey900,
            tertiaryContainer = BlueGrey700, onTertiaryContainer = BlueGrey100,
            error = ErrorDark, onError = OnErrorDark,
            errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
            background = Grey900, onBackground = Grey100,
            surface = Grey900, onSurface = Grey100,
            surfaceVariant = Grey800, onSurfaceVariant = Grey300,
            outline = Grey500, outlineVariant = Grey700,
            inverseSurface = Grey100, inverseOnSurface = Grey900, inversePrimary = BlueGrey700,
        )
    }
}

@Composable
fun MsstNetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    colorSchemeOption: ColorSchemeOption = ColorSchemeOption.LIGHT_BLUE,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> getDarkColorScheme(colorSchemeOption)
        else -> getLightColorScheme(colorSchemeOption)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}