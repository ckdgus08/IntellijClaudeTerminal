package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Layer 1 unit tests — pure-function helpers in [ClaudeTabsHelpers].
 *
 * These lock in the logic for:
 *  - JSON field extraction + escape handling
 *  - Generic tab-name detection
 *  - Redundant-rename detection (the guard we added after seeing /resume churn)
 *  - Config parsing with lenient fallbacks
 *  - Shell-name detection
 *  - Project hash derivation
 *
 * No IntelliJ platform, no filesystem — pure JUnit.
 */
class ClaudeTabsHelpersTest {

    // ── extractJsonString ─────────────────────────────────────────

    @Test fun extractJsonString_basicKey() {
        val json = """{"sessionId":"abc-123","cwd":"/home/example"}"""
        assertEquals("abc-123", ClaudeTabsHelpers.extractJsonString(json, "sessionId"))
        assertEquals("/home/example", ClaudeTabsHelpers.extractJsonString(json, "cwd"))
    }

    @Test fun extractJsonString_missingKeyReturnsNull() {
        val json = """{"a":"1"}"""
        assertNull(ClaudeTabsHelpers.extractJsonString(json, "missing"))
    }

    @Test fun extractJsonString_handlesEscapedQuotes() {
        val json = """{"name":"Tab with \"quotes\""}"""
        assertEquals("Tab with \"quotes\"", ClaudeTabsHelpers.extractJsonString(json, "name"))
    }

    @Test fun extractJsonString_handlesEscapedBackslashes() {
        val json = """{"cwd":"C:\\Users\\user"}"""
        assertEquals("C:\\Users\\user", ClaudeTabsHelpers.extractJsonString(json, "cwd"))
    }

    @Test fun extractJsonString_ignoresWhitespaceAroundColon() {
        val json = """{"sessionId"  :   "abc"}"""
        assertEquals("abc", ClaudeTabsHelpers.extractJsonString(json, "sessionId"))
    }

    // ── esc ───────────────────────────────────────────────────────

    @Test fun esc_escapesBackslashAndQuote() {
        assertEquals("""C:\\Users\\user""", ClaudeTabsHelpers.esc("""C:\Users\user"""))
        assertEquals("""say \"hi\"""", ClaudeTabsHelpers.esc("""say "hi""""))
    }

    @Test fun esc_leavesPlainStringAlone() {
        assertEquals("Plain Text 123", ClaudeTabsHelpers.esc("Plain Text 123"))
    }

    // ── isGenericTabName ──────────────────────────────────────────

    @Test fun isGenericTabName_recognisesDefaults() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local (2)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local (42)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("bash"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("bash (3)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("pwsh"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("pwsh (5)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("PowerShell"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("cmd"))
    }

    @Test fun isGenericTabName_rejectsUserNames() {
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Fix Auth Bug"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Rider Plugin Tab Fix"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName("local"))         // case-sensitive
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Local-thing"))   // not the default format
        assertFalse(ClaudeTabsHelpers.isGenericTabName(""))
    }

    @Test fun isGenericTabName_trimsWhitespace() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("  Local  "))
    }

    // ── isRenameRedundant ─────────────────────────────────────────

