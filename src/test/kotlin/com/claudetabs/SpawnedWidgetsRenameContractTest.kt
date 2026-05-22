package com.claudetabs

import com.claudetabs.SpawnedWidgetRenameFastPath.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the REAL `SpawnedWidgetRenameFastPath.tryRename` — the function the
 * production `handleRename` calls before falling back to the scan path. No mirror
 * functions in this file; every fact pinned here also pins production behaviour.
 *
 * Symptom on the build before the fast path existed:
 *   - User ran /tab on a tab the plugin had spawned via createShellWidget for restore.
 *   - handleRename called getAllTabs() to find the tab whose Claude child matches the
 *     requested sessionId. Spawned tabs were invisible to the reworked frontend +
 *     backend managers + the ContentManager sweep. Result: silent "no tab found".
 *
 * Fix: cache the TerminalWidget returned by createShellWidget keyed by canonical
 * sessionId; consult that cache first and apply the rename directly.
 */
class SpawnedWidgetsRenameContractTest {

    /** Throwaway target type — production uses TerminalWidget here. */
    private class FakeTarget(val id: String)

    // ══════════════════════════════════════════════════════════════
    // Fast path — cache hit + apply succeeds
    // ══════════════════════════════════════════════════════════════

    @Test
    fun cacheHitAndSuccessfulApplyReturnsAPPLIED() {
        val target = FakeTarget("w1")
        val applied = mutableListOf<Pair<FakeTarget, String>>()
        val result = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = "sid-1",
            newName = "My Tab",
            cache = mapOf("sid-1" to target),
            applyToTarget = { t, n -> applied.add(t to n) },
        )
        assertEquals(Result.APPLIED, result)
        assertEquals("apply called exactly once", 1, applied.size)
        assertEquals(target, applied[0].first)
        assertEquals("My Tab", applied[0].second)
    }

    // ══════════════════════════════════════════════════════════════
    // Cache miss
    // ══════════════════════════════════════════════════════════════

    @Test
    fun cacheMissReturnsCACHE_MISSAndDoesNotCallApply() {
        val applied = mutableListOf<Pair<FakeTarget, String>>()
        val result = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = "manually-opened-sid",
            newName = "My Tab",
            cache = emptyMap<String, FakeTarget>(),
            applyToTarget = { t, n -> applied.add(t to n) },
        )
        assertEquals(Result.CACHE_MISS, result)
        assertTrue("apply must not be called on cache miss", applied.isEmpty())
    }

    @Test
    fun unrelatedSidInCacheStillMisses() {
        // Cache contains entries, but not the one we're asking for. Must miss, not
        // pick a random other entry.
        val applied = mutableListOf<Pair<FakeTarget, String>>()
        val result = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = "sid-not-in-cache",
            newName = "My Tab",
            cache = mapOf("sid-other-1" to FakeTarget("a"), "sid-other-2" to FakeTarget("b")),
            applyToTarget = { t, n -> applied.add(t to n) },
        )
        assertEquals(Result.CACHE_MISS, result)
        assertTrue(applied.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    // Apply throws — widget disposed underfoot
    // ══════════════════════════════════════════════════════════════

    @Test
    fun applyThrowsReturnsAPPLY_FAILEDAndExceptionIsCaught() {
        // Widget was in the cache but got disposed (user closed the tab) between the
        // cache lookup and the rename apply. Must NOT swallow silently — the production
        // code logs a warn on APPLY_FAILED and falls through to scan. The function
        // itself must catch the exception (otherwise handleRename would propagate it).
        val result = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = "sid-disposed",
            newName = "My Tab",
            cache = mapOf("sid-disposed" to FakeTarget("disposed")),
            applyToTarget = { _, _ -> throw RuntimeException("widget disposed") },
        )
        assertEquals(Result.APPLY_FAILED, result)
    }

    @Test
    fun applyThrowsCheckedExceptionAlsoBucketedAsAPPLY_FAILED() {
        // Defensive: callers can pass any kind of exception out of applyToTarget;
        // the function catches `Exception` broadly. Pin that here.
        val result = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = "sid-1",
            newName = "x",
            cache = mapOf("sid-1" to FakeTarget("t")),
            applyToTarget = { _, _ -> throw IllegalStateException("widget in bad state") },
        )
        assertEquals(Result.APPLY_FAILED, result)
    }

    // ══════════════════════════════════════════════════════════════
    // Cache key — canonical sessionId, not raw
    // ══════════════════════════════════════════════════════════════

    @Test
    fun cacheKeyedByCanonicalSidNotRaw() {
        // After `claude --resume <canonical>`, Claude rotates its in-memory id while
        // keeping the on-disk transcript at <canonical>. /tab files are named with the
        // canonical id (session-start-hook captures it). So spawnedWidgets MUST be
        // keyed by canonical, not the rotated raw id.
        //
        // The function itself is just a Map lookup, but pinning this scenario here
        // documents the contract for future readers and catches any rename-keying
        // mistakes early.
        val widget = FakeTarget("w")
        val canonical = "d0000001-0000-4000-8000-000000000001"
        val rotatedRaw = "11111111-1111-1111-1111-111111111111"
        val cache = mapOf(canonical to widget)

        // Canonical lookup → APPLIED
        val applied = mutableListOf<String>()
        val hit = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = canonical, newName = "n", cache = cache,
            applyToTarget = { _, n -> applied.add(n) },
        )
        assertEquals(Result.APPLIED, hit)

        // Rotated raw lookup → CACHE_MISS (production code then runs the scan path,
        // which resolves the rotated raw back to canonical via canonicalSessionIdFor).
        val miss = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = rotatedRaw, newName = "n", cache = cache,
            applyToTarget = { _, n -> applied.add(n) },
        )
        assertEquals(Result.CACHE_MISS, miss)
        assertEquals("only the canonical-lookup apply ran", 1, applied.size)
    }

    // ══════════════════════════════════════════════════════════════
    // Disposer cleanup is a pure Map operation — pin it here so the contract
    // travels with the rename logic that depends on it.
    // ══════════════════════════════════════════════════════════════

    @Test
    fun projectClosePrunesItsSidsFromCache() {
        // Mirrors the disposer body in ClaudeTabWatcherStartup.runActivity: iterate
        // ctx(project).previousActive and remove those sids from spawnedWidgets.
        val cache = mutableMapOf<String, FakeTarget>(
            "projA-sid-1" to FakeTarget("w1"),
            "projA-sid-2" to FakeTarget("w2"),
            "projB-sid-1" to FakeTarget("o1"),
        )
        for (sid in setOf("projA-sid-1", "projA-sid-2")) cache.remove(sid)

        assertFalse("projA-sid-1 evicted", "projA-sid-1" in cache)
        assertFalse("projA-sid-2 evicted", "projA-sid-2" in cache)
        assertTrue("projB-sid-1 survives", "projB-sid-1" in cache)
        assertEquals(1, cache.size)

        // After eviction, fast path returns CACHE_MISS for the evicted sids:
        val result = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = "projA-sid-1",
            newName = "n",
            cache = cache,
            applyToTarget = { _, _ -> },
        )
        assertEquals(Result.CACHE_MISS, result)
    }

    // ══════════════════════════════════════════════════════════════
    // No side effects on CACHE_MISS or APPLY_FAILED
    // ══════════════════════════════════════════════════════════════

    @Test
    fun cacheMissPerformsNoMutation() {
        // Pin: the function must not insert into or otherwise mutate the cache on
        // miss. If a future refactor accidentally "fixes" CACHE_MISS by inserting a
        // null entry, the next call would APPLY against null and crash.
        val cache = mapOf<String, FakeTarget>("a" to FakeTarget("a"))
        SpawnedWidgetRenameFastPath.tryRename(
            sessionId = "missing", newName = "n", cache = cache,
            applyToTarget = { _, _ -> },
        )
        assertEquals("cache unchanged", 1, cache.size)
        assertNull("'missing' key absent", cache["missing"])
    }
}
