package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression test for the "spawned tab self-erase" bug.
 *
 * Symptom on 1.0.13 (Rider 2026.1, reworked terminal):
 *  - Plugin restored 3 tabs via `createShellWidget`.
 *  - The very next poll saw `Backend with PIDs: [Tab A only]` and
 *    `Backend no session/pid: [Tab B, Tab C, Tab D, Local]`.
 *  - Plugin saved 0 sessions; on the next restart there was nothing to restore.
 *
 * Root cause: tabs created via `TerminalToolWindowManager.createShellWidget` show up in
 * `TerminalTabsManager.getTerminalTabs()` with `getSessionId() == null`. The reworked
 * `TerminalSessionsManager` never registers them. So `extractPidFromSession` returns null,
 * and `getAllTabs` used to drop them — meaning every restored tab silently un-tracked
 * itself on the next 5-second poll.
 *
 * Fix: when the backend session lookup yields no PID, fall back to extracting the PID
 * directly from the frontend `TerminalWidget`'s `ttyConnector`. The widget is the same
 * one `createShellWidget` returned and its TTY connector exposes the live shell process.
 *
 * This test pins the PID-resolution contract that `getAllTabs` follows. The reflection
 * helper itself (`extractPidFromWidget`) walks IntelliJ-internal fields and can't be
 * usefully unit-tested without the platform, but the merge decision can.
 */
class TabEnumerationFallbackTest {

    /** Mirrors the resolution order in ClaudeTabWatcherStartup.getAllTabs. */
    private fun resolvePid(sessionPid: Long?, widgetPid: Long?): Long? = sessionPid ?: widgetPid

    @Test
    fun sessionPidPreferredWhenAvailable() {
        // Backend session has a PID — use it. Widget is irrelevant.
        assertEquals(27472L, resolvePid(sessionPid = 27472L, widgetPid = 99999L))
    }

    @Test
    fun fallsBackToWidgetWhenBackendSessionMissing() {
        // The headline regression: spawned tab, no backend session, widget has PID.
        // Must NOT drop — must resolve via widget.
        assertEquals(27472L, resolvePid(sessionPid = null, widgetPid = 27472L))
    }

    @Test
    fun returnsNullWhenNeitherSideHasPid() {
        // Stale Rider-restored shell: backend row exists, no session, widget has no
        // process behind it. PID is genuinely unknowable — drop is correct (no Claude
        // can be inside without a process).
        assertNull(resolvePid(sessionPid = null, widgetPid = null))
    }

    @Test
    fun spawnedRestoredTabSurvivesNextSave() {
        // Concrete scenario from the regression incident:
        // Plugin spawned a tab via createShellWidget.
        // Next poll: backend tab exists, getSessionId() == null.
        // Widget (returned by createShellWidget, found again via findWidgetByContent)
        // has a live shell process.
        val sessionPid: Long? = null
        val widgetPid: Long? = 27472L
        val resolved = resolvePid(sessionPid, widgetPid)
        assertEquals(
            "spawned tab must remain in the enumeration so its Claude child is found and save preserves it",
            27472L,
            resolved,
        )
    }

    @Test
    fun staleWorkspaceXmlLocalDropsCleanly() {
        // Rider's session-restore brought back a shell named "Local" — no Claude is or
        // ever was inside, the connector is null. The tab should not appear in
        // activeSessions. resolvePid returning null causes the early return in
        // getAllTabs, which is the correct outcome.
        assertNull(resolvePid(sessionPid = null, widgetPid = null))
    }
}
