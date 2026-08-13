package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quitting Claude and starting it again in the same tab replaces the session without
 * `/clear` being involved — and without the pid that [ClaudeStatusStore.supersededSessions]
 * needs to link the two ids. Reproduced from a real run, terminal `2effd38a`:
 *
 *   status/b38f686a….json → {"event":"SessionEnd","reason":"prompt_input_exit","pid":24373}
 *   pid 24373 gone; the terminal now hosts 09776e86… under pid 25106
 *
 * The tab then stayed bound to `b38f686a`: it was re-attached as `✕` on every poll, and the
 * name the user typed into the tab strip was filed under `b38f686a` while everything that
 * repaints the tab keyed off `09776e86` — so the rename came back 19 seconds later.
 *
 * `TERM_SESSION_ID` is the only thing on disk that spans the two, which is what
 * [ClaudeTabsHelpers.terminalHandovers] reads.
 */
class TerminalHandoverTest {

    private val term = "2effd38a-f30e-41d2-8514-a1876e9abf79"
    private val old = "b38f686a-a280-4584-bca2-a6583c6ae8d8"
    private val new = "09776e86-2ae6-4b06-a322-2b1386ba4cf4"

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
        val rotated = "ffffffff-0000-0000-0000-000000000000"
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
        val rotatedOld = "11111111-0000-0000-0000-000000000000"
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
        val term2 = "79bee2d0-8efb-4c99-b000-000000000000"
        val old2 = "bbf9ad15-9151-4742-bf2d-fd5fafb7c307"
        val new2 = "e63a9bda-fec3-4003-a176-6281b00c3e67"
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
