package com.claudetabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.jediterm.terminal.ProcessTtyConnector
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.file.*

/**
 * Entry point for the Claude Terminal Tab Persistence plugin.
 *
 * This is a JetBrains IntelliJ Platform post-startup activity. When a project opens, it:
 *
 *  1. **Deploys** its bash integration (`rename-tab.sh`, `session-start-hook.sh`), slash commands,
 *     a CLAUDE.md section, and permission/settings entries into `~/.claude/`.
 *  2. **Watches** `~/.claude/rider-plugin/tabs/` for `{sessionId}.json` rename files written by the
 *     bash scripts when the user runs `/tab` or any other command that names a terminal tab.
 *  3. **Polls** the terminal tool window every [POLL_INTERVAL_MS] to:
 *     - Match Claude Code processes to their terminal tab (by walking each tab's PID tree).
 *     - Apply any pending renames.
 *     - Save the set of named tabs to a per-project restore file.
 *     - Detect closed sessions and append them to `history.json` for `/tabs-history`.
 *  4. **Restores** saved tabs after a Rider restart — typing `claude --resume <id>` into each
 *     matching idle terminal.
 *
 * ### Design notes
 *
 * - **Reflection-heavy**: navigates IntelliJ's reworked (and classic) terminal internals because
 *   the public API doesn't expose what's needed. Fallback paths are graceful — see [renameTab].
 * - **Race-condition free**: tab identification uses JetBrains' `TERM_SESSION_ID` env var, which
 *   is unique per terminal tab. The `session-start-hook.sh` writes the mapping
 *   `TERM_SESSION_ID → Claude session ID` per-tab, so no shared FIFO queue is needed.
 * - **Manual-rename priority**: if the user renames a tab themselves, [lastAppliedName] lets us
 *   detect it and back off — we won't overwrite their choice.
 *
 * See `plugin.xml` for Marketplace metadata and `README.md` for user-facing docs.
 */
class ClaudeTabWatcherStartup : StartupActivity.DumbAware {

    companion object {
        private val LOG = Logger.getInstance(ClaudeTabWatcherStartup::class.java)

        /** Poll cadence for detecting rename files and session state changes. */
        private const val POLL_INTERVAL_MS = 5_000L

        /**
         * Grace period after the plugin attaches to a project during which every
         * `ContentManagerListener.contentRemoved` event is treated as Rider's
         * tool-window re-layout (restored tabs being attached, split/popout
         * reflow, drag-to-reorder) — NOT a user close. 30 seconds is long enough
         * to cover the slowest observed startup (Rider 2026.1 with ~10 tabs
         * across 3 projects opens in ~12-18s after the welcome screen). The
         * previous lack of this grace was the root cause of the bug where 3-5
         * tabs went missing per restart: re-layout events were being recorded
         * as "user closed this tab" and subtracted from the restore file. */
        const val STARTUP_GRACE_MS = 30_000L

        /**
         * After the startup grace, real-looking close events are deferred this
         * long before being recorded. During the delay we check whether the
         * Claude process for that sessionId is still alive on disk
         * (~/.claude/sessions/<pid>.json). If alive → it was a Rider UI shuffle
         * (split move, popout, drag) and we do nothing. If dead → genuine user
         * close, add to `userClosedSessions`. 3 seconds is plenty for Claude's
         * sessions/<pid>.json file to disappear on a real terminate (the file
         * is unlinked when the process exits). */
        const val CLOSE_VERIFY_DELAY_MS = 3_000L

        /** Root of Claude Code's user data (scripts, sessions, commands live under this). */
        private val CLAUDE_HOME = File(System.getProperty("user.home"), ".claude")

        /** Where Claude Code writes `{PID}.json` session files. Read-only for the plugin. */
        private val SESSIONS_DIR = File(CLAUDE_HOME, "sessions")

        /** Where bash scripts drop `{sessionId}.json` rename directives for the plugin to pick up. */
        private val TABS_DIR = File(CLAUDE_HOME, "rider-plugin/tabs")

        /** Where per-project restore files (`restore-<projectPath>.json`) and `history.json` live. */
        private val STATE_DIR = File(CLAUDE_HOME, "rider-plugin")

        /** Markers wrapping the plugin's section of `~/.claude/CLAUDE.md` so it can be replaced cleanly. */
        private const val CLAUDE_MD_MARKER = "<!-- rider-claude-tabs-plugin -->"

        /** Permission lines inserted into `~/.claude/settings.json` so Claude can run our helper scripts
         *  without prompting. The first one is legacy (kept for backward-compatible cleanup); the rest cover
         *  the bundled Node helpers used by /tab and /tabs-backup. */
        private val PERMISSION_ENTRIES = listOf(
            "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)",
            "Bash(bash ~/.claude/rider-plugin/tab.sh *)",
            "Bash(node ~/.claude/rider-plugin/tab-backup.js *)",
            "Bash(node ~/.claude/rider-plugin/backup-active.js)",
            "Bash(node ~/.claude/rider-plugin/backup-active.js *)",
            "Bash(node ~/.claude/rider-plugin/current-project.js)",
        )

        /** Long-term session history — one JSON entry per closed/backed-up session. */
        private val HISTORY_FILE = File(CLAUDE_HOME, "rider-plugin/history.json")

        /** Rotating snapshots of restore-*.json — one per successful non-empty save. */
        private val SNAPSHOTS_DIR = File(CLAUDE_HOME, "rider-plugin/snapshots")

        /**
         * User-overridable config file. Read once at startup (see [loadConfig]). Defaults
         * below are used when the file is missing or a field is malformed. Users can create
         * or edit this file to change retention policies without recompiling the plugin.
         */
        private val CONFIG_FILE = File(CLAUDE_HOME, "rider-plugin/config.json")

        /** Singleton storage helper — owns all read/write of the per-project restore file,
         *  snapshots, and history.json. The orchestration in this class converts between its
         *  nested [SavedSession] and Storage's identically-shaped record at the boundary. */
        private val storage = ClaudeTabsStorage(CLAUDE_HOME)

        // ── Live config (loaded from CONFIG_FILE; defaults apply if not overridden). ──

        /** History entries older than this are pruned on every append. Default: 90 days. */
        var historyMaxAgeMs: Long = 90L * 24 * 60 * 60 * 1000
            private set

        /** How many recent snapshots to retain per project. Default: 10. */
        var snapshotKeepCount: Int = 10
            private set

        /**
         * Load [CONFIG_FILE] and apply any recognised fields, falling back to defaults for
         * anything missing or malformed. Accepted fields:
         *   - `historyMaxAgeDays` — integer (converted to ms internally)
         *   - `snapshotKeepCount` — integer
         */
        private fun loadConfig() {
            if (!CONFIG_FILE.exists()) return
            try {
                val cfg = ClaudeTabsHelpers.parseConfig(CONFIG_FILE.readText())
                historyMaxAgeMs = cfg.historyMaxAgeMs
                snapshotKeepCount = cfg.snapshotKeepCount
                LOG.info("[ClaudeTabs] Config loaded: historyMaxAgeDays=${historyMaxAgeMs / (24*60*60*1000)}, snapshotKeepCount=$snapshotKeepCount")
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] Config load failed (using defaults): ${e.message}")
            }
        }

