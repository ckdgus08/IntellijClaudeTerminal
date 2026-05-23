package com.claudetabs

/**
 * Decision logic for [com.intellij.ui.content.ContentManagerListener.contentRemoved] events —
 * extracted so it can be exercised without an IntelliJ Project / Content / ToolWindow.
 *
 * The platform's `contentRemoved` callback fires for FAR more cases than "user clicked X":
 *
 *   - User clicked the tab's X button. ✓ Real close.
 *   - User right-clicked → Close Tab / Close Other Tabs / Close All Tabs. ✓ Real close.
 *   - Project shutdown teardown. ✗ NOT a close (filtered by [projectClosing]).
 *   - IDE exit. ✗ NOT a close (filtered by [projectClosing]).
 *   - Tool window re-layout: tab moves between split panes / popouts. The platform fires
 *     contentRemoved on the old Content AND contentAdded on a new Content backed by the
 *     SAME TerminalWidget. ✗ NOT a close.
 *   - Splitter collapse / drag-to-reorder. Same shape as the re-layout case. ✗ NOT a close.
 *
 * This module classifies the IMMEDIATE event into one of three decisions:
 *
 *   1. [Decision.IgnoreProjectClosing] — project is in shutdown teardown.
 *   2. [Decision.NoMappedSession] — the closed Content wasn't a tracked Claude session.
 *   3. [Decision.DeferAndVerify] — looks like a close; orchestration must verify by
 *      checking whether the captured widget is still attached to any Content in the
 *      tool window (UI shuffle vs. genuine close — see
 *      [ClaudeTabWatcherStartup.scheduleCloseVerification]).
 *
 * **Why no time-based startup grace anymore:** Earlier builds ignored every contentRemoved
 * within the first 30 seconds after the plugin attached, to suppress re-layout shuffles
 * during restore. That over-shoots — a user who clicks X on a just-restored tab within
 * those 30 seconds had their close silently dropped, so the tab came back forever. The
 * widget-attachment check in the verify step distinguishes shuffles from closes regardless
 * of when the event fires, so we no longer need a blanket time filter.
 */
internal object TabCloseClassifier {

    /** Result of classifying a [contentRemoved] event. */
    sealed class Decision {
        /** Caller should schedule a deferred verification check (widget-attachment after a
         *  short delay). Only record `userClosedSessions` if the verify confirms a real
         *  close. */
        data class DeferAndVerify(val sessionId: String) : Decision()
        /** Ignore — project is shutting down (tear-down events, not user intent). */
        object IgnoreProjectClosing : Decision()
        /** Ignore — no session mapped to the closed content (terminal tab wasn't a
         *  tracked Claude session). */
        object NoMappedSession : Decision()
    }

    /**
     * Classify a [contentRemoved] event for immediate handling. The caller is expected
     * to:
     *   - On [Decision.DeferAndVerify], schedule a widget-attachment check ~1.5 seconds
     *     later and only then add the sessionId to `userClosedSessions`.
     *   - On any Ignore decision, no further action.
     *
     * @param projectClosing whether [ClaudeTabWatcherStartup.ProjectCtx.projectClosing]
     *  is set. When true, all close events are project teardown.
     * @param sessionIdFromMap result of looking up the closed `Content` in `contentToSid`.
     * @param sessionIdFromWidgetFallback secondary lookup via `spawnedWidgets`.
     */
    fun classify(
        projectClosing: Boolean,
        sessionIdFromMap: String?,
        sessionIdFromWidgetFallback: String?,
    ): Decision {
        if (projectClosing) return Decision.IgnoreProjectClosing
        val sid = sessionIdFromMap ?: sessionIdFromWidgetFallback
        return if (sid != null) Decision.DeferAndVerify(sid) else Decision.NoMappedSession
    }
}
