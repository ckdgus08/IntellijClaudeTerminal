package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests for [TabCloseClassifier.classify] — the pure decision logic behind the
 * `ContentManagerListener.contentRemoved` handler. The classifier is intentionally minimal:
 * the shuffle-vs-real-close discrimination happens in
 * [ClaudeTabWatcherStartup.scheduleCloseVerification] via the widget-attachment check, so
 * the classifier itself only filters the obvious teardown case and routes everything else
 * to the deferred verify.
 *
 * Pinned rules:
 *
 *   1. While `projectClosing == true`, EVERY close event is teardown — ignore.
 *   2. Past the project-closing filter, a close event with no mapped session is just a
 *      non-Claude terminal tab and is ignored as [Decision.NoMappedSession].
 *   3. Past the project-closing filter, a close event with a resolvable session id is
 *      always a [Decision.DeferAndVerify]. The orchestration decides shuffle-vs-real-close
 *      via the widget-attachment verify step.
 *
 * There is intentionally no time-based startup grace. Earlier builds had one and it was
 * the root cause of a bug where users who clicked X on a just-restored tab within ~30
 * seconds of restart had their close silently dropped, leaving the tab to come back
 * forever. The widget-attachment verify catches re-layout shuffles regardless of when
 * they fire, so we don't need the blanket time filter.
 */
class TabCloseClassifierContractTest {

    // ══════════════════════════════════════════════════════════════
    // RULE 1 — projectClosing short-circuits everything
    // ══════════════════════════════════════════════════════════════

    @Test fun projectClosing_ignoresMappedSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            sessionIdFromMap = "sid-1",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    @Test fun projectClosing_ignoresWidgetFallbackSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = "sid-2",
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    @Test fun projectClosing_ignoresEvenIfBothSourcesAgree() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            sessionIdFromMap = "sid-3",
            sessionIdFromWidgetFallback = "sid-3",
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    @Test fun projectClosing_ignoresEvenWithNoMappedSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    // ══════════════════════════════════════════════════════════════
    // RULE 2 — no mapped session ⇒ NoMappedSession
    // ══════════════════════════════════════════════════════════════

    @Test fun noMappedSession_returnsNoMappedSession() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.NoMappedSession, d)
    }

    // ══════════════════════════════════════════════════════════════
    // RULE 3 — mapped session ⇒ DeferAndVerify (orchestration verifies)
    // ══════════════════════════════════════════════════════════════

    @Test fun mapHit_returnsDeferAndVerify() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = "sid-A",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("sid-A"), d)
    }

    @Test fun widgetFallback_returnsDeferAndVerify() {
        // Rare race: getAllTabs hasn't populated contentToSid yet but spawnedWidgets
        // already has the widget. Fallback must still recognise this as a candidate close.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = "sid-B",
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("sid-B"), d)
    }

    @Test fun mapPreferredOverWidgetFallback() {
        // When both sources are populated they must agree in practice, but if they ever
        // diverged the explicit map (populated by getAllTabs after the canonical-id
        // resolve) is the authoritative one.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = "sid-explicit",
            sessionIdFromWidgetFallback = "sid-fallback",
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("sid-explicit"), d)
    }

    // ══════════════════════════════════════════════════════════════
    // BUG REGRESSION — a user closing a tab immediately after restart
    // ══════════════════════════════════════════════════════════════

    @Test fun fastCloseRightAfterRestart_yieldsDeferDecision() {
        // The regression we're guarding against: tab auto-restored a few seconds ago,
        // user clicks X almost immediately. Earlier classifier returned IgnoreStartupGrace
        // here and the close was silently dropped — the tab came back on every restart.
        // The new classifier has no time-based grace, so this is a normal DeferAndVerify
        // and the widget-attachment verify decides whether it's a real close.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = "just-restored-sid",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.DeferAndVerify("just-restored-sid"), d)
    }

    // ══════════════════════════════════════════════════════════════
    // END-TO-END: shutdown sequence drops nothing
    // ══════════════════════════════════════════════════════════════

    @Test fun shutdownSequence_dropsNoSessions() {
        // ProjectManagerListener sets projectClosing, then 5 tabs get torn down. None
        // should reach DeferAndVerify (those are project teardown, not user closes).
        val sids = listOf("a", "b", "c", "d", "e")
        val wouldDefer = mutableSetOf<String>()
        for (sid in sids) {
            val d = TabCloseClassifier.classify(
                projectClosing = true,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.DeferAndVerify) wouldDefer.add(d.sessionId)
        }
        assertEquals("shutdown teardown must produce zero DeferAndVerify",
            emptySet<String>(), wouldDefer)
    }

    @Test fun normalUserCloseSequence_yieldsDeferDecisions() {
        // User closes 3 tabs by clicking X. Each one must be a DeferAndVerify so the
        // orchestration runs the widget-attachment check before recording.
        val sids = listOf("a", "b", "c")
        val deferred = mutableSetOf<String>()
        for (sid in sids) {
            val d = TabCloseClassifier.classify(
                projectClosing = false,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.DeferAndVerify) deferred.add(d.sessionId)
        }
        assertEquals(setOf("a", "b", "c"), deferred)
    }
}
