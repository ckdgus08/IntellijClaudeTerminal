package com.claudetabs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Claude's 60-second idle nudge is not someone waiting on you.
 *
 * Reproduced with timestamps from a live session that had finished its turn:
 *
 *   22:22:11  sessions/39694.json  status=idle          ← the turn ended
 *   22:23:11  status/a6049e87.json Notification         ← exactly 60s later
 *   22:26:15  a6049e87:WAITING(hook=Notification,claude=idle)
 *
 * The tab showed ✓, flipped to ⚠ a minute later, and stayed there. Nothing had
 * happened — Claude fires `notification_type: "idle_prompt"` with the message
 * "Claude is waiting for your input" after `messageIdleNotifThresholdMs`.
 *
 * The event is dropped rather than reinterpreted, because the status file holds one edge
 * per session: recording it would overwrite the `Stop` underneath, leaving nothing to fall
 * back to. Both layers are covered here — the script that declines to write it, and the
 * resolver, for files written before the script knew better.
 */
class IdleNotificationTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── The resolver ──────────────────────────────────────────────

    @Test fun theIdleNudgeEstablishesNothing() {
        assertNull(StatusResolver.fromHookEvent("Notification", notificationType = StatusResolver.IDLE_NOTIFICATION))
    }

    /** Every other notification is still someone waiting on you — that's the whole feature. */
    @Test fun realNotificationsStillMeanWaiting() {
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification"))
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification", notificationType = "worker_permission_prompt"))
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification", notificationType = "agent_needs_input"))
        assertEquals(ClaudeStatus.WAITING, StatusResolver.fromHookEvent("Notification", notificationType = ""))
    }

    /**
     * The exact reproduction: a finished turn, then the nudge. With the nudge establishing
     * nothing the session file decides, and its `idle` must not be read as "never ran a
     * turn" — that is what rule 3 of the resolver is for.
     */
    @Test fun aFinishedTurnStaysFinishedThroughTheNudge() {
        val nudge = StatusResolver.HookSignal("Notification", ts = 2_000, notificationType = StatusResolver.IDLE_NOTIFICATION)
        val idle = StatusResolver.SessionSignal("idle", statusUpdatedAt = 1_000, alive = true)
        assertEquals(ClaudeStatus.IDLE, StatusResolver.resolve(nudge, idle))

        // …and with the Stop still on record — which is the state the script's filter
        // preserves — it reads as finished.
        val stop = StatusResolver.HookSignal("Stop", ts = 1_500)
        assertEquals(ClaudeStatus.FINISHED, StatusResolver.resolve(stop, idle))
    }

    // ── The store ─────────────────────────────────────────────────

    @Test fun readsTheNotificationTypeOffDisk() {
        val home = tmp.root
        val statusDir = File(home, "intellij-claude-terminal/status").apply { mkdirs() }
        File(home, "sessions").mkdirs()
        val sid = "a6049e87-25a9-4e5e-82af-537607e2b5bf"
        File(statusDir, "$sid.json").writeText(
            """{"event":"Notification","source":"","notificationType":"idle_prompt","sessionId":"$sid","ts":1786195391622,"pid":39694}"""
        )
        // Nothing else says anything, so an ignored event leaves the session unreadable
        // rather than reporting a state it can't know.
        assertTrue(ClaudeStatusStore(home).snapshot { true }.isEmpty())
    }

    // ── The hook script ───────────────────────────────────────────

    /**
     * The filter has to live in the script, so this asserts the shipped resource still has
     * it. Losing it silently reintroduces the bug, and only on a machine that has been idle
     * for a minute.
     */
    @Test fun theShippedHookDeclinesToRecordTheNudge() {
        val script = javaClass.classLoader
            .getResourceAsStream("claude-integration/status-hook.sh")!!
            .bufferedReader().readText()
        assertTrue("must read notification_type", script.contains("notification_type"))
        assertTrue("must record it for the plugin", script.contains("\\\"notificationType\\\""))
        assertTrue("must special-case the nudge", script.contains("idle_prompt"))
        assertTrue("must guard the status write", script.contains("SKIP_STATUS_WRITE"))
        // The filter is an allowlist, so the blocking types have to be in it by name. The
        // deny-list version that only knew about the nudge let `agent_completed` and the
        // rest through — the same bug with a different type. See [NotificationTypeTest].
        for (blocking in StatusResolver.BLOCKING_NOTIFICATIONS) {
            assertTrue("must keep recording $blocking", script.contains(blocking))
        }
        // SessionEnd's reason is what tells a `/clear` hand-over from a real exit.
        assertTrue("must record the SessionEnd reason", script.contains("\\\"reason\\\""))
        // The TERM_SESSION_ID bridge is a mapping, not a status, and must keep being
        // refreshed by every event including this one.
        val bridgeWrite = script.substringAfter("termsess-")
        assertTrue("bridge write must not be guarded", !bridgeWrite.contains("SKIP_STATUS_WRITE"))
    }
}
