package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is allowed to overwrite a tab's name.
 *
 * The conversation-derived name has to reach three places it previously didn't — a tab
 * restored on startup, a terminal the user opened by hand, and a tab whose conversation was
 * replaced by `/clear` — without ever touching a name the user typed.
 *
 * The ordering that makes that safe, strongest first:
 *
 *  1. `names.json` with `setBy=user`. Both `/tab` and right-click → Rename Session persist
 *     through `upsertName(…, "user")`, so this one entry covers every manual surface. It is
 *     checked before anything else and ends the decision.
 *  2. A title that is generic, blank, or an AI-overlay artefact — nobody chose it.
 *  3. A title the plugin itself last applied. Replacing our own is how a name follows the
 *     conversation across `/clear`.
 *
 * Anything else is someone's deliberate choice arriving by a route we don't model, and is
 * left alone.
 */
class NamePriorityTest {

    /** The decision as [ClaudeTabWatcherStartup.applyConversationNames] makes it. */
    private fun mayRename(
        current: String?,
        userChosen: String? = null,
        lastAppliedByUs: String? = null,
        derived: String = "새 대화 이름",
        projectName: String = "projects",
    ): Boolean {
        if (userChosen != null) return false
        if (current == derived) return false
        return current.isNullOrBlank() ||
            ClaudeTabsHelpers.isGenericTabName(current) ||
            ClaudeTabsHelpers.isAiOverlayName(current, projectName) ||
            lastAppliedByUs == current
    }

    // ── The name must land ────────────────────────────────────────

    @Test fun namesATerminalTheUserJustOpened() {
        // The default tab name, in whatever language the IDE runs in.
        assertTrue(mayRename(current = "로컬"))
        assertTrue(mayRename(current = "Local"))
        assertTrue(mayRename(current = "Local (2)"))
    }

    @Test fun namesATabThatHasNoTitleYet() {
        assertTrue(mayRename(current = null))
        assertTrue(mayRename(current = "   "))
    }

    /**
     * After `/clear` the tab still shows the *previous* conversation's name — which the
     * plugin put there. Recognising it as ours is the only thing that lets the new
     * conversation's name replace it.
     */
    @Test fun replacesItsOwnNameAfterAClear() {
        val ours = "이전 대화 이름"
        assertTrue(mayRename(current = ours, lastAppliedByUs = ours))
    }

    // ── The name must not land ────────────────────────────────────

    @Test fun neverOverwritesANameTheUserTyped() {
        // Strongest rule in the system: true regardless of what the tab currently shows.
        assertFalse(mayRename(current = "내 탭", userChosen = "내 탭"))
        assertFalse(mayRename(current = "로컬", userChosen = "내 탭"))
        assertFalse(mayRename(current = null, userChosen = "내 탭"))
        assertFalse(mayRename(current = "내 탭", userChosen = "내 탭", lastAppliedByUs = "내 탭"))
    }

    /**
     * A title that is neither generic nor ours came from somewhere we don't model. Assuming
     * we may overwrite it is how a manual rename gets lost.
     */
    @Test fun leavesUnrecognisedTitlesAlone() {
        assertFalse(mayRename(current = "backend deploy"))
        assertFalse(mayRename(current = "이전 대화 이름", lastAppliedByUs = "다른 이름"))
    }

    @Test fun doesNotRewriteTheNameItAlreadyApplied() {
        // Same value: a no-op that would otherwise churn the title on every poll.
        assertFalse(mayRename(current = "새 대화 이름", derived = "새 대화 이름"))
    }

    /**
     * A directory name that merely starts with the default one is a real name. Treating it
     * as generic would make the plugin overwrite it.
     */
    @Test fun doesNotMistakeARealNameForTheDefault() {
        assertFalse(mayRename(current = "로컬 서버 디버깅"))
        assertFalse(mayRename(current = "Local overrides"))
    }
}
