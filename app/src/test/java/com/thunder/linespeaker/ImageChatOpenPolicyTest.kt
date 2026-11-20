package com.thunder.linespeaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageChatOpenPolicyTest {
    private fun allowed(enabled: Boolean = true, ocr: Boolean = true, access: Boolean = true,
        interactive: Boolean = true, locked: Boolean = false, latest: Boolean = true,
        creator: String? = LineSourcePolicy.OA_PACKAGE, now: Long = 30_000, last: Long? = null) =
        ImageChatOpenPolicy.canOpen(enabled, ocr, access, interactive, locked, latest, creator, now, last)

    @Test fun requiresExplicitOptInAndScreenPermission() {
        assertTrue(allowed())
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(ocr = false))
        assertFalse(allowed(access = false))
    }

    @Test fun neverOpensOnLockedOrDarkScreenOrForOtherApps() {
        assertFalse(allowed(locked = true))
        assertFalse(allowed(interactive = false))
        assertFalse(allowed(creator = "com.other.app"))
        assertFalse(allowed(creator = null))
    }

    @Test fun skipsOlderBatchItemsAndThrottlesRepeatedRequests() {
        assertFalse(allowed(latest = false))
        assertFalse(allowed(last = 20_000))
        assertTrue(allowed(last = 15_000))
        assertFalse(allowed(now = 10_000, last = 20_000))
    }
}
