package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer 1 unit tests — pure-function detection logic in [AiAgentsDetector.matches].
 *
 * The IntelliJ-API-touching paths (`isActive`, Registry, ToolWindowManager) need a platform
 * fixture and are exercised by the plugin loading on Rider; this suite covers the deterministic
 * decision logic so we can change candidates / normalisation without spinning up the platform.
 */
class AiAgentsDetectorTest {

    // ── Registry-disable shortcut ─────────────────────────────────

    @Test fun matches_returnsFalse_whenRegistryDeferIsOff() {
        // Registry is off → always defer to legacy behavior, regardless of registered AI tool windows.
        assertFalse(
            AiAgentsDetector.matches(
                toolWindowIds = setOf("AI Agents", "Junie"),
                registryDefer = false,
            )
        )
    }

    // ── Tool window ID matching (case-insensitive) ────────────────

    @Test fun matches_detects_toolWindow_caseInsensitive_lower() {
        assertTrue(
            AiAgentsDetector.matches(
                toolWindowIds = setOf("ai agents"),
                registryDefer = true,
            )
        )
    }

    @Test fun matches_detects_toolWindow_caseInsensitive_upper() {
        assertTrue(
            AiAgentsDetector.matches(
                toolWindowIds = setOf("AI AGENTS"),
                registryDefer = true,
            )
        )
    }

    @Test fun matches_detects_known_toolWindow_ids() {
        for (id in AiAgentsDetector.TOOL_WINDOW_CANDIDATES) {
            assertTrue(
                "Expected match for tool window id '$id'",
                AiAgentsDetector.matches(
                    toolWindowIds = setOf(id),
                    registryDefer = true,
                )
            )
        }
    }

    @Test fun matches_returnsFalse_for_unrelated_toolWindow() {
        // Project View, Terminal, Run, Debug — these are not AI tool windows.
        assertFalse(
            AiAgentsDetector.matches(
                toolWindowIds = setOf("Project", "Terminal", "Run", "Debug"),
                registryDefer = true,
            )
        )
    }

    // ── Empty inputs ──────────────────────────────────────────────

    @Test fun matches_returnsFalse_for_empty_inputs() {
        assertFalse(
            AiAgentsDetector.matches(
                toolWindowIds = emptySet(),
                registryDefer = true,
            )
        )
    }

    // ── Mixed (one match among many ignored) ──────────────────────

    @Test fun matches_returnsTrue_when_one_AI_toolwindow_among_many() {
        assertTrue(
            AiAgentsDetector.matches(
                toolWindowIds = setOf("Project", "Terminal", "Light Agent", "Debug"),
                registryDefer = true,
            )
        )
    }
}
