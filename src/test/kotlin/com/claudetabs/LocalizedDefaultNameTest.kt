package com.claudetabs

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IDE's default terminal name is localised, and this predicate used to be English only.
 *
 * On a Korean IDE the default tab is called 로컬, so `isGenericTabName` said false and the
 * plugin treated an untouched terminal as a name someone had deliberately chosen —
 * everywhere the predicate is consulted, not just in one feature. The spare-terminal sweep
 * is what surfaced it, reporting the tab with every other guard passing:
 *
 *   '로컬'(pid=91000 generic=false claude=false children=0[] ours=false → keep)
 */
class LocalizedDefaultNameTest {

    @After fun reset() { ClaudeTabsHelpers.localizedDefaultNames = emptySet() }

    @Test fun theBundledLanguagePacksAreRecognisedWithoutAnyRuntimeLookup() {
        // Values read out of the localization-ko / -ja / -zh plugins the IDE ships with.
        assertTrue("Korean", ClaudeTabsHelpers.isGenericTabName("로컬"))
        assertTrue("Japanese", ClaudeTabsHelpers.isGenericTabName("ローカル"))
        assertTrue("Chinese", ClaudeTabsHelpers.isGenericTabName("本地"))
    }

    @Test fun aRuntimeResolvedNameIsRecognised() {
        // Whatever the terminal plugin's bundle says in this IDE's language.
        ClaudeTabsHelpers.localizedDefaultNames = setOf("Lokal")
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Lokal"))
    }

    @Test fun theNumberedVariantsCountToo() {
        // A second default tab is "로컬 (2)", exactly as English gets "Local (2)".
        assertTrue(ClaudeTabsHelpers.isGenericTabName("로컬 (2)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("本地 (11)"))
        ClaudeTabsHelpers.localizedDefaultNames = setOf("Lokal")
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Lokal (3)"))
    }

    @Test fun englishStillWorks() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local (2)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("bash"))
    }

    @Test fun aRealNameIsStillARealName() {
        // The point of the predicate: these must never be treated as disposable.
        assertFalse(ClaudeTabsHelpers.isGenericTabName("로컬 서버 디버깅"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName("본지사 동기화"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Local dev notes"))
        ClaudeTabsHelpers.localizedDefaultNames = setOf("Lokal")
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Lokaler Server"))
    }

    @Test fun anEmptyOrBlankRegistrationIsIgnored() {
        // A failed bundle lookup must not make every tab look generic.
        ClaudeTabsHelpers.localizedDefaultNames = setOf("", "   ")
        assertFalse(ClaudeTabsHelpers.isGenericTabName("anything"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName(""))
    }
}
