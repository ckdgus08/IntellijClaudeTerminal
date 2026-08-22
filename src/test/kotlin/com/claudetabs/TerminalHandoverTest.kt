package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quitting Claude and starting it again in the same tab replaces the session without
 * `/clear` being involved — and without the pid that [ClaudeStatusStore.supersededSessions]
 * needs to link the two ids. A representative transition is:
 *
 *   status/b0000002….json → {"event":"SessionEnd","reason":"prompt_input_exit","pid":4001}
 *   pid 4001 gone; the terminal now hosts b0000003… under pid 4002
 *
 * The tab can then stay bound to the old id: it is re-attached as `✕` on every poll, while
 * the new process and status updates are keyed by the replacement id.
 *
 * `TERM_SESSION_ID` is the only thing on disk that spans the two, which is what
 * [ClaudeTabsHelpers.terminalHandovers] reads.
 */
class TerminalHandoverTest {

    private val term = "b0000001-0000-4000-8000-000000000001"
    private val old = "b0000002-0000-4000-8000-000000000002"
    private val new = "b0000003-0000-4000-8000-000000000003"

    private fun handovers(
        previous: Map<String, String>,
        current: Map<String, String>,
        interesting: Set<String> = setOf(old),
        canonical: (String) -> String = { it },
    ) = ClaudeTabsHelpers.terminalHandovers(previous, current, interesting, canonical)

    @Test fun spotsASessionReplacedInTheSameTerminal() {
        assertEquals(
            mapOf(old to new),
            handovers(mapOf(term to old), mapOf(term to new)),
        )
    }

    /**
     * The ordinary case by a wide margin — the terminal is still running the session it was
     * running last tick. Reporting it would re-key every tab to itself every 1.5 seconds.
     */
    @Test fun anUnchangedTerminalIsNotAHandover() {
        assertTrue(handovers(mapOf(term to old), mapOf(term to old)).isEmpty())
    }

    /**
     * The bridge file only keeps the newest id, so the first sighting of a terminal has
     * nothing to compare against. Inventing a hand-over there would fire on every restored
     * tab at IDE start, where the "old" id is simply the one we are restoring.
     */
    @Test fun aTerminalSeenForTheFirstTimeReportsNothing() {
        assertTrue(handovers(emptyMap(), mapOf(term to new)).isEmpty())
        assertTrue(handovers(mapOf("some-other-terminal" to old), mapOf(term to new)).isEmpty())
    }

    /**
     * `claude --resume` rotates the in-memory id while staying the same conversation. Both
     * ids canonicalise to the same transcript, so it is a rotation, not a hand-over.
     */
    @Test fun aRotatedIdForTheSameConversationIsNotAHandover() {
        val rotated = "b0000004-0000-4000-8000-000000000004"
        assertTrue(
            handovers(
                previous = mapOf(term to old),
                current = mapOf(term to rotated),
                canonical = { if (it == rotated) old else it },
            ).isEmpty()
        )
    }

    /** A session nobody holds a tab for has nothing to hand over. */
    @Test fun ignoresSessionsTheCallerIsNotHolding() {
        assertTrue(handovers(mapOf(term to old), mapOf(term to new), interesting = emptySet()).isEmpty())
        assertTrue(
            handovers(mapOf(term to old), mapOf(term to new), interesting = setOf("someone-else")).isEmpty()
        )
    }

    /**
     * Scoping is checked on the id the caller actually tracks — the canonical one — not on
     * whatever the hook happened to record.
     */
    @Test fun scopingIsCheckedAfterCanonicalisation() {
        val rotatedOld = "b0000005-0000-4000-8000-000000000005"
        assertEquals(
            mapOf(old to new),
            handovers(
                previous = mapOf(term to rotatedOld),
                current = mapOf(term to new),
                interesting = setOf(old),
                canonical = { if (it == rotatedOld) old else it },
            ),
        )
    }

    /** A bridge file that names no session establishes nothing. */
    @Test fun aBlankSuccessorIsNotAHandover() {
        assertTrue(handovers(mapOf(term to old), mapOf(term to "")).isEmpty())
    }

    /** Several tabs can be restarted between two passes; each is its own hand-over. */
    @Test fun handlesMoreThanOneTerminalAtOnce() {
        val term2 = "b0000006-0000-4000-8000-000000000006"
        val old2 = "b0000007-0000-4000-8000-000000000007"
        val new2 = "b0000008-0000-4000-8000-000000000008"
        assertEquals(
            mapOf(old to new, old2 to new2),
            handovers(
                previous = mapOf(term to old, term2 to old2),
                current = mapOf(term to new, term2 to new2),
                interesting = setOf(old, old2),
            ),
        )
    }

    /**
     * A terminal that has gone away between passes is not a hand-over — there is no
     * successor to hand anything to, and the tab close path already covers it.
     */
    @Test fun aTerminalMissingFromTheCurrentReadingIsNotAHandover() {
        assertTrue(handovers(mapOf(term to old), emptyMap()).isEmpty())
    }

    /**
     * The stale bridge files that accumulate for terminals nobody has open must not drag the
     * canonicaliser — resolving an id can cost a directory scan, and on a real install this
     * map held 73 terminals against a handful of live tabs.
     */
    @Test fun onlyChangedTerminalsAreCanonicalised() {
        val canonicalised = mutableListOf<String>()
        val previous = (1..50).associate { "term-$it" to "sid-$it" }
        val current = previous + mapOf("term-7" to new)
        handovers(
            previous = previous,
            current = current,
            interesting = setOf("sid-7"),
            canonical = { canonicalised.add(it); it },
        )
        assertEquals(listOf("sid-7", new), canonicalised)
    }
}
