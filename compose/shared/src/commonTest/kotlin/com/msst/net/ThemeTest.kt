package com.msst.net

import com.msst.net.ui.theme.DarkColors
import com.msst.net.ui.theme.LightColors
import com.msst.net.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeTest {
    @Test
    fun storedThemeModeRoundTrips() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.parse(it.key)) }
    }

    @Test
    fun missingOrUnknownThemeFollowsTheSystem() {
        assertEquals(ThemeMode.System, ThemeMode.parse(null))
        assertEquals(ThemeMode.System, ThemeMode.parse(""))
        assertEquals(ThemeMode.System, ThemeMode.parse("sepia"))
    }

    @Test
    fun colourSetsMatchTheirMode() {
        assertFalse(LightColors.dark)
        assertTrue(DarkColors.dark)
    }
}
