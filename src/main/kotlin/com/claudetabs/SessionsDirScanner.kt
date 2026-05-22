package com.claudetabs

import com.claudetabs.ClaudeTabsStorage.SavedSession
import java.io.File

/**
 * STEP 6b of the poll loop, extracted as a pure(-ish) function so it can be exercised
 * without standing up an IntelliJ Project + ProcessHandle + TerminalWidget.
 *
 * **Why this exists as a separate file:** in Rider 2026.1's reworked terminal,
 * tabs the plugin spawns via `createShellWidget` are invisible to the platform's
 * tab-enumeration APIs (`TerminalToolWindowTabsManager`, `TerminalTabsManager`,
 * occasionally even `ContentManager.contents`). The tab-driven save loop would
 * therefore drop them on every poll: 10 spawned, 1 saved, restart → 1 restored.
 *
 * **Fix:** ignore tabs entirely for the existence check. Walk `~/.claude/sessions/`
 * directly — one `<pid>.json` per running Claude process — and decide per-file via
 * five predicates: numeric PID, alive in OS, actually `claude` (recycling guard),
 * cwd under this project, transcript on disk.
 *
 * The orchestration around this (the wider `poll()` method) still depends on Project
 * and Logger and can't be unit-tested directly, but this function — which contains
 * all the actual decision logic — is fully testable with injected lambdas.
 */
internal object SessionsDirScanner {

    /** Subset of `ProcessHandle.Info` we care about. The implementation in production
     *  pulls these via `ProcessHandle.of(pid).get().info().command()` / `.commandLine()`;
     *  tests inject fake values directly. */
    data class ProcessInfo(val command: String, val commandLine: String)

    /** Bucketed accounting of what the scan accepted/rejected. Matches the diagnostic
     *  counters the original inline code logged at STEP 6b. */
    data class ScanResult(
        val added: List<SavedSession>,
        val scanned: Int,
        val skipDead: Int,
        val skipOtherProject: Int,
        val skipAlreadyHave: Int,
        val skipNoTranscript: Int,
    ) {
        /** One-line summary suitable for the periodic log entry. */
        fun statusLine(): String =
            "scanned=$scanned added=${added.size} skipDead=$skipDead skipOtherProject=$skipOtherProject " +
                "skipAlreadyHave=$skipAlreadyHave skipNoTranscript=$skipNoTranscript"
    }

    /** Result of the alive + recycling check. Returned by [aliveProcessInfo] so the
     *  scan knows whether to bump `skipDead` for a missing-or-dead-PID or a recycled-
     *  to-non-claude PID (both bucket under skipDead in the production counter). */
    sealed class ProcessLookup {
        object DeadOrMissing : ProcessLookup()
        data class Alive(val info: ProcessInfo) : ProcessLookup()
    }

    /**
     * Recycling guard: given a [ProcessInfo], does this look like a Claude process?
     *
     * Public so tests + the rest of the codebase can use the same heuristic. The check
     * is intentionally loose — `claude.exe`, the `node` wrapper invoking `@anthropic`,
     * and the `.cmd` shim all count.
     */
    fun looksLikeClaude(info: ProcessInfo): Boolean =
        info.command.endsWith("claude") ||
            info.command.endsWith("claude.exe") ||
            info.command.endsWith("claude.cmd") ||
            info.commandLine.contains("@anthropic", true) ||
            info.commandLine.contains("claude-code", true) ||
            (info.command.contains("node", true) && info.commandLine.contains("claude", true))

    /**
     * Run the scan over [sessionsDir].
     *
     * All non-pure dependencies are injected as lambdas so this function is a unit-
     * testable black box. Production calls it with real `ProcessHandle.of`, the
     * existing canonical-id resolver, etc. Tests inject deterministic fakes.
     */
    fun scan(
        sessionsDir: File,
        projectBasePath: String?,
        alreadyActiveIds: Set<String>,
        processLookup: (pid: Long) -> ProcessLookup,
        canonicalSessionId: (pid: Long, cwd: String, rawSid: String, startedAt: Long) -> String,
        hasTranscript: (cwd: String, sid: String) -> Boolean,
        resolveName: (sid: String) -> String,
        readBypass: (cwd: String, sid: String) -> Boolean,
        now: () -> Long = { System.currentTimeMillis() },
    ): ScanResult {
        val added = mutableListOf<SavedSession>()
        var scanned = 0
        var skipDead = 0
        var skipOtherProject = 0
        var skipAlreadyHave = 0
        var skipNoTranscript = 0

        // Defensive try/catch wraps the whole loop so a single bad file (or an
        // ENOENT on the sessions dir itself) doesn't abort the rest of the scan.
        // The original inline code had the same shape.
        try {
            val known = alreadyActiveIds.toMutableSet()
            sessionsDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { sf ->
                scanned++

                val pid = sf.nameWithoutExtension.toLongOrNull() ?: return@forEach

                // PID must be alive AND it must still be Claude (vs a recycled PID).
                // Both failure modes bucket under skipDead — matches the production
                // counter the user is reading off idea.log.
                when (val lookup = processLookup(pid)) {
                    is ProcessLookup.DeadOrMissing -> { skipDead++; return@forEach }
                    is ProcessLookup.Alive -> {
                        if (!looksLikeClaude(lookup.info)) { skipDead++; return@forEach }
                    }
                }

                val text = try { sf.readText() } catch (_: Exception) { return@forEach }
                val rawSessionId = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: return@forEach
                val cwd = ClaudeTabsHelpers.extractJsonString(text, "cwd") ?: return@forEach

                if (!ClaudeTabsHelpers.isCwdUnderProject(cwd, projectBasePath)) {
                    skipOtherProject++
                    return@forEach
                }

                val startedAt = Regex(""""startedAt":(\d+)""").find(text)
                    ?.groupValues?.get(1)?.toLongOrNull()
                    ?: now()

                val canonical = canonicalSessionId(pid, cwd, rawSessionId, startedAt)
                if (canonical in known) { skipAlreadyHave++; return@forEach }
                if (!hasTranscript(cwd, canonical)) { skipNoTranscript++; return@forEach }

                val name = resolveName(canonical)
                val bypass = readBypass(cwd, canonical)
                added.add(SavedSession(canonical, cwd, name, bypass))
                known.add(canonical)
            }
        } catch (_: Exception) {
            // Production code logs at DEBUG here. Returning the partial result is
            // safer than re-throwing — the next poll will retry, and any sessions
            // already added are still saved correctly.
        }

        return ScanResult(added, scanned, skipDead, skipOtherProject, skipAlreadyHave, skipNoTranscript)
    }
}
