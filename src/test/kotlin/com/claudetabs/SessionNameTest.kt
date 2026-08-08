package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Using Claude's own name for a session as the tab name.
 *
 * Claude Code keeps a `name` in `~/.claude/sessions/<pid>.json` and records where it came
 * from. Observed on a real install:
 *
 *   nameSource=derived  name=riderclaudetabs-29                      (built from the cwd)
 *   nameSource=derived  name=projects-68                             (built from the cwd)
 *   nameSource=auto     name=install script setup rework      (Claude's summary)
 *
 * Only the last kind is worth putting on a tab. The first two are no better than the tab's
 * own default and would just look like noise that changes for no reason.
 */
class SessionNameTest {

    @Test fun usesTheSummaryClaudeWrote() {
        assertEquals(
            "install script setup rework",
            ClaudeTabsHelpers.meaningfulSessionName("install script setup rework", "auto"),
        )
    }

    @Test fun usesADeliberatelySetName() {
        assertEquals("billing migration", ClaudeTabsHelpers.meaningfulSessionName("billing migration", "user"))
    }

    @Test fun ignoresNamesDerivedFromTheDirectory() {
        // These are the common case and they carry no information the tab doesn't already
        // have — the tab is in that directory.
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("riderclaudetabs-29", "derived"))
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("projects-68", "derived"))
    }

    @Test fun ignoresAnUnknownOrAbsentSource() {
        // Allowlist: a source a future CLI adds is not trusted until we have looked at it.
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("something", "brand-new-source"))
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("something", null))
    }

    @Test fun ignoresEmptyNames() {
        assertNull(ClaudeTabsHelpers.meaningfulSessionName(null, "auto"))
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("", "auto"))
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("   ", "auto"))
    }

    @Test fun ignoresAGenericNameEvenFromAGoodSource() {
        // "Local" as an auto name would be indistinguishable from the tab's default, and
        // would defeat the "is the live title still generic?" test at the apply site.
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("Local", "auto"))
        assertNull(ClaudeTabsHelpers.meaningfulSessionName("bash", "user"))
    }

    @Test fun trimsSurroundingWhitespace() {
        assertEquals("fix auth flow", ClaudeTabsHelpers.meaningfulSessionName("  fix auth flow  ", "auto"))
    }
}