    @Test fun isRenameRedundant_nullOrBlankCurrent_neverRedundant() {
        assertFalse(ClaudeTabsHelpers.isRenameRedundant(null, "Anything"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("", "Anything"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("   ", "Anything"))
    }

    @Test fun isRenameRedundant_genericCurrent_neverRedundant() {
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Local", "Local"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Local (2)", "Local (2)"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("bash", "Some Name"))
    }

    @Test fun isRenameRedundant_exactMatch() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "Fix Auth Bug"))
    }

    @Test fun isRenameRedundant_caseInsensitiveMatch() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "fix auth bug"))
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "FIX AUTH BUG"))
    }

    @Test fun isRenameRedundant_whitespaceNormalised() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "  Fix  Auth  Bug  "))
    }

    @Test fun isRenameRedundant_highJaccardOverlap() {
        // Very similar rewordings — Claude often picks these on resume
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "Fix Auth Bug Details"))
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Login Redirect Bug", "Login Redirect Bug Fix"))
    }

    @Test fun isRenameRedundant_differentTopics_notRedundant() {
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "Cert Renewal"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Rider Plugin", "Database Migration"))
    }

    @Test fun isRenameRedundant_singleWord_notRedundantUnlessExact() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Foo", "Foo"))
        // Single-word names don't hit the Jaccard branch — only exact match matters
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Foo", "Bar"))
    }

    // ── parseConfig ───────────────────────────────────────────────

    @Test fun parseConfig_missingFileReturnsDefaults() {
        val c = ClaudeTabsHelpers.parseConfig(null)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT, c)
    }

    @Test fun parseConfig_validFields() {
        val c = ClaudeTabsHelpers.parseConfig("""{"historyMaxAgeDays":30,"snapshotKeepCount":5}""")
        assertEquals(30L * 24 * 60 * 60 * 1000, c.historyMaxAgeMs)
        assertEquals(5, c.snapshotKeepCount)
    }

    @Test fun parseConfig_missingFieldsFallBackToDefaults() {
        val c = ClaudeTabsHelpers.parseConfig("""{"historyMaxAgeDays":7}""")
        assertEquals(7L * 24 * 60 * 60 * 1000, c.historyMaxAgeMs)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.snapshotKeepCount, c.snapshotKeepCount)
    }

    @Test fun parseConfig_zeroSnapshotsDisablesRotation() {
        val c = ClaudeTabsHelpers.parseConfig("""{"snapshotKeepCount":0}""")
        assertEquals(0, c.snapshotKeepCount)
    }

    @Test fun parseConfig_negativeHistoryIgnored() {
        // Negative history days are ignored — default applies.
        val c = ClaudeTabsHelpers.parseConfig("""{"historyMaxAgeDays":-1}""")
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.historyMaxAgeMs, c.historyMaxAgeMs)
    }

    @Test fun parseConfig_malformedReturnsDefaults() {
        val c = ClaudeTabsHelpers.parseConfig("not valid json at all {{}}")
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT, c)
    }

    // ── isShellCommand ────────────────────────────────────────────

    @Test fun isShellCommand_recognisesShells() {
        assertTrue(ClaudeTabsHelpers.isShellCommand("/usr/bin/bash"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("C:\\Windows\\System32\\cmd.exe"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("pwsh.exe"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("POWERSHELL.EXE"))  // case-insensitive
        assertTrue(ClaudeTabsHelpers.isShellCommand("zsh"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("fish"))
    }

    @Test fun isShellCommand_rejectsNonShells() {
        assertFalse(ClaudeTabsHelpers.isShellCommand("node"))
        assertFalse(ClaudeTabsHelpers.isShellCommand("/usr/local/bin/claude"))
        assertFalse(ClaudeTabsHelpers.isShellCommand("rider64.exe"))
    }

    // ── projectHashForPath ────────────────────────────────────────

    @Test fun projectHash_stableForSamePath() {
        val a = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\MyApp")
        val b = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\MyApp")
        assertEquals(a, b)
    }

    @Test fun projectHash_normalisesSeparators() {
        val fwd = ClaudeTabsHelpers.projectHashForPath("D:/Dev/MyApp")
        val bwd = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\MyApp")
        assertEquals(fwd, bwd)
    }

    @Test fun projectHash_nullPath_usesDefault() {
        assertEquals("default", ClaudeTabsHelpers.projectHashForPath(null))
    }

    @Test fun projectHash_containsNoFilesystemReservedChars() {
        val hash = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\MyApp")
        // Expect no `:`, `\`, `/` — safe to use as a filename
        assertFalse(hash.contains(':'))
        assertFalse(hash.contains('\\'))
        assertFalse(hash.contains('/'))
    }

    // ── isAiOverlayName ────────────────────────────────────────────

    @Test fun isAiOverlayName_brailleSpinnerPrefix() {
        // ml-llm writes Braille-spinner glyphs while a Claude session is active.
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("⠂ rider-claude-tab-namer", "rider-claude-tab-namer"))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("⠐ rider-claude-tab-namer", "rider-claude-tab-namer"))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("⠁ MyApp", "MyApp"))
    }

    @Test fun isAiOverlayName_middleDotPrefix() {
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("· rider-claude-tab-namer", "rider-claude-tab-namer"))
    }

    @Test fun isAiOverlayName_noPrefix_exactProjectName() {
        // Bare project name is also overlay output (idle state, no spinner).
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("MyApp", "MyApp"))
    }

    @Test fun isAiOverlayName_caseInsensitive() {
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("⠂ myapp", "MyApp"))
    }

    @Test fun isAiOverlayName_returnsFalse_forUserName() {
        // A user-chosen name like "Test 104" is not the overlay.
        assertFalse(ClaudeTabsHelpers.isAiOverlayName("Test 104", "rider-claude-tab-namer"))
        assertFalse(ClaudeTabsHelpers.isAiOverlayName("Login Bug", "MyApp"))
    }

    @Test fun isAiOverlayName_returnsFalse_forNullOrBlank() {
        assertFalse(ClaudeTabsHelpers.isAiOverlayName(null, "MyApp"))
        assertFalse(ClaudeTabsHelpers.isAiOverlayName("", "MyApp"))
    }

    @Test fun isAiOverlayName_glyphPrefix_isOverlay_evenWithoutProjectName() {
        // Glyph-prefix is sufficient on its own; projectName only matters for the bare-name fallback.
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("⠂ MyApp", null))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("⠂ MyApp", ""))
    }

    @Test fun isAiOverlayName_topicDetected_asteriskPrefixed() {
        // The AI Assistant doesn't only overlay with project name — it also generates
        // topic-specific names from conversation content. Any glyph-prefixed name is overlay.
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("* rest-401-auth-flow", "MyApp"))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("* Claude Code", "MyApp"))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("· Some Auto Topic", "MyApp"))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("⠂ Cert Renewal", "MyApp"))
    }

    @Test fun isAiOverlayName_userTypedNameWithoutGlyph_isNotOverlay() {
        // A user-typed name without a status-glyph prefix is NOT overlay.
        assertFalse(ClaudeTabsHelpers.isAiOverlayName("rest-401-auth-flow", "MyApp"))
        assertFalse(ClaudeTabsHelpers.isAiOverlayName("Claude Code", "MyApp"))
        assertFalse(ClaudeTabsHelpers.isAiOverlayName("Some Auto Topic", "MyApp"))
    }

    @Test fun isAiOverlayName_dingbatsHeavyAsterisk_isOverlay() {
        // U+2733 ✳ is what Claude Code 1.x actually emits in production overlay titles.
        // Older versions of this regex missed it (range stopped at U+28FF), allowing
        // "✳ Claude Code" to flow through to the save file as the literal tab name.
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("✳ Claude Code", "MyApp"))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("✳ Refactor auth flow", "MyApp"))
        assertTrue(ClaudeTabsHelpers.isAiOverlayName("✳ Some Chat Topic", "MyApp"))
    }

    @Test fun isAiOverlayName_dingbatsSiblings_alsoCaught() {
        // Defense in depth: the AI host could swap to any sibling glyph in a future release.
        // Codepoints U+2731-U+273F are all covered by the regex.
        for (cp in 0x2731..0x273F) {
            val glyph = String(Character.toChars(cp))
            val name = "$glyph some chat title"
            assertTrue("U+${cp.toString(16)} $glyph should match", ClaudeTabsHelpers.isAiOverlayName(name, "MyApp"))
        }
    }

    // ── extractResumeIdFromArgs ──────────────────────────────────
    // Locks in the parser used by canonicalSessionIdFor's primary path. The argv-based
    // canonical resolver replaced an mtime heuristic that swapped tab names when two
    // resumed sessions were concurrently active in the same cwd.

    private val UUID_A = "a0000001-00ab-4cde-8fab-000000000001"
    private val UUID_B = "a0000002-00ab-4cde-8fab-000000000002"

    @Test fun extractResumeId_longFlagWithSpace() {
        val argv = arrayOf("claude", "--resume", UUID_A, "--dangerously-skip-permissions")
        assertEquals(UUID_A, ClaudeTabsHelpers.extractResumeIdFromArgs(argv))
    }

    @Test fun extractResumeId_longFlagWithEquals() {
        val argv = arrayOf("claude", "--resume=$UUID_B")
        assertEquals(UUID_B, ClaudeTabsHelpers.extractResumeIdFromArgs(argv))
    }

    @Test fun extractResumeId_shortFlag() {
        val argv = arrayOf("claude", "-r", UUID_A)
        assertEquals(UUID_A, ClaudeTabsHelpers.extractResumeIdFromArgs(argv))
    }

    @Test fun extractResumeId_noResumeFlag_returnsNull() {
        val argv = arrayOf("claude", "--dangerously-skip-permissions")
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(argv))
    }

    @Test fun extractResumeId_resumeFlagButNoValue_returnsNull() {
        // --resume at end of argv with no following arg.
        val argv = arrayOf("claude", "--resume")
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(argv))
    }

    @Test fun extractResumeId_resumeWithNonUuidValue_returnsNull() {
        // Not a UUID — Claude wouldn't accept it, but defensively we don't treat
        // arbitrary strings as canonical session ids.
        val argv = arrayOf("claude", "--resume", "not-a-uuid")
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(argv))
    }

    @Test fun extractResumeId_nullArgv_returnsNull() {
        // ProcessHandle.info().arguments() returns Optional.empty on platforms where
        // argv isn't readable; we forward as null.
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(null))
    }

    @Test fun extractResumeId_emptyArgv_returnsNull() {
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(emptyArray()))
    }

    @Test fun extractResumeId_uppercaseHexUuid_accepted() {
        // UUIDs from `crypto.randomUUID()` are lowercase, but the regex tolerates
        // mixed-case input defensively.
        val mixed = "A0000001-00aB-4CDE-8fAB-000000000001"
        val argv = arrayOf("claude", "--resume", mixed)
        assertEquals(mixed, ClaudeTabsHelpers.extractResumeIdFromArgs(argv))
    }

    // ══════════════════════════════════════════════════════════════
    // isCwdUnderProject — guards the per-project restore file from
    // cross-project leaks (the actual production bug we fixed in 1.0.10:
    // a Mobile App Dev session with cwd=D:\Dev\MyApp-mobile leaked
    // into restore-D--Dev-MyApp.json and then silently failed at
    // resume time because `claude --resume` is cwd-scoped).
    // ══════════════════════════════════════════════════════════════

    @Test fun isCwdUnderProject_exactMatch_true() {
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_descendant_true() {
        // Subdirs should restore — `claude --resume` from project root finds them
        // because the user typically `cd`-s into the subdir first.
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp\\apps\\server", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_siblingProject_false() {
        // The actual bug: D:\Dev\MyApp-mobile is NOT under D:\Dev\MyApp despite
        // the prefix overlap. The trailing `/` boundary in the impl prevents this.
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp-mobile", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_siblingProjectDescendant_false() {
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp-mobile\\apps", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_unrelatedTree_false() {
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject("C:\\Other\\Project", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_caseInsensitive_true() {
        // Windows paths are case-insensitive in practice — a project opened with mixed-case
        // basePath should still match a session whose cwd was recorded lower-case.
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("d:\\dev\\myapp", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_forwardSlashCwd_true() {
        // Claude writes cwd with backslashes on Windows but other tools may use forward
        // slashes; normalise both sides.
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:/Dev/MyApp", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_trailingSlash_true() {
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp\\", "D:\\Dev\\MyApp"))
    }

    @Test fun isCwdUnderProject_nullBasePath_true() {
        // Detached / default Rider projects have no basePath. Allow all entries through
        // rather than silently dropping every session in the file.
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp", null))
    }

    @Test fun isCwdUnderProject_blankCwd_false() {
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject("", "D:\\Dev\\MyApp"))
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject(null, "D:\\Dev\\MyApp"))
    }

    // ══════════════════════════════════════════════════════════════
    // ownedByProjectSave (1.0.18)
    // ══════════════════════════════════════════════════════════════
    //
    // Contract: a session belongs to a project's save iff its tab is in that project's
    // Rider window (tab-walk discovered it → in tabWalkOwnedSids) OR its cwd is under
    // the project's basePath (legacy cwd-prefix matching, fallback for scanner-discovered).
    // The tab-walk signal trumps cwd because the window-hosting is authoritative.
    //
    // The worktree case is the bug that drove this: cwd outside basePath, but tab in
    // window → must be kept. Pre-1.0.18 the cwd filter dropped it as "cross-project leak."

    @Test fun ownedByProjectSave_tabWalkOwns_cwdOutsideBasePath_KEPT() {
        // Worktree: Claude process runs in D:\Dev\MyApp-feature, tab is in D:\Dev\MyApp's
        // Rider window. tab-walk discovered it → must be kept.
        val kept = ClaudeTabsHelpers.ownedByProjectSave(
            sessionId = "sid-worktree",
            cwd = "D:\\Dev\\MyApp-feature",
            projectBasePath = "D:\\Dev\\MyApp",
            tabWalkOwnedSids = setOf("sid-worktree"),
        )
        assertTrue("tab-walk owns it; cwd is a sibling worktree; must keep", kept)
    }

    @Test fun ownedByProjectSave_tabWalkOwns_cwdSiblingProject_KEPT() {
        // Even more extreme: a session in a totally unrelated cwd, but its tab is in this
        // window (e.g. user did `cd ~/other-project && claude` in this project's terminal).
        val kept = ClaudeTabsHelpers.ownedByProjectSave(
            sessionId = "sid-misc",
            cwd = "C:\\elsewhere",
            projectBasePath = "D:\\Dev\\MyApp",
            tabWalkOwnedSids = setOf("sid-misc"),
        )
        assertTrue(kept)
    }

    @Test fun ownedByProjectSave_notTabWalkOwned_cwdUnderBasePath_KEPT() {
        // Scanner-discovered (not in tabWalkOwnedSids), but cwd matches. Standard case.
        val kept = ClaudeTabsHelpers.ownedByProjectSave(
            sessionId = "sid-scanner",
            cwd = "D:\\Dev\\MyApp\\subdir",
            projectBasePath = "D:\\Dev\\MyApp",
            tabWalkOwnedSids = emptySet(),
        )
        assertTrue("cwd-under-basePath fallback applies", kept)
    }

    @Test fun ownedByProjectSave_notTabWalkOwned_cwdOutsideBasePath_LEAKED() {
        // True cross-project leak: not in our window AND cwd is a sibling. Drop.
        val kept = ClaudeTabsHelpers.ownedByProjectSave(
            sessionId = "sid-leak",
            cwd = "D:\\Dev\\OtherApp",
            projectBasePath = "D:\\Dev\\MyApp",
            tabWalkOwnedSids = emptySet(),
        )
        assertFalse("Not in our window and cwd is elsewhere — must drop", kept)
    }

    @Test fun ownedByProjectSave_emptyTabWalkSet_behavesLikeLegacyCwdCheck() {
        // Empty tabWalkOwnedSids = 1.0.17 behavior. Should match isCwdUnderProject exactly.
        val cases = listOf(
            Triple("D:\\Dev\\MyApp", "D:\\Dev\\MyApp", true),
            Triple("D:\\Dev\\MyApp\\sub", "D:\\Dev\\MyApp", true),
            Triple("D:\\Dev\\MyApp-mobile", "D:\\Dev\\MyApp", false),
            Triple("D:\\Dev\\OtherApp", "D:\\Dev\\MyApp", false),
        )
        for ((cwd, base, expected) in cases) {
            assertEquals(
                "cwd=$cwd base=$base expected=$expected",
                expected,
                ClaudeTabsHelpers.ownedByProjectSave("sid", cwd, base, emptySet()),
            )
        }
    }

    @Test fun ownedByProjectSave_siblingPathLookalike_notKeptWithoutTabWalkClaim() {
        // The classic foo / foo-mobile sibling collision. Without tab-walk ownership,
        // cwd-under-basePath rule says no. This is the safety net.
        val kept = ClaudeTabsHelpers.ownedByProjectSave(
            sessionId = "sid",
            cwd = "D:\\Dev\\MyApp-mobile\\src",
            projectBasePath = "D:\\Dev\\MyApp",
            tabWalkOwnedSids = emptySet(),
        )
        assertFalse(kept)
    }

    @Test fun ownedByProjectSave_siblingPathLookalike_keptIfTabWalkClaims() {
        // If the user happens to have a MyApp-mobile session open in MyApp's Rider
        // window (unusual but possible), tab-walk's claim wins. Keep.
        val kept = ClaudeTabsHelpers.ownedByProjectSave(
            sessionId = "sid-mobile-in-app-window",
            cwd = "D:\\Dev\\MyApp-mobile\\src",
            projectBasePath = "D:\\Dev\\MyApp",
            tabWalkOwnedSids = setOf("sid-mobile-in-app-window"),
        )
        assertTrue(kept)
    }

    // ══════════════════════════════════════════════════════════════
    // isProcessHostedByJvm (1.0.18) — bottom-up tab discovery
    // ══════════════════════════════════════════════════════════════
    //
    // Pin the contract: a Claude PID belongs to a Rider JVM iff walking its parent chain
    // hits that JVM's PID within maxDepth hops. This is THE fix for Rider 2026.1's
    // reworked terminal hiding shell PIDs from reflection — bottom-up doesn't need
    // platform APIs. Default 8-hop depth covers wrappers (node → pwsh → cmd → rider).

    @Test fun isProcessHostedByJvm_directChild_true() {
        // Simplest case: claudePid's parent IS the JVM.
        val parents = mapOf(100L to 999L)  // 100 → 999 (JVM)
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 100L, jvmPid = 999L, parentOf = { parents[it] },
        )
        assertTrue(hosted)
    }

    @Test fun isProcessHostedByJvm_threeHopChain_true() {
        // Typical: claude → pwsh → rider64 = 3 hops.
        val parents = mapOf(100L to 200L, 200L to 300L, 300L to 999L)
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 100L, jvmPid = 999L, parentOf = { parents[it] },
        )
        assertTrue(hosted)
    }

    @Test fun isProcessHostedByJvm_chainEndsBeforeReachingJvm_false() {
        // Parent chain leads to init (no parent), not our JVM.
        val parents = mapOf(100L to 200L, 200L to 1L)
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 100L, jvmPid = 999L, parentOf = { parents[it] },
        )
        assertFalse(hosted)
    }

    @Test fun isProcessHostedByJvm_chainHitsDifferentJvm_false() {
        // Session hosted by a DIFFERENT Rider window (different project).
        val parents = mapOf(100L to 200L, 200L to 888L)  // 888 = other JVM
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 100L, jvmPid = 999L, parentOf = { parents[it] },
        )
        assertFalse(hosted)
    }

    @Test fun isProcessHostedByJvm_parentReturnsNullImmediately_false() {
        // Orphan / detached process — no parent at all.
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 100L, jvmPid = 999L, parentOf = { null },
        )
        assertFalse(hosted)
    }

    @Test fun isProcessHostedByJvm_exactlyAtMaxDepth_findsJvm() {
        // JVM is at the maxDepth-th hop exactly. Boundary check.
        val parents = mapOf(100L to 200L, 200L to 300L, 300L to 400L, 400L to 999L)
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 100L, jvmPid = 999L, parentOf = { parents[it] }, maxDepth = 4,
        )
        assertTrue(hosted)
    }

    @Test fun isProcessHostedByJvm_jvmDeeperThanMaxDepth_false() {
        // JVM is 5 hops away but we only walk 4. Should not find it.
        val parents = mapOf(100L to 200L, 200L to 300L, 300L to 400L, 400L to 500L, 500L to 999L)
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 100L, jvmPid = 999L, parentOf = { parents[it] }, maxDepth = 4,
        )
        assertFalse(hosted)
    }

    @Test fun isProcessHostedByJvm_startPidIsAlsoTheJvm_false() {
        // Edge case: never returns true for the start itself — must be an ANCESTOR
        // (the JVM doesn't host itself).
        val hosted = ClaudeTabsHelpers.isProcessHostedByJvm(
            startPid = 999L, jvmPid = 999L, parentOf = { null },
        )
        assertFalse("start == jvm should not count as hosted (no ancestor walk happened)", hosted)
    }

    @Test fun isProcessHostedByJvm_riderWindowScenario_correctAttribution() {
        // Representative Rider 2026.1 multi-window scenario:
        //   PID 500 = ProjectAlpha Rider window JVM
        //   PID 600 = ProjectBeta Rider window JVM
        //   PID 100 = Claude in ProjectAlpha (parent chain: 100 → 110 pwsh → 500 JVM)
        //   PID 200 = Claude in ProjectBeta  (parent chain: 200 → 210 pwsh → 600 JVM)
        //   PID 300 = Claude orphaned    (parent chain: 300 → 310 pwsh → 1 init)
        val parents = mapOf(
            100L to 110L, 110L to 500L,
            200L to 210L, 210L to 600L,
            300L to 310L, 310L to 1L,
        )
        val parentOf = { pid: Long -> parents[pid] }

        // ProjectAlpha's poll (jvmPid=500) sees PID 100, not PID 200 or 300.
        assertTrue(ClaudeTabsHelpers.isProcessHostedByJvm(100L, 500L, parentOf))
        assertFalse(ClaudeTabsHelpers.isProcessHostedByJvm(200L, 500L, parentOf))
        assertFalse(ClaudeTabsHelpers.isProcessHostedByJvm(300L, 500L, parentOf))

        // ProjectBeta's poll (jvmPid=600) sees PID 200, not 100 or 300.
        assertFalse(ClaudeTabsHelpers.isProcessHostedByJvm(100L, 600L, parentOf))
        assertTrue(ClaudeTabsHelpers.isProcessHostedByJvm(200L, 600L, parentOf))
        assertFalse(ClaudeTabsHelpers.isProcessHostedByJvm(300L, 600L, parentOf))
    }

    // ── hasTranscriptAnywhere ─────────────────────────────────────

    @Rule @JvmField val tmp: TemporaryFolder = TemporaryFolder()

    private fun seedTranscript(projects: java.io.File, encodedCwd: String, sid: String) {
        val dir = java.io.File(projects, encodedCwd).also { it.mkdirs() }
        java.io.File(dir, "$sid.jsonl").writeText("{}")
    }

    @Test fun hasTranscriptAnywhere_fastPathHit() {
        val projects = tmp.newFolder("projects")
        seedTranscript(projects, "D--path-to-ProjectA", "abc")
        assertTrue(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "abc", "D:\\path\\to\\ProjectA"))
    }

    @Test fun hasTranscriptAnywhere_fallback_resumedFromWorktreeCwd_findsTranscriptInOriginalProjectDir() {
        // Regression: a session started in /repos/MyApp (transcript lives there), later
        // resumed by sid from a worktree at /repos/MyApp-feature. The alive session reports
        // cwd=MyApp-feature but Claude keeps appending to the original transcript path.
        // Pre-fix: hasTranscript looked only in `projects/-repos-MyApp-feature/` → empty →
        // false → ancestry walk skipped the session as `skipNoTranscript` → worktree resume
        // never made it into the restore file.
        val projects = tmp.newFolder("projects")
        seedTranscript(projects, "-repos-MyApp", "wt-sid-1")  // original transcript dir
        java.io.File(projects, "-repos-MyApp-feature").mkdirs()  // empty worktree dir

        assertTrue(
            "transcript exists under main project; fallback must find it",
            ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "wt-sid-1", "/repos/MyApp-feature"),
        )
    }

    @Test fun hasTranscriptAnywhere_missing_returnsFalseAfterScanningAllDirs() {
        val projects = tmp.newFolder("projects")
        seedTranscript(projects, "-repos-ProjectA", "real-sid")
        seedTranscript(projects, "-repos-ProjectB", "other-sid")
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "nonexistent-sid", "/repos/ProjectA"))
    }

    @Test fun hasTranscriptAnywhere_blankSessionId_false() {
        val projects = tmp.newFolder("projects")
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "", "/repos/X"))
    }

    @Test fun hasTranscriptAnywhere_blankCwd_stillScansAllDirs() {
        val projects = tmp.newFolder("projects")
        seedTranscript(projects, "-repos-Somewhere", "sid-x")
        assertTrue(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "sid-x", ""))
        assertTrue(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "sid-x", null))
    }

    @Test fun hasTranscriptAnywhere_emptyProjectsDir_false() {
        val projects = tmp.newFolder("projects")
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "any-sid", "/repos/X"))
    }
}
