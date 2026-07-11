package dev.gold.mdvault.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentWebViewPolicyTest {

    @Test
    fun `only a user gesture in the main frame can open an external web URL`() {
        assertTrue(shouldLaunchExternalNavigation("https", isForMainFrame = true, hasGesture = true))
        assertTrue(shouldLaunchExternalNavigation("HTTP", isForMainFrame = true, hasGesture = true))
        assertFalse(shouldLaunchExternalNavigation("https", isForMainFrame = true, hasGesture = false))
        assertFalse(shouldLaunchExternalNavigation("https", isForMainFrame = false, hasGesture = true))
        assertFalse(shouldLaunchExternalNavigation("javascript", isForMainFrame = true, hasGesture = true))
        assertFalse(shouldLaunchExternalNavigation(null, isForMainFrame = true, hasGesture = true))
    }
}
