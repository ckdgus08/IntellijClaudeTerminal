package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests for [TabCloseClassifier.classify] — the pure decision logic behind the
 * `ContentManagerListener.contentRemoved` handler. These pin the rules that matter for
 * not silently wiping user data on startup or shutdown:
 *
 *   1. While `projectClosing == true`, EVERY close event must be ignored.
 *   2. Within the startup grace period, EVERY close event must be ignored (Rider is
 *      re-laying out tool windows — restored tabs being attached, splits, popouts).
 *      This was the root cause of the missing-tabs bug.
 *   3. Past the startup grace period, a close event with a mapped session is a
 *      [Decision.DeferAndVerify] — the orchestration in
 *      [ClaudeTabWatcherStartup.scheduleCloseVerification] then does the
 *      process-alive check before recording.
 *   4. A close event with no mapped session is just a non-Claude terminal tab and
 *      is ignored.
 *
 * The shape of the inputs mirrors what the IntelliJ listener will give us at runtime, but
 * the classifier itself is platform-free so these tests don't need BasePlatformTestCase.
 */
class TabCloseClassifierContractTest {

    private val grace = 30_000L
    private val pastGrace = 31_000L  // any value > grace
    private val insideGrace = 5_000L  // any value < grace

    // ══════════════════════════════════════════════════════════════
    // RULE 1 — projectClosing short-circuits everything (highest precedence)
    // ══════════════════════════════════════════════════════════════

    @Test fun projectClosing_ignoresMappedSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid-1",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    @Test fun projectClosing_ignoresWidgetFallbackSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = "sid-2",
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    @Test fun projectClosing_ignoresEvenIfBothSourcesAgree() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid-3",
            sessionIdFromWidgetFallback = "sid-3",
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    @Test fun projectClosing_ignoresEvenWithNoMappedSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    @Test fun projectClosing_winsOverStartupGrace() {
        // Edge: project shutdown that happens during the grace period. The earlier filter
        // (projectClosing) should take precedence so logs show the right reason.
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            millisSinceStartup = insideGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    // ══════════════════════════════════════════════════════════════
    // RULE 2 — startup grace ignores close events (THE BUG FIX)
    // ══════════════════════════════════════════════════════════════

    @Test fun insideStartupGrace_mapHit_ignored() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = insideGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid-A",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreStartupGrace, d)
    }

    @Test fun insideStartupGrace_widgetFallback_ignored() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = insideGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = "sid-B",
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreStartupGrace, d)
    }

    @Test fun insideStartupGrace_noSession_stillIgnored() {
        // No mapped session inside grace: still report grace ignore, not NoMappedSession.
        // The grace reason is more informative for debugging the missing-tabs class of bugs.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = insideGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreStartupGrace, d)
    }

    @Test fun atExactGraceBoundary_stillIgnored() {
        // millisSinceStartup == grace is the boundary. The classifier treats < grace as
        // inside (strict less-than), so exactly equal is OUT. Pin this so a future refactor
        // doesn't accidentally flip it to <= and shorten the grace by an instant.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = grace,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("sid"), d)
    }

    @Test fun oneMsBeforeGraceBoundary_ignored() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = grace - 1,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreStartupGrace, d)
    }

    // ══════════════════════════════════════════════════════════════
    // RULE 3 — past grace + mapped session ⇒ DeferAndVerify (orchestration verifies)
    // ══════════════════════════════════════════════════════════════

    @Test fun pastGrace_mapHit_returnsDeferAndVerify() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid-A",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("sid-A"), d)
    }

    @Test fun pastGrace_widgetFallback_returnsDeferAndVerify() {
        // Rare race: getAllTabs hasn't populated contentToSid yet but spawnedWidgets
        // already has the widget. Fallback must still recognise this as a close.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = "sid-B",
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("sid-B"), d)
    }

    @Test fun pastGrace_mapPreferredOverWidgetFallback() {
        // When both sources are populated they must agree in practice, but if they ever
        // diverged the explicit map (populated by getAllTabs after the canonical-id
        // resolve) is the authoritative one.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = "sid-explicit",
            sessionIdFromWidgetFallback = "sid-fallback",
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("sid-explicit"), d)
    }

    // ══════════════════════════════════════════════════════════════
    // RULE 4 — past grace + no session ⇒ NoMappedSession
    // ══════════════════════════════════════════════════════════════

    @Test fun pastGrace_noMappedSession_returnsNoMappedSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            millisSinceStartup = pastGrace,
            startupGraceMillis = grace,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.NoMappedSession, d)
    }

    // ══════════════════════════════════════════════════════════════
    // END-TO-END: startup re-layout drops nothing
    // ══════════════════════════════════════════════════════════════

    @Test fun startupReLayoutSequence_dropsNoSessions() {
        // Simulate the bug: 5 tabs get contentRemoved at startup (Rider re-layout). None
        // should ever reach a state where they'd be added to userClosedSessions.
        val sids = listOf("a", "b", "c", "d", "e")
        val wouldDefer = mutableSetOf<String>()
        for (sid in sids) {
            val d = TabCloseClassifier.classify(
                projectClosing = false,
                millisSinceStartup = 2_500L,  // 2.5s after attach — well within grace
                startupGraceMillis = grace,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.DeferAndVerify) wouldDefer.add(d.sessionId)
        }
        assertEquals("startup re-layout must produce zero DeferAndVerify (would-be closes)",
            emptySet<String>(), wouldDefer)
    }

    @Test fun shutdownSequence_dropsNoSessions() {
        // Simulate the project-close path: ProjectManagerListener sets projectClosing,
        // then 5 tabs get torn down. None should reach DeferAndVerify.
        val sids = listOf("a", "b", "c", "d", "e")
        val wouldDefer = mutableSetOf<String>()
        for (sid in sids) {
            val d = TabCloseClassifier.classify(
                projectClosing = true,
                millisSinceStartup = pastGrace,
                startupGraceMillis = grace,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.DeferAndVerify) wouldDefer.add(d.sessionId)
        }
        assertEquals("shutdown teardown must produce zero DeferAndVerify",
            emptySet<String>(), wouldDefer)
    }

    @Test fun normalUserCloseSequence_yieldsDeferDecisions() {
        // Simulate normal usage: well past startup, user closes 3 tabs by clicking X.
        // Each one should be a DeferAndVerify (the orchestration then runs the alive-check).
        val sids = listOf("a", "b", "c")
        val deferred = mutableSetOf<String>()
        for (sid in sids) {
            val d = TabCloseClassifier.classify(
                projectClosing = false,
                millisSinceStartup = 60_000L,  // 1 minute in
                startupGraceMillis = grace,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.DeferAndVerify) deferred.add(d.sessionId)
        }
        assertEquals(setOf("a", "b", "c"), deferred)
    }
}
