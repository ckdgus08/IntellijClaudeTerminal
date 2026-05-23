package com.claudetabs

/**
 * Decision logic for [com.intellij.ui.content.ContentManagerListener.contentRemoved] events —
 * extracted so it can be exercised without an IntelliJ Project / Content / ToolWindow.
 *
 * The platform's `contentRemoved` callback fires for FAR more cases than "user clicked X":
 *
 *   - User clicked the tab's X button. ✓ Real close.
 *   - User right-clicked → Close Tab / Close Other Tabs / Close All Tabs. ✓ Real close.
 *   - Project shutdown teardown. ✗ NOT a close.
 *   - IDE exit. ✗ NOT a close.
 *   - Tool window re-layout on project open (restored tabs, split panes, popped-out
 *     windows being attached). ✗ NOT a close — but fires looking just like a real close.
 *     This was the bug that ate the user's tabs at 11:18 — the listener fired
 *     contentRemoved for 3 tabs that had just finished restoring and the plugin marked
 *     them as "user-closed" forever.
 *   - Splitter collapse / drag-to-reorder / content-move-to-another-window. ✗ NOT a close.
 *
 * To distinguish real closes from the noise, we layer three filters:
 *
 *   1. **projectClosing** — set by ProjectManagerListener.projectClosing BEFORE the
 *      tool window tears down. When true, every contentRemoved is teardown.
 *   2. **Startup grace period** — for the first ~30 seconds after the plugin attaches
 *      to a project, Rider is re-laying out tool windows (restored tabs, splits,
 *      popouts). All contentRemoved events in this window are NOT user closes.
 *   3. **Defer-and-verify** — even past the grace period, we don't mark immediately.
 *      We defer ~3 seconds and check whether the Claude process for that sessionId is
 *      still alive. If alive → it was a Rider UI shuffle (split move, drag, etc.). If
 *      dead → real user close.
 *
 * This module returns the IMMEDIATE classifier decision. The deferred-verification
 * step happens in [ClaudeTabWatcherStartup.scheduleCloseVerification] which schedules
 * the actual check on a pooled executor.
 */
internal object TabCloseClassifier {

    /** Result of classifying a [contentRemoved] event. */
    sealed class Decision {
        /** Caller should schedule a deferred-verification check (process-alive after a
         *  short delay). Only mark `userClosedSessions` if the verify confirms a real
         *  close. */
        data class DeferAndVerify(val sessionId: String) : Decision()
        /** Ignore — project is shutting down (tear-down events, not user intent). */
        object IgnoreProjectClosing : Decision()
        /** Ignore — within the startup grace period (Rider re-laying out tool windows). */
        object IgnoreStartupGrace : Decision()
        /** Ignore — no session mapped to the closed content (terminal tab wasn't a
         *  tracked Claude session). */
        object NoMappedSession : Decision()
    }

    /**
     * Classify a [contentRemoved] event for immediate handling. The caller is expected
     * to:
     *   - On [Decision.DeferAndVerify], schedule a process-alive check ~3 seconds later
     *     (see [ClaudeTabWatcherStartup.scheduleCloseVerification]) and only then add
     *     the sessionId to `userClosedSessions`.
     *   - On any Ignore decision, no further action.
     *
     * @param projectClosing whether [ClaudeTabWatcherStartup.ProjectCtx.projectClosing]
     *  is set. When true, all close events are project teardown.
     * @param millisSinceStartup how long the plugin has been attached to this project.
     *  Compared against [startupGraceMillis].
     * @param startupGraceMillis grace period (typically 30000 / 30 seconds). Events
     *  inside this window are project setup events, not user closes.
     * @param sessionIdFromMap result of looking up the closed `Content` in `contentToSid`.
     * @param sessionIdFromWidgetFallback secondary lookup via `spawnedWidgets`.
     */
    fun classify(
        projectClosing: Boolean,
        millisSinceStartup: Long,
        startupGraceMillis: Long,
        sessionIdFromMap: String?,
        sessionIdFromWidgetFallback: String?,
    ): Decision {
        if (projectClosing) return Decision.IgnoreProjectClosing
        if (millisSinceStartup < startupGraceMillis) return Decision.IgnoreStartupGrace
        val sid = sessionIdFromMap ?: sessionIdFromWidgetFallback
        return if (sid != null) Decision.DeferAndVerify(sid) else Decision.NoMappedSession
    }
}
