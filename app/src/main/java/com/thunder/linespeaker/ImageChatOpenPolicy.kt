package com.thunder.linespeaker

internal object ImageChatOpenPolicy {
    fun canOpen(
        enabled: Boolean, screenOcrEnabled: Boolean, accessibilityEnabled: Boolean,
        interactive: Boolean, locked: Boolean, latest: Boolean, creatorPackage: String?,
        nowMs: Long, lastOpenMs: Long?
    ): Boolean {
        if (!enabled || !screenOcrEnabled || !accessibilityEnabled || !interactive || locked || !latest) return false
        if (!LineSourcePolicy.accepts(creatorPackage)) return false
        if (lastOpenMs != null && (nowMs < lastOpenMs || nowMs - lastOpenMs < 15_000L)) return false
        return true
    }
}