        /** Write a commented template config.json if the file doesn't exist yet. */
        private fun maybeWriteConfigTemplate() {
            if (CONFIG_FILE.exists()) return
            try {
                CONFIG_FILE.parentFile?.mkdirs()
                CONFIG_FILE.writeText(
                    """{
  "_comment": "Claude Terminal Tab Persistence — edit values and restart Rider to apply.",
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10
}
"""
                )
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] Config template write failed: ${e.message}")
            }
        }

        /**
         * Removes all plugin artifacts from ~/.claude.
         * Called on plugin uninstall or via /tabs-clear command.
         */
        @JvmStatic
        fun uninstall() {
            // 1. Remove CLAUDE.md section
            val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
            if (claudeMd.exists()) {
                val text = claudeMd.readText()
                if (text.contains(CLAUDE_MD_MARKER)) {
                    val pattern = Regex("\n?${Regex.escape(CLAUDE_MD_MARKER)}.*?${Regex.escape(CLAUDE_MD_MARKER)}\n?", RegexOption.DOT_MATCHES_ALL)
                    claudeMd.writeText(text.replace(pattern, "\n").trim() + "\n")
                }
            }

            // 2. Remove permission entries from settings.json
            val settings = File(CLAUDE_HOME, "settings.json")
            if (settings.exists()) {
                var text = settings.readText()
                for (entry in PERMISSION_ENTRIES) {
                    text = text.replace("\"$entry\", ", "")
                        .replace(", \"$entry\"", "")
                        .replace("\"$entry\"", "")
                }
                settings.writeText(text)
            }

            // 3. Remove deployed scripts and data
            File(CLAUDE_HOME, "rider-plugin").deleteRecursively()
            File(CLAUDE_HOME, "commands/tab.md").delete()
            File(CLAUDE_HOME, "commands/tabs-clear.md").delete()
            File(CLAUDE_HOME, "commands/tabs-restore.md").delete()
            File(CLAUDE_HOME, "commands/tabs-history.md").delete()
            File(CLAUDE_HOME, "commands/tabs-backup.md").delete()
            File(CLAUDE_HOME, "commands/tabs-status.md").delete()
            // Legacy command filenames (pre-rename)
            File(CLAUDE_HOME, "commands/clear-tabs.md").delete()
            File(CLAUDE_HOME, "commands/restore-tabs.md").delete()
            File(CLAUDE_HOME, "commands/tab-history.md").delete()
            File(CLAUDE_HOME, "commands/backup-tabs.md").delete()
        }
    }

    private val renamedSessions = mutableSetOf<String>()
    /** Last name the plugin itself applied to each session. If the current tab name diverges from
     * this, we infer the user manually renamed the tab and back off — see [poll]. */
    private val lastAppliedName = mutableMapOf<String, String>()

    /**
     * Direct sessionId → TerminalWidget map for tabs WE spawned via [spawnNewTabAndRestore].
     *
     * The Rider 2026.1 reworked terminal has at least four separate tab-tracking subsystems
     * (`TerminalToolWindowTabsManager`, `TerminalTabsManager`, `TerminalSessionsManager`,
     * `ContentManager`) and tabs created via [TerminalToolWindowManager.createShellWidget] don't
     * consistently register with any of them — so the platform's tab-enumeration APIs miss them.
     * Rather than fight the platform layer by layer, we keep our own handle to the widget at
     * spawn time and use it for any operation that needs to touch the spawned tab (renames,
     * title pinning, listener installation). The widget reference is durable across the
     * lifetime of the tab.
     *
     * Cleared per project on project close (see disposer).
     */
    private val spawnedWidgets = java.util.concurrent.ConcurrentHashMap<String, com.intellij.terminal.ui.TerminalWidget>()

    /**
     * Rotated → canonical session id aliases. Populated when [poll]'s canonical resolution
     * detects that a session's current in-memory id differs from its canonical (transcript-
     * backed) id — this happens after `claude --resume`, which rotates the in-memory id but
     * keeps the transcript filename at the canonical id.
     *
     * Why it matters: the `/tab` script reads `sessions/<pid>.json` and writes the rename
     * file keyed by whichever id Claude currently reports — the rotated one for resumed
     * sessions. But [spawnedWidgets] and [lastAppliedName] are keyed by canonical (the id
     * we used at spawn). Without an alias map, [handleRename] looks up by the rotated id,
     * misses every cache, and the rename silently fails. With the map: alias-resolve before
     * any cache lookup, and writes go under both keys so future operations work either way.
     */
    private val sessionAliases = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Resolve [sid] to its canonical form. Two-tier:
     *
     *  1. Cached alias (populated by [poll] after the first canonical resolution).
     *  2. Eager discovery: walk `sessions/<pid>.json` to find a process whose rotated id
     *     matches [sid], compute canonical via [canonicalSessionIdFor], cache the result.
     *
     * Tier 2 matters because [poll] runs every 5s and the user may hit `/tab` immediately
     * after `/resume` — before the poll has had a chance to record the alias. Without eager
     * lookup, the first `/tab` after resume would fail. */
    internal fun canonicalize(sid: String): String {
        sessionAliases[sid]?.let { return it }
        try {
            val files = SESSIONS_DIR.listFiles { f -> f.name.endsWith(".json") } ?: return sid
            for (sf in files) {
                val pid = sf.nameWithoutExtension.toLongOrNull() ?: continue
                val text = try { sf.readText() } catch (_: Exception) { continue }
                val rawSid = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: continue
                if (rawSid != sid) continue
                val cwd = ClaudeTabsHelpers.extractJsonString(text, "cwd") ?: continue
                val startedAt = Regex(""""startedAt":(\d+)""").find(text)?.groupValues?.get(1)
                    ?.toLongOrNull() ?: System.currentTimeMillis()
                val canonical = canonicalSessionIdFor(pid, cwd, rawSid, startedAt)
                if (canonical != sid) sessionAliases[sid] = canonical
                return canonical
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] canonicalize($sid) eager lookup failed: ${e.message}")
        }
        return sid
    }

    /** How long after `loadRestoreFile` to wait before firing the create-restore. Lets Rider
     *  finish bringing back its own remembered terminal tab titles (it can't bring back the
     *  Claude processes that were inside — those are gone — but it does restore the tab
     *  *titles* from `.idea/workspace.xml`). We close any such stale tabs (matching one of our
     *  saved session names AND containing no Claude process) before spawning fresh ones, to
     *  avoid duplicates. 5s is plenty for Rider's terminal tool window to populate.
     *
     *  The old design *matched* saved sessions to existing tabs and typed `claude --resume`
     *  into them — every off-by-one and "wrong chat restored" bug came from that step. The
     *  new design spawns a brand-new tab per saved session via
     *  [TerminalToolWindowManager.createShellWidget] and types the correct resume command in
     *  *by construction*, so mis-assignment is impossible. */
    private val RESTORE_SETTLE_MS = 5_000L

    /** Per-session disposables for installed [com.intellij.terminal.TerminalTitleListener]s.
     *  Listener re-applies our [lastAppliedName] whenever the AI Assistant (or anything else)
     *  overwrites `userDefinedTitle`. One listener per session; idempotent install. */
    private val titleListenerDisposables = mutableMapOf<String, Disposable>()

    /** Rate-limit map for high-frequency log keys (e.g. AI overlay re-apply, restore-pending
     *  no-tab). Key is an arbitrary log identifier (often `sessionId` or `pending-$sessionId`),
     *  value is the last-logged epoch ms. Without this we'd log dozens of lines per second
     *  during Claude streaming as the AI Assistant rewrites the title on every output chunk.
     *  Set Registry key `rider.claude.tabs.verboseLogs=true` to bypass rate limiting entirely. */
    private val rateLimitedLogAt = mutableMapOf<String, Long>()
    private val RATE_LIMITED_LOG_INTERVAL_MS = 60_000L

    /** When was each active session last upserted into history.json — throttles continuous
     *  history tracking so the 5s poll loop doesn't rewrite history.json once per session per
     *  tick. We still want frequent upsert so a hard PC crash doesn't drop the session from
     *  history, but every poll is overkill. 60s is a reasonable trade. */
    private val lastHistoryUpsertAt = mutableMapOf<String, Long>()
    private val HISTORY_UPSERT_INTERVAL_MS = 60_000L

    /** True if Registry key `rider.claude.tabs.verboseLogs` is set OR DEBUG logging is enabled
     *  for this class. When true, rate-limited log lines fire on every event instead of being
     *  suppressed. Use this for diagnosing title-contention or restore-matching issues without
     *  having to rebuild the plugin. */
    private fun isVerboseLogging(): Boolean {
        return try {
            com.intellij.openapi.util.registry.Registry.`is`("rider.claude.tabs.verboseLogs", false)
        } catch (_: Throwable) { false } || LOG.isDebugEnabled
    }

    /**
     * Per-project mutable state.
     *
     * `ClaudeTabWatcherStartup` is registered as a `<postStartupActivity>` and is therefore an
     * **application-level singleton** — IntelliJ creates one instance and calls [runActivity]
     * once per project. Any plain `private val foo = mutableListOf(...)` field on this class
     * is *shared* across every open project, which is exactly the wrong scope for restore-queue
     * and active-session bookkeeping. Concretely the bug we hit:
     *  - Project A's poll wrote A's active sessions into `previousActive`.
     *  - Project B's poll then read the same `previousActive`, didn't see A's sessions in B's
     *    `activeSessions`, and concluded they had "closed" — appending them to history every
     *    poll cycle. Symptoms: history.json polluted with constant fake closures, and
     *    `pendingRestores` could grow with cross-project entries on overlapping startups.
     *
     * Everything below that's logically per-project lives in [ProjectCtx], indexed by
     * [com.intellij.openapi.project.Project.getLocationHash] (stable for the lifetime of the
     * project window). Per-session state (keyed by globally-unique session UUIDs) does NOT
     * need scoping and stays at instance level.
     */
    private data class ProjectCtx(
        /** Saved sessions loaded from disk that we still need to spawn fresh tabs for. */
        val pendingRestores: MutableList<SavedSession> = mutableListOf(),
        /** Wall-clock ms when [loadRestoreFile] populated [pendingRestores]. Used to enforce
         *  the [RESTORE_SETTLE_MS] grace period before [processPendingRestores] fires — so
         *  Rider has time to repopulate any remembered tab titles, and we can detect/close
         *  the stale empty-shell ones before spawning our own. */
        var pendingRestoresLoadedAt: Long = 0L,
        /** True once the create-restore has fired for this project on the current Rider run.
         *  Prevents a second pass from spawning duplicate tabs if the restore file isn't yet
         *  deleted (e.g. a save races us before we get to clean up). */
        var restoreFired: Boolean = false,
        /** Sessions seen active at least once in this project. Used to detect closures and write
         *  history — must be project-scoped or one project's poll will mark the other's sessions
         *  as closed every cycle. */
        val previousActive: MutableMap<String, SavedSession> = mutableMapOf(),
        /** Cumulative list of sessions restored on this Rider start, for [writeLastRestoreSnapshot]. */
        val restoredThisRun: MutableList<SavedSession> = mutableListOf(),
        /** Per-project poll counter. Drives the every-12th-poll diagnostic logs in [getAllTabs]
         *  and [poll]; sharing it would make logs fire on a different cadence per project. */
        var pollCount: Int = 0,
        /** Sessions for which we already spawned a fresh terminal tab via
         *  [TerminalToolWindowManager.createShellWidget]. Belt-and-braces dedup in case
         *  [processPendingRestores] is re-entered while the previous spawn is still warming up. */
        val spawnedForSession: MutableSet<String> = mutableSetOf(),
        /** Session IDs the user explicitly closed via the terminal tab's X (right-click
         *  → Close Tab / Close Others / Close All also funnel through Content removal).
         *  Subtracted from the high-water-mark union when writing the restore file, so
         *  user-closed sessions don't come back on the next start. NOT populated when the
         *  PROJECT (or Rider itself) is closing — see [projectClosing]. */
        val userClosedSessions: MutableSet<String> = mutableSetOf(),
        /** Map from a terminal tab's `Content` to the canonical sessionId we know it holds.
         *  Populated by [getAllTabs] each poll; consulted by the `ContentManagerListener`
         *  when a Content is removed so we can identify which session was just closed.
         *  IdentityHashMap because `Content` doesn't implement value-equality. */
        val contentToSid: MutableMap<com.intellij.ui.content.Content, String> = java.util.IdentityHashMap(),
        /** True once the project is in its shutdown sequence (`ProjectManagerListener.
         *  projectClosing` fired OR our disposer ran). While true, `ContentManagerListener`
         *  ignores `contentRemoved` events — those are project-shutdown tear-down, not
         *  user-initiated tab closes. */
        @Volatile var projectClosing: Boolean = false,
        /** Wall-clock ms when the plugin attached to this project. Used by
         *  [TabCloseClassifier] to apply a [STARTUP_GRACE_MS] grace period during which
         *  `contentRemoved` events are tool-window re-layout (restored tabs, splits,
         *  popouts being re-attached) rather than user closes. This is the root cause
         *  of the missing-tabs bug: Rider fired contentRemoved for 3-5 tabs at
         *  startup, the old listener marked them as user-closed, and the save loop
         *  subtracted them from the restore file forever. */
        val startupAt: Long = System.currentTimeMillis(),
    )

    private val projectCtx = java.util.concurrent.ConcurrentHashMap<String, ProjectCtx>()
    private fun ctx(project: Project): ProjectCtx =
        projectCtx.computeIfAbsent(project.locationHash) { ProjectCtx() }

    /**
     * Schedule a deferred process-alive check for [sid] after [CLOSE_VERIFY_DELAY_MS]. The
     * actual close decision happens in the scheduled task:
     *  - If the project is disposed or closing when the task fires → skip (project teardown,
     *    not user intent).
     *  - If [isClaudeAliveForSession] returns true → the close event was a Rider UI shuffle
     *    (split move, popout, drag-to-reorder). The process is still running, so DO NOT
     *    record a user close.
     *  - Otherwise → genuine user close (X button, right-click → Close Tab, etc.). Add to
     *    [ProjectCtx.userClosedSessions] so the save loop subtracts it from the restore file.
     *
     * Logs every step with `[ClaudeTabs][verify]` so diagnostic timelines are reconstructable
     * from idea.log without guessing.
     */
    private fun scheduleCloseVerification(project: Project, sid: String, tabName: String) {
        val executor = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
        executor.schedule({
            try {
                val verbose = isVerboseLogging()
                if (project.isDisposed) {
                    if (verbose) LOG.info("[ClaudeTabs][verify] sid=$sid skip — project disposed during wait (tab='$tabName')")
                    return@schedule
                }
                val c = ctx(project)
                if (c.projectClosing) {
                    if (verbose) LOG.info("[ClaudeTabs][verify] sid=$sid skip — project closing during wait (tab='$tabName')")
                    return@schedule
                }
                val alive = isClaudeAliveForSession(sid, verbose)
                if (alive) {
                    if (verbose) LOG.info("[ClaudeTabs][verify] sid=$sid alive=true — UI shuffle, NOT recording (tab='$tabName')")
                    return@schedule
                }
                // Genuine close: process is gone, past grace, project isn't shutting down. This
                // IS a state change (the session won't auto-restore next time) so it logs at
                // info regardless of verbose mode.
                synchronized(c.userClosedSessions) { c.userClosedSessions.add(sid) }
                LOG.info("[ClaudeTabs] Tab closed by user — session $sid removed from restore (tab='$tabName')")
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs][verify] sid=$sid unexpected error: ${e.message}", e)
            }
        }, CLOSE_VERIFY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Scan [SESSIONS_DIR] for a `<pid>.json` whose `sessionId` field matches [sid] AND whose
     * pid is an alive process. Returns true if such a session file is found.
     *
     * Claude unlinks its `~/.claude/sessions/<pid>.json` when the process exits cleanly. On
     * a hard kill (terminal X button) the file may linger briefly until the OS reaps it, but
     * the pid lookup catches that — we only consider a session alive if BOTH the file exists
     * and `ProcessHandle.of(pid).isPresent`.
     */
    private fun isClaudeAliveForSession(sid: String, verbose: Boolean = false): Boolean {
        val dir = SESSIONS_DIR
        if (!dir.isDirectory) {
            if (verbose) LOG.info("[ClaudeTabs][verify] sid=$sid SESSIONS_DIR missing — assuming dead")
            return false
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return false
        for (f in files) {
            try {
                val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
                val text = f.readText()
                // Cheap substring check first; full parse only if the sid string appears.
                if (!text.contains(sid)) continue
                if (!text.contains("\"sessionId\":\"$sid\"")) continue
                val processAlive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                if (processAlive) {
                    if (verbose) LOG.info("[ClaudeTabs][verify] sid=$sid found alive pid=$pid")
                    return true
                } else {
                    if (verbose) LOG.info("[ClaudeTabs][verify] sid=$sid file present (pid=$pid) but process not alive")
                }
            } catch (_: Exception) { /* unreadable file, skip */ }
        }
        return false
    }

    /**
     * IntelliJ entry point. Fires once per project open. Starts two coroutines:
     *  - a [java.nio.file.WatchService] on the tabs dir for instant renames
     *  - a main poll loop that does rename fallback + state save + history tracking
     *
     * The project [Disposable] ensures both coroutines shut down on project close, and any
     * still-active sessions get written to history for later browsing.
     */
    override fun runActivity(project: Project) {
        // Startup banner — captures plugin version, IDE build, project, and AI host presence.
        val ideInfo = try {
            val app = com.intellij.openapi.application.ApplicationInfo.getInstance()
            "${app.versionName} ${app.fullVersion} (build ${app.build.asString()})"
        } catch (e: Exception) { "unknown (${e.message})" }
        val pluginVersion = try {
            com.intellij.ide.plugins.PluginManager.getPluginByClass(javaClass)?.version ?: "unknown"
        } catch (e: Exception) { "unknown (${e.message})" }
        LOG.info("[ClaudeTabs] ════════════════════════════════════════════════════════")
        LOG.info("[ClaudeTabs] Started for: ${project.name}")
        LOG.info("[ClaudeTabs] Plugin version: $pluginVersion")
        LOG.info("[ClaudeTabs] IDE: $ideInfo")
        LOG.info("[ClaudeTabs] Project base path: ${project.basePath}")
        if (AiAgentsDetector.isActive(project)) {
            LOG.info("[ClaudeTabs] JetBrains AI Assistant / Claude Agent host detected — this plugin manages " +
                    "terminal-launched Claude CLI sessions only. AI Chat tabs are managed by JetBrains. " +
                    "Set Registry key '${AiAgentsDetector.REGISTRY_KEY}'=false to silence this notice.")
        } else {
            LOG.info("[ClaudeTabs] No AI Assistant host detected.")
        }
        LOG.info("[ClaudeTabs] Verbose logs: ${if (isVerboseLogging()) "ON (bypassing rate limits)" else "OFF (rate-limited; set Registry rider.claude.tabs.verboseLogs=true to enable)"}")
        LOG.info("[ClaudeTabs] Close detection: startup grace=${STARTUP_GRACE_MS}ms, verify delay=${CLOSE_VERIFY_DELAY_MS}ms (ProjectCtx.startupAt=${ctx(project).startupAt})")
        LOG.info("[ClaudeTabs] ════════════════════════════════════════════════════════")
        TABS_DIR.mkdirs()
        maybeWriteConfigTemplate()
        loadConfig()
        deployClaudeIntegration()
        pruneStaleHistoryEntries()
        pruneStaleRestoreEntries(project)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // ProjectManagerListener.projectClosing fires BEFORE any tab content is removed
        // during project teardown. Set our closing flag so the ContentManagerListener
        // installed below treats subsequent contentRemoved events as project-shutdown
        // (don't mark as user-closed), not as user-initiated tab closures.
        try {
            val pmListener = object : com.intellij.openapi.project.ProjectManagerListener {
                override fun projectClosing(p: com.intellij.openapi.project.Project) {
                    if (p == project) {
                        ctx(project).projectClosing = true
                        LOG.info("[ClaudeTabs] Project closing detected — suppressing user-close tracking")
                    }
                }
            }
            com.intellij.openapi.project.ProjectManager.getInstance().addProjectManagerListener(project, pmListener)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] ProjectManagerListener install failed: ${e.message}")
        }

        // ContentManagerListener tracks terminal-tab close events. The naive interpretation
        // (every contentRemoved == user closed this tab) was the root cause of the
        // missing-tabs bug. Rider fires contentRemoved during STARTUP tool-window
        // re-layout (restored tabs being attached, splits, popouts) — those look
        // identical to real user closes. The old code marked those as user-closed and
        // the save loop subtracted them out of the restore file forever.
        //
        // The classifier (TabCloseClassifier) now applies two filters:
        //   1. Startup grace [STARTUP_GRACE_MS]: events in the first 30s after attach
        //      are tool-window re-layout, not user closes. Ignore unconditionally.
        //   2. Defer-and-verify [CLOSE_VERIFY_DELAY_MS]: past the grace period, schedule
        //      a 3-second-deferred check on whether the Claude process for that
        //      sessionId is still alive (~/.claude/sessions/<pid>.json present). If
        //      alive → it was a UI shuffle (split move, drag), do nothing. If dead →
        //      genuine user close, add to userClosedSessions.
        try {
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow
            val cmgr = tw?.contentManager
            if (cmgr != null) {
                val cmListener = object : com.intellij.ui.content.ContentManagerListener {
                    override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                        val c = ctx(project)
                        val content = event.content
                        val displayName = try { content.displayName ?: "?" } catch (_: Exception) { "?" }
                        val widgetSid = spawnedWidgets.entries.firstOrNull { (_, w) ->
                            try {
                                TerminalToolWindowManager.findWidgetByContent(content) === w
                            } catch (_: Exception) { false }
                        }?.key
                        val mapSid = c.contentToSid[content]
                        val sinceStartup = System.currentTimeMillis() - c.startupAt
                        // Per-event detail logged at DEBUG so production idea.log isn't spammed
                        // by every tool-window re-layout, split move, and drag. Enable by setting
                        // Registry rider.claude.tabs.verboseLogs=true OR enabling DEBUG for this
                        // class via Help → Diagnostic Tools → Debug Log Settings.
                        val verbose = isVerboseLogging()
                        if (verbose) {
                            LOG.info("[ClaudeTabs][close] contentRemoved fired" +
                                    " — project='${project.name}' tab='$displayName'" +
                                    " mapSid=${mapSid ?: "none"} widgetSid=${widgetSid ?: "none"}" +
                                    " projectClosing=${c.projectClosing}" +
                                    " msSinceStartup=$sinceStartup graceMs=$STARTUP_GRACE_MS")
                        }
                        when (val d = TabCloseClassifier.classify(
                            projectClosing = c.projectClosing,
                            millisSinceStartup = sinceStartup,
                            startupGraceMillis = STARTUP_GRACE_MS,
                            sessionIdFromMap = mapSid,
                            sessionIdFromWidgetFallback = widgetSid,
                        )) {
                            is TabCloseClassifier.Decision.DeferAndVerify -> {
                                if (verbose) LOG.info("[ClaudeTabs][close] decision=DeferAndVerify sid=${d.sessionId} — scheduling alive-check in ${CLOSE_VERIFY_DELAY_MS}ms")
                                scheduleCloseVerification(project, d.sessionId, displayName)
                                c.contentToSid.remove(content)
                            }
                            TabCloseClassifier.Decision.IgnoreProjectClosing -> {
                                if (verbose) LOG.info("[ClaudeTabs][close] decision=IgnoreProjectClosing tab='$displayName'")
                            }
                            TabCloseClassifier.Decision.IgnoreStartupGrace -> {
                                if (verbose) LOG.info("[ClaudeTabs][close] decision=IgnoreStartupGrace (${sinceStartup}ms elapsed) tab='$displayName'")
                            }
                            TabCloseClassifier.Decision.NoMappedSession -> {
                                if (verbose) LOG.info("[ClaudeTabs][close] decision=NoMappedSession tab='$displayName'")
                            }
                        }
                    }
                }
                cmgr.addContentManagerListener(cmListener)
                // Tie the listener lifetime to the project so it gets removed on close.
                Disposer.register(project as Disposable, Disposable {
                    try { cmgr.removeContentManagerListener(cmListener) } catch (_: Exception) {}
                })
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] ContentManagerListener install failed: ${e.message}")
        }

        Disposer.register(project as Disposable, Disposable {
            val c = ctx(project)
            // Make sure flag is set before history flush (defence in depth — usually the
            // ProjectManagerListener has already set it).
            c.projectClosing = true
            LOG.info("[ClaudeTabs] Project closing — saving ${c.previousActive.size} session(s) to history")
            for ((sid, session) in c.previousActive) {
                appendToHistory(session)
                // Drop any cached widget refs for this project so we don't leak them
                // across close/reopen cycles (the widget is being disposed anyway).
                spawnedWidgets.remove(sid)
            }
            // Drop the entry entirely; if the project is reopened, runActivity fires again and a
            // fresh ProjectCtx is created on first ctx() call. Leaving stale ctxs in the map
            // would slowly leak memory across many close/reopen cycles.
            projectCtx.remove(project.locationHash)
            scope.cancel()
        })

        // File watcher for instant renames
        scope.launch {
            delay(2_000)
            try { watchTabsDirectory(project) } catch (e: Exception) { LOG.debug("[ClaudeTabs] Watcher failed: ${e.message}") }
        }

        // Main poll loop
        scope.launch {
            delay(3_000)

            // Load restore file
            withContext(Dispatchers.Main) { loadRestoreFile(project) }

            val startupTime = System.currentTimeMillis()
            while (isActive) {
                try {
                    withContext(Dispatchers.Main) {
                        processPendingRestores(project)
                        poll(project)
                    }
                } catch (_: ProcessCanceledException) { break }
                catch (e: Exception) {
                    if (e.message?.contains("disposed") == true) break
                    if (ctx(project).pollCount % 12 == 0) LOG.warn("[ClaudeTabs] Poll: ${e.message}")
                }
                val inBurst = System.currentTimeMillis() - startupTime < 60_000
                delay(if (inBurst || ctx(project).pendingRestores.isNotEmpty()) 2_000L else POLL_INTERVAL_MS)
                ctx(project).pollCount++
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TERMINAL TAB ACCESS — stable API, all panels
    // ══════════════════════════════════════════════════════════════

    /**
     * A unified view of a single terminal tab across IntelliJ's two terminal backends:
     *  - **Classic** terminal (`TerminalWidget` via `TerminalToolWindowManager`) — [widget] set, [content] set.
     *  - **Reworked** terminal (2024.3+ split-panel aware) — [reworkedSession] + [reworkedTabId] set.
     *
     * Exactly one of the two paths will be populated, depending on which backend is active for
     * this particular tab. [pid] is the shell process PID (PowerShell / bash / cmd) at the root
     * of the tab — we walk its children with [findClaudeChild] to find the Claude process.
     */
    data class TabInfo(
        val content: Content?,              // null for reworked API tabs (split panels)
        val widget: TerminalWidget?,        // null when using reworked API
        val pid: Long,
        val reworkedSession: Any? = null,   // reworked session for PID/command access
        val reworkedTabId: Int? = null,     // for renameTerminalTab()
        val tabName: String = ""            // current tab name
    )

    /**
     * Enumerate every terminal tab in the project's terminal tool window.
     *
     * Uses reflection on both the reworked and classic terminal APIs; tabs from both paths are
     * merged into a single list of [TabInfo] entries with their PIDs resolved. Silent on API drift
     * (see `LOG.debug` messages) so one backend missing doesn't block the other.
     */
    private fun getAllTabs(project: Project): List<TabInfo> {
        val result = mutableListOf<TabInfo>()
        val pollCount = ctx(project).pollCount

        // Defensive: bail if the Terminal tool window isn't registered for this project
        // (e.g. terminal plugin disabled, or pre-init). This plugin only ever drives the
        // Terminal tool window — anything else (AI Chat, AI Agents, ACP) is out of scope.
        if (TerminalToolWindowManager.getInstance(project).toolWindow == null) return result

        // Step 1: Get frontend views — store view + content per tab
        data class FrontendEntry(val view: Any, val content: Content?)
        val frontendTabs = mutableListOf<FrontendEntry>()
        try {
            val feMgrCls = Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
            val feMgr = feMgrCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val feTabs = feMgr?.javaClass?.getMethod("getTabs")?.invoke(feMgr) as? List<*>
            if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 1: Frontend has ${feTabs?.size ?: 0} tabs")

            feTabs?.forEach { feTab ->
                feTab ?: return@forEach
                try {
                    val content = feTab.javaClass.getMethod("getContent").invoke(feTab) as? Content
                    val view = feTab.javaClass.getMethod("getView").invoke(feTab) ?: return@forEach
                    frontendTabs.add(FrontendEntry(view, content))
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] frontend tab access failed: ${e.message}")
                }
            }
            if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 2: Frontend tabs: ${frontendTabs.size}, names: ${frontendTabs.map { it.content?.displayName ?: "?" }}")
        } catch (_: ClassNotFoundException) {
            // Older IntelliJ — reworked terminal frontend not available. Falls back to classic paths.
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] TerminalToolWindowTabsManager unavailable: ${e.message}")
        }

        // Step 2: Get backend tabs (name → PID + session) for process detection
        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val tabs = tm?.let { invokeSuspend(it, tmCls.methods.find { m -> m.name == "getTerminalTabs" }!!) as? List<*> }
            if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 3: Backend has ${tabs?.size ?: 0} tabs")

            val smCls = Class.forName("com.intellij.terminal.backend.TerminalSessionsManager")
            val sm = smCls.getMethod("getInstance").invoke(null)
            val getSess = sm?.let { smCls.methods.find { m -> m.name == "getSession" && m.parameterCount == 1 } }

            val backendNames = mutableListOf<String>()
            val backendWithPids = mutableListOf<String>()
            val backendNoSession = mutableListOf<String>()

            tabs?.forEach { tab ->
                tab ?: return@forEach
                try {
                    val name = tab.javaClass.getMethod("getName").invoke(tab) as? String ?: return@forEach
                    val tabId = tab.javaClass.getMethod("getId").invoke(tab) as? Int ?: return@forEach
                    backendNames.add(name)

                    // Resolve frontend match + widget FIRST, so we have a widget for the
                    // PID fallback if the reworked backend doesn't track this tab's session.
                    val backendIdx = backendNames.size - 1
                    val fe = frontendTabs.getOrNull(backendIdx)
                    val view = fe?.view
                    val content = fe?.content
                    val hasFrontend = fe != null

                    // Resolve the public TerminalWidget for this Content even on reworked tabs.
                    // Without this, path 4 of renameTab (widget.terminalTitle.change) never fires
                    // because we previously set widget=null for reworked tabs. With it, we can
                    // hit the stable buildTitle() pipeline which is the only one that beats the
                    // JetBrains AI Assistant overlay (userDefinedTitle wins over applicationTitle
                    // in TerminalTitle.buildTitle, confirmed via bytecode inspection).
                    val widget: com.intellij.terminal.ui.TerminalWidget? = content?.let {
                        try {
                            TerminalToolWindowManager.findWidgetByContent(it)
                        } catch (e: Exception) {
                            LOG.debug("[ClaudeTabs] findWidgetByContent failed for '$name': ${e.message}")
                            null
                        }
                    }

                    // Try backend session for PID first.
                    val sessIdObj = tab.javaClass.getMethod("getSessionId").invoke(tab)
                    val session = sessIdObj?.let { getSess?.invoke(sm, it) }
                    val sessionPid = session?.let { extractPidFromSession(it) }

                    // Fallback: extract PID from the frontend widget directly. Required for
                    // tabs spawned via createShellWidget (our restore flow) and for Rider's
                    // workspace.xml-restored stale shells — both have backend rows with
                    // sessionId==null in Rider 2026.1's reworked terminal.
                    val pid = sessionPid ?: extractPidFromWidget(widget)

                    if (pid == null) {
                        // Tag the failure mode for diagnostics — helps tell "backend dropped it"
                        // from "widget didn't have a process either" (stale shell).
                        val tag = when {
                            sessIdObj == null && widget == null -> "no-sess, no-widget"
                            sessIdObj == null -> "no-sess, widget-no-pid"
                            session == null -> "sess-unresolved, widget-no-pid"
                            else -> "sess-no-pid, widget-no-pid"
                        }
                        backendNoSession.add("$name($tag)")
                        return@forEach
                    }

                    val src = if (sessionPid != null) "sess" else "widget"
                    backendWithPids.add("$name→PID$pid($src)")

                    result.add(TabInfo(
                        content = content,
                        widget = widget,
                        pid = pid,
                        reworkedSession = view ?: session,
                        reworkedTabId = tabId,
                        tabName = name
                    ))

                    if (!hasFrontend) {
                        LOG.info("[ClaudeTabs] Backend tab '$name' has NO frontend view match!")
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] backend tab access failed: ${e.message}")
                }
            }
            if (pollCount % 12 == 0) {
                LOG.info("[ClaudeTabs] STEP 3a: Backend all names: $backendNames")
                LOG.info("[ClaudeTabs] STEP 3b: Backend with PIDs: $backendWithPids")
                if (backendNoSession.isNotEmpty()) LOG.info("[ClaudeTabs] STEP 3c: Backend no session/pid: $backendNoSession")
            }
        } catch (_: ClassNotFoundException) {
            // Older IntelliJ — reworked terminal backend not available. Falls back to classic paths.
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] TerminalTabsManager unavailable: ${e.message}")
        }

        // STEP 5: ContentManager sweep — pick up tabs the reworked managers forgot.
        //
        // In Rider 2026.1, tabs created via `TerminalToolWindowManager.createShellWidget` (our
        // restore flow) often don't register with either `TerminalToolWindowTabsManager` (frontend)
        // OR `TerminalTabsManager` (backend). The user sees 7 tabs in the strip; both reworked
        // managers report 1. Without this sweep, `handleRename` enumerates only the 1 tab and
        // every /tab command for a restored session hits "no tab found".
        //
        // ContentManager.contents is the bedrock IntelliJ tool-window API — every visible tab is
        // backed by a Content here, regardless of which higher-level manager owns it. Walking it
        // gives us a complete view. We only ADD tabs not already in result (matched by Content
        // reference) so backend-tracked tabs keep their reworkedSession/reworkedTabId metadata.
        var sweepStatus = "skipped"
        try {
            val cmgrTw = TerminalToolWindowManager.getInstance(project).toolWindow
            val cmgr = cmgrTw?.contentManager
            val contentList = cmgr?.contents?.toList() ?: emptyList()
            val knownContents = result.mapNotNull { it.content }.toSet()
            var sweptAdded = 0
            var sweptSkipKnown = 0
            var sweptSkipNoWidget = 0
            var sweptSkipNoPid = 0
            contentList.forEach { content ->
                if (content in knownContents) { sweptSkipKnown++; return@forEach }
                val widget = try {
                    TerminalToolWindowManager.findWidgetByContent(content)
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] ContentManager sweep findWidgetByContent failed: ${e.message}")
                    null
                }
                if (widget == null) { sweptSkipNoWidget++; return@forEach }
                val pid = extractPidFromWidget(widget)
                if (pid == null) { sweptSkipNoPid++; return@forEach }
                result.add(TabInfo(
                    content = content,
                    widget = widget,
                    pid = pid,
                    reworkedSession = null,
                    reworkedTabId = null,
                    tabName = content.displayName ?: "Local",
                ))
                sweptAdded++
            }
            sweepStatus = "tw=${cmgrTw != null} cmgr=${cmgr != null} contents=${contentList.size} added=$sweptAdded skipKnown=$sweptSkipKnown skipNoWidget=$sweptSkipNoWidget skipNoPid=$sweptSkipNoPid"
        } catch (e: Exception) {
            sweepStatus = "exception: ${e.message}"
            LOG.debug("[ClaudeTabs] ContentManager sweep failed: ${e.message}")
        }
        if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 5: ContentManager sweep — $sweepStatus")

        // STEP 6: Union with spawnedWidgets — tabs we created via createShellWidget on restore.
        // Belt-and-suspenders against the same Rider 2026.1 gap STEP 5 covers: even if a spawned
        // tab somehow escapes the ContentManager sweep (e.g. it lives in a popped-out window
        // whose toolWindow.contentManager we can't reach from the main project frame), we still
        // have the widget reference we kept at spawn time. Without this, our own restored tabs
        // can fail to appear in the save loop and get lost on the next restart.
        var spawnAdded = 0
        var spawnSkipKnown = 0
        var spawnSkipNoPid = 0
        try {
            val knownWidgets = result.mapNotNull { it.widget }.toSet()
            for ((sid, widget) in spawnedWidgets) {
                if (widget in knownWidgets) { spawnSkipKnown++; continue }
                val pid = extractPidFromWidget(widget)
                if (pid == null) { spawnSkipNoPid++; continue }
                val title = try { widget.terminalTitle.buildTitle() } catch (_: Exception) { null }
                result.add(TabInfo(
                    content = null,
                    widget = widget,
                    pid = pid,
                    reworkedSession = null,
                    reworkedTabId = null,
                    tabName = title ?: "Local",
                ))
                spawnAdded++
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] spawnedWidgets union failed: ${e.message}")
        }
        if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 6: spawnedWidgets union — cached=${spawnedWidgets.size} added=$spawnAdded skipKnown=$spawnSkipKnown skipNoPid=$spawnSkipNoPid")

        if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 4: Total: ${result.size} → ${result.map { "'${it.tabName}'→PID${it.pid}" }}")
        return result
    }

    // ══════════════════════════════════════════════════════════════
    // REWORKED API REFLECTION HELPERS
    // ══════════════════════════════════════════════════════════════
    // These navigate private fields of IntelliJ's "reworked" terminal
    // classes (public API doesn't expose what we need). The many inner
    // try/catches are intentional: each iteration is a best-effort probe
    // and failing one field is expected — we silently try the next.

    /**
     * Walk the session object (and its `delegate`, if any) looking for a
     * `ttyConnector` field, then unwrap a [ProcessTtyConnector] to get
     * the underlying Windows/Unix PID.
     * Returns null if no connector/process is accessible.
     */
    private fun extractPidFromSession(session: Any): Long? {
        val targets = mutableListOf(session)
        try {
            val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true
            f.get(session)?.let { targets.add(0, it) }
        } catch (_: Exception) { /* no delegate — fine */ }

        for (t in targets) {
            try {
                for (field in t.javaClass.declaredFields) {
                    if (!field.name.contains("ttyConnector", true)) continue
                    field.isAccessible = true
                    val c = field.get(t) ?: continue
                    if (c is ProcessTtyConnector) return c.process.pid()
                    try {
                        (c.javaClass.getMethod("getProcess").invoke(c) as? Process)?.let { return it.pid() }
                    } catch (_: Exception) { /* no getProcess — try fields */ }
                    for (cf in c.javaClass.declaredFields) {
                        cf.isAccessible = true
                        val v = cf.get(c)
                        if (v is ProcessTtyConnector) return v.process.pid()
                        if (v is Process) return v.pid()
                    }
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] extractPidFromSession probe failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Extract the shell PID from a [TerminalWidget] directly, bypassing the reworked
     * `TerminalSessionsManager`. Critical because tabs created via
     * [TerminalToolWindowManager.createShellWidget] — which is the restore flow's mechanism — do
     * NOT register a sessionId with the reworked backend in Rider 2026.1. They show up in
     * `TerminalTabsManager.getTerminalTabs()` with `getSessionId() == null`, so
     * [extractPidFromSession] returns null for them. Without this fallback the tab would be
     * dropped from [getAllTabs], the next save would write an empty list, and the just-restored
     * tab would silently un-track itself on the next 5s poll — the "create, don't match" design's
     * self-erasing bug.
     *
     * Also handles Rider-restored stale shells (workspace.xml leftovers named "Local") for the
     * same reason. For those the widget's ttyConnector may itself be null (no process behind the
     * restored shell), in which case we return null — that's correct: a tab with no process can't
     * have Claude inside, so it doesn't belong in the save list.
     *
     * Strategy:
     *   1. Try the public `getTtyConnector()` getter via reflection (cross-version safe).
     *   2. Reflection walk over the widget's declared fields looking for anything named
     *      `ttyConnector*`, then unwrap a [ProcessTtyConnector] or a `getProcess()` method.
     */
    private fun extractPidFromWidget(widget: TerminalWidget?): Long? {
        widget ?: return null

        // Public-API path first.
        try {
            val getter = widget.javaClass.methods.find { it.name == "getTtyConnector" && it.parameterCount == 0 }
            val connector = getter?.invoke(widget)
            if (connector is ProcessTtyConnector) return connector.process.pid()
            if (connector != null) {
                try {
                    (connector.javaClass.getMethod("getProcess").invoke(connector) as? Process)?.let { return it.pid() }
                } catch (_: Exception) { /* fall through to field walk */ }
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] extractPidFromWidget getTtyConnector probe failed: ${e.message}")
        }

        // Field walk — handles reworked widget impls where the connector is buried.
        var cls: Class<*>? = widget.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (!field.name.contains("ttyConnector", true)) continue
                try {
                    field.isAccessible = true
                    val c = field.get(widget) ?: continue
                    if (c is ProcessTtyConnector) return c.process.pid()
                    try {
                        (c.javaClass.getMethod("getProcess").invoke(c) as? Process)?.let { return it.pid() }
                    } catch (_: Exception) { /* try nested fields */ }
                    for (cf in c.javaClass.declaredFields) {
                        cf.isAccessible = true
                        val v = cf.get(c)
                        if (v is ProcessTtyConnector) return v.process.pid()
                        if (v is Process) return v.pid()
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] extractPidFromWidget field probe failed for ${field.name}: ${e.message}")
                }
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * Sibling of [extractPidFromSession] for getting the raw [com.jediterm.terminal.TtyConnector]
     * when we need to send commands (e.g. restore flow) rather than just read the PID.
     */
    private fun extractConnectorFromSession(session: Any): com.jediterm.terminal.TtyConnector? {
        val targets = mutableListOf(session)
        try {
            val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true
            f.get(session)?.let { targets.add(0, it) }
        } catch (_: Exception) { /* no delegate — fine */ }

        for (t in targets) {
            try {
                for (field in t.javaClass.declaredFields) {
                    if (!field.name.contains("ttyConnector", true)) continue
                    field.isAccessible = true
                    val c = field.get(t)
                    if (c is com.jediterm.terminal.TtyConnector) return c
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] extractConnectorFromSession probe failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Call a Kotlin `suspend` function via reflection by constructing an explicit [kotlin.coroutines.Continuation]
     * and blocking on its completion. Needed because many of IntelliJ's internal methods are suspend
     * functions exposed only via `Method.invoke`.
     */
    private fun invokeSuspend(target: Any, method: java.lang.reflect.Method): Any? = kotlinx.coroutines.runBlocking {
        val d = CompletableDeferred<Any?>()
        val cont = object : kotlin.coroutines.Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) }
        }
        val r = method.invoke(target, cont)
        if (r == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) d.await() else r
    }

    /**
     * Apply [name] to the given [tab].
     *
     * On Rider 2026.1 with JetBrains AI Assistant active, the canonical surface to write is
     * `TerminalTitle.userDefinedTitle` (the source-of-truth field that wins in `buildTitle()`
     * — confirmed via bytecode inspection of `intellij.platform.lang.impl.jar`). Setting this
     * via the public `terminalTitle.change { ... }` lambda fires `TerminalTitleListener`s
     * which propagate to:
     *   - `Content.displayName` (visible tab label) — via `updateTabNameOnTitleChange`
     *   - The backend tab name (for save/restore) — via `updateBackendTabNameOnTitleChange`
     *
     * The order of paths attempted:
     *   1. **`TerminalWidget.terminalTitle.change { state.userDefinedTitle = name }`** — primary,
     *      stable API. Works for both classic and reworked tabs because [getAllTabs] now resolves
     *      the widget via `findWidgetByContent` even on the reworked path.
     *   2. **`TerminalTitle.State` reflection on `view.getTitle()`** — fallback for the rare case
     *      where `findWidgetByContent` returns null but the reworked view is reachable.
     *   3. **`Content.displayName` direct write** — belt-and-suspenders. Gets overwritten by the
     *      next title-listener fire, but flashes correctly until then.
     *   4. **Backend `TerminalTabsManager.renameTerminalTab()`** — persists across IDE restarts.
     *   5. **Listener install** — if [sessionId] is given, register a `TerminalTitleListener` so
     *      any future overwrite of `userDefinedTitle` (e.g. by the AI Assistant overlay) is
     *      detected and re-applied automatically without waiting for the 5s poll.
     */
    private fun isRenameRedundant(currentName: String?, newName: String): Boolean =
        ClaudeTabsHelpers.isRenameRedundant(currentName, newName)

    private fun renameTab(project: Project, tab: TabInfo, name: String, sessionId: String? = null) {
        // Resolve a TerminalTitle reference. Prefer the public API via `widget.terminalTitle`;
        // fall back to scanning the reworked-session view for a TerminalTitle field/getter.
        val title = findTerminalTitle(tab)

        // Redundancy short-circuit: skip the rename APIs only if both the backend name AND the
        // TerminalTitle's userDefinedTitle already match. The backend can be correct (restored
        // from prior session) while the FRONTEND `Content.displayName` is being overlaid by the
        // AI Assistant — in that case we still need to apply userDefinedTitle so the listener
        // chain repaints. Always install the listener regardless of the short-circuit.
        val backendMatches = isRenameRedundant(tab.tabName, name)
        val titleMatches = title?.userDefinedTitle == name
        if (backendMatches && titleMatches) {
            LOG.info("[ClaudeTabs] Skipping redundant rename '${tab.tabName}' → '$name' (userDefinedTitle already set)")
            if (sessionId != null && title != null) {
                lastAppliedName.putIfAbsent(sessionId, name)
                installTitleListener(project, title, sessionId)
            }
            return
        }
        if (backendMatches && !titleMatches) {
            LOG.info("[ClaudeTabs] Backend matches but userDefinedTitle is '${title?.userDefinedTitle}' — proceeding with rename to drive frontend listeners")
        }
        if (title != null) {
            try {
                title.change { userDefinedTitle = name }
                LOG.info("[ClaudeTabs] Renamed via TerminalTitle.change(): userDefinedTitle='${title.userDefinedTitle}', applicationTitle='${title.applicationTitle}', defaultTitle='${title.defaultTitle}'")
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] TerminalTitle.change() failed: ${e.message}")
            }
        } else {
            LOG.warn("[ClaudeTabs] Could NOT resolve TerminalTitle for tab '${tab.tabName}' (widget=${tab.widget != null}, view=${tab.reworkedSession?.javaClass?.simpleName}) — relying on Content.displayName + backend rename only")
        }

        // Belt-and-suspenders: also write Content.displayName directly. If the title-listener
        // chain propagates correctly this is redundant, but if not it makes the rename visible
        // until the listener settles.
        tab.content?.displayName = name

        // Backend persistence: ensures the rename survives IDE restart.
        if (tab.reworkedTabId != null) {
            try {
                val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
                val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
                val renameMethod = tmCls.methods.find { it.name == "renameTerminalTab" }
                if (tm != null && renameMethod != null) {
                    val d = CompletableDeferred<Any?>()
                    val cont = object : kotlin.coroutines.Continuation<Any?> {
                        override val context = kotlin.coroutines.EmptyCoroutineContext
                        override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) }
                    }
                    renameMethod.invoke(tm, tab.reworkedTabId, name, true, cont)
                    LOG.info("[ClaudeTabs] Backend rename submitted: tabId=${tab.reworkedTabId}")
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] Backend renameTerminalTab failed: ${e.message}")
            }
        }

        // Install a TerminalTitleListener so the next time userDefinedTitle gets overwritten
        // (e.g. by the JetBrains AI Assistant overlay), we re-apply our value immediately
        // rather than waiting for the 5s poll to catch up.
        if (sessionId != null && title != null) {
            installTitleListener(project, title, sessionId)
        }
    }

    /**
     * Resolve a [com.intellij.terminal.TerminalTitle] reference for [tab], preferring the
     * public API via `widget.terminalTitle`. Falls back to inspecting the reworked-session
     * view object via reflection — Rider 2026.1's reworked tabs sometimes have widget=null
     * out of `findWidgetByContent`, so we have to dig.
     */
    private fun findTerminalTitle(tab: TabInfo): com.intellij.terminal.TerminalTitle? {
        tab.widget?.let { return it.terminalTitle }
        val view = tab.reworkedSession ?: return null
        // Try common getter names
        for (methodName in listOf("getTitle", "getTerminalTitle")) {
            try {
                val result = view.javaClass.getMethod(methodName).invoke(view)
                if (result is com.intellij.terminal.TerminalTitle) {
                    LOG.info("[ClaudeTabs] Resolved TerminalTitle via $methodName() on ${view.javaClass.simpleName}")
                    return result
                }
            } catch (_: NoSuchMethodException) {
                // try next
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] $methodName() failed on ${view.javaClass.simpleName}: ${e.message}")
            }
        }
        // Scan declared fields
        for (f in view.javaClass.declaredFields) {
            if (com.intellij.terminal.TerminalTitle::class.java.isAssignableFrom(f.type)) {
                try {
                    f.isAccessible = true
                    val result = f.get(view) as? com.intellij.terminal.TerminalTitle
                    if (result != null) {
                        LOG.info("[ClaudeTabs] Resolved TerminalTitle via field '${f.name}' on ${view.javaClass.simpleName}")
                        return result
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] field-${f.name} access failed: ${e.message}")
                }
            }
        }
        // Last resort: walk a 'delegate' field if present, then repeat
        try {
            val delegateField = view.javaClass.getDeclaredField("delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(view)
            if (delegate != null && delegate !== view) {
                LOG.info("[ClaudeTabs] Walking 'delegate' field on ${view.javaClass.simpleName}")
                return findTerminalTitle(tab.copy(reworkedSession = delegate, widget = null))
            }
        } catch (_: NoSuchFieldException) { /* no delegate */ }
        catch (e: Exception) { LOG.debug("[ClaudeTabs] delegate walk failed: ${e.message}") }
        return null
    }

    /**
     * Install a [com.intellij.terminal.TerminalTitleListener] for [sessionId] (idempotent —
     * one listener per session). Whenever `userDefinedTitle` diverges from [lastAppliedName],
     * we re-apply unless the new value looks like a deliberate user rename. Disposed when the
     * project closes (via the [Disposer] hierarchy rooted at the project).
     */
    private fun installTitleListener(project: Project, title: com.intellij.terminal.TerminalTitle, sessionId: String) {
        if (sessionId in titleListenerDisposables) return
        try {
            val parentDisposable = Disposer.newDisposable("ClaudeTabs-titleListener-$sessionId")
            Disposer.register(project as Disposable, parentDisposable)
            val listener = object : com.intellij.terminal.TerminalTitleListener {
                override fun onTitleChanged(t: com.intellij.terminal.TerminalTitle) {
                    val desired = lastAppliedName[sessionId] ?: return
                    val current = t.userDefinedTitle
                    if (current == desired) return
                    val looksLikeOverlay = current == null ||
                        current.isBlank() ||
                        ClaudeTabsHelpers.isGenericTabName(current) ||
                        ClaudeTabsHelpers.isAiOverlayName(current, project.name)
                    if (looksLikeOverlay) {
                        // The AI Assistant overlay can rewrite the title many times per second
                        // during Claude streaming. Re-applying our value is silent and cheap, so
                        // we always do it — but the log line is rate-limited to once per session
                        // per minute so idea.log doesn't drown. Set Registry key
                        // `rider.claude.tabs.verboseLogs=true` to log every occurrence.
                        val verbose = isVerboseLogging()
                        val now = System.currentTimeMillis()
                        val lastLogged = rateLimitedLogAt[sessionId] ?: 0L
                        if (verbose) {
                            LOG.info("[ClaudeTabs] AI overlay overwrote title for session $sessionId (now '$current') — re-applying '$desired' [verbose]")
                        } else if (now - lastLogged > RATE_LIMITED_LOG_INTERVAL_MS) {
                            LOG.info("[ClaudeTabs] AI overlay overwrote title for session $sessionId (now '$current') — re-applying '$desired' (further events suppressed for ${RATE_LIMITED_LOG_INTERVAL_MS / 1000}s; set Registry rider.claude.tabs.verboseLogs=true for every event)")
                            rateLimitedLogAt[sessionId] = now
                        }
                        try {
                            t.change { userDefinedTitle = desired }
                        } catch (e: Exception) {
                            LOG.debug("[ClaudeTabs] re-apply via change() failed: ${e.message}")
                        }
                    } else if (current != null) {
                        LOG.info("[ClaudeTabs] User-driven title for session $sessionId: '$current' — accepting")
                        lastAppliedName[sessionId] = current
                        // Right-click → Rename Session (and any other in-app rename surface)
                        // funnels through the title listener. Treat it as a writing action:
                        // persist the new name to the restore file immediately so the user's
                        // rename survives a crash before the next poll.
                        val canonical = canonicalize(sessionId)
                        if (canonical != sessionId) lastAppliedName[canonical] = current
                        persistRenameImmediately(project, canonical, current)
                    }
                }
            }
            title.addTitleListener(listener, parentDisposable)
            titleListenerDisposables[sessionId] = parentDisposable
            LOG.info("[ClaudeTabs] Installed TerminalTitleListener for session $sessionId")
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] installTitleListener failed for session $sessionId: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FILE WATCHER — instant rename
    // ══════════════════════════════════════════════════════════════

    /**
     * Watch [TABS_DIR] with Java NIO's [java.nio.file.WatchService] and route new rename files
     * to their handler immediately, rather than waiting for the next poll.
     *
     * File-name conventions:
     *  - `termsess-{TERM_SESSION_ID}.json` — legacy format — routed to [handleTermSessionRename]
     *  - `pid-{scriptPid}.json` — legacy format — routed to [handlePidRename]
     *  - `{sessionId}.json` — primary format — routed to [handleRename]
     *
     * Runs for the lifetime of the project's coroutine scope.
     */
    private suspend fun watchTabsDirectory(project: Project) {
        val watcher = FileSystems.getDefault().newWatchService()
        TABS_DIR.toPath().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
        LOG.info("[ClaudeTabs] Watcher active")

        while (currentCoroutineContext().isActive) {
            val key = watcher.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
            for (event in key.pollEvents()) {
                val filename = (event.context() as? Path)?.toString() ?: continue
                if (!filename.endsWith(".json")) continue
                delay(100)
                try {
                    val f = File(TABS_DIR, filename)
                    if (!f.exists()) continue
                    val text = f.readText()
                    val name = extractJsonString(text, "name") ?: continue

                    if (filename.startsWith("termsess-")) {
                        // TERM_SESSION_ID-keyed file: match JetBrains terminal session → tab
                        val termSessionId = filename.removePrefix("termsess-").removeSuffix(".json")
                        LOG.info("[ClaudeTabs] Watcher: termsess-rename '$name' for TERM_SESSION_ID=$termSessionId")
                        withContext(Dispatchers.Main) { handleTermSessionRename(project, termSessionId, name) }
                        f.delete()
                    } else if (filename.startsWith("pid-")) {
                        // PID-keyed file: walk up from script PID to find shell → tab
                        val scriptPid = filename.removePrefix("pid-").removeSuffix(".json").toLongOrNull() ?: continue
                        LOG.info("[ClaudeTabs] Watcher: PID-rename '$name' from script PID $scriptPid")
                        withContext(Dispatchers.Main) { handlePidRename(project, scriptPid, name) }
                        f.delete()
                    } else {
                        // Session-keyed file: use session ID to find Claude → shell → tab
                        val sessionId = filename.removeSuffix(".json")
                        LOG.info("[ClaudeTabs] Watcher: session-rename '$name' for $sessionId")
                        withContext(Dispatchers.Main) { handleRename(project, sessionId, name) }
                    }
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] Watcher: ${e.message}")
                }
            }
            key.reset()
        }
    }

    /**
     * Handle PID-keyed rename: walk up from the bash script's PID to find the
     * terminal shell, then match to a tab.
     */
    private fun handlePidRename(project: Project, scriptPid: Long, name: String) {
        // Walk up from script PID: bash(script) → node(claude) → ... → shell(terminal)
        val shellPid = findShellAncestor(scriptPid)
        if (shellPid == null) {
            LOG.info("[ClaudeTabs] PID-RENAME: no shell ancestor for script PID $scriptPid")
            return
        }
        LOG.info("[ClaudeTabs] PID-RENAME: script PID $scriptPid → shell PID $shellPid")

        val tabs = getAllTabs(project)
        val match = tabs.find { it.pid == shellPid }
        if (match != null) {
            LOG.info("[ClaudeTabs] PID-RENAME: '${match.tabName}' → '$name'")
            lastAppliedName["pid-$scriptPid"] = name
            renameTab(project, match, name, sessionId = "pid-$scriptPid")
            renamedSessions.add("pid-$scriptPid")
        } else {
            LOG.info("[ClaudeTabs] PID-RENAME: FAILED — shell PID $shellPid not in tabs: ${tabs.map { it.pid }}")
        }
    }

    /**
     * Handle TERM_SESSION_ID-keyed rename: match the JetBrains terminal session ID
     * to a backend tab, then rename. This is race-condition free because each terminal
     * tab has a unique, stable TERM_SESSION_ID env var that propagates to all subprocesses.
     */
    private fun handleTermSessionRename(project: Project, termSessionId: String, name: String) {
        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val backendTabs = tm?.let { invokeSuspend(it, tmCls.methods.find { m -> m.name == "getTerminalTabs" }!!) as? List<*> }
            val allTabs = getAllTabs(project)

            backendTabs?.forEachIndexed { index, tab ->
                tab ?: return@forEachIndexed
                try {
                    val sessIdObj = tab.javaClass.getMethod("getSessionId").invoke(tab) ?: return@forEachIndexed
                    val sessIdStr = sessIdObj.toString()
                    LOG.info("[ClaudeTabs] TERMSESS: Tab $index sessId='$sessIdStr' vs target='$termSessionId'")

                    // Match: toString() may return raw UUID or wrapped like TerminalSessionId(uuid)
                    if (sessIdStr == termSessionId || sessIdStr.contains(termSessionId) || termSessionId.contains(sessIdStr)) {
                        // Match backend tab to our TabInfo via the stable backend tab ID.
                        // (Using iteration index breaks off-by-one when any backend tab has no
                        // PID and is filtered out of getAllTabs — see issue where rename landed
                        // on the wrong tab, one position off.)
                        val backendTabId = tab.javaClass.getMethod("getId").invoke(tab) as? Int
                        val tabInfo = if (backendTabId != null) {
                            allTabs.find { it.reworkedTabId == backendTabId }
                        } else {
                            allTabs.getOrNull(index)  // very old fallback
                        }
                        if (tabInfo != null) {
                            LOG.info("[ClaudeTabs] TERMSESS: MATCH tab $index (backendId=$backendTabId) '${tabInfo.tabName}' → '$name'")
                            lastAppliedName["termsess-$termSessionId"] = name
                            renameTab(project, tabInfo, name, sessionId = "termsess-$termSessionId")
                            renamedSessions.add("termsess-$termSessionId")

                            // Also track the Claude session ID for save/restore
                            val claudeProcess = findClaudeChild(tabInfo.pid)
                            if (claudeProcess != null) {
                                val sf = File(SESSIONS_DIR, "${claudeProcess.pid()}.json")
                                if (sf.exists()) {
                                    val claudeSessionId = try { extractJsonString(sf.readText(), "sessionId") } catch (_: Exception) { null }
                                    if (claudeSessionId != null) {
                                        renamedSessions.add(claudeSessionId)
                                        lastAppliedName[claudeSessionId] = name
                                    }
                                }
                            }
                            return
                        } else {
                            LOG.warn("[ClaudeTabs] TERMSESS: matched backend tab (id=$backendTabId) but no TabInfo with that id in allTabs")
                        }
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] TERMSESS: per-tab probe failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] TERMSESS: ${e.message}")
        }
        LOG.info("[ClaudeTabs] TERMSESS: no tab found for TERM_SESSION_ID=$termSessionId")
    }

    /**
     * After a successful rename, immediately persist the new name to the restore file so it
     * survives a crash before the next poll. Updates ONLY the entry for [canonicalSessionId];
     * other entries are preserved verbatim. If the session has no existing entry but [cwd] is
     * known, an entry is added so the rename can drive both naming and restoration.
     *
     * Per user contract: "/tab is a writing action that should overwrite whatever is saved
     * for the title for this tab (and back it up) but just for this one".
     */
    private fun persistRenameImmediately(
        project: Project,
        canonicalSessionId: String,
        newName: String,
        cwd: String? = null,
        bypassPermissions: Boolean = false,
    ) {
        try {
            val projectHash = projectHash(project)
            val read = storage.loadRestoreSafe(projectHash)
            // Refuse to clobber a corrupted file — same contract as the poll save path.
            if (read is ClaudeTabsStorage.RestoreRead.ReadFailed) {
                LOG.debug("[ClaudeTabs] persistRenameImmediately: restore file unreadable (${read.reason}) — skipping")
                return
            }
            val existing = (read as ClaudeTabsStorage.RestoreRead.Ok).sessions
            // Try the explicit cwd hint first, then fall back to the per-project memory of
            // previously-active sessions (covers tabs we know about from a recent poll but
            // that aren't in the restore file yet).
            val cwdHint = cwd ?: ctx(project).previousActive[canonicalSessionId]?.cwd

            when (val outcome = ImmediateRenamePersistence.compute(
                canonicalSessionId = canonicalSessionId,
                newName = newName,
                existing = existing,
                cwdHint = cwdHint,
                bypassPermissionsHint = bypassPermissions,
                projectBasePath = project.basePath,
            )) {
                is ImmediateRenamePersistence.Outcome.Skip -> {
                    LOG.debug("[ClaudeTabs] persistRenameImmediately: ${outcome.reason}")
                }
                is ImmediateRenamePersistence.Outcome.Write -> {
                    val content = storage.serialiseSessions(outcome.updated)
                    val f = storage.restoreFile(projectHash)
                    f.parentFile?.mkdirs()
                    storage.writeAtomic(f, content)
                    storage.writeSnapshot(projectHash, content, snapshotKeepCount)
                    LOG.info("[ClaudeTabs] /tab persisted: $canonicalSessionId → '$newName' (restore file + snapshot updated)")
                }
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] persistRenameImmediately failed: ${e.message}")
        }
    }

    /**
     * Handle session-ID-keyed rename — the primary path.
     *
     * For each terminal tab, walks the process tree to find a Claude child whose session file
     * contains the target [sessionId]; if found, renames that tab.
     */
    private fun handleRename(project: Project, sessionId: String, name: String) {
        // The /tab script reads sessions/<pid>.json's sessionId field — which is the ROTATED
        // id for resumed sessions. spawnedWidgets is keyed by the CANONICAL id we used at
        // spawn. Canonicalise here so the fast-path cache lookup hits either way. For
        // never-resumed sessions canonicalize() is a no-op (no alias recorded yet → returns
        // sessionId unchanged).
        val canonicalSid = canonicalize(sessionId)

        // FAST PATH: tabs WE spawned have a widget reference cached at spawn time. The
        // decision logic — cache hit + apply success vs miss vs apply-failure — is
        // implemented in [SpawnedWidgetRenameFastPath] so it can be unit-tested without
        // an IntelliJ TerminalWidget. Side-effect orchestration (state mutation, file
        // cleanup, logging, early return) stays here.
        val fastResult = SpawnedWidgetRenameFastPath.tryRename(
            sessionId = canonicalSid,
            newName = name,
            cache = spawnedWidgets,
            applyToTarget = { widget, newName -> widget.terminalTitle.change { userDefinedTitle = newName } },
        )
        when (fastResult) {
            SpawnedWidgetRenameFastPath.Result.APPLIED -> {
                // Track the name under BOTH the canonical id (where the title listener and
                // spawnedWidgets are keyed) AND the raw id we received (so any future poll
                // that sees this raw id treats it as already-renamed).
                lastAppliedName[canonicalSid] = name
                lastAppliedName[sessionId] = name
                renamedSessions.add(canonicalSid)
                renamedSessions.add(sessionId)
                try { File(TABS_DIR, "$sessionId.json").delete() } catch (_: Exception) { }
                if (canonicalSid != sessionId) {
                    try { File(TABS_DIR, "$canonicalSid.json").delete() } catch (_: Exception) { }
                }
                LOG.info("[ClaudeTabs] RENAME (direct/spawned): '$name' for session $canonicalSid via cached widget (req=$sessionId)")
                // Per contract: /tab immediately overwrites the saved name + snapshot for
                // THIS session. Poll's next sweep will still confirm/refresh, but the
                // user's named tab is durable before then.
                persistRenameImmediately(project, canonicalSid, name)
                return
            }
            SpawnedWidgetRenameFastPath.Result.APPLY_FAILED -> {
                LOG.warn("[ClaudeTabs] RENAME (direct/spawned): cached widget rename failed for $canonicalSid — falling through to scan")
                // Don't return — let the scan path try
            }
            SpawnedWidgetRenameFastPath.Result.CACHE_MISS -> {
                // Tab wasn't spawned by us — fall through to the scan path which
                // handles classic terminal tabs and manually-opened ones.
            }
        }

        // Find the tab whose Claude child has this session ID. After `claude --resume`,
        // sessions/<pid>.json holds a ROTATED id while transcripts still append to the
        // ORIGINAL (canonical) id. Rename files are named with the canonical id (because
        // session-start-hook captured it at SessionStart), so we accept a match if either
        // the raw or canonical id of the tab equals the requested sessionId.
        val tabs = getAllTabs(project)

        for (tab in tabs) {
            val claudeProcess = findClaudeChild(tab.pid) ?: continue
            val claudePid = claudeProcess.pid()
            val sf = File(SESSIONS_DIR, "$claudePid.json")
            if (!sf.exists()) continue
            val st = try { sf.readText() } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] session file read failed (${sf.name}): ${e.message}")
                continue
            }
            val rawSessionId = extractJsonString(st, "sessionId") ?: continue
            val cwd = extractJsonString(st, "cwd") ?: continue
            val startedAt = Regex(""""startedAt":(\d+)""").find(st)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
            val canonical = canonicalSessionIdFor(claudePid, cwd, rawSessionId, startedAt)

            if (rawSessionId == sessionId || canonical == sessionId) {
                val matchedVia = if (rawSessionId == sessionId) "raw" else "canonical (raw=$rawSessionId)"
                LOG.info("[ClaudeTabs] RENAME: '${tab.tabName}' → '$name' (session $sessionId matched tab PID ${tab.pid} via $matchedVia)")
                // Track under both ids so the poll loop's renamedSessions check stays consistent
                // regardless of which id future reads see.
                lastAppliedName[sessionId] = name
                lastAppliedName[canonical] = name
                lastAppliedName[rawSessionId] = name
                renameTab(project, tab, name, sessionId = canonical)
                renamedSessions.add(sessionId)
                renamedSessions.add(canonical)
                renamedSessions.add(rawSessionId)
                try { File(TABS_DIR, "$sessionId.json").delete() } catch (_: Exception) { }
                // Per contract: /tab immediately persists the new name to the restore file
                // (and snapshots) so it survives a crash before the next poll.
                persistRenameImmediately(
                    project = project,
                    canonicalSessionId = canonical,
                    newName = name,
                    cwd = cwd,
                    bypassPermissions = readPermissionMode(cwd, canonical),
                )
                return
            }
        }

        LOG.info("[ClaudeTabs] RENAME: no tab found for session $sessionId")
    }

    // ══════════════════════════════════════════════════════════════
    // POLL — fallback rename + state save
    // ══════════════════════════════════════════════════════════════

    /**
     * Main periodic loop. On each tick:
     *
     *  1. Cleans up any `termsess-*.json` files the watcher missed.
     *  2. Detects **manual renames** — user-edited tab names get preserved and we stop rewriting them.
     *  3. Applies **fallback renames** — any pending `{sessionId}.json` that wasn't picked up by the watcher.
     *  4. Updates the per-project **restore file** (`restore-<projectPath>.json`) with currently named tabs.
     *  5. Detects **closed sessions** (present last tick, gone now) and appends them to history.json.
     *
     * Called every [POLL_INTERVAL_MS] (or faster during the 60s startup burst).
     */
    private fun poll(project: Project) {
        val c = ctx(project)
        // Poll fallback: process any unhandled termsess-*.json files
        TABS_DIR.listFiles()?.filter { it.name.startsWith("termsess-") && it.name.endsWith(".json") }?.forEach { f ->
            try {
                val termSessionId = f.name.removePrefix("termsess-").removeSuffix(".json")
                if ("termsess-$termSessionId" !in renamedSessions) {
                    val name = extractJsonString(f.readText(), "name") ?: return@forEach
                    LOG.info("[ClaudeTabs] POLL: processing termsess file $termSessionId")
                    handleTermSessionRename(project, termSessionId, name)
                    f.delete()
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] termsess file processing failed for ${f.name}: ${e.message}")
            }
        }

        val tabs = getAllTabs(project)
        val activeSessions = mutableListOf<SavedSession>()
        val claudeSessions = mutableListOf<String>()

        for (tab in tabs) {
            val claudeProcess = findClaudeChild(tab.pid) ?: continue
            val claudePid = claudeProcess.pid()

            val sf = File(SESSIONS_DIR, "$claudePid.json")
            if (!sf.exists()) continue
            val st = try { sf.readText() } catch (_: Exception) { continue }
            val rawSessionId = extractJsonString(st, "sessionId") ?: continue
            val cwd = extractJsonString(st, "cwd") ?: continue
            val startedAt = Regex(""""startedAt":(\d+)""").find(st)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()

            // Resolve rotated session IDs (Claude --resume rotates in-memory id but keeps
            // appending to the ORIGINAL transcript) to their canonical (transcript-backed) id.
            val sessionId = canonicalSessionIdFor(claudePid, cwd, rawSessionId, startedAt)
            // Record the rotated→canonical alias so /tab and the title listener can resolve
            // the right cache entries when the user runs the script (which reads the rotated
            // id from sessions/<pid>.json) against a resumed session.
            if (sessionId != rawSessionId) {
                sessionAliases[rawSessionId] = sessionId
            }
            // Record Content → sessionId so the ContentManagerListener can identify which
            // session is in a tab that the user just closed via the X / right-click menu.
            tab.content?.let { c.contentToSid[it] = sessionId }

            claudeSessions.add("'${tab.tabName}'→session:${sessionId.take(8)}")

            // Detect manual renames: if user changed the name from what we last set,
            // respect their choice. EXCEPTION: if the new name looks like the JetBrains AI
            // Assistant terminal overlay (project name with a status/spinner prefix), it's
            // not a manual rename — it's the AI host overwriting our label every poll cycle.
            // In that case, re-apply our last name immediately.
            if (sessionId in renamedSessions) {
                val lastSet = lastAppliedName[sessionId]
                val currentName = tab.tabName ?: ""
                val divergedFromOurs = lastSet != null && currentName != lastSet && !isGenericTabName(currentName)
                if (divergedFromOurs) {
                    val isOverlay = ClaudeTabsHelpers.isAiOverlayName(currentName, project.name)
                    if (isOverlay) {
                        LOG.info("[ClaudeTabs] AI overlay churn: tab now '$currentName' — re-applying '$lastSet'")
                        renameTab(project, tab, lastSet!!, sessionId = sessionId)
                    } else {
                        LOG.info("[ClaudeTabs] Manual rename detected: plugin set '$lastSet', now '$currentName' — respecting user choice")
                        lastAppliedName[sessionId] = currentName
                        File(TABS_DIR, "$sessionId.json").delete()
                    }
                }
            }

            // Fallback rename — always honor a present rename file. A file in TABS_DIR
            // means the user just ran /tab; we should apply it even if this session was
            // renamed earlier (auto-restore, prior /tab). The watcher may have missed it
            // (timing race, restart, canonical-id mismatch) so this is the safety net.
            // We also try the raw (rotated) id so a watcher-written file survives session rotation.
            val candidateFiles = listOf(
                File(TABS_DIR, "$sessionId.json"),
                File(TABS_DIR, "$rawSessionId.json"),
            ).distinctBy { it.absolutePath }
            for (renameFile in candidateFiles) {
                if (!renameFile.exists()) continue
                val name = try { extractJsonString(renameFile.readText(), "name") } catch (_: Exception) { null } ?: continue
                if (lastAppliedName[sessionId] == name) {
                    // Already applied — quiet cleanup, no log spam.
                    try { renameFile.delete() } catch (_: Exception) { }
                    continue
                }
                LOG.info("[ClaudeTabs] POLL RENAME: '${tab.tabName}' → '$name' (file=${renameFile.name})")
                lastAppliedName[sessionId] = name
                lastAppliedName[rawSessionId] = name
                renameTab(project, tab, name, sessionId = sessionId)
                renamedSessions.add(sessionId)
                renamedSessions.add(rawSessionId)
                try { renameFile.delete() } catch (_: Exception) { }
            }

            // Resolve the title we'll save. buildTitle() can return an AI-Assistant-overlay-
            // tainted string (e.g. "⠐ dfn3-adaptive-bleed-bridge") because the overlay writes
            // into userDefinedTitle, which wins in buildTitle's priority chain. Saving that
            // would persist the glyph into restore-<hash>.json and a future restored tab would
            // show the glyph forever. Detect overlay-shape names and fall back to the cleanest
            // signal we have: the last user-applied /tab name, then any previously-tracked
            // session name, then the raw tab name, then a default.
            val rawTitle = tab.widget?.terminalTitle?.buildTitle() ?: tab.tabName
            val title = if (rawTitle != null && ClaudeTabsHelpers.isAiOverlayName(rawTitle, project.name)) {
                lastAppliedName[sessionId]
                    ?: lastAppliedName[rawSessionId]
                    ?: c.previousActive[sessionId]?.tabName
                    ?: tab.tabName
                    ?: "Claude"
            } else {
                rawTitle ?: "Claude"
            }

            // Save EVERY Claude session that has a transcript on disk — generic tab names
            // (Local, Local (2), pwsh, …) included. The user's wish is "every claude session
            // I had open should auto-restore", so name-gating here would silently exclude any
            // session the user didn't bother renaming. We still skip sessions whose transcript
            // hasn't been flushed yet — those get picked up on the next poll once the file
            // exists, and `claude --resume <id>` would fail without it anyway.
            if (!hasTranscript(cwd, sessionId)) continue

            val bypass = readPermissionMode(cwd, sessionId)
            activeSessions.add(SavedSession(sessionId, cwd, title, bypass))
        }

        // ──────────────────────────────────────────────────────────────
        // STEP 6b: SESSIONS_DIR direct scan — tab-enumeration-independent.
        // ──────────────────────────────────────────────────────────────
        // The tab-driven loop above misses any tab the platform's tab APIs can't see — in
        // Rider 2026.1 that's most tabs we spawn via createShellWidget (sessionId=null on
        // the backend row, no ttyConnector on the frontend widget). Result: 10 spawned
        // tabs, 1 saved, restart → 1 restored.
        //
        // The decision logic for this scan lives in [SessionsDirScanner.scan] so it can
        // be exercised by unit tests with injected lambdas. The bindings below adapt the
        // production-world dependencies (real ProcessHandle, the canonical-id resolver,
        // spawnedWidgets cache, etc.) to the lambda surface the scanner expects.
        val scan = SessionsDirScanner.scan(
            sessionsDir = SESSIONS_DIR,
            projectBasePath = project.basePath,
            alreadyActiveIds = activeSessions.mapTo(mutableSetOf()) { it.sessionId },
            processLookup = { pid ->
                val ph = ProcessHandle.of(pid).orElse(null)
                if (ph == null || !ph.isAlive) SessionsDirScanner.ProcessLookup.DeadOrMissing
                else SessionsDirScanner.ProcessLookup.Alive(
                    SessionsDirScanner.ProcessInfo(
                        command = ph.info().command().orElse(""),
                        commandLine = ph.info().commandLine().orElse(""),
                    )
                )
            },
            canonicalSessionId = ::canonicalSessionIdFor,
            hasTranscript = ::hasTranscript,
            resolveName = { sid ->
                // Name priority for sessions found via direct scan:
                //   1. The widget we spawned for this session (current live title)
                //   2. The name we last applied via /tab
                //   3. The name we tracked in previousActive
                //   4. "Claude" default
                val fromWidget = run {
                    val w = spawnedWidgets[sid]
                    try { w?.terminalTitle?.buildTitle() } catch (_: Exception) { null }
                }
                fromWidget ?: lastAppliedName[sid] ?: c.previousActive[sid]?.tabName ?: "Claude"
            },
            readBypass = ::readPermissionMode,
        )
        // The scanner uses ClaudeTabsStorage.SavedSession; production uses the
        // identically-shaped nested SavedSession in this class. Convert at the boundary.
        activeSessions.addAll(scan.added.map { SavedSession(it.sessionId, it.cwd, it.tabName, it.bypassPermissions) })
        if (c.pollCount % 12 == 0) {
            LOG.info("[ClaudeTabs] STEP 6b: SESSIONS_DIR scan — ${scan.statusLine()}")
        }

        if (c.pollCount % 12 == 0) {
            if (claudeSessions.isNotEmpty()) LOG.info("[ClaudeTabs] STEP 6: Claude sessions found: $claudeSessions")
            LOG.info("[ClaudeTabs] STEP 7: Saving ${activeSessions.size} active session(s)")
        }

        // Detect closed sessions and write to history. previousActive is per-project; without
        // that scoping, project A's poll would see project B's sessions in previousActive,
        // declare them "closed" (since they're not in A's activeSessions), and pollute history
        // every cycle.
        val currentIds = activeSessions.map { it.sessionId }.toSet()
        for ((id, session) in c.previousActive) {
            if (id !in currentIds) {
                appendToHistory(session)
                LOG.info("[ClaudeTabs] Session closed, saved to history: '${session.tabName}'")
            }
        }
        c.previousActive.clear()
        for (s in activeSessions) c.previousActive[s.sessionId] = s

        // Continuously upsert active sessions into history. The graceful-close path above only
        // runs when poll observes a session disappear; on a hard PC crash / Rider OOM kill it
        // never fires, so without this every active-but-uncrashed session would be missing
        // from /tabs-history after the crash. Throttled per-sessionId to once per minute so we
        // don't thrash history.json on every 5s poll. appendToHistory already replaces by sid.
        val nowMs = System.currentTimeMillis()
        for (s in activeSessions) {
            val last = lastHistoryUpsertAt[s.sessionId] ?: 0L
            if (nowMs - last < HISTORY_UPSERT_INTERVAL_MS) continue
            appendToHistory(s)
            lastHistoryUpsertAt[s.sessionId] = nowMs
        }

        // Union the active set with pendingRestores BEFORE writing the restore file. This is
        // what survives "resume failed but the user expects the session to come back next
        // restart": a session that was saved, loaded into pendingRestores, then never spawned
        // (transcript briefly missing, Claude binary error, AI overlay collision, etc.) would
        // otherwise be silently overwritten by an activeSessions-only save and lost forever.
        // pruneStaleRestoreEntries at next startup drops anything whose transcript is gone,
        // so this can't grow unbounded.
        val toPersist = activeSessions.toMutableList()
        val haveIds = toPersist.mapTo(mutableSetOf()) { it.sessionId }
        for (pending in c.pendingRestores) {
            if (pending.sessionId in haveIds) continue
            if (!hasTranscript(pending.cwd, pending.sessionId)) continue
            toPersist.add(pending)
            haveIds.add(pending.sessionId)
        }
        saveState(project, toPersist)
    }

    // ══════════════════════════════════════════════════════════════
    // SESSION SAVE / RESTORE
    // ══════════════════════════════════════════════════════════════

    /** A Claude Code session that has been (or currently is) associated with a named terminal tab. */
    data class SavedSession(val sessionId: String, val cwd: String, val tabName: String, val bypassPermissions: Boolean)

    /** Lock object guarding all reads/writes to [HISTORY_FILE]. */
    private val historyLock = Any()

    /**
     * Append (or update) a session entry in history.json.
     *
     * Called when a tab closes (or when the IDE is shutting down) to preserve the session so the user
     * can browse/resume it later via `/tabs-history`. Entries older than [historyMaxAgeMs] are pruned.
     *
     * Thread-safe: wrapped in [historyLock] because the poll loop, file watcher, and project-close
     * disposable can all call this concurrently.
     */
    private fun appendToHistory(session: SavedSession) = synchronized(historyLock) {
        try {
            val now = System.currentTimeMillis()
            val existing = loadHistorySafe() ?: run {
                LOG.warn("[ClaudeTabs] history.json read failed — refusing to write '${session.tabName}' (would risk wiping existing entries). Will retry on next close.")
                return@synchronized
            }
            val entries = existing.toMutableList()

            // Don't duplicate — replace any existing entry for the same sessionId.
            entries.removeAll { extractJsonString(it, "sessionId") == session.sessionId }

            val entry = "{\"sessionId\":\"${esc(session.sessionId)}\",\"cwd\":\"${esc(session.cwd)}\",\"tabName\":\"${esc(session.tabName)}\",\"bypassPermissions\":${session.bypassPermissions},\"closedAt\":$now}"
            entries.add(entry)

            // Prune entries older than configured retention window.
            val cutoff = now - historyMaxAgeMs
            val pruned = entries.filter { raw ->
                val ts = Regex(""""closedAt":(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
                ts != null && ts > cutoff
            }

            HISTORY_FILE.parentFile?.mkdirs()
            val sb = StringBuilder("[\n")
            pruned.forEachIndexed { i, e ->
                sb.append("  $e")
                if (i < pruned.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            writeAtomic(HISTORY_FILE, sb.toString())
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] History write failed: ${e.message}")
        }
    }

    /**
     * Read [HISTORY_FILE] as a list of raw JSON entry strings.
     *
     * Returns:
     *  - empty list when the file is missing OR genuinely empty (`""` / `[]` / whitespace).
     *  - the parsed entry list when readable.
     *  - **null** when the file exists with non-trivial content but reading or parsing
     *    failed. Callers MUST abort writing on null — overwriting on a failed read is
     *    how silent history wipes happened (transient AV / OneDrive / indexer locks).
     */
    private fun loadHistorySafe(): List<String>? = synchronized(historyLock) {
        if (!HISTORY_FILE.exists()) return@synchronized emptyList()
        val text = try {
            HISTORY_FILE.readText()
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] history.json readText failed: ${e.message}")
            return@synchronized null
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return@synchronized emptyList()
        val matches = Regex("""\{[^}]+\}""").findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) {
            LOG.warn("[ClaudeTabs] history.json has ${trimmed.length} chars but no parseable entries — corrupt or partial write")
            return@synchronized null
        }
        matches
    }

    /** Atomic write helper for history.json: write to `<name>.tmp.<nanos>` next to the
     *  target, then rename. Falls back to delete-then-rename on Windows. Throws if both
     *  attempts fail so the caller knows not to claim success. */
    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp.${System.nanoTime()}")
        target.parentFile?.mkdirs()
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("atomic rename failed for ${target.absolutePath}")
            }
        }
    }

    /**
     * Remove entries from `history.json` whose transcript file no longer exists. Catches
     * "rotated" sessionIds from previous Claude --resume runs (Claude sometimes writes a
     * fresh sessionId to `~/.claude/sessions/<pid>.json` while the actual transcript stays
     * under the original ID; old plugin builds saved the rotated ID, making it useless for
     * future resume).
     *
     * Idempotent — safe to call on every startup. Only logs when entries were actually pruned.
     */
    private fun pruneStaleHistoryEntries() {
        synchronized(historyLock) {
            if (!HISTORY_FILE.exists()) return
            try {
                val entries = loadHistorySafe() ?: run {
                    LOG.warn("[ClaudeTabs] pruneStaleHistoryEntries skipped — history.json read failed (would risk wipe)")
                    return
                }
                val (kept, pruned) = entries.partition { raw ->
                    val sid = extractJsonString(raw, "sessionId") ?: return@partition true
                    val cwd = extractJsonString(raw, "cwd") ?: return@partition true
                    hasTranscript(cwd, sid)
                }
                if (pruned.isEmpty()) return
                val sb = StringBuilder("[\n")
                kept.forEachIndexed { i, e ->
                    sb.append("  $e")
                    if (i < kept.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("]")
                writeAtomic(HISTORY_FILE, sb.toString())
                LOG.info("[ClaudeTabs] Pruned ${pruned.size} stale history entries (rotated/missing transcripts): ${pruned.mapNotNull { extractJsonString(it, "tabName") }}")
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] pruneStaleHistoryEntries failed: ${e.message}")
            }
        }
    }

    /**
     * Same as [pruneStaleHistoryEntries] but for the per-project restore file. Removes
     * sessions that would fail at restore time:
     *   1. Missing transcript (rotated/deleted on disk).
     *   2. Cross-project leak — session's saved cwd is outside this project's tree.
     *      `claude --resume <sid>` is cwd-scoped, so a session from project B can't be
     *      resumed in project A's terminal even if A's restore file mentions it.
     *
     * Without (2) we'd type `claude --resume <sid>` into the user's terminal and watch it
     * silently fail with "No conversation found" — visible-from-the-outside as "no tabs
     * auto-restored on load" (the actual bug report we got).
     */
    private fun pruneStaleRestoreEntries(project: Project) {
        try {
            val rf = getStateFile(project)
            if (!rf.exists()) return
            val text = rf.readText()
            val basePath = project.basePath
            val entries = Regex("""\{[^}]+\}""").findAll(text).map { it.value }.toList()
            val droppedNoTranscript = mutableListOf<String>()
            val droppedCrossProject = mutableListOf<String>()
            val kept = entries.filter { raw ->
                val sid = extractJsonString(raw, "sessionId") ?: return@filter true
                val cwd = extractJsonString(raw, "cwd") ?: return@filter true
                val name = extractJsonString(raw, "tabName") ?: "?"
                if (!ClaudeTabsHelpers.isCwdUnderProject(cwd, basePath)) {
                    droppedCrossProject.add("$name (cwd=$cwd)")
                    return@filter false
                }
                if (!hasTranscript(cwd, sid)) {
                    droppedNoTranscript.add(name)
                    return@filter false
                }
                true
            }
            if (droppedNoTranscript.isEmpty() && droppedCrossProject.isEmpty()) return
            val sb = StringBuilder("[\n")
            kept.forEachIndexed { i, e ->
                sb.append("  $e")
                if (i < kept.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            rf.writeText(sb.toString())
            if (droppedNoTranscript.isNotEmpty()) {
                LOG.info("[ClaudeTabs] Pruned ${droppedNoTranscript.size} stale restore entries (rotated/missing transcripts): $droppedNoTranscript")
            }
            if (droppedCrossProject.isNotEmpty()) {
                LOG.info("[ClaudeTabs] Pruned ${droppedCrossProject.size} cross-project restore entries (cwd outside ${basePath ?: "<unknown>"}): $droppedCrossProject")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] pruneStaleRestoreEntries failed: ${e.message}")
        }
    }

    /** Stable hash of the project path, used as the suffix for restore/snapshot file names. */
    private fun projectHash(project: Project): String =
        ClaudeTabsHelpers.projectHashForPath(project.basePath)

    private fun getStateFile(project: Project): File = File(STATE_DIR, "restore-${projectHash(project)}.json")

    /**
     * List this project's snapshot files in the snapshots dir, newest first (by filename —
     * filenames are `<projectHash>__<timestampMs>.json` so lexical order = chronological order).
     *
     * Uses `__` (double underscore) as the delimiter because the projectHash itself contains
     * single dashes (e.g. `repos-MyApp`). The legacy `-` delimiter caused sibling-project
     * collisions: `repos-MyApp`'s `startsWith("repos-MyApp-")` also matched
     * `repos-MyApp-mobile-*.json`, cross-pruning and cross-restoring between projects.
     * Legacy filenames are still accepted; cwd validation in [loadRestoreFile] rejects any
     * that snuck in from the wrong project.
     */
    private fun listSnapshots(project: Project): List<File> {
        val hash = projectHash(project)
        val newPrefix = "${hash}__"
        val legacyPrefix = "${hash}-"
        return SNAPSHOTS_DIR.listFiles()
            ?.filter {
                it.name.endsWith(".json") &&
                    (it.name.startsWith(newPrefix) || it.name.startsWith(legacyPrefix))
            }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Write a timestamped snapshot of [content] (already-serialised JSON array) to the snapshots
     * dir, then prune older snapshots beyond [snapshotKeepCount]. Silently best-effort — if this
     * fails the user still has the live restore file and history.json.
     */
    private fun writeSnapshot(project: Project, content: String) {
        if (snapshotKeepCount <= 0) return  // user disabled snapshots entirely
        try {
            SNAPSHOTS_DIR.mkdirs()
            val file = File(SNAPSHOTS_DIR, "${projectHash(project)}__${System.currentTimeMillis()}.json")
            file.writeText(content)

            // Prune older snapshots beyond the retention window.
            val existing = listSnapshots(project)
            if (existing.size > snapshotKeepCount) {
                existing.drop(snapshotKeepCount).forEach { old ->
                    try { old.delete() } catch (_: Exception) { /* best effort */ }
                }
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Snapshot write failed: ${e.message}")
        }
    }

    /**
     * Write [sessions] to the project's restore file as a **high-water-mark union** with the
     * existing on-disk content, minus sessions the user has explicitly closed.
     *
     * Crash-safety / drift-safety contract (delegated to [ClaudeTabsStorage.saveState]):
     *   - The file content is `(existing ∪ new) − userClosed`, filtered by transcript existence.
     *     Union semantics mean a session can't be evicted just because one poll happened to miss
     *     it (Claude restart, PID race, AI overlay collision); it survives until the user
     *     explicitly closes it OR its transcript disappears.
     *   - The only path that *removes* a session from auto-restore is:
     *       1. ContentManagerListener observes the user closing the tab (X / right-click → Close)
     *       2. The transcript file is gone (resume would fail anyway)
     *     Project shutdown is NOT a close — `projectClosing` short-circuits the listener.
     *   - Writes are atomic (tmp + rename) so a crash mid-write can't corrupt the file.
     *   - A snapshot is written for every successful save (rolling history of last N).
     */
    private fun saveState(project: Project, sessions: List<SavedSession>) {
        try {
            // Drop sessions whose cwd is outside this project's tree. They belong to a
            // sibling/different project and would silently fail `claude --resume <sid>` from
            // this project's terminal (the resume is cwd-scoped). Without this filter, a
            // session run via `cd /some/other/project && claude` from inside another window
            // leaks into the wrong restore file.
            val basePath = project.basePath
            val (kept, leaked) = sessions.partition {
                ClaudeTabsHelpers.isCwdUnderProject(it.cwd, basePath)
            }
            if (leaked.isNotEmpty()) {
                LOG.info("[ClaudeTabs] Skipping ${leaked.size} cross-project session(s) at save (cwd outside ${basePath ?: "<unknown>"}): ${leaked.map { "${it.tabName}@${it.cwd}" }}")
            }

            val c = ctx(project)
            // Snapshot userClosed at call time so a concurrent close doesn't mutate mid-write.
            val userClosed = synchronized(c.userClosedSessions) { c.userClosedSessions.toSet() }

            // Convert this class's nested SavedSession → Storage's identically-shaped record.
            val newForStorage = kept.map {
                ClaudeTabsStorage.SavedSession(it.sessionId, it.cwd, it.tabName, it.bypassPermissions)
            }

            storage.saveState(
                projectHash = projectHash(project),
                newSessions = newForStorage,
                keepCount = snapshotKeepCount,
                userClosedSessionIds = userClosed,
                transcriptCheck = { cwd, sid -> hasTranscript(cwd, sid) },
            )
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Save state failed: ${e.message}")
        }
    }

    /**
     * Load the project's restore file into [pendingRestores]. If the live file is missing,
     * empty, or an empty array, fall back to the most recent non-empty snapshot from
     * [SNAPSHOTS_DIR]. This protects against:
     *   - Previous poll wiping the file (pre-fix bug).
     *   - Crashes during save that leave an empty or truncated file.
     *   - Accidental deletion by the user or external tools.
     */
    private fun loadRestoreFile(project: Project) {
        val sources = mutableListOf<File>().apply {
            val live = getStateFile(project)
            if (live.exists()) add(live)
            addAll(listSnapshots(project))  // newest → oldest
        }

        // Used by snapshot fallback to reject snapshots from sibling projects (the legacy
        // single-dash filename pattern lets `repos-MyApp-mobile-*.json` be matched by
        // `repos-MyApp`'s prefix). New filenames use `__` as the delimiter so this can't
        // happen, but old files on disk still need defensive cwd validation.
        val projectBaseNorm = project.basePath?.replace("\\", "/")?.trimEnd('/')?.lowercase()
        for ((index, source) in sources.withIndex()) {
            try {
                val json = source.readText().trim()
                if (json.isEmpty() || json == "[]") continue

                val rawParsed = mutableListOf<SavedSession>()
                for (m in Regex("""\{[^}]+\}""").findAll(json)) {
                    val o = m.value
                    rawParsed.add(SavedSession(
                        extractJsonString(o, "sessionId") ?: continue,
                        extractJsonString(o, "cwd") ?: continue,
                        extractJsonString(o, "tabName") ?: continue,
                        o.contains("\"bypassPermissions\":true")
                    ))
                }
                if (rawParsed.isEmpty()) continue

                // Filter cross-project entries on BOTH live and snapshot sources. Previously the
                // live file (index 0) was loaded blind; that's how a session from a sibling
                // project (/repos/MyApp-mobile) ended up in /repos/MyApp's restore file and
                // silently failed at resume time. pruneStaleRestoreEntries already cleans the
                // file on disk before we get here, but parse-then-filter at load is a belt
                // alongside the on-disk braces, in case the file was written by an older plugin
                // version or modified between prune and load.
                val (parsed, dropped) = rawParsed.partition { s ->
                    ClaudeTabsHelpers.isCwdUnderProject(s.cwd, project.basePath)
                }
                if (dropped.isNotEmpty()) {
                    LOG.info("[ClaudeTabs] ${source.name}: dropped ${dropped.size} cross-project entr${if (dropped.size == 1) "y" else "ies"} at load: ${dropped.map { "${it.tabName}@${it.cwd}" }}")
                }
                if (parsed.isEmpty()) continue

                if (index > 0 && projectBaseNorm != null) {
                    val matchesProject = parsed.any { s ->
                        s.cwd.replace("\\", "/").trimEnd('/').lowercase() == projectBaseNorm
                    }
                    if (!matchesProject) {
                        LOG.info("[ClaudeTabs] Snapshot ${source.name} skipped — cwd doesn't match this project (${project.basePath})")
                        continue
                    }
                }

                val c = ctx(project)
                c.pendingRestores.addAll(parsed)
                c.pendingRestoresLoadedAt = System.currentTimeMillis()
                val provenance = if (index == 0) "live restore file" else "snapshot (${source.name})"
                LOG.info("[ClaudeTabs] ${c.pendingRestores.size} session(s) to restore from $provenance")
                // Don't delete yet — delete after all restores complete
                return
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] Restore source ${source.name} parse failed (trying next): ${e.message}")
            }
        }

        if (sources.isNotEmpty()) {
            LOG.info("[ClaudeTabs] No non-empty restore source found (${sources.size} candidates checked)")
        }
    }

    /**
     * Restore pending sessions by spawning a fresh terminal tab for each one. Runs once per
     * Rider start (per project), after a short settle delay so Rider has time to bring back
     * any of its own remembered tabs.
     *
     * **Design — "create, don't match".** The old design tried to find an existing terminal
     * tab that "looked right" for each saved session (exact-name match → idle generic-name
     * → any-idle after 15s) and typed `claude --resume <id>` into it. Every "off-by-one",
     * "wrong session restored", and "second project restored nothing" bug we shipped came
     * from that step — when more than one project was open, or when Rider's session-restore
     * brought tabs back in waves with previous-tab title inheritance, the matcher silently
     * paired the wrong saved session with the wrong tab.
     *
     * The new design has no matching step:
     *
     *  1. **Settle.** Wait [RESTORE_SETTLE_MS] after [loadRestoreFile] so any tabs Rider
     *     remembered (from `.idea/workspace.xml`) are fully in place. Rider never restores
     *     the *processes* that were inside those tabs — only their titles — so by the time
     *     we fire, any saved-session-name tab we see has no Claude in it.
     *
     *  2. **Close stale empty-Claude tabs.** For each tab whose title matches one of our
     *     saved session names AND has no Claude process inside, close it via
     *     [TerminalToolWindowManager.closeTab]. These are Rider's leftovers; leaving them
     *     produces visible duplicates after step 3. Non-Claude tabs (npm, etc.) are not
     *     touched.
     *
     *  3. **Spawn fresh.** For each saved session, call [spawnNewTabAndRestore] — opens a
     *     brand-new terminal at the saved cwd, sets `userDefinedTitle = saved.tabName`, and
     *     executes `claude --resume <sessionId>`. Off-by-one is mathematically impossible
     *     because the resume id is hard-bound to the tab at creation time.
     *
     * Set [ProjectCtx.restoreFired] after running so a second pass (e.g. if save races us
     * before we delete the restore file) doesn't double-spawn.
     */
    private fun processPendingRestores(project: Project) {
        val c = ctx(project)
        if (c.restoreFired || c.pendingRestores.isEmpty()) return

        // Settle delay — let Rider finish restoring its own remembered tab titles before we
        // start closing/creating tabs, so we see the full leftover set.
        val now = System.currentTimeMillis()
        val ageMs = if (c.pendingRestoresLoadedAt > 0) now - c.pendingRestoresLoadedAt else 0
        if (ageMs < RESTORE_SETTLE_MS) return

        val sessions = c.pendingRestores.toList()
        val savedTabNames = sessions.map { it.tabName }.toSet()

        // Step 1: Close stale empty-Claude tabs. Any tab whose title matches a saved session
        // name AND has no Claude process inside is a Rider leftover from `workspace.xml` —
        // closing it prevents the user seeing two copies of e.g. "Lint Triage" after we
        // spawn the live one in step 2. Non-Claude tabs are untouched.
        //
        // We walk `ContentManager.contents` directly here (not [getAllTabs]) because Rider's
        // stale workspace.xml-restored tabs often have no widget AND no PID — they're title-
        // only ghosts — and [getAllTabs] drops PID-less entries. That made cleanup invisible
        // to those exact tabs, producing visible duplicates after we spawned the live one.
        val mgr = try {
            TerminalToolWindowManager.getInstance(project)
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Restore: TerminalToolWindowManager unavailable — ${e.message}")
            null
        }
        val cmgr = mgr?.toolWindow?.contentManager
        val allContents = cmgr?.contents?.toList() ?: emptyList()
        var closed = 0
        if (mgr != null) {
            for (content in allContents) {
                val displayName = content.displayName ?: continue
                if (displayName !in savedTabNames) continue
                val widget = try {
                    TerminalToolWindowManager.findWidgetByContent(content)
                } catch (_: Exception) { null }
                val pid = extractPidFromWidget(widget)
                // Keep only tabs that actually have a Claude child. Everything else (no
                // widget, no PID, or PID with no Claude inside) is a stale ghost and gets
                // closed.
                if (pid != null && findClaudeChild(pid) != null) continue
                try {
                    mgr.closeTab(content)
                    closed++
                    LOG.info("[ClaudeTabs] Closed stale empty-Claude tab '$displayName' (pid=${pid ?: "none"}, no claude process)")
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] closeTab failed for '$displayName': ${e.message}")
                }
            }
        }
        if (closed > 0) LOG.info("[ClaudeTabs] Restore: closed $closed stale empty-Claude tab(s) before spawn")

        // Step 2: Spawn a fresh tab per saved session. The sessionId is bound to the tab
        // at creation time, so we cannot type the wrong resume command into the wrong tab.
        val restored = mutableListOf<SavedSession>()
        for (s in sessions) {
            if (s.sessionId in c.spawnedForSession) continue
            if (spawnNewTabAndRestore(project, s)) {
                c.spawnedForSession.add(s.sessionId)
                restored.add(s)
            }
        }

        c.pendingRestores.removeAll(restored)
        c.restoreFired = true  // never run again this Rider start, even if restore file reappears

        if (restored.isNotEmpty()) {
            c.restoredThisRun.addAll(restored)
            writeLastRestoreSnapshot(project)
            LOG.info("[ClaudeTabs] Restore complete: spawned ${restored.size} fresh tab(s)")
        }

        // Delete restore file so a future poll's saveState doesn't see stale entries.
        try { getStateFile(project).delete() } catch (_: Exception) { }
    }

    /**
     * "By any means necessary" restore: open a brand-new terminal tab via the public
     * [TerminalToolWindowManager.createShellWidget] API, set its name, and send
     * `claude --resume <id>` to the fresh shell. Used when no existing tab can host the
     * pending session (Rider booted with zero terminal tabs, or every tab is already busy
     * with another Claude session).
     *
     * Returns true on success, false if the spawn API threw — caller leaves the entry in
     * pendingRestores so the next poll retries (subject to spawnedForSession dedup).
     *
     * The widget API is engine-agnostic across classic and reworked terminals as of 2024.3+.
     * Must be called on EDT (the call site hops via `withContext(Dispatchers.Main)` already).
     */
    private fun spawnNewTabAndRestore(project: Project, s: SavedSession): Boolean {
        return try {
            val mgr = TerminalToolWindowManager.getInstance(project)
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            tw?.activate(null, false, false)
            // CRITICAL: deferTerminalSessionUntilUiShown=false. With true, the shell process
            // only starts when the tab becomes visible (focused). Since we pass requestFocus=false
            // (to avoid stealing focus N times during a multi-tab restore), the shell would
            // never start for any non-active tab, and sendCommandToExecute below would type
            // into a cold widget that never runs `claude --resume`. The previous
            // implementation set this to true and that's why only the focused tab "worked"
            // post-restore — the others looked like empty "Local" shells.
            // For a tab whose saved name is generic ("Local", "Local (2)", "pwsh", etc.) we
            // pass the saved value at creation so IntelliJ has *something* to show in the tab
            // strip immediately, but we deliberately DO NOT pin it via userDefinedTitle below.
            // Reason: buildTitle()'s priority is userDefinedTitle > applicationTitle > default.
            // If we pin "Local", we'd block Claude's resumed-chat title (which Claude sets via
            // OSC escape into applicationTitle) from ever surfacing — so all tabs would stay
            // "Local" forever. By leaving userDefinedTitle null on generic saves, Claude's chat
            // title naturally wins as soon as the resume finishes. For non-generic saved names
            // (user previously ran `/tab <name>`), we DO pin so the user's choice survives.
            //
            // createNewSession is the 2025.x+ replacement for the deprecated createShellWidget
            // (the deprecated wrapper just delegates here with shellCommand=null anyway).
            // shellCommand=null → use the platform's default shell. defer=false → start the
            // shell process immediately so sendCommandToExecute below types into a live TTY
            // rather than a queued widget waiting for UI activation.
            val widget = mgr.createNewSession(s.cwd, s.tabName, null, false, false)
            // Record the widget for direct rename access. The platform's tab-enumeration APIs
            // can't reliably find tabs we spawned this way (see [spawnedWidgets] docs), so
            // [handleRename] looks here first before falling back to [getAllTabs].
            spawnedWidgets[s.sessionId] = widget
            val pinUserTitle = !isGenericTabName(s.tabName)
            if (pinUserTitle) {
                lastAppliedName[s.sessionId] = s.tabName
                try {
                    widget.terminalTitle.change { userDefinedTitle = s.tabName }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] spawn: terminalTitle.change failed: ${e.message}")
                }
            } else {
                LOG.info("[ClaudeTabs] spawn: leaving userDefinedTitle null for generic name '${s.tabName}' — Claude's chat title will surface as applicationTitle wins buildTitle()")
            }
            // Install the title listener so the spawned tab gets the same overlay protection
            // as long-lived tabs do via renameTab() — covers the case where we pinned the
            // user's name and the AI Assistant overlay tries to overwrite it later.
            try { installTitleListener(project, widget.terminalTitle, s.sessionId) } catch (_: Exception) { }
            val cmd = buildResumeCmd(s)
            // sendCommandToExecute is the engine-agnostic API; safe on both classic and
            // reworked widgets. With defer=false the shell is started by now so the command
            // is typed into the live TTY rather than queued indefinitely.
            ApplicationManager.getApplication().invokeLater {
                try {
                    widget.sendCommandToExecute(cmd)
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] spawn: sendCommandToExecute failed: ${e.message}")
                }
            }
            LOG.info("[ClaudeTabs] Spawned new terminal tab '${s.tabName}' at cwd=${s.cwd} for session ${s.sessionId}")
            true
        } catch (e: Throwable) {
            LOG.warn("[ClaudeTabs] spawnNewTabAndRestore failed for '${s.tabName}': ${e.message}")
            false
        }
    }

    /**
     * Snapshot the cumulative list of sessions restored on this Rider start to
     * `~/.claude/rider-plugin/last-restore.json`. Read by `/tabs-status` so users can see
     * how many sessions came back without scraping idea.log.
     */
    private fun writeLastRestoreSnapshot(project: Project) {
        try {
            val restoredThisRun = ctx(project).restoredThisRun
            val out = File(STATE_DIR, "last-restore.json")
            out.parentFile?.mkdirs()
            val sessions = restoredThisRun.joinToString(",") { s ->
                val n = s.tabName.replace("\\", "\\\\").replace("\"", "\\\"")
                val sid = s.sessionId.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"tabName":"$n","sessionId":"$sid"}"""
            }
            val projectName = project.name.replace("\\", "\\\\").replace("\"", "\\\"")
            val json = """{"restoredAt":${System.currentTimeMillis()},"projectName":"$projectName","count":${restoredThisRun.size},"sessions":[$sessions]}"""
            out.writeText(json)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] last-restore.json write failed: ${e.message}")
        }
    }

    /**
     * Build the `claude --resume <sid>` command for a restored session. Appends
     * `--dangerously-skip-permissions` ONLY when the saved session was itself running in
     * bypass mode (i.e. [readPermissionMode] saw `"permissionMode":"bypassPermissions"`
     * in the transcript).
     *
     * Past versions also consulted `settings.json` for `skipDangerousModePermissionPrompt`,
     * but that setting controls whether Claude *prompts* before honoring bypass — it does
     * NOT mean "every session should start in bypass mode." Conflating the two caused
     * every restored tab to come back in bypass mode even when the original session was
     * `auto`, which is the wrong default and a real security concern: it would silently
     * elevate a session's permission level across a restart.
     */
    private fun buildResumeCmd(s: SavedSession): String = buildString {
        append("claude --resume ${s.sessionId}")
        if (s.bypassPermissions) append(" --dangerously-skip-permissions")
    }

    /**
     * True if a transcript file exists at the path Claude --resume looks for:
     * `~/.claude/projects/<cwd-encoded>/<sessionId>.jsonl`.
     *
     * Used to filter out "rotated" sessionIds — when Claude --resume runs, the new claude
     * process sometimes writes a fresh sessionId to `sessions/<pid>.json` while the actual
     * persisted transcript is still under the ORIGINAL id. Saving the rotated id to history
     * makes resume commands fail with "No conversation found".
     */
    private fun hasTranscript(cwd: String, sessionId: String): Boolean {
        if (cwd.isBlank() || sessionId.isBlank()) return false
        val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
        return File(File(CLAUDE_HOME, "projects/$h"), "$sessionId.jsonl").exists()
    }

    /**
     * Given a sessionId we read from `sessions/<pid>.json`, return the **canonical**
     * sessionId for that Claude process — i.e. the one that maps to a real transcript on
     * disk. For non-resumed sessions this is the same id. For resumed sessions, Claude
     * rotates the in-memory sessionId but keeps appending to the original transcript.
     *
     * Strategy (in priority order):
     *  1. If [currentSessionId] itself has a transcript on disk — return it (non-resumed case).
     *  2. Read the claude process's argv via [ProcessHandle.info]. If it contains
     *     `--resume <uuid>`, that uuid IS the canonical id (it's literally what the user
     *     asked to resume). This is the only fully-reliable signal when multiple resumed
     *     sessions are concurrently active in the same cwd, because their transcript
     *     mtimes are all "now" (constantly appended to).
     *  3. Last-resort fallback: pick the transcript file whose mtime is closest to
     *     [claudeStartedAt] within ±60s, with claim tracking to avoid two PIDs claiming
     *     the same transcript. This is fragile when sessions overlap heavily — kept only
     *     for the case where argv is unavailable (some platforms / sandbox configurations).
     *
     * Cached per-PID via [pidToCanonicalSession].
     */
    private val pidToCanonicalSession = mutableMapOf<Long, String>()

    private fun canonicalSessionIdFor(pid: Long, cwd: String, currentSessionId: String, claudeStartedAt: Long): String {
        pidToCanonicalSession[pid]?.let { return it }
        if (hasTranscript(cwd, currentSessionId)) {
            pidToCanonicalSession[pid] = currentSessionId
            return currentSessionId
        }

        // Strategy 2: read the claude process's argv directly. This is the canonical signal —
        // `claude --resume <uuid>` literally tells us the canonical id, no heuristic needed.
        val argvCanonical = try {
            val ph = ProcessHandle.of(pid).orElse(null)
            val args = ph?.info()?.arguments()?.orElse(null)
            ClaudeTabsHelpers.extractResumeIdFromArgs(args)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] argv read failed for PID $pid: ${e.message}")
            null
        }
        if (argvCanonical != null) {
            if (argvCanonical != currentSessionId) {
                LOG.info("[ClaudeTabs] Resolved rotated sessionId '$currentSessionId' (PID $pid) → canonical '$argvCanonical' via argv --resume")
            }
            pidToCanonicalSession[pid] = argvCanonical
            return argvCanonical
        }

        // Strategy 3 (fragile fallback): mtime-closest-to-startedAt with claim tracking.
        val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
        val projDir = File(CLAUDE_HOME, "projects/$h")
        if (!projDir.isDirectory) {
            LOG.info("[ClaudeTabs] Rotated sessionId '$currentSessionId' has no transcript, no argv, no project dir at $projDir — keeping rotated id")
            return currentSessionId
        }
        val transcripts = projDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
        if (transcripts == null || transcripts.isEmpty()) {
            LOG.info("[ClaudeTabs] Rotated sessionId '$currentSessionId': no argv, no transcripts in $projDir — keeping rotated id")
            return currentSessionId
        }
        val claimed = pidToCanonicalSession.values.toSet()
        val candidates = transcripts
            .filter { it.name.removeSuffix(".jsonl") !in claimed }
            .filter { kotlin.math.abs(it.lastModified() - claudeStartedAt) <= 60_000 }
        val best = candidates.minByOrNull { kotlin.math.abs(it.lastModified() - claudeStartedAt) }
        if (best == null) {
            LOG.info("[ClaudeTabs] Rotated sessionId '$currentSessionId' (PID $pid, startedAt ${java.util.Date(claudeStartedAt)}): no argv, no transcript matched within ±60s, claimed=${claimed.size} — keeping rotated id")
            return currentSessionId
        }
        val canonical = best.name.removeSuffix(".jsonl")
        if (canonical != currentSessionId) {
            val deltaMs = kotlin.math.abs(best.lastModified() - claudeStartedAt)
            LOG.info("[ClaudeTabs] Resolved rotated sessionId '$currentSessionId' (PID $pid) → canonical '$canonical' via mtime fallback (delta ${deltaMs}ms) — argv unavailable")
        }
        pidToCanonicalSession[pid] = canonical
        return canonical
    }

    private fun readPermissionMode(cwd: String, sessionId: String): Boolean {
        val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
        val f = File(File(CLAUDE_HOME, "projects/$h"), "$sessionId.jsonl")
        if (!f.exists()) return false
        return try {
            BufferedReader(FileReader(f)).use { r ->
                repeat(5) {
                    val l = r.readLine() ?: return@use false
                    if (l.contains("\"permission-mode\"")) return@use extractJsonString(l, "permissionMode") == "bypassPermissions"
                }; false
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] session jsonl read failed: ${e.message}")
            false
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CLAUDE DETECTION
    // ══════════════════════════════════════════════════════════════

    /**
     * Find the currently-alive Claude process PID that owns the given [sessionId],
     * by scanning the JSON files in `~/.claude/sessions/`. Returns null if no match
     * or the process has exited.
     */
    private fun findClaudePidForSession(sessionId: String): Long? {
        for (f in SESSIONS_DIR.listFiles() ?: emptyArray()) {
            if (!f.name.endsWith(".json")) continue
            try {
                if (extractJsonString(f.readText(), "sessionId") != sessionId) continue
                val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return pid
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] session lookup error for ${f.name}: ${e.message}")
            }
        }
        return null
    }

    /** @return true if [cmd] ends in a known shell executable name (any OS). */
    private fun isShellCommand(cmd: String): Boolean = ClaudeTabsHelpers.isShellCommand(cmd)

    /**
     * Walk up at most 5 levels from [claudePid] looking for the terminal shell process that hosts
     * the Claude instance. Used by the PID-rename flow when the script writes its own PID and we
     * need to map back to a specific tab.
     */
    private fun findShellAncestor(claudePid: Long): Long? {
        var current = ProcessHandle.of(claudePid).orElse(null) ?: return null
        for (i in 0 until 5) {
            val parent = current.parent().orElse(null) ?: break
            current = parent
            val cmd = current.info().command().orElse("")
            if (isShellCommand(cmd)) return current.pid()
        }
        return ProcessHandle.of(claudePid).flatMap { it.parent() }.flatMap { it.parent() }.map { it.pid() }.orElse(null)
    }

    /**
     * Starting from the shell PID [pid] (the process hosting a terminal tab), search the full
     * descendant tree for a running Claude Code CLI process. Returns null if nothing matches.
     */
    private fun findClaudeChild(pid: Long): ProcessHandle? {
        val h = ProcessHandle.of(pid).orElse(null) ?: return null
        return findClaudeRec(h)
    }

    /** Recursive worker for [findClaudeChild]. Matches `claude[.exe|.cmd]` or `node` + `claude` args. */
    private fun findClaudeRec(h: ProcessHandle): ProcessHandle? {
        for (c in h.children().toList()) {
            val cmd = c.info().command().orElse(""); val line = c.info().commandLine().orElse("")
            if ((cmd.contains("claude", true) || line.contains("claude", true)) &&
                (cmd.endsWith("claude") || cmd.endsWith("claude.exe") || cmd.endsWith("claude.cmd") ||
                        line.contains("@anthropic", true) || line.contains("claude-code", true) ||
                        (cmd.contains("node", true) && line.contains("claude", true)))) return c
            findClaudeRec(c)?.let { return it }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════
    // AUTO-DEPLOY
    // ══════════════════════════════════════════════════════════════

    // CLAUDE_MD_MARKER and PERMISSION_ENTRIES are in the companion object

    /**
     * Installs (and updates) the plugin's bash integration into the user's `~/.claude/` directory.
     * Safe to call on every startup — it's idempotent:
     *  - Files are overwritten from JAR resources (so script updates ship with plugin updates).
     *  - CLAUDE.md section is replaced between its markers (so instruction text stays current).
     *  - Permissions & hooks are only added if missing.
     *  - Old-named command files (pre-rename) are cleaned up.
     *
     * The complementary [uninstall] method lives in the companion object.
     */
    private fun deployClaudeIntegration() {
        try {
            deployResource("claude-integration/rename-tab.sh", File(CLAUDE_HOME, "rider-plugin/rename-tab.sh"))
            deployResource("claude-integration/tab.sh", File(CLAUDE_HOME, "rider-plugin/tab.sh"))
            deployResource("claude-integration/session-start-hook.sh", File(CLAUDE_HOME, "rider-plugin/session-start-hook.sh"))
            // Bundled Node helpers — slash commands invoke these as one-liners so the script body
            // doesn't dump into the user's terminal on every /tab or /tabs-backup.
            deployResource("claude-integration/tab-backup.js", File(CLAUDE_HOME, "rider-plugin/tab-backup.js"))
            deployResource("claude-integration/backup-active.js", File(CLAUDE_HOME, "rider-plugin/backup-active.js"))
            deployResource("claude-integration/current-project.js", File(CLAUDE_HOME, "rider-plugin/current-project.js"))
            File(CLAUDE_HOME, "commands").mkdirs()
            deployResource("claude-integration/tab.md", File(CLAUDE_HOME, "commands/tab.md"))
            deployResource("claude-integration/tabs-clear.md", File(CLAUDE_HOME, "commands/tabs-clear.md"))
            deployResource("claude-integration/tabs-restore.md", File(CLAUDE_HOME, "commands/tabs-restore.md"))
            deployResource("claude-integration/tabs-history.md", File(CLAUDE_HOME, "commands/tabs-history.md"))
            deployResource("claude-integration/tabs-backup.md", File(CLAUDE_HOME, "commands/tabs-backup.md"))
            deployResource("claude-integration/tabs-status.md", File(CLAUDE_HOME, "commands/tabs-status.md"))
            // Cleanup old command filenames (pre-rename)
            File(CLAUDE_HOME, "commands/clear-tabs.md").delete()
            File(CLAUDE_HOME, "commands/restore-tabs.md").delete()
            File(CLAUDE_HOME, "commands/tab-history.md").delete()
            File(CLAUDE_HOME, "commands/backup-tabs.md").delete()

            val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
            val existing = if (claudeMd.exists()) claudeMd.readText() else ""
            val claudeMdBlock = """
$CLAUDE_MD_MARKER
## Terminal Tab Naming (Rider Plugin)
At the **start of every conversation**, rename your Rider terminal tab by running:
```bash
bash ~/.claude/rider-plugin/rename-tab.sh "Short Topic Name"
```

**Naming priority:**
1. If the user provides a name — in their first message, via `/tab`, or by saying "name it X", "call this X", "name this X tab" — use their **exact words** as the tab name. "name this left tab" means the name IS "left tab". Never reinterpret the user's words as a description; take them literally as the desired name.
2. Otherwise, pick a concise name (3-5 words) that describes the conversation's purpose.
3. Update it if the topic shifts significantly.

This applies to **new chats, resumed chats** (`--resume`), **and `/resume`**. On resume, re-use the previous tab name if the topic hasn't changed.

**Scope note:** This plugin manages **terminal-launched Claude CLI sessions only**. Sessions started in the JetBrains AI Assistant chat tool window (the "AI Agents" panel: Junie / Claude Agent / Codex) are managed by JetBrains and are not auto-restored across Rider restarts by this plugin.
$CLAUDE_MD_MARKER
""".trimStart()
            if (existing.contains(CLAUDE_MD_MARKER)) {
                // Replace existing section with latest version
                val pattern = Regex("$CLAUDE_MD_MARKER.*?$CLAUDE_MD_MARKER", RegexOption.DOT_MATCHES_ALL)
                val updated = existing.replace(pattern, claudeMdBlock.trim())
                if (updated != existing) {
                    claudeMd.writeText(updated)
                    LOG.info("[ClaudeTabs] Updated CLAUDE.md section")
                }
            } else {
                // First install — append
                claudeMd.appendText("\n$claudeMdBlock")
                LOG.info("[ClaudeTabs] Added CLAUDE.md section")
            }

            addPermission()
            addSessionStartHook()
        } catch (e: Exception) { LOG.warn("[ClaudeTabs] Deploy failed: ${e.message}") }
    }

    private val HOOK_MARKER = "session-start-hook.sh"
    private val HOOK_MARKER_LEGACY = "active-sessions"

    private fun addSessionStartHook() {
        val sf = File(CLAUDE_HOME, "settings.json")
        if (!sf.exists()) return
        try {
            val text = sf.readText()
            if (text.contains(HOOK_MARKER) || text.contains(HOOK_MARKER_LEGACY)) return

            val hookEntry = """
                      {
                        "hooks": [
                          {
                            "type": "command",
                            "command": "bash ~/.claude/rider-plugin/session-start-hook.sh",
                            "timeout": 5
                          }
                        ]
                      }
            """.trimIndent()

            if (!text.contains("\"hooks\"")) {
                // No hooks section at all — add the entire block
                val hookJson = "\"hooks\": {\n    \"SessionStart\": [\n      $hookEntry\n    ]\n  }"
                sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  $hookJson\n}")
                LOG.info("[ClaudeTabs] Added hooks section with SessionStart hook")
            } else if (!text.contains("\"SessionStart\"")) {
                // Has hooks but no SessionStart — add SessionStart array
                sf.writeText(text.replace(Regex(""""hooks"\s*:\s*\{"""), "\"hooks\": {\n    \"SessionStart\": [\n      $hookEntry\n    ],"))
                LOG.info("[ClaudeTabs] Added SessionStart hook to existing hooks")
            } else {
                // Has SessionStart but our hook isn't in it — append to the array
                sf.writeText(text.replace(Regex(""""SessionStart"\s*:\s*\["""), "\"SessionStart\": [\n      $hookEntry,"))
                LOG.info("[ClaudeTabs] Appended hook to existing SessionStart array")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Hook install failed: ${e.message}")
        }
    }

    private fun addPermission() {
        val sf = File(CLAUDE_HOME, "settings.json")
        if (!sf.exists()) return
        try {
            for (entry in PERMISSION_ENTRIES) {
                val text = sf.readText()
                if (text.contains(entry)) continue
                when {
                    text.contains("\"allow\"") ->
                        sf.writeText(text.replace(Regex(""""allow"\s*:\s*\["""), "\"allow\": [\"$entry\", "))
                    text.contains("\"permissions\"") ->
                        sf.writeText(text.replace(Regex(""""permissions"\s*:\s*\{"""), "\"permissions\": {\n    \"allow\": [\"$entry\"],"))
                    else ->
                        sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  \"permissions\": {\n    \"allow\": [\"$entry\"]\n  }\n}")
                }
            }
        } catch (e: Exception) { LOG.debug("[ClaudeTabs] Permission install failed: ${e.message}") }
    }

    /**
     * Deploy a packaged classpath resource to [target]. For executable scripts (`.sh`,
     * `.bash`), strips any embedded carriage returns before writing. This is defensive: the
     * source tree pins these files as LF via `.gitattributes`, but if a future Gradle copy
     * task or a misconfigured checkout reintroduces CR, bash on macOS / Linux fails with
     * `$'\r': command not found` (issue #1). Stripping CR is harmless on Windows where
     * Git Bash / MSYS2 / WSL all read LF fine.
     */
    private fun deployResource(path: String, target: File) {
        try {
            javaClass.classLoader.getResourceAsStream(path)?.use { stream ->
                target.parentFile?.mkdirs()
                val bytes = stream.readBytes()
                val isExecutableScript = path.endsWith(".sh") || path.endsWith(".bash")
                if (isExecutableScript) {
                    // Strip CR. Avoids the `$'\r': command not found` failure mode on
                    // macOS / Linux when the .sh resource was packaged with CRLF.
                    target.writeBytes(bytes.filter { it != 0x0D.toByte() }.toByteArray())
                } else {
                    target.writeBytes(bytes)
                }
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Deploy resource failed: $path — ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════════

    // Pure helpers delegated to ClaudeTabsHelpers so they can be unit-tested
    // without needing an IntelliJ Project instance.
    private fun extractJsonString(json: String, key: String): String? = ClaudeTabsHelpers.extractJsonString(json, key)
    private fun isGenericTabName(name: String): Boolean = ClaudeTabsHelpers.isGenericTabName(name)
    private fun esc(s: String): String = ClaudeTabsHelpers.esc(s)
}
