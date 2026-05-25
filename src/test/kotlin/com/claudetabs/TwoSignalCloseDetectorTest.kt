package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [TwoSignalCloseDetector] -- the pure-function decision logic behind
 * 1.0.17's two-signal close detection.
 *
 * Pinned rules:
 *
 *   - **Signal 1 (`decideOnRemoveQuery`)**: `projectClosing` short-circuits everything.
 *     If not closing, `TEMPORARY_REMOVED_KEY` (drag/reorder/pane move) short-circuits.
 *     If not temporary, a null sid short-circuits (can't pend an unidentified close).
 *     Only when all three pass does the sid get added to pendingClose.
 *
 *   - **Signal 2 (`confirmPending`)**: a pending sid is CONFIRMED user-closed only when
 *     its Claude process is DEAD (not in the alive set). If the process is alive AND the
 *     pending entry is older than `expiryMs`, it's EXPIRED (dropped -- not a real close).
 *     Otherwise it's KEPT for the next poll cycle.
 *
 *   - **No state escapes the pure functions** -- all decisions are deterministic given
 *     the inputs. Tests pin every branch.
 *
 * Why these exact rules: signal 1 alone false-positived on whole-window-close (every
 * tab's widget genuinely gone -- projectClosing race) and split-pane closes. Signal 2
 * alone is too aggressive -- a Claude crash inside a still-open tab shouldn't drop the
 * saved session. Requiring both is the conservative choice the user explicitly asked
 * for after seeing 8 false-positives accumulated in user-closed during one session.
 */
class TwoSignalCloseDetectorTest {

    // ══════════════════════════════════════════════════════════════
    // SIGNAL 1 -- decideOnRemoveQuery
    // ══════════════════════════════════════════════════════════════

    @Test fun signal1_projectClosing_skipsRegardlessOfOtherInputs() {
        val d = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = true, isTemporary = false, sid = "sid-1",
        )
        assertEquals(TwoSignalCloseDetector.Signal1.SkipProjectClosing, d)
    }

    @Test fun signal1_projectClosing_evenWithTemporary() {
        val d = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = true, isTemporary = true, sid = "sid-1",
        )
        // projectClosing takes precedence over temporary -- both would skip but for
        // different reasons; we report the more specific cause.
        assertEquals(TwoSignalCloseDetector.Signal1.SkipProjectClosing, d)
    }

    @Test fun signal1_projectClosing_evenWithNullSid() {
        val d = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = true, isTemporary = false, sid = null,
        )
        assertEquals(TwoSignalCloseDetector.Signal1.SkipProjectClosing, d)
    }

    @Test fun signal1_temporaryRemoved_skipsRegardlessOfSid() {
        val d = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = false, isTemporary = true, sid = "sid-2",
        )
        assertEquals(TwoSignalCloseDetector.Signal1.SkipTemporary, d)
    }

    @Test fun signal1_temporaryRemoved_skipsEvenWithNullSid() {
        val d = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = false, isTemporary = true, sid = null,
        )
        assertEquals(TwoSignalCloseDetector.Signal1.SkipTemporary, d)
    }

    @Test fun signal1_nullSid_skipsWhenNotClosingOrTemporary() {
        val d = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = false, isTemporary = false, sid = null,
        )
        assertEquals(TwoSignalCloseDetector.Signal1.SkipNoSid, d)
    }

    @Test fun signal1_validClose_addsSidToPending() {
        val d = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = false, isTemporary = false, sid = "sid-real-close",
        )
        assertEquals(TwoSignalCloseDetector.Signal1.AddToPending("sid-real-close"), d)
    }

    // ══════════════════════════════════════════════════════════════
    // SIGNAL 2 -- confirmPending (process-dead + expiry)
    // ══════════════════════════════════════════════════════════════

    @Test fun signal2_emptyPending_emptyResult() {
        val r = TwoSignalCloseDetector.confirmPending(
            pendingClose = emptyMap(),
            aliveSids = emptySet(),
            now = 1000L,
        )
        assertTrue(r.confirmed.isEmpty())
        assertTrue(r.expired.isEmpty())
        assertTrue(r.kept.isEmpty())
    }

    @Test fun signal2_processDead_confirms() {
        val r = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("dead-sid" to 1000L),
            aliveSids = emptySet(), // process is dead -> not in alive set
            now = 2000L,
        )
        assertEquals(setOf("dead-sid"), r.confirmed)
        assertTrue(r.expired.isEmpty())
        assertTrue(r.kept.isEmpty())
    }

    @Test fun signal2_processAlive_youngEntry_keeps() {
        val r = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("alive-sid" to 1000L),
            aliveSids = setOf("alive-sid"),
            now = 5000L,   // only 4s old, not expired
            expiryMs = 30_000L,
        )
        assertTrue(r.confirmed.isEmpty())
        assertTrue(r.expired.isEmpty())
        assertEquals(setOf("alive-sid"), r.kept)
    }

    @Test fun signal2_processAlive_oldEntry_expires() {
        val r = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("alive-but-old" to 1000L),
            aliveSids = setOf("alive-but-old"),
            now = 40_000L,   // 39s old, past 30s expiry
            expiryMs = 30_000L,
        )
        assertTrue(r.confirmed.isEmpty())
        assertEquals(setOf("alive-but-old"), r.expired)
        assertTrue(r.kept.isEmpty())
    }

    @Test fun signal2_multipleEntries_classifiedIndependently() {
        // now=40_000L, expiryMs=30_000L.
        //   real-close: dead -> confirm
        //   alive-young at 15_000L -> age=25_000ms (< 30s) -> keep
        //   alive-old at 100L -> age=39_900ms (> 30s) -> expire
        //   another-dead: dead -> confirm
        val r = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf(
                "real-close" to 1000L,
                "alive-young" to 15_000L,
                "alive-old" to 100L,
                "another-dead" to 4000L,
            ),
            aliveSids = setOf("alive-young", "alive-old"),
            now = 40_000L,
            expiryMs = 30_000L,
        )
        assertEquals(setOf("real-close", "another-dead"), r.confirmed)
        assertEquals(setOf("alive-old"), r.expired)
        assertEquals(setOf("alive-young"), r.kept)
    }

    @Test fun signal2_exactlyAtExpiry_keeps_oneMsPast_expires() {
        // Boundary: an entry exactly at the expiry threshold is NOT expired (kept).
        // One ms past becomes expired.
        val atExpiry = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("at" to 0L),
            aliveSids = setOf("at"),
            now = 30_000L,
            expiryMs = 30_000L,
        )
        assertEquals(setOf("at"), atExpiry.kept)

        val pastExpiry = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("past" to 0L),
            aliveSids = setOf("past"),
            now = 30_001L,
            expiryMs = 30_000L,
        )
        assertEquals(setOf("past"), pastExpiry.expired)
    }

    @Test fun signal2_aliveSidsCanContainOthers_thatDoesntMatter() {
        // The aliveSids set may include sids that aren't in pendingClose at all (it's
        // built from a project-wide ProcessHandle scan). confirmPending only cares
        // about the intersection with the pendingClose keyset.
        val r = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("dead" to 1000L),
            aliveSids = setOf("some-other-alive-sid", "yet-another"),
            now = 2000L,
        )
        assertEquals(setOf("dead"), r.confirmed)
    }

    // ══════════════════════════════════════════════════════════════
    // FULL SCENARIO PINS -- end-to-end behavior for each user action
    // ══════════════════════════════════════════════════════════════

    @Test fun scenario_userXClosesTab_signal1FiresThenProcessDies_thenConfirmed() {
        // Step 1: contentRemoveQuery fires for the tab. Not closing, not temporary,
        // sid resolves. -> AddToPending.
        val s1 = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = false, isTemporary = false, sid = "tab-sid",
        )
        assertEquals(TwoSignalCloseDetector.Signal1.AddToPending("tab-sid"), s1)
        // Step 2: caller adds sid to pendingClose, t=1000. Rider kills shell; Claude
        // dies. At next poll (t=3000), tab-sid is no longer in aliveSids.
        val s2 = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("tab-sid" to 1000L),
            aliveSids = emptySet(),   // Claude is dead
            now = 3000L,
        )
        assertEquals(setOf("tab-sid"), s2.confirmed)
    }

    @Test fun scenario_userClosesRiderWindow_projectClosing_signal1SkipsImmediately() {
        // Window close: ProjectManagerListener.projectClosing fires FIRST, sets the flag.
        // Subsequent contentRemoveQuery events bail. No sid ever enters pendingClose.
        val s1 = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = true, isTemporary = false, sid = "tab-sid",
        )
        assertEquals(TwoSignalCloseDetector.Signal1.SkipProjectClosing, s1)
        // Even if the flag races and the listener doesn't see it: scope cancels with
        // the project, so the poll loop never runs to confirm.  Both layers fail-safe.
    }

    @Test fun scenario_panelDragReorder_temporaryKeyFlips_signal1Skips() {
        // Drag-reorder: ContentManagerImpl sets TEMPORARY_REMOVED_KEY before firing
        // contentRemoveQuery. We see isTemporary=true, skip.
        val s1 = TwoSignalCloseDetector.decideOnRemoveQuery(
            projectClosing = false, isTemporary = true, sid = "tab-sid",
        )
        assertEquals(TwoSignalCloseDetector.Signal1.SkipTemporary, s1)
    }

    @Test fun scenario_claudeCrashesInOpenTab_signal2WithoutSignal1_noRecord() {
        // Claude crashes inside an open tab. The tab is still there (no contentRemoveQuery
        // fires -> nothing enters pendingClose). Signal 2 alone never records anything
        // because there's no pending entry to confirm. This is the desired behavior:
        // a crashed Claude session should NOT be marked user-closed -- the user might
        // /tab it back to life via --resume.
        val s2 = TwoSignalCloseDetector.confirmPending(
            pendingClose = emptyMap(),   // nothing ever pended
            aliveSids = emptySet(),      // Claude is dead
            now = 5000L,
        )
        assertTrue(s2.confirmed.isEmpty())
    }

    @Test fun scenario_phantomRemoveEvent_processStaysAlive_expires() {
        // Some other event (not user X-click) fired contentRemoveQuery for whatever
        // reason. Sid got pended. Claude process keeps running. After 30s, we drop
        // the pending entry -- no false-positive recorded.
        val s2 = TwoSignalCloseDetector.confirmPending(
            pendingClose = mapOf("phantom" to 0L),
            aliveSids = setOf("phantom"),
            now = 31_000L,
            expiryMs = 30_000L,
        )
        assertEquals(setOf("phantom"), s2.expired)
        assertTrue(s2.confirmed.isEmpty())
    }
}
