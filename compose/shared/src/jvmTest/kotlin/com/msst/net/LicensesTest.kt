package com.msst.net

import com.msst.net.resources.Res
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class LicensesTest {
    /** The notices LicensesDialog shows are bundled where it reads them. */
    @Test
    fun thirdPartyNoticesAreBundled() = runTest {
        val text = Res.readBytes("files/THIRD_PARTY_LICENSES.txt").decodeToString()
        assertTrue(text.startsWith("MSST-Net Controller — third-party software notices"))
        assertTrue("Apache License 2.0" in text)
    }
}
