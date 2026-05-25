package com.claudetabs

internal object TwoSignalCloseDetector {

    sealed class Signal1 {
        object SkipProjectClosing : Signal1()
        object SkipTemporary : Signal1()
        object SkipNoSid : Signal1()
        data class AddToPending(val sid: String) : Signal1()
    }

    fun decideOnRemoveQuery(
        projectClosing: Boolean,
        isTemporary: Boolean,
        sid: String?,
    ): Signal1 {
        if (projectClosing) return Signal1.SkipProjectClosing
        if (isTemporary) return Signal1.SkipTemporary
        if (sid == null) return Signal1.SkipNoSid
        return Signal1.AddToPending(sid)
    }

    data class ConfirmResult(
        val confirmed: Set<String>,
        val expired: Set<String>,
        val kept: Set<String>,
    )

    val PENDING_EXPIRY_MS: Long = 30000L

    fun confirmPending(
        pendingClose: Map<String, Long>,
        aliveSids: Set<String>,
        now: Long,
        expiryMs: Long = PENDING_EXPIRY_MS,
    ): ConfirmResult {
        val confirmed = mutableSetOf<String>()
        val expired = mutableSetOf<String>()
        val kept = mutableSetOf<String>()
        for ((sid, addedAt) in pendingClose) {
            val age = now - addedAt
            if (sid !in aliveSids) {
                confirmed.add(sid)
            } else if (age > expiryMs) {
                expired.add(sid)
            } else {
                kept.add(sid)
            }
        }
        return ConfirmResult(confirmed, expired, kept)
    }
}
