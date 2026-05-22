package com.claudetabs

/**
 * Pure decision logic for the "/tab is a writing action" contract — i.e. when the user
 * runs `/tab <name>` (or right-click → Rename Session), the new name should be written
 * to the restore file IMMEDIATELY for that one session, instead of waiting for the next
 * poll. Extracted so it can be exercised without an IntelliJ Project.
 *
 * Inputs are deliberately storage-shaped (existing list, new name, cwd hint) and the
 * output is the **next file content** (a fresh list of [ClaudeTabsStorage.SavedSession]) —
 * or [Outcome.Skip] when the caller should not write.
 *
 * The companion [ClaudeTabWatcherStartup.persistRenameImmediately] supplies the bindings
 * (loads existing list from disk, calls into this object, writes the resulting list
 * atomically + snapshots it).
 */
internal object ImmediateRenamePersistence {

    sealed class Outcome {
        /** Caller should write [updated] to the restore file. */
        data class Write(val updated: List<ClaudeTabsStorage.SavedSession>) : Outcome()

        /** Caller should NOT write — reason is informational only. */
        data class Skip(val reason: String) : Outcome()
    }

    /**
     * Compute the new restore-file contents after a /tab-style rename of [canonicalSessionId]
     * to [newName].
     *
     * Rules:
     *  - If [canonicalSessionId] already has an entry in [existing], update its `tabName`
     *    (preserving its `cwd` and `bypassPermissions`).
     *  - Otherwise, if [cwdHint] is provided and is under [projectBasePath], add a new
     *    entry for the session.
     *  - Otherwise, return [Outcome.Skip] — we can't write a meaningful entry without a cwd.
     *
     * The caller can rely on the order being stable: existing entries keep their order,
     * the updated/new entry stays in its previous position (or is appended).
     */
    fun compute(
        canonicalSessionId: String,
        newName: String,
        existing: List<ClaudeTabsStorage.SavedSession>,
        cwdHint: String?,
        bypassPermissionsHint: Boolean,
        projectBasePath: String?,
        isCwdUnderProject: (cwd: String, projectBase: String?) -> Boolean = ClaudeTabsHelpers::isCwdUnderProject,
    ): Outcome {
        val byId = linkedMapOf<String, ClaudeTabsStorage.SavedSession>()
        for (s in existing) byId[s.sessionId] = s

        val current = byId[canonicalSessionId]
        val resolvedCwd = current?.cwd ?: cwdHint
        if (resolvedCwd == null) {
            return Outcome.Skip("no cwd for $canonicalSessionId — deferring to poll")
        }
        if (!isCwdUnderProject(resolvedCwd, projectBasePath)) {
            return Outcome.Skip("cwd=$resolvedCwd is cross-project — skipping")
        }

        byId[canonicalSessionId] = ClaudeTabsStorage.SavedSession(
            sessionId = canonicalSessionId,
            cwd = resolvedCwd,
            tabName = newName,
            bypassPermissions = current?.bypassPermissions ?: bypassPermissionsHint,
        )
        return Outcome.Write(byId.values.toList())
    }
}
