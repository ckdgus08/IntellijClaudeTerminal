package com.claudetabs

/**
 * Decision logic for [com.intellij.ui.content.ContentManagerListener.contentRemoved] events —
 * extracted so it can be exercised without an IntelliJ Project / Content / ToolWindow.
 *
 * The platform's [com.intellij.ui.content.ContentManagerListener.contentRemoved] callback
 * fires for every tab close, including:
 *   - User clicked the tab's X button.
 *   - User right-clicked → Close Tab / Close Other Tabs / Close All Tabs.
 *   - The project is shutting down and the tool window is tearing its tabs down.
 *   - The IDE is exiting.
 *
 * We want to record ONLY the first two as user-initiated closes — those are the ones the
 * user expects to exclude the session from the next-restart restore. Project-shutdown
 * removals must NOT mark sessions as user-closed; otherwise closing Rider would silently
 * delete the user's saved sessions.
 *
 * The orchestration in [ClaudeTabWatcherStartup] sets [Decision] inputs from real
 * Project / Content / widget objects; this module just applies the rule.
 */
internal object TabCloseClassifier {

    /** Result of classifying a [contentRemoved] event. */
    sealed class Decision {
        /** This is a user-initiated close — record [sessionId] in `userClosedSessions`. */
        data class UserClosed(val sessionId: String) : Decision()
        /** Ignore — project is shutting down (tear-down events, not user intent). */
        object IgnoreProjectClosing : Decision()
        /** Ignore — no session mapped to the closed content (terminal tab wasn't a tracked
         *  Claude session). */
        object NoMappedSession : Decision()
    }

    /**
     * Classify a [contentRemoved] event.
     *
     * @param projectClosing whether [ClaudeTabWatcherStartup.ProjectCtx.projectClosing] is set.
     *  When true, all close events are project teardown and MUST be ignored.
     * @param sessionIdFromMap the result of looking up the closed `Content` in the
     *  `contentToSid` map (the primary source of truth — populated by getAllTabs).
     * @param sessionIdFromWidgetFallback the result of the secondary lookup that walks
     *  `spawnedWidgets` matching the widget by content. Used as a fallback for the rare race
     *  where contentRemoved fires before getAllTabs has populated the map for a tab we just
     *  spawned.
     */
    fun classify(
        projectClosing: Boolean,
        sessionIdFromMap: String?,
        sessionIdFromWidgetFallback: String?,
    ): Decision {
        if (projectClosing) return Decision.IgnoreProjectClosing
        val sid = sessionIdFromMap ?: sessionIdFromWidgetFallback
        return if (sid != null) Decision.UserClosed(sid) else Decision.NoMappedSession
    }
}
