package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests for [TabCloseClassifier.classify] — the pure decision logic behind the
 * `ContentManagerListener.contentRemoved` handler. These pin the rules that matter for
 * not silently wiping user data on shutdown:
 *
 *   1. While `projectClosing == true`, EVERY close event must be ignored.
 *   2. While `projectClosing == false`, a session mapped via [contentToSid] OR via the
 *      widget-fallback lookup is a user-close.
 *   3. While `projectClosing == false`, a content with no mapped session is just a
 *      non-Claude tab (terminal that wasn't tracked) and is ignored.
 *
 * The shape of the inputs mirrors what the IntelliJ listener will give us at runtime, but
 * the classifier itself is platform-free so these tests don't need BasePlatformTestCase.
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
        // Edge: project shutdown teardown of a non-Claude terminal. Either ignore-result
        // works in production, but we pin IgnoreProjectClosing here so a future refactor
        // that distinguishes shutdown teardown from "real" closes stays consistent.
        val d = TabCloseClassifier.classify(
            projectClosing = true,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.IgnoreProjectClosing, d)
    }

    // ══════════════════════════════════════════════════════════════
    // RULE 2 — !projectClosing + mapped session ⇒ UserClosed
    // ══════════════════════════════════════════════════════════════

    @Test fun normalClose_mapHit_returnsUserClosed() {
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = "sid-A",
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.UserClosed("sid-A"), d)
    }

    @Test fun normalClose_widgetFallback_returnsUserClosed() {
        // Rare race: getAllTabs hasn't populated contentToSid yet but spawnedWidgets
        // already has the widget. Fallback must still recognise this as a user close.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = "sid-B",
        )
        assertEquals(TabCloseClassifier.Decision.UserClosed("sid-B"), d)
    }

    @Test fun normalClose_mapPreferredOverWidgetFallback() {
        // When both sources are populated they must agree in practice, but if they ever
        // diverged the explicit map (populated by getAllTabs after the canonical-id
        // resolve) is the authoritative one.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = "sid-explicit",
            sessionIdFromWidgetFallback = "sid-fallback",
        )
        assertEquals(TabCloseClassifier.Decision.UserClosed("sid-explicit"), d)
    }

    // ══════════════════════════════════════════════════════════════
    // RULE 3 — !projectClosing + no session ⇒ NoMappedSession
    // ══════════════════════════════════════════════════════════════

    @Test fun normalClose_noMappedSession_returnsNoMappedSession() {
        // Non-Claude terminal tab the user closed. We must not record it.
        val d = TabCloseClassifier.classify(
            projectClosing = false,
            sessionIdFromMap = null,
            sessionIdFromWidgetFallback = null,
        )
        assertEquals(TabCloseClassifier.Decision.NoMappedSession, d)
    }

    // ══════════════════════════════════════════════════════════════
    // END-TO-END: shutdown sequence + restart drops nothing
    // ══════════════════════════════════════════════════════════════

    @Test fun shutdownSequence_dropsNoSessions() {
        // Simulate the project-close path: ProjectManagerListener sets projectClosing,
        // then 5 tabs get torn down. None should end up in userClosedSessions.
        val projectClosing = true
        val sids = listOf("a", "b", "c", "d", "e")
        val userClosed = mutableSetOf<String>()
        for (sid in sids) {
            val d = TabCloseClassifier.classify(
                projectClosing = projectClosing,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.UserClosed) userClosed.add(d.sessionId)
        }
        assertEquals("shutdown teardown must leave userClosed empty",
            emptySet<String>(), userClosed)
    }

    @Test fun normalRunSequence_recordsOnlyExplicitCloses() {
        // Simulate: 3 user-closes followed by project shutdown. Only the first 3 count.
        val userClosed = mutableSetOf<String>()

        // Phase 1: user closes tabs a, b, c
        for (sid in listOf("a", "b", "c")) {
            val d = TabCloseClassifier.classify(
                projectClosing = false,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.UserClosed) userClosed.add(d.sessionId)
        }

        // Phase 2: project shutdown — sids d, e get torn down
        for (sid in listOf("d", "e")) {
            val d = TabCloseClassifier.classify(
                projectClosing = true,
                sessionIdFromMap = sid,
                sessionIdFromWidgetFallback = null,
            )
            if (d is TabCloseClassifier.Decision.UserClosed) userClosed.add(d.sessionId)
        }

        assertEquals(setOf("a", "b", "c"), userClosed)
    }
}
