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
import com.intellij.ui.content.ContentManager
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
 *  2. **Watches** `~/.claude/intellij-claude-terminal/tabs/` for `{sessionId}.json` rename files written by the
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

        /** Pending-close entries are dropped after this long without an observed
         *  process-death. Generous (Rider kills child shells within milliseconds of
         *  an X-click and Claude takes ~1s to clean up), so 30s is "if we haven't
         *  seen the process die by now, it wasn't a real close." */
        const val PENDING_CLOSE_EXPIRY_MS = 30_000L

        /** Root of Claude Code's user data (scripts, sessions, commands live under this). */
        private val CLAUDE_HOME = File(System.getProperty("user.home"), ".claude")

        /** Where Claude Code writes `{PID}.json` session files. Read-only for the plugin. */
        private val SESSIONS_DIR = File(CLAUDE_HOME, "sessions")

        /** Where bash scripts drop `{sessionId}.json` rename directives for the plugin to pick up. */
        private val TABS_DIR = File(CLAUDE_HOME, "intellij-claude-terminal/tabs")

        /** Where per-project restore files (`restore-<projectPath>.json`) and `history.json` live. */
        private val STATE_DIR = File(CLAUDE_HOME, "intellij-claude-terminal")

        /** Markers wrapping the plugin's section of `~/.claude/CLAUDE.md` so it can be replaced cleanly. */
        private const val CLAUDE_MD_MARKER = "<!-- intellij-claude-terminal -->"

        /**
         * Delete the section this plugin used to write into `~/.claude/CLAUDE.md`.
         *
         * That section told Claude how and when to rename a tab, and it stopped being true
         * when names started coming from the conversation itself — it opened with "you do
         * not need to name the tab" and then documented `/tab`, a slash command that no
         * longer exists.
         *
         * Instructions in `CLAUDE.md` are read on every turn of every session on the
         * machine, so a stale one is not free: it spends context and invites the model to
         * act on a workflow that is gone. Nothing replaces it, because naming now needs no
         * cooperation from the conversation at all.
         *
         * Called on start as well as on uninstall, so an install that already carries the
         * section loses it at the next launch rather than only when the plugin is removed.
         */
        private fun removeClaudeMdSection() {
            val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
            if (!claudeMd.exists()) return
            try {
                val text = claudeMd.readText()
                if (!text.contains(CLAUDE_MD_MARKER)) return
                val pattern = Regex(
                    "\n?${Regex.escape(CLAUDE_MD_MARKER)}.*?${Regex.escape(CLAUDE_MD_MARKER)}\n?",
                    RegexOption.DOT_MATCHES_ALL,
                )
                claudeMd.writeText(text.replace(pattern, "\n").trim() + "\n")
                LOG.info("[ClaudeTabs] Removed the plugin's CLAUDE.md section — tab naming no longer needs an instruction")
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] CLAUDE.md cleanup skipped: ${e.message}")
            }
        }

        /** Permission lines inserted into `~/.claude/settings.json` so Claude can run our helper scripts
         *  without prompting. The first one is legacy (kept for backward-compatible cleanup); the rest cover
         *  the bundled Node helpers used by /tab and /tabs-backup. */
        private val PERMISSION_ENTRIES = listOf(
            "Bash(bash ~/.claude/intellij-claude-terminal/rename-tab.sh *)",
        )

        /**
         * Permissions earlier versions granted, removed from `settings.json` on start so an
         * old install stops carrying them.
         *
         * The paths here are deliberately the **old** `rider-plugin` ones. These entries
         * exist only to undo what a previous version wrote, so rewriting them to the current
         * directory — which never had a `tab.sh` — would leave the real grants in place and
         * revoke nothing.
         */
        private val RETIRED_PERMISSION_ENTRIES = listOf(
            // Slash commands that no longer exist.
            "Bash(bash ~/.claude/rider-plugin/tab.sh *)",
            "Bash(node ~/.claude/rider-plugin/tab-backup.js *)",
            "Bash(node ~/.claude/rider-plugin/backup-active.js)",
            "Bash(node ~/.claude/rider-plugin/backup-active.js *)",
            "Bash(node ~/.claude/rider-plugin/current-project.js)",
            // The rename helper, under the directory used before the plugin was renamed.
            "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)",
        )

        /** Reads the hook edges + Claude's own per-session `status` field. See [ClaudeStatusStore]. */
        private val statusStore = ClaudeStatusStore(CLAUDE_HOME)

        /** How often the status indicator re-reads the (tiny) status files.
         *
         *  The tab glyph has to feel immediate, and the 5s save poll is far too coarse for
         *  that. A dedicated short tick is used instead of a [java.nio.file.WatchService]
         *  because the JDK has no native watcher on macOS — `FileSystems.getDefault()`
         *  returns the polling implementation there, which fires on a 10s sensitivity and
         *  would be *slower* than this loop. Each tick stats a handful of small files. */
        private const val STATUS_POLL_MS = 400L

        /** How often the status loop looks for tabs it hasn't attached to yet. Short enough
         *  that a tab picks up its glyph within a couple of seconds of appearing, long
         *  enough that the reflective fallback routes don't run on every 400ms tick. */
        private const val STATUS_ATTACH_INTERVAL_MS = 1_500L

        /** How long after a restore the spare-terminal sweep keeps looking. Long enough to
         *  outlast a shell that is still settling, short enough that it can never close a
         *  terminal someone has since started using. */
        private const val DEFAULT_TERMINAL_SWEEP_WINDOW_MS = 20_000L

        /**
         * Last status snapshot, shared by every open project window.
         *
         * One status loop runs per project, but the files it reads are global — with three
         * windows open that would be three identical directory walks every 400ms. The
         * memo collapses them to one. TTL is deliberately just under the poll interval so a
         * window that ticks slightly out of phase still gets fresh data rather than reusing
         * a snapshot that is about to be replaced anyway.
         */
        private val statusSnapshotLock = Any()
        private var statusSnapshot: Map<String, ClaudeStatusStore.Reading> = emptyMap()
        private var statusSnapshotAt = 0L
        private const val STATUS_SNAPSHOT_TTL_MS = 300L

        /**
         * How far into a transcript to look for the opening question. The first user turn is
         * within the first few lines; the budget only exists so a transcript that somehow
         * has none isn't read end to end.
         */
        private const val PROMPT_SCAN_LINES = 40

        /**
         * How far into a transcript to look for its `permission-mode` record. It sits in the
         * first couple of lines on every transcript checked; the budget only bounds the read
         * for one that somehow has none.
         */
        private const val PERMISSION_SCAN_LINES = 10

        private fun sharedStatusSnapshot(): Map<String, ClaudeStatusStore.Reading> =
            synchronized(statusSnapshotLock) {
                val now = System.currentTimeMillis()
                if (now - statusSnapshotAt >= STATUS_SNAPSHOT_TTL_MS) {
                    statusSnapshot = statusStore.snapshot()
                    statusSnapshotAt = now
                }
                statusSnapshot
            }

        /** Long-term session history — one JSON entry per closed/backed-up session. */
        private val HISTORY_FILE = File(CLAUDE_HOME, "intellij-claude-terminal/history.json")

        /** Rotating snapshots of restore-*.json — one per successful non-empty save. */
        private val SNAPSHOTS_DIR = File(CLAUDE_HOME, "intellij-claude-terminal/snapshots")

        /**
         * User-overridable config file. Read once at startup (see [loadConfig]). Defaults
         * below are used when the file is missing or a field is malformed. Users can create
         * or edit this file to change retention policies without recompiling the plugin.
         */
        private val CONFIG_FILE = File(CLAUDE_HOME, "intellij-claude-terminal/config.json")

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

        /** Remote Control auto-start settings. See [RemoteControlLauncher.Config]. */
        internal var remoteControl: RemoteControlLauncher.Config = RemoteControlLauncher.Config.DEFAULT
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
                val text = CONFIG_FILE.readText()
                val cfg = ClaudeTabsHelpers.parseConfig(text)
                historyMaxAgeMs = cfg.historyMaxAgeMs
                snapshotKeepCount = cfg.snapshotKeepCount
                remoteControl = RemoteControlLauncher.parseConfig(text)
                LOG.info("[ClaudeTabs] Config loaded: historyMaxAgeDays=${historyMaxAgeMs / (24*60*60*1000)}, snapshotKeepCount=$snapshotKeepCount, remoteControl.enabled=${remoteControl.enabled}")
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
  "snapshotKeepCount": 10,

  "_remoteControl": "Starts `claude remote-control` once per project so you can drive this machine's sessions from claude.ai/code or the Claude mobile app. This exposes control of local sessions to your Claude account while the IDE is open — set enabled=false to turn it off. mode: tab (visible terminal tab) | background (no tab; output goes to remote-control-<project>.log). spawnMode: same-dir | worktree | session.",
  "remoteControl": {
    "enabled": true,
    "mode": "tab",
    "spawnMode": "same-dir",
    "extraArgs": ""
  }
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
            removeClaudeMdSection()

            // 2. Remove our hooks and permission entries from settings.json.
            //    Tree-based, like the install side: the old string-replace left dangling
            //    commas behind whenever an entry wasn't in the exact position it assumed.
            val settings = File(CLAUDE_HOME, "settings.json")
            if (settings.exists()) {
                try {
                    ClaudeSettingsPatcher.unpatch(settings.readText(), PERMISSION_ENTRIES + RETIRED_PERMISSION_ENTRIES)
                        ?.let { settings.writeText(it) }
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] settings.json cleanup skipped: ${e.message}")
                }
            }

            // 3. Remove deployed scripts and data
            File(CLAUDE_HOME, "intellij-claude-terminal").deleteRecursively()
            removeRetiredSlashCommands()
        }

        /**
         * Delete the slash commands this plugin used to install.
         *
         * They were `/tab`, `/tabs-status`, `/tabs-backup`, `/tabs-history`, `/tabs-restore`
         * and `/tabs-clear`, and every one of them has been overtaken:
         *
         *  - naming is automatic now (the tab takes Claude's own session name), and the IDE's
         *    own right-click → Rename Session covers the deliberate case — the plugin
         *    respects a title typed there and never overwrites it
         *  - `/tabs-history` and `/tabs-status` duplicated Claude's own `/resume` and
         *    `claude agents`, and did it with less information: they would happily offer to
         *    resume a session that was still running
         *  - the rest only existed to poke the plugin's own state files
         *
         * Run on every start, not just on uninstall: an install that predates this leaves
         * the files behind, and a stale command that half-works is worse than none.
         */
        private fun removeRetiredSlashCommands() {
            val names = listOf(
                "tab.md", "tabs-clear.md", "tabs-restore.md", "tabs-history.md",
                "tabs-backup.md", "tabs-status.md",
                // Pre-rename filenames, still out there on old installs.
                "clear-tabs.md", "restore-tabs.md", "tab-history.md", "backup-tabs.md",
            )
            var removed = 0
            for (n in names) {
                val f = File(CLAUDE_HOME, "commands/$n")
                if (f.exists() && runCatching { f.delete() }.getOrDefault(false)) removed++
            }
            // The Node helpers only ever existed to back those commands.
            for (n in listOf("tab.sh", "tab-backup.js", "backup-active.js", "current-project.js")) {
                val f = File(CLAUDE_HOME, "intellij-claude-terminal/$n")
                if (f.exists() && runCatching { f.delete() }.getOrDefault(false)) removed++
            }
            if (removed > 0) LOG.info("[ClaudeTabs] Removed $removed retired slash-command file(s) — tab naming is automatic now; use right-click → Rename Session to set one by hand")
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
     * 1.0.18 cross-project arbitration: maps `sessionId → projectHash`. Populated by each
     * project's tab-walk when it discovers a session attached to its window. A session in this
     * map is "claimed" by that project — other projects' [SessionsDirScanner] passes skip it
     * (even if the cwd matches their basePath). Ensures each session lives in exactly one
     * restore file.
     *
     * Necessary because [SessionsDirScanner] matches by cwd-under-basePath, which would
     * otherwise classify a worktree session as belonging to whichever project's basePath
     * coincidentally shares a prefix. Tab-walk knows which window the session is ACTUALLY in,
     * so its claim trumps cwd-based guessing.
     *
     * Entries get refreshed every poll (no expiry needed); the map is cleared on project close
     * via [ProjectCtx] teardown.
     */
    private val claimedByTabWalk = java.util.concurrent.ConcurrentHashMap<String, String>()

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

    /**
     * Current status glyph state per canonical sessionId, as last **applied to a tab**.
     *
     * Distinct from what [statusStore] reads off disk: this records what the tab strip is
     * actually showing, so the fast status loop can skip the (reflective, main-thread) title
     * write when nothing changed. Without it, every 400ms tick would repaint every tab.
     */
    private val appliedStatus = java.util.concurrent.ConcurrentHashMap<String, ClaudeStatus>()

    /**
     * When the plugin spawned a tab for a session, so a restored tab that hasn't started its
     * `claude --resume` yet isn't painted as dead. See [ClaudeTabsHelpers.shouldPaintExited].
     */
    private val tabSpawnedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Sessions observed in any state other than exited — the other half of that check. */
    private val sessionEverRunning = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * A transcript's opening question, and the file mtime it was read at. A null [name]
     * records "nothing usable *yet*", which is why the mtime is kept — see [transcriptName].
     */
    private data class PromptName(val name: String?, val mtime: Long)

    /** `sessionId → ` the label derived from the transcript's opening question, memoised. */
    private val promptNameCache = java.util.concurrent.ConcurrentHashMap<String, PromptName>()

    /** A tab handle plus the project window that owns it. See [tabForSession]. */
    private data class TrackedTab(val tab: TabInfo, val projectHash: String)

    /**
     * Session id → the tab hosting it, refreshed by each [poll].
     *
     * The status loop needs to reach a tab far more often than the 5s poll runs, and
     * [getAllTabs] is heavy (four reflective enumeration passes over the reworked terminal
     * managers). Caching the resolved [TabInfo] lets the fast loop write a title directly.
     * Entries go stale when a tab closes; the write is guarded and the next poll drops them.
     *
     * The owning project is recorded because this map — like [claimedByTabWalk] — is shared
     * across every open project window (the platform creates one instance of this activity
     * and runs it per project). Without the tag, each window's poll would prune the other
     * windows' sessions on every cycle and the glyphs would flicker away.
     */
    private val tabForSession = java.util.concurrent.ConcurrentHashMap<String, TrackedTab>()

    /**
     * The bare (undecorated) name the plugin believes each session's tab should carry.
     *
     * The status glyph is a *view* concern: it is prefixed onto the live title only, and is
     * stripped before any title is read back into names.json, the restore file, or history.
     * Persisting a decorated name would resurrect tabs called `"● backend"` after a restart.
     */
    private val baseNameForSession = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Shell PIDs of tabs hosting a `claude remote-control` server.
     *
     * The tab-walk must skip these. A Remote Control server is not a chat session: the tab
     * hosts a server process, and whatever session it pre-creates belongs to the server,
     * not to this tab. Tracking it would put the server's session in the restore file and
     * bring it back after a restart as a plain tab running `claude --resume` against it.
     *
     * The session-kind filter already rejects `kind: "rc"`, but this does not depend on
     * Claude tagging the pre-created session the way we expect.
     */
    private val remoteControlShellPids = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Throttle for the status loop's own attach pass. See [STATUS_ATTACH_INTERVAL_MS]. */
    @Volatile private var lastStatusAttachAt = 0L

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
     *  overwrites `userDefinedTitle`. One listener per session; idempotent install.
     *
     *  Concurrent because [handOverTab] disposes off the EDT while [installTitleListener]
     *  runs on it. */
    private val titleListenerDisposables = java.util.concurrent.ConcurrentHashMap<String, Disposable>()

    /**
     * `TERM_SESSION_ID → sessionId` as of the last [rebindSupersededSessions] pass.
     *
     * The bridge file keeps only the newest id per terminal, so telling "this terminal now
     * hosts a different session" from "this terminal has always hosted this session" needs a
     * previous reading to compare against. This is it. See [ClaudeTabsHelpers.terminalHandovers].
     */
    private val lastTermSessionSid = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Rate-limit map for high-frequency log keys (e.g. AI overlay re-apply, restore-pending
     *  no-tab). Key is an arbitrary log identifier (often `sessionId` or `pending-$sessionId`),
     *  value is the last-logged epoch ms. Without this we'd log dozens of lines per second
     *  during Claude streaming as the AI Assistant rewrites the title on every output chunk.
     *  Set Registry key `claude.terminal.tabs.verboseLogs=true` to bypass rate limiting entirely. */
    private val rateLimitedLogAt = mutableMapOf<String, Long>()
    private val RATE_LIMITED_LOG_INTERVAL_MS = 60_000L

    /** When was each active session last upserted into history.json — throttles continuous
     *  history tracking so the 5s poll loop doesn't rewrite history.json once per session per
     *  tick. We still want frequent upsert so a hard PC crash doesn't drop the session from
     *  history, but every poll is overkill. 60s is a reasonable trade. */
    private val lastHistoryUpsertAt = mutableMapOf<String, Long>()
    private val HISTORY_UPSERT_INTERVAL_MS = 60_000L

    /** True if Registry key `claude.terminal.tabs.verboseLogs` is set OR DEBUG logging is enabled
     *  for this class. When true, rate-limited log lines fire on every event instead of being
     *  suppressed. Use this for diagnosing title-contention or restore-matching issues without
     *  having to rebuild the plugin. */
    private fun isVerboseLogging(): Boolean {
        return try {
            com.intellij.openapi.util.registry.Registry.`is`("claude.terminal.tabs.verboseLogs", false)
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
        /** Terminal tool-window content count and when it last changed — the signal the
         *  restore settle waits on instead of a fixed sleep. */
        var lastContentCount: Int = -1,
        var lastContentChangeAt: Long = 0L,
        /** True once the create-restore has fired for this project on the current Rider run.
         *  Prevents a second pass from spawning duplicate tabs if the restore file isn't yet
         *  deleted (e.g. a save races us before we get to clean up). */
        var restoreFired: Boolean = false,
        /** Set once [ensureRemoteControl] has run for this project, so a failed spawn isn't
         *  retried on every poll and a second window of the same project can't double-start. */
        var remoteControlStarted: Boolean = false,
        /** When the restore spawn completed. The spare-terminal sweep retries for a short
         *  window after this, rather than running once: at the instant restore finishes the
         *  IDE's default shell can transiently have a child (this machine's .zshrc ends in
         *  `exec zsh`), and a single shot would read that as "in use" and never look again. */
        var restoreFiredAt: Long = 0L,
        /** Set once the sweep has closed something or its window has expired. */
        var defaultTerminalSweepDone: Boolean = false,
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
        /** Two-poll-grace tracking: sids present in the restore file at the last save call
         *  but absent from that save's `newSessions`. On the NEXT save, if a sid is still
         *  missing AND in this set, it gets evicted; if it's missing for the FIRST time
         *  (not in this set), it's kept (grace). Prevents single-poll races (Claude restart,
         *  brief PID/transcript gap, scanner missed a session for one cycle) from wiping
         *  legitimate entries. Evictions still happen — they just need two consecutive
         *  misses, ~10s at the 5s poll cadence. */
        var missedLastPoll: Set<String> = emptySet(),
        /** Sids whose `contentRemoveQuery` fired with no temporary-removed marker AND not
         *  during projectClosing. Value is the wall-clock ms when added. The poll loop
         *  promotes entries to [userClosedSessions] only after also confirming the Claude
         *  process for that sid is DEAD. Entries that linger past 30s without process death
         *  are dropped — those were ambiguous events (pane reorder we didn't catch, etc.),
         *  NOT real user closes.
         *
         *  Why two signals: contentRemoveQuery alone false-positives on whole-window-close
         *  (projectClosing flag races). Process-death alone false-positives on Claude
         *  process crashes (`claude --resume` failures, OOM kills, etc. shouldn't be
         *  recorded as user-closed). Requiring BOTH is the conservative choice that the
         *  user explicitly asked for. */
        val pendingClose: MutableMap<String, Long> = mutableMapOf(),
        /** Map from a terminal tab's `Content` to the canonical sessionId we know it holds.
         *  Populated by [getAllTabs] each poll; consulted by the `ContentManagerListener`
         *  when a Content is removed so we can identify which session was just closed.
         *  IdentityHashMap because `Content` doesn't implement value-equality. */
        val contentToSid: MutableMap<com.intellij.ui.content.Content, String> = java.util.IdentityHashMap(),
        /** True once the project is in its shutdown sequence (`ProjectManagerListener.
         *  projectClosing` fired OR our disposer ran). While true, the contentRemoveQuery
         *  listener bails immediately — those events are project-shutdown tear-down, not
         *  user-initiated tab closes. */
        @Volatile var projectClosing: Boolean = false,
        /** Wall-clock ms when the plugin attached to this project. Used by the startup-
         *  grace gate in [poll] to skip empty-save during the first 60s while spawned
         *  tabs are still warming up. */
        val startupAt: Long = System.currentTimeMillis(),
    )

    private val projectCtx = java.util.concurrent.ConcurrentHashMap<String, ProjectCtx>()
    private fun ctx(project: Project): ProjectCtx =
        projectCtx.computeIfAbsent(project.locationHash) { ProjectCtx() }

    // ── Pre-1.0.17 close-detection helpers removed in 1.0.17 ──────────────────
    // `scheduleCloseVerification`, `isTabStillPresent`, and `removeFromRestoreImmediately`
    // implemented the old defer-and-verify scheme: capture widget at contentRemoved time,
    // wait 1.5s, walk the primary ContentManager, mark user-closed if orphan. That scheme
    // false-positived during window-close (every widget genuinely gone) and split-pane
    // closes (widget moved to a sibling ContentManager). 1.0.17 replaces it with the
    // two-signal design — see the contentRemoveQuery listener in runActivity() and the
    // pendingClose confirmation block in poll(). The new design requires BOTH a
    // contentRemoveQuery signal (filtered by TEMPORARY_REMOVED_KEY + projectClosing) AND
    // a process-death observation before recording user-closed, so false-positives on
    // any single signal cannot mark a tab as closed.

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
        LOG.info("[ClaudeTabs] Verbose logs: ${if (isVerboseLogging()) "ON (bypassing rate limits)" else "OFF (rate-limited; set Registry claude.terminal.tabs.verboseLogs=true to enable)"}")
        LOG.info("[ClaudeTabs] Close detection: two-signal (contentRemoveQuery + process-dead) — pending expiry=${PENDING_CLOSE_EXPIRY_MS}ms (ProjectCtx.startupAt=${ctx(project).startupAt})")
        // Self-test (1.0.17): probe the load-bearing APIs we depend on at startup. If any
        // fail, the save path is still resilient (it uses ~/.claude/sessions/*.json — not
        // the terminal APIs — for the active-sessions list), but cosmetic features like
        // name-application and close-detection rely on these. Surfacing the failure mode
        // here means a user filing an issue can include this line instead of us asking
        // them to instrument the IDE.
        try {
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow
            val terminalBackend = try {
                Class.forName("com.intellij.terminal.backend.TerminalTabsManager"); true
            } catch (_: Throwable) { false }
            val terminalFrontend = try {
                Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"); true
            } catch (_: Throwable) { false }
            val dockManager = try {
                Class.forName("com.intellij.openapi.fileEditor.impl.DockManager"); true
            } catch (_: Throwable) { false }
            val namesFileExists = storage.namesFile.exists()
            val namesEntryCount = try { storage.loadNames().size } catch (_: Exception) { -1 }
            LOG.info("[ClaudeTabs] Self-test: toolWindow=${tw != null} backend=$terminalBackend frontend=$terminalFrontend dockManager=$dockManager names.json=${if (namesFileExists) "present(${namesEntryCount})" else "missing"}")
            if (tw == null) {
                LOG.warn("[ClaudeTabs] Self-test: Terminal tool window unavailable for this project — name-application is disabled; save data remains intact via SessionsDirScanner. Sessions will still restore on next start, but tab strip name re-apply won't work.")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Self-test probe failed (non-fatal): ${e.message}")
        }
        LOG.info("[ClaudeTabs] ════════════════════════════════════════════════════════")
        TABS_DIR.mkdirs()
        resolveLocalizedDefaultTerminalName()
        maybeWriteConfigTemplate()
        loadConfig()
        deployClaudeIntegration()
        pruneStaleHistoryEntries()
        pruneStaleRestoreEntries(project)
        // Hook files outlive their sessions (SessionEnd writes one last edge and then
        // nothing ever removes it), so reap the stale ones once per IDE start. Sessions
        // still tracked in names.json are kept regardless of age.
        try {
            statusStore.prune(liveSessionIds = storage.loadNames().keys)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] status file prune failed: ${e.message}")
        }

        // Hydrate the in-memory userClosed set from disk so a tab the user X-ed in a previous
        // Rider session (and then crashed before the next save) doesn't get auto-restored.
        // Was in-memory only pre-1.0.17 — that lost user-closes across hard kills.
        try {
            val persisted = storage.loadUserClosed(projectHash(project))
            if (persisted.isNotEmpty()) {
                val c = ctx(project)
                synchronized(c.userClosedSessions) { c.userClosedSessions.addAll(persisted) }
                LOG.info("[ClaudeTabs] Loaded ${persisted.size} persisted user-closed sid(s) from disk")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] user-closed load failed: ${e.message}")
        }

        // Maintain project-index.json (1.0.17): one global file mapping cwd → projectHash so
        // the /tabs-status skill can resolve the current project in ~5ms via a Read instead
        // of paying ~500-800ms for a Node cold-start to walk up looking for .idea/.
        // Upserted on every project open; entries that no longer correspond to an open
        // project just stay in the index (harmless — at worst a stale basePath that no
        // ancestor matches, then the skill falls back to Node).
        try {
            updateProjectIndex(project)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] project-index.json update failed: ${e.message}")
        }

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

        // ContentManagerListener tracks terminal-tab close events. Pre-1.0.17 this used
        // `contentRemoved` + a 1.5s widget-attachment verify, which still false-positived
        // on whole-window-close (every tab's widget genuinely gone) and split-pane closes
        // (widget moves to a sibling ContentManager we didn't walk).
        //
        // 1.0.17 switches to the **two-signal** design (per user request, after the window-
        // close false-positives accumulated 8 entries in user-closed during one session):
        //
        //   Signal 1: contentRemoveQuery fires AND content is NOT marked TEMPORARY_REMOVED
        //             (the platform's own discriminator for shuffle/drag/pane-move —
        //             ContentManagerImpl.doRemoveContent checks this same key to skip
        //             non-removal cases) AND projectClosing is false.
        //   Signal 2: the Claude process for that sid is dead at next poll-loop check.
        //
        // A sid only becomes user-closed when BOTH signals fire. Signal 1 alone is too
        // noisy (the platform fires it for many things). Signal 2 alone is too aggressive
        // (a Claude crash inside a still-open tab shouldn't drop the saved session).
        // Together they're conservative: every recorded close has the user's UI action AND
        // an observable process-death consequence.
        //
        // Window-close path: ProjectManager.projectClosing fires → c.projectClosing = true
        // → listener bails before adding to pendingClose. Even if the order races,
        // Disposer cancels the poll scope so signal 2 never confirms.
        try {
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow
            val cmgr = tw?.contentManager
            if (cmgr != null) {
                val cmListener = object : com.intellij.ui.content.ContentManagerListener {
                    override fun contentRemoveQuery(event: com.intellij.ui.content.ContentManagerEvent) {
                        val c = ctx(project)
                        val content = event.content
                        val displayName = try { content.displayName ?: "?" } catch (_: Exception) { "?" }
                        // TEMPORARY_REMOVED_KEY is the platform's own discriminator for
                        // drag/reorder/pane-split/tear-off (ContentManagerImpl.doRemoveContent
                        // checks this same key to skip the disposal path).
                        val isTemporary = try {
                            content.getUserData(com.intellij.ui.content.Content.TEMPORARY_REMOVED_KEY) == true
                        } catch (_: Throwable) { false }
                        // Resolve sid via contentToSid map first, fall back to widget identity
                        // against the spawnedWidgets cache.
                        val capturedWidget = try {
                            TerminalToolWindowManager.findWidgetByContent(content)
                        } catch (_: Exception) { null }
                        val widgetSid = if (capturedWidget != null) {
                            spawnedWidgets.entries.firstOrNull { (_, w) -> w === capturedWidget }?.key
                        } else null
                        val sid = c.contentToSid[content] ?: widgetSid
                        val verbose = isVerboseLogging()
                        when (val d = TwoSignalCloseDetector.decideOnRemoveQuery(
                            projectClosing = c.projectClosing,
                            isTemporary = isTemporary,
                            sid = sid,
                        )) {
                            TwoSignalCloseDetector.Signal1.SkipProjectClosing -> {
                                if (verbose) LOG.info("[ClaudeTabs][close] removeQuery skipped — projectClosing tab='$displayName'")
                            }
                            TwoSignalCloseDetector.Signal1.SkipTemporary -> {
                                if (verbose) LOG.info("[ClaudeTabs][close] removeQuery skipped — TEMPORARY_REMOVED_KEY set (shuffle/drag/split) tab='$displayName'")
                            }
                            TwoSignalCloseDetector.Signal1.SkipNoSid -> {
                                if (verbose) LOG.info("[ClaudeTabs][close] removeQuery sid-unknown tab='$displayName' — cannot pend")
                            }
                            is TwoSignalCloseDetector.Signal1.AddToPending -> {
                                // Signal 1 confirmed. Queue for signal-2 (process-dead)
                                // verification at next poll. Don't write user-closed yet.
                                c.pendingClose[d.sid] = System.currentTimeMillis()
                                c.contentToSid.remove(content)
                                LOG.info("[ClaudeTabs][close] Signal 1 (removeQuery) for sid=${d.sid} tab='$displayName' — added to pendingClose, awaiting signal 2 (process-dead)")
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
            // Short head start only — the restore path waits for the terminal tool window to
            // settle on its own (see processPendingRestores), so a long fixed delay here is
            // just added latency before the first tab appears.
            delay(1_000)

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

        // Remote Control server — one per project, started after the restore spawn has had
        // time to settle so it doesn't compete with restored tabs for the terminal tool
        // window. `isRemoteControlServing` shells out to lsof, so this stays off the EDT
        // until the actual tab creation.
        scope.launch {
            delay(12_000)
            try {
                val start = withContext(Dispatchers.Default) {
                    RemoteControlLauncher.decide(
                        config = remoteControl,
                        alreadyStartedThisRun = ctx(project).remoteControlStarted,
                        // Two independent checks: a pid we recorded ourselves (decidable,
                        // survives an IDE restart) and a scan for one started by hand.
                        externalServerForThisDir = remoteControlAlreadyRunning(project) ||
                            isRemoteControlServing(project.basePath),
                        projectBasePath = project.basePath,
                    )
                }
                if (start is RemoteControlLauncher.Decision.Skip) {
                    LOG.info("[ClaudeTabs][rc] Not starting Remote Control — ${start.reason}")
                } else {
                    withContext(Dispatchers.Main) { startRemoteControlTab(project) }
                }
            } catch (_: ProcessCanceledException) {
                // project closed mid-probe
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs][rc] startup failed: ${e.message}")
            }
        }

        // Status indicator loop — deliberately separate from the poll above.
        //
        // The save poll is expensive (four reflective tab enumerations, a process-ancestry
        // walk, transcript existence checks) and runs on a 5s cadence tuned for persistence,
        // not for UI latency. The status glyph has to land within a fraction of a second of
        // Claude changing state, so it gets its own loop that does almost nothing: read a
        // handful of small files, diff against what's already on screen, and touch only the
        // tabs that actually changed.
        scope.launch {
            // Starts early: the loop now attaches on its own, so there is nothing to wait
            // for. 1.5s is just enough for the terminal tool window to exist.
            delay(1_500)
            while (isActive) {
                try {
                    refreshStatuses(project)
                } catch (_: ProcessCanceledException) { break }
                catch (e: Exception) {
                    if (e.message?.contains("disposed") == true) break
                    LOG.debug("[ClaudeTabs][status] refresh failed: ${e.message}")
                }
                delay(STATUS_POLL_MS)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // STATUS INDICATOR
    // ══════════════════════════════════════════════════════════════

    /**
     * Read the current status of every tracked session and repaint the tabs whose state
     * changed.
     *
     * Runs off the EDT (file reads only); the actual title writes are marshalled to the UI
     * thread, and only for tabs that changed — a steady state costs one directory listing
     * plus a few small reads per tick and touches no Swing state at all.
     *
     * Sessions are matched to tabs through [tabForSession], which [poll] refreshes. A tab
     * that has closed since the last poll leaves a stale entry behind; writing to it throws
     * and is swallowed, and the next poll drops the entry.
     */
    private suspend fun refreshStatuses(project: Project) {
        val thisProjectHash = projectHash(project)

        // Attach on this loop rather than waiting for poll().
        //
        // poll() returns early during its startup grace — up to 60s while restored tabs are
        // still coming up — and the attach used to live after that return, so the indicator
        // could not appear at all until the grace ended. Measured on a real start: plugin up
        // at 20:01:54, first attach at 20:02:21. Twenty-seven seconds of blank tabs, which is
        // the first thing anyone notices.
        //
        // Attaching is cheap for the case that matters (a map walk over spawnedWidgets), so
        // it runs here on a short throttle instead. poll() still calls it, which is what
        // keeps handles fresh as tabs move.
        val now = System.currentTimeMillis()
        if (now - lastStatusAttachAt >= STATUS_ATTACH_INTERVAL_MS) {
            lastStatusAttachAt = now
            // Read the disk *before* going to the UI thread. Both of these are file I/O —
            // measured at ~1.9ms together — and they used to run inside the EDT block below,
            // which is a UI stall every 1.5s for work that touches no UI at all.
            rebindSupersededSessions()
            val termMap = try { statusStore.termSessionMap() } catch (_: Exception) { emptyMap() }
            withContext(Dispatchers.Main) {
                try {
                    attachStatusTabs(project, tabForSession.keys.toSet(), termMap)
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs][status] attach from status loop failed: ${e.message}")
                }
            }
        }

        if (tabForSession.isEmpty()) return

        val readings = sharedStatusSnapshot()
        if (readings.isEmpty()) return

        // sid → (tab, newStatus) for the tabs that actually need repainting. Scoped to this
        // window so two open projects don't both paint (and both log) the same tab.
        val pending = mutableListOf<Triple<String, TabInfo, ClaudeStatus>>()
        for ((sid, tracked) in tabForSession) {
            if (tracked.projectHash != thisProjectHash) continue
            // A resumed session's hook files are keyed by the rotated id Claude reported at
            // hook time, while everything else here is keyed by the canonical one.
            val reading = readings[sid] ?: readings[rawIdFor(sid)] ?: continue
            if (reading.status != ClaudeStatus.EXITED) {
                sessionEverRunning.add(sid)
            } else if (!ClaudeTabsHelpers.shouldPaintExited(tabSpawnedAt[sid], sid in sessionEverRunning, now)) {
                // Restored tab whose Claude hasn't started yet — the EXITED it reads is the
                // dead process from before the restart, not this one.
                continue
            }
            if (appliedStatus[sid] == reading.status) continue
            pending.add(Triple(sid, tracked.tab, reading.status))
        }
        if (pending.isEmpty()) return

        withContext(Dispatchers.Main) {
            for ((sid, tab, status) in pending) {
                val previous = appliedStatus[sid]
                appliedStatus[sid] = status
                val base = baseNameForSession[sid]
                    ?: lastAppliedName[sid]
                    ?: storage.nameFor(sid)
                    ?: tab.tabName
                if (applyStatusToTab(tab, base, status, project)) {
                    LOG.info("[ClaudeTabs][status] ${sid.take(8)} ${previous?.name ?: "-"} → ${status.name} ('$base')")
                } else {
                    // The tab is gone or its title surface is unreachable. Drop the cached
                    // state so we don't keep retrying every tick; the next poll re-adds it
                    // if the tab is still really there.
                    appliedStatus.remove(sid)
                    tabForSession.remove(sid)
                }
            }
        }
    }

    /**
     * The title to write for a tab in [status]: the bare name when the tab carries the
     * status icon, glyph-prefixed when it doesn't.
     *
     * Both used to go on at once, and that turned out to be more than redundant. Writing
     * `userDefinedTitle` does not stay on the frontend: the platform's own title listeners
     * propagate it to `Content.displayName` *and* to the backend tab name, which is the one
     * persisted into `workspace.xml`. So every status change wrote its glyph into saved
     * state, and a stale one came back on the next start — observed as a backend tab
     * literally named `⚠ 로컬` outliving the session it described.
     *
     * The glyph is now the fallback for the case that justified it in the first place: a tab
     * whose `Content` the platform won't hand over, which can show no icon and would
     * otherwise show no state at all.
     */
    private fun titleFor(baseName: String, status: ClaudeStatus?, content: Content?): String =
        if (content != null) baseName else StatusDecoration.decorate(baseName, status)

    /**
     * Put [status] on [tab]: the icon when the tab can carry one, the glyph in the title
     * when it can't, and the tooltip either way. Returns false if no surface could be
     * reached, which the caller treats as "this tab is gone".
     *
     * Only the frontend surfaces are touched. The backend tab name (the one that persists
     * into `workspace.xml`) keeps the bare name written by [renameTab] — a glyph there would
     * outlive the session it describes and reappear, stale, on the next IDE start.
     */
    private fun applyStatusToTab(tab: TabInfo, baseName: String, status: ClaudeStatus, project: Project? = null): Boolean {
        val content = tab.content ?: project?.let { contentForWidget(it, tab.widget) }
        val display = titleFor(baseName, status, content)
        var applied = false
        if (content != null) {
            try {
                // Setting the icon is not enough to see one. A tool window hides its
                // contents' icons unless the content opts in via ToolWindow.SHOW_CONTENT_ICON,
                // so every icon written before this went somewhere real and rendered nowhere —
                // which is why the glyph, not the icon, was doing all the work.
                //
                // Reflective because the key sits in intellij.platform.ide.core, which this
                // plugin doesn't depend on; the icon is a nice-to-have and must not be able
                // to take the status path down with it.
                enableContentIcon(content)
                content.icon = ClaudeStatusIcons.forStatus(status)
                applied = true
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs][status] setIcon failed: ${e.message}")
            }
        }

        val title = try { findTerminalTitle(tab) } catch (_: Exception) { null }
        if (title != null) {
            try {
                title.change { userDefinedTitle = display }
                applied = true
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs][status] TerminalTitle.change failed: ${e.message}")
            }
        }

        try {
            content?.let {
                it.displayName = display
                it.description = StatusDecoration.tooltip(baseName, status)
                applied = true
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][status] Content update failed: ${e.message}")
        }

        return applied
    }

    /**
     * The `Content` hosting [widget], found by asking the tool window which widget each of
     * its contents holds.
     *
     * Tabs this plugin spawned are tracked by widget, because that is the only handle the
     * platform reliably gives us for them — but an icon needs a `Content`. Rather than
     * threading one through at spawn time (where it isn't available yet either), it is
     * looked up here and cached; the mapping only changes when tabs are created or closed.
     */
    private val contentForWidgetCache = java.util.concurrent.ConcurrentHashMap<TerminalWidget, Content>()

    /** Contents already opted in to showing an icon — the key only needs setting once. */
    private val iconEnabledContents = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Content, Boolean>()
    )

    /**
     * Opt [content] in to having its icon drawn on the tool-window tab.
     *
     * `Content.setIcon` alone paints nothing: a tool window only renders its contents' icons
     * when the content carries `ToolWindow.SHOW_CONTENT_ICON`. Without it the icon is stored
     * and never shown, which is exactly what happened — the heartbeat could report that every
     * tracked tab had a reachable `Content` while the tab strip showed no icons at all.
     *
     * Logged once per content, because "the platform refused the key" and "the icon is drawn
     * but you don't like it" are otherwise indistinguishable from the outside.
     */
    private fun enableContentIcon(content: Content) {
        if (!iconEnabledContents.add(content)) return
        try {
            val twCls = Class.forName("com.intellij.openapi.wm.ToolWindow")
            @Suppress("UNCHECKED_CAST")
            val key = twCls.getField("SHOW_CONTENT_ICON").get(null) as com.intellij.openapi.util.Key<Boolean>
            content.putUserData(key, true)
            LOG.info("[ClaudeTabs][status] Enabled tab icons for '${content.displayName}' via ToolWindow.SHOW_CONTENT_ICON")
        } catch (e: Throwable) {
            LOG.info("[ClaudeTabs][status] Could not enable tab icons (${e.javaClass.simpleName}: ${e.message}) — the glyph in the tab name stays the only indicator")
        }
    }

    private fun contentForWidget(project: Project, widget: TerminalWidget?): Content? {
        if (widget == null) return null
        val cmgr = try {
            TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager
        } catch (_: Exception) { null } ?: return null

        contentForWidgetCache[widget]?.let { cached ->
            // A closed tab's Content would otherwise stay in the map, and writing to it
            // silently paints nothing. Still being in the manager is the liveness test.
            if (cmgr.contents.any { it === cached }) return cached
            contentForWidgetCache.remove(widget)
        }
        return try {
            cmgr.contents.firstOrNull { c ->
                try { TerminalToolWindowManager.findWidgetByContent(c) === widget } catch (_: Exception) { false }
            }?.also { contentForWidgetCache[widget] = it }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][status] contentForWidget failed: ${e.message}")
            null
        }
    }

    /**
     * Ask the terminal plugin what it calls a default tab in this IDE's language.
     *
     * The generic-name list was English only, so on a Korean IDE the default tab (로컬) read
     * as a deliberately chosen name — which made the plugin treat an untouched terminal as
     * meaningful everywhere that predicate is used. Reading the same bundle key the terminal
     * itself uses keeps this correct in any language, rather than growing a list.
     */
    private fun resolveLocalizedDefaultTerminalName() {
        try {
            val bundle = Class.forName("org.jetbrains.plugins.terminal.TerminalBundle")
            val message = bundle.getMethod("message", String::class.java, Array<Any>::class.java)
            val name = message.invoke(null, "local.terminal.default.name", arrayOf<Any>()) as? String
            if (!name.isNullOrBlank()) {
                ClaudeTabsHelpers.localizedDefaultNames = setOf(name.trim())
                LOG.info("[ClaudeTabs] Localised default terminal name: '" + name.trim() + "' — untouched tabs with this name now count as generic")
            }
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] Could not resolve the localised default terminal name (${e.javaClass.simpleName}) — falling back to the bundled list")
        }
    }

    /**
     * Periodic one-liner describing why the status indicator is (or isn't) showing anything.
     *
     * Every transition already logs, but the most likely failure — no glyphs at all — is
     * silent by construction: [refreshStatuses] returns immediately when no tab has been
     * resolved to a session, so an empty tab-walk produces no evidence whatsoever. This
     * prints the inputs at the same cadence as the STEP 6b/6d counters, so "the glyphs never
     * appeared" can be diagnosed from a log excerpt instead of a debugger.
     *
     * Reading it:
     *  - `tracked=0` with `tabs>0` — no tab resolved to a session. If `termMap=0` too, no
     *    session has fired a hook yet: either the hooks aren't in `~/.claude/settings.json`,
     *    or every running session predates their installation and needs restarting.
     *  - `termMap>0` but still `tracked=0` — the bridge has mappings but no backend tab's
     *    session id matched one of them.
     *  - `readings=0` — neither the hooks nor Claude's own session files produced any state.
     */
    private fun logStatusHeartbeat(project: Project, tabCount: Int) {
        try {
            val thisProjectHash = projectHash(project)
            val mine = tabForSession.filterValues { it.projectHash == thisProjectHash }
            // The memo, not a fresh read: this is a log line, and the status loop has
            // already paid for a snapshot within the last 300ms.
            val readings = try { sharedStatusSnapshot() } catch (_: Exception) { emptyMap() }
            val shown = mine.keys.joinToString(", ") { sid ->
                val st = appliedStatus[sid]?.name ?: "-"
                val hook = readings[sid]?.hookEvent ?: readings[rawIdFor(sid)]?.hookEvent ?: "-"
                val claude = readings[sid]?.sessionStatus ?: readings[rawIdFor(sid)]?.sessionStatus ?: "-"
                "${sid.take(8)}:$st(hook=$hook,claude=$claude)"
            }.ifBlank { "none" }
            val termMap = try { statusStore.termSessionMap().size } catch (_: Exception) { -1 }
            // How many tracked tabs expose a `Content` for the icon to be written to. This
            // is reachability only — it says the write lands somewhere real, NOT that the
            // tab strip draws it. Named for what it measures: reading the old `icons=2/2` as
            // "icons work" was wrong, and the icons were in fact invisible the whole time
            // for want of ToolWindow.SHOW_CONTENT_ICON.
            val withContent = mine.count { (_, t) -> (t.tab.content ?: contentForWidget(project, t.tab.widget)) != null }
            LOG.info(
                "[ClaudeTabs][status] tabs=$tabCount tracked=${mine.size} iconTarget=$withContent/${mine.size} " +
                    "readings=${readings.size} termMap=$termMap " +
                    "hookDir=${statusStore.statusDir.absolutePath} exists=${statusStore.statusDir.exists()} — $shown"
            )
            if (mine.isEmpty()) logTabIdentityProbe(project)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][status] heartbeat failed: ${e.message}")
        }
    }

    /**
     * Dump what identity is reachable from each terminal `Content`, for the case where
     * nothing could be attached.
     *
     * Both routes to a tab's identity have now been observed failing on IntelliJ 2026.1:
     * the shell PID is either absent or belongs to a different (empty) tab, and the
     * backend/frontend tab managers intermittently report zero tabs while `ContentManager`
     * still holds several — which leaves the `TERM_SESSION_ID` bridge with nothing to read
     * the id from.
     *
     * Rather than guess at another API, this prints the shape of what is actually reachable
     * from a Content on this build: the widget class and any member that looks like it could
     * carry a session id or the process environment. One log excerpt then says which handle
     * to use, instead of another round of speculative reflection.
     */
    private fun logTabIdentityProbe(project: Project) {
        try {
            val cmgr = TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager ?: return
            val contents = cmgr.contents
            if (contents.isEmpty()) return
            val report = contents.joinToString(" | ") { content ->
                val name = try { content.displayName ?: "?" } catch (_: Exception) { "?" }
                val widget = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                if (widget == null) return@joinToString "'$name'(no-widget)"
                val cls: Class<*> = widget.javaClass
                // The *values* reachable from the widget, not just the member names: the
                // attach path matches a known TERM_SESSION_ID by string content, so what
                // matters is whether any of these actually carries one.
                val values = buildList {
                    for (m in cls.methods) {
                        if (m.parameterCount != 0) continue
                        val n = m.name.lowercase()
                        if (!(n.startsWith("getsession") || n == "getid" || n == "getsessionid" ||
                                n == "getttyconnector" || n == "getttyconnectoraccessor")
                        ) continue
                        val v = try { m.isAccessible = true; m.invoke(widget) } catch (_: Throwable) { "<threw>" }
                        val text = try { v?.toString()?.take(120) ?: "null" } catch (_: Exception) { "<toString threw>" }
                        add("${m.name}()=[${v?.javaClass?.simpleName ?: "null"}] $text")
                    }
                }
                "'$name'(${cls.simpleName}: ${values.joinToString(" ; ").ifBlank { "nothing-identity-like" }})"
            }
            val candidates = try { statusStore.termSessionMap().keys.joinToString(",") { it.take(8) } } catch (_: Exception) { "?" }
            LOG.info("[ClaudeTabs][status] identity probe — ${contents.size} content(s), looking for TERM_SESSION_IDs [$candidates]: $report")
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][status] identity probe failed: ${e.message}")
        }
    }

    /**
     * Hand each tab over from the session that used to be in it to the one there now.
     *
     * Without this the tab stays bound to the old id forever. The old id's last hook event
     * is `SessionEnd`, which [StatusResolver] treats as terminal by design, so the tab sits
     * at `✕` while the conversation in it is actively running — and it never picks up the
     * new conversation's name either, because the name is derived per session id.
     *
     * Two things replace a session in a tab, and they need different evidence to spot:
     *
     *  - **`/clear`** keeps the process, so the pid links the two ids. See
     *    [ClaudeStatusStore.supersededSessions].
     *  - **Quitting Claude and running it again** starts a new process, so the pid join has
     *    nothing to join on. The `TERM_SESSION_ID` bridge spans it. See
     *    [ClaudeTabsHelpers.terminalHandovers].
     */
    private fun rebindSupersededSessions() {
        // Only sessions we hold a tab for can be handed over, and scoping the lookup to them
        // is what stops this costing more on a machine that has simply run Claude a lot:
        // every other SessionEnd on disk belongs to a conversation with no tab to give away.
        val superseded = try {
            statusStore.supersededSessions(tabForSession.keys + spawnedWidgets.keys)
        } catch (_: Exception) {
            emptyMap()
        }
        for ((oldSid, newSid) in superseded) handOverTab(oldSid, newSid, "replaced in place (/clear)")

        val currentTerm = try { statusStore.termSessionMap() } catch (_: Exception) { return }
        val handovers = ClaudeTabsHelpers.terminalHandovers(
            previous = lastTermSessionSid,
            current = currentTerm,
            // Recomputed rather than reused: the pass above may already have re-keyed some
            // of these, and handing the same tab over twice would drop it.
            interesting = tabForSession.keys + spawnedWidgets.keys,
            canonical = ::canonicalize,
        )
        lastTermSessionSid.keys.retainAll(currentTerm.keys)
        lastTermSessionSid.putAll(currentTerm)
        for ((oldSid, newSid) in handovers) handOverTab(oldSid, newSid, "Claude restarted in the same terminal")
    }

    /**
     * Move every per-session handle and cache from [oldSid] to [newSid].
     *
     * Re-keying [spawnedWidgets] is the load-bearing part: it is what Strategy 0 of
     * [attachStatusTabs] rebuilds `tabForSession` from, so leaving the old key there would
     * re-attach the dead session on the very next pass — which is exactly how four dead
     * sessions came to be re-attached to live tabs on every poll, each painting its `✕` over
     * the status the live session had just put there.
     *
     * [reason] is for the log only.
     */
    private fun handOverTab(oldSid: String, newSid: String, reason: String) {
        if (oldSid == newSid) return

        // Carry the permission mode across, before anything else — this is the only
        // moment both sessions are linkable, and the successor's transcript records no
        // mode of its own. Unconditional on whether a tab is being rebound: the value
        // has to reach the restore file either way. See readPermissionMode.
        if (!inheritedPermissionMode.containsKey(newSid) &&
            transcriptPermissionMode(null, newSid) == null
        ) {
            transcriptPermissionMode(null, oldSid)?.let { mode ->
                inheritedPermissionMode[newSid] = mode
                LOG.info("[ClaudeTabs] ${newSid.take(8)} inherits permission mode '$mode' from ${oldSid.take(8)} — a replaced session writes none of its own")
            }
        }

        val widget = spawnedWidgets.remove(oldSid)
        val trackedTab = tabForSession.remove(oldSid)
        if (widget == null && trackedTab == null) return

        if (widget != null) spawnedWidgets.putIfAbsent(newSid, widget)
        if (trackedTab != null) tabForSession.putIfAbsent(newSid, trackedTab)

        // A name the user typed belongs to the *tab*, not to the conversation that
        // happened to be in it, so it follows the hand-over and keeps outranking
        // everything. Without this, the replacement would quietly overwrite it — the
        // successor has no names.json entry of its own, so nothing would be left to protect.
        carryUserChosenName(oldSid, newSid)

        // The plugin's own last-applied name moves too, so the title now on the tab
        // still counts as ours. That is what lets the new conversation's name replace
        // it; dropping the entry instead would leave the tab advertising the replaced
        // conversation forever, since a non-generic title we no longer recognise is
        // treated as someone's deliberate choice.
        lastAppliedName.remove(oldSid)?.let { lastAppliedName.putIfAbsent(newSid, it) }

        // The title listener closes over the id it was installed for, and it is what records
        // a rename typed into the tab strip. Left on the old id it files the user's name
        // under a session nothing reads any more — and goes on re-applying that session's
        // last name over the new one. Dropping it lets the next rename install a listener
        // keyed to the session the tab actually hosts.
        disposeTitleListener(oldSid)

        // Everything else is per-conversation and must be re-derived.
        appliedStatus.remove(oldSid)
        baseNameForSession.remove(oldSid)
        promptNameCache.remove(oldSid)
        sessionEverRunning.remove(oldSid)
        tabSpawnedAt.remove(oldSid)?.let { tabSpawnedAt.putIfAbsent(newSid, it) }

        LOG.info("[ClaudeTabs][status] ${oldSid.take(8)} $reason — handing its tab to ${newSid.take(8)}")
    }

    /**
     * Remove the [com.intellij.terminal.TerminalTitleListener] installed for [sessionId], if
     * any, so a later [installTitleListener] for the same tab is not skipped as redundant.
     *
     * Disposal goes to the EDT: this is called from the status loop's background pass, and
     * the listener list it unregisters from is the terminal's UI state.
     */
    private fun disposeTitleListener(sessionId: String) {
        val disposable = titleListenerDisposables.remove(sessionId) ?: return
        ApplicationManager.getApplication().invokeLater {
            try {
                Disposer.dispose(disposable)
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] disposing title listener for ${sessionId.take(8)} failed: ${e.message}")
            }
        }
    }

    /**
     * Copy a `setBy=user` name from [oldSid] to [newSid], so a hand-over can't lose it.
     *
     * Both `/tab` and right-click → Rename Session land in `names.json` as `setBy=user`,
     * and that entry is the single thing that makes a name un-overwritable. It is keyed by
     * session id, so after `/clear` the successor starts with none — the protection would
     * evaporate at exactly the moment the tab is about to be renamed.
     */
    private fun carryUserChosenName(oldSid: String, newSid: String) {
        try {
            val names = storage.loadNames()
            if (names[newSid]?.setBy == "user") return
            val chosen = names[oldSid]?.takeIf { it.setBy == "user" }?.name ?: return
            storage.upsertName(newSid, chosen, "user")
            lastAppliedName[newSid] = chosen
            LOG.info("[ClaudeTabs] Carried the user's tab name '$chosen' across to ${newSid.take(8)}")
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] carrying user name failed: ${e.message}")
        }
    }

    /** The rotated (pre-canonicalisation) id aliased to [canonicalSid], if one is known. */
    private fun rawIdFor(canonicalSid: String): String =
        sessionAliases.entries.firstOrNull { it.value == canonicalSid }?.key ?: canonicalSid

    /**
     * Attach sessions to tabs by `TERM_SESSION_ID`, for the tabs the PID walk can't reach.
     *
     * On IntelliJ 2026.1's reworked terminal the PID route collapses: the platform hands out
     * shell PIDs belonging to *empty* tabs while the tabs actually hosting Claude report no
     * PID at all. Observed on a real install — `getAllTabs` returned three tabs whose shells
     * were childless `zsh`, while six live Claude sessions hung off shells the enumeration
     * never mentioned. `shell pid → child claude` can't work when the shell pid is wrong.
     *
     * `TERM_SESSION_ID` doesn't have that problem. The backend tab exposes it, every process
     * the tab spawns inherits it, and the status hook records `TERM_SESSION_ID → sessionId`
     * from inside the session. No PID anywhere in the chain.
     *
     * Additive: [sessionsAlreadyTracked] are the ones the PID walk did resolve, and they win
     * — this only fills the gaps. Returns the sids it attached so the caller can keep them
     * out of the prune.
     */
    private fun attachStatusTabs(
        project: Project,
        sessionsAlreadyTracked: Set<String>,
        /**
         * The `TERM_SESSION_ID → sessionId` bridge. Passed in rather than read here so the
         * caller can do the file I/O off the UI thread; null means "read it yourself",
         * which is what the 5s poll does since it is already on the EDT for tab access.
         */
        termSessionMap: Map<String, String>? = null,
    ): Set<String> {
        if (termSessionMap == null) rebindSupersededSessions()

        val termMap = termSessionMap
            ?: try { statusStore.termSessionMap() } catch (_: Exception) { emptyMap() }

        val attached = mutableSetOf<String>()
        val thisProjectHash = projectHash(project)

        // Strategy 0 — tabs this plugin spawned, straight out of [spawnedWidgets].
        //
        // These are the ones that need the indicator most and that every other route
        // misses. Measured on 2026.1: the tab managers reported 1 tab (an empty shell)
        // while ContentManager held 4, and the three they didn't know about were exactly
        // the three the plugin had spawned — their widgets carry no PID, and their
        // `getSession()` throws.
        //
        // But the plugin created them, so it already holds the widget keyed by session id.
        // No reflection, no PID, no TERM_SESSION_ID — just the handle we were given at
        // spawn time.
        for ((spawnSid, widget) in spawnedWidgets) {
            val canonical = canonicalize(spawnSid)
            if (canonical in sessionsAlreadyTracked || spawnSid in sessionsAlreadyTracked) continue
            if (canonical in attached) continue
            val name = try { StatusDecoration.strip(widget.terminalTitle.buildTitle()) } catch (_: Exception) { "" }
            val handle = TabInfo(
                content = null,
                widget = widget,
                pid = -1L,
                reworkedSession = null,
                reworkedTabId = null,
                rawTabName = name,
            )
            tabForSession[canonical] = TrackedTab(handle, thisProjectHash)
            baseNameForSession.putIfAbsent(
                canonical,
                storage.nameFor(canonical) ?: name.takeIf { it.isNotBlank() && !isGenericTabName(it) } ?: "Claude",
            )
            attached.add(canonical)
        }

        if (termMap.isEmpty()) return attached

        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return emptySet()
            val backendTabs = invokeSuspend(tm, tmCls.methods.first { it.name == "getTerminalTabs" }) as? List<*>
                ?: return emptySet()

            // Frontend tabs pair with backend tabs by index — the same assumption getAllTabs
            // makes. The Content is what carries the title we repaint.
            val contents = mutableListOf<Content?>()
            val views = mutableListOf<Any?>()
            try {
                val feMgrCls = Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
                val feMgr = feMgrCls.getMethod("getInstance", Project::class.java).invoke(null, project)
                val feTabs = feMgr?.javaClass?.getMethod("getTabs")?.invoke(feMgr) as? List<*>
                feTabs?.forEach { feTab ->
                    contents.add(try { feTab?.javaClass?.getMethod("getContent")?.invoke(feTab) as? Content } catch (_: Exception) { null })
                    views.add(try { feTab?.javaClass?.getMethod("getView")?.invoke(feTab) } catch (_: Exception) { null })
                }
            } catch (_: ClassNotFoundException) {
                // Classic terminal only — no frontend view list to pair with.
            }

            backendTabs.forEachIndexed { index, tab ->
                tab ?: return@forEachIndexed
                try {
                    val sessIdStr = tab.javaClass.getMethod("getSessionId").invoke(tab)?.toString()
                        ?: return@forEachIndexed
                    // The backend may report the id bare or wrapped (TerminalSessionId(uuid)),
                    // so compare the same loose way handleTermSessionRename does.
                    val sid = termMap.entries.firstOrNull { (termSessionId, _) ->
                        sessIdStr == termSessionId ||
                            sessIdStr.contains(termSessionId) ||
                            termSessionId.contains(sessIdStr)
                    }?.value ?: return@forEachIndexed

                    val canonical = canonicalize(sid)
                    if (canonical in sessionsAlreadyTracked || sid in sessionsAlreadyTracked) return@forEachIndexed

                    val content = contents.getOrNull(index)
                    val view = views.getOrNull(index)
                    val widget = content?.let {
                        try { TerminalToolWindowManager.findWidgetByContent(it) } catch (_: Exception) { null }
                    }
                    if (content == null && widget == null && view == null) return@forEachIndexed

                    val name = tab.javaClass.getMethod("getName").invoke(tab) as? String ?: ""
                    // pid = -1: this handle exists only to paint a title. It is never added
                    // to the tab list poll() walks, so nothing tries to find a Claude under it.
                    val handle = TabInfo(
                        content = content,
                        widget = widget,
                        pid = -1L,
                        reworkedSession = view,
                        reworkedTabId = null,
                        rawTabName = name,
                    )
                    tabForSession[canonical] = TrackedTab(handle, thisProjectHash)
                    baseNameForSession.putIfAbsent(
                        canonical,
                        storage.nameFor(canonical) ?: handle.tabName.takeIf { it.isNotBlank() && !isGenericTabName(it) } ?: "Claude",
                    )
                    attached.add(canonical)
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs][status] termsess attach failed for tab $index: ${e.message}")
                }
            }
        } catch (_: ClassNotFoundException) {
            // Reworked backend absent — the PID path is all there is on this IDE.
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][status] termsess attach pass failed: ${e.message}")
        }

        // Strategy B — go through ContentManager instead of the tab managers.
        //
        // The managers above intermittently report zero tabs while ContentManager still
        // holds every one of them (observed: `Backend has 0 tabs` alongside `contents=3`),
        // which leaves Strategy A with nothing to read an id from. ContentManager keeps
        // working, and its widgets expose `getSession()`, so the terminal id is reachable
        // from there — just not under a name we can hard-code across IDE versions.
        val stillUnmatched = termMap.keys.filter { termMap[it] !in attached }
        if (stillUnmatched.isNotEmpty()) {
            attached += attachViaContentManager(project, termMap, sessionsAlreadyTracked + attached, thisProjectHash)
        }
        return attached
    }

    /**
     * Attach by scanning each terminal widget for a `TERM_SESSION_ID` we already know.
     *
     * Deliberately searches by value rather than by accessor name. The identity probe on
     * 2026.1 found `TerminalWidgetBridge.getSession()` / `getTtyConnector()`, but the shape
     * *inside* those has already changed twice across releases and hard-coding the next
     * accessor name just buys one more version. What is stable is the value: the terminal's
     * id is a UUID, and [known] already holds every UUID the hook has seen. So walk a couple
     * of levels of zero-argument accessors and take the first object whose string form
     * contains one of them.
     */
    private fun attachViaContentManager(
        project: Project,
        termMap: Map<String, String>,
        alreadyTracked: Set<String>,
        thisProjectHash: String,
    ): Set<String> {
        val attached = mutableSetOf<String>()
        try {
            val cmgr = TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager ?: return attached
            for (content in cmgr.contents) {
                val widget = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                    ?: continue
                val termSessionId = findKnownIdentifier(widget, termMap.keys) ?: continue
                val sid = termMap[termSessionId] ?: continue
                val canonical = canonicalize(sid)
                if (canonical in alreadyTracked || sid in alreadyTracked) continue

                val name = try { content.displayName ?: "" } catch (_: Exception) { "" }
                val handle = TabInfo(
                    content = content,
                    widget = widget,
                    pid = -1L,
                    reworkedSession = null,
                    reworkedTabId = null,
                    rawTabName = name,
                )
                tabForSession[canonical] = TrackedTab(handle, thisProjectHash)
                baseNameForSession.putIfAbsent(
                    canonical,
                    storage.nameFor(canonical) ?: handle.tabName.takeIf { it.isNotBlank() && !isGenericTabName(it) } ?: "Claude",
                )
                attached.add(canonical)
                LOG.info("[ClaudeTabs][status] attached '${handle.tabName}' → ${canonical.take(8)} via ContentManager widget scan (TERM_SESSION_ID=${termSessionId.take(8)})")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][status] ContentManager attach failed: ${e.message}")
        }
        return attached
    }

    /**
     * Breadth-first search from [root] for an object whose string form contains one of
     * [known], following zero-argument accessors.
     *
     * Bounded hard: only members that could plausibly hold a session identity are followed,
     * and the walk stops at [maxNodes]. Reflection over an arbitrary object graph is exactly
     * the kind of thing that silently becomes a per-poll performance problem, and a widget
     * transitively reaches the whole editor.
     */
    private fun findKnownIdentifier(root: Any, known: Set<String>, maxNodes: Int = 40): String? {
        if (known.isEmpty()) return null
        val queue = ArrayDeque<Pair<Any, Int>>().apply { add(root to 0) }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            if (!seen.add(node)) continue
            visited++

            val asText = try { node.toString() } catch (_: Exception) { "" }
            if (asText.length <= 512) {
                known.firstOrNull { asText.contains(it) }?.let { return it }
            }
            if (depth >= 2) continue

            for (m in node.javaClass.methods) {
                if (m.parameterCount != 0) continue
                val n = m.name.lowercase()
                if (!(n.startsWith("getsession") || n == "getid" || n == "getsessionid" ||
                        n == "getttyconnector" || n == "getttyconnectoraccessor" || n == "get")
                ) continue
                val child = try {
                    m.isAccessible = true
                    m.invoke(node)
                } catch (_: Throwable) { null } ?: continue
                queue.add(child to depth + 1)
            }
        }
        return null
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
        /** Title exactly as the platform reports it — including the status glyph this plugin
         *  prefixes onto live tabs. Only the status code should read this; everything else
         *  wants [tabName]. */
        val rawTabName: String = ""
    ) {
        /**
         * The tab's name with any status glyph stripped.
         *
         * Every comparison and every persistence path (names.json, restore files, history)
         * goes through here, so the decoration can never round-trip into stored state — the
         * failure mode that would otherwise restore tabs literally named `"● backend"`.
         */
        val tabName: String get() = StatusDecoration.strip(rawTabName)
    }

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
                        rawTabName = name
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
                    rawTabName = content.displayName ?: "Local",
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
                    rawTabName = title ?: "Local",
                ))
                spawnAdded++
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] spawnedWidgets union failed: ${e.message}")
        }
        if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 6: spawnedWidgets union — cached=${spawnedWidgets.size} added=$spawnAdded skipKnown=$spawnSkipKnown skipNoPid=$spawnSkipNoPid")

        // STEP 6c (1.0.18): DockManager containers — covers popped-out terminal tabs.
        //
        // When the user drags a terminal tab out into its own floating window, the tab leaves
        // `TerminalToolWindowManager.getInstance(project).toolWindow.contentManager` (which only
        // sees the docked tool-window strip). It's hosted by a separate `DockContainer` that
        // DockManager tracks. Without walking these, popped-out tabs go silent: poll doesn't see
        // them, they fall out of the restore file, and on next restart they don't come back.
        //
        // Reflective access (no compile-time dep on DockManager internals) — degrades to
        // "no popout coverage" on older IDEs or if the API shifts, rather than crashing.
        var dockAdded = 0
        var dockContainersWalked = 0
        var dockSkipNoMgr = 0
        var dockSkipNoCmgr = 0
        var dockSkipKnown = 0
        var dockSkipNoWidget = 0
        var dockSkipNoPid = 0
        try {
            val dockMgrCls = Class.forName("com.intellij.openapi.fileEditor.impl.DockManager")
            val dockMgr = dockMgrCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            if (dockMgr == null) {
                dockSkipNoMgr++
            } else {
                val containers = dockMgrCls.methods.firstOrNull { it.name == "getContainers" }
                    ?.invoke(dockMgr) as? Collection<*>
                val knownContents = result.mapNotNull { it.content }.toSet()
                val knownWidgets = result.mapNotNull { it.widget }.toSet()
                containers?.forEach { container ->
                    container ?: return@forEach
                    dockContainersWalked++
                    try {
                        // DockContainer doesn't have a single canonical ContentManager accessor;
                        // try the common shapes (getContentManager / containerComponent → manager).
                        val cmgr = container.javaClass.methods.firstOrNull { it.name == "getContentManager" }
                            ?.invoke(container) as? ContentManager
                        if (cmgr == null) { dockSkipNoCmgr++; return@forEach }
                        for (content in cmgr.contents) {
                            if (content in knownContents) { dockSkipKnown++; continue }
                            val widget = try {
                                TerminalToolWindowManager.findWidgetByContent(content)
                            } catch (_: Exception) { null }
                            if (widget == null) { dockSkipNoWidget++; continue }
                            if (widget in knownWidgets) { dockSkipKnown++; continue }
                            val pid = extractPidFromWidget(widget)
                            if (pid == null) { dockSkipNoPid++; continue }
                            result.add(TabInfo(
                                content = content,
                                widget = widget,
                                pid = pid,
                                reworkedSession = null,
                                reworkedTabId = null,
                                rawTabName = content.displayName ?: "Local",
                            ))
                            dockAdded++
                        }
                    } catch (e: Exception) {
                        LOG.debug("[ClaudeTabs] DockManager container walk failed: ${e.message}")
                    }
                }
            }
        } catch (_: ClassNotFoundException) {
            // Older IDE without DockManager. Acceptable degrade — popouts won't be covered.
            dockSkipNoMgr++
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] DockManager unavailable: ${e.message}")
        }
        if (pollCount % 12 == 0) {
            LOG.info(
                "[ClaudeTabs] STEP 6c: DockManager popouts — containers=$dockContainersWalked added=$dockAdded skipKnown=$dockSkipKnown skipNoWidget=$dockSkipNoWidget skipNoPid=$dockSkipNoPid skipNoCmgr=$dockSkipNoCmgr skipNoMgr=$dockSkipNoMgr"
            )
        }

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

        // The status glyph rides on the live title only. `name` stays bare through every
        // persistence path; `display` is what the tab strip shows. Remembering the bare name
        // here is what lets the status loop repaint later without re-deriving it.
        if (sessionId != null) baseNameForSession[sessionId] = name
        val status = sessionId?.let { appliedStatus[it] }
        val display = titleFor(name, status, tab.content ?: contentForWidget(project, tab.widget))

        // Redundancy short-circuit: skip the rename APIs only if both the backend name AND the
        // TerminalTitle's userDefinedTitle already match. The backend can be correct (restored
        // from prior session) while the FRONTEND `Content.displayName` is being overlaid by the
        // AI Assistant — in that case we still need to apply userDefinedTitle so the listener
        // chain repaints. Always install the listener regardless of the short-circuit.
        val backendMatches = isRenameRedundant(tab.tabName, name)
        val titleMatches = title?.userDefinedTitle == display
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
                title.change { userDefinedTitle = display }
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
        tab.content?.displayName = display
        tab.content?.description = StatusDecoration.tooltip(name, status)

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
        // containsKey, explicitly: `in` on a ConcurrentHashMap resolves to containsValue.
        if (titleListenerDisposables.containsKey(sessionId)) return
        try {
            val parentDisposable = Disposer.newDisposable("ClaudeTabs-titleListener-$sessionId")
            Disposer.register(project as Disposable, parentDisposable)
            val listener = object : com.intellij.terminal.TerminalTitleListener {
                override fun onTitleChanged(t: com.intellij.terminal.TerminalTitle) {
                    val desired = lastAppliedName[sessionId] ?: return
                    // Compare on the bare name: our own status glyph is not a title change
                    // worth reacting to, and treating it as one would make this listener
                    // fight the status loop on every state transition.
                    val current = StatusDecoration.strip(t.userDefinedTitle).ifBlank { null }
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
                        // `claude.terminal.tabs.verboseLogs=true` to log every occurrence.
                        val verbose = isVerboseLogging()
                        val now = System.currentTimeMillis()
                        val lastLogged = rateLimitedLogAt[sessionId] ?: 0L
                        if (verbose) {
                            LOG.info("[ClaudeTabs] AI overlay overwrote title for session $sessionId (now '$current') — re-applying '$desired' [verbose]")
                        } else if (now - lastLogged > RATE_LIMITED_LOG_INTERVAL_MS) {
                            LOG.info("[ClaudeTabs] AI overlay overwrote title for session $sessionId (now '$current') — re-applying '$desired' (further events suppressed for ${RATE_LIMITED_LOG_INTERVAL_MS / 1000}s; set Registry claude.terminal.tabs.verboseLogs=true for every event)")
                            rateLimitedLogAt[sessionId] = now
                        }
                        try {
                            // Re-apply through the same rule the status loop uses, so an
                            // overlay overwrite doesn't silently drop the indicator — and
                            // doesn't reintroduce a glyph on a tab that shows an icon.
                            val tabNow = tabForSession[sessionId]?.tab
                            val contentNow = tabNow?.let { it.content ?: contentForWidget(project, it.widget) }
                            t.change { userDefinedTitle = titleFor(desired, appliedStatus[sessionId], contentNow) }
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
                        if (canonical != sessionId) {
                            lastAppliedName[canonical] = current
                            baseNameForSession[canonical] = current
                        }
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
                    // Rotate backups BEFORE overwriting live so backup-1 always holds the
                    // pre-rename state. Defense in depth alongside writeAtomic.
                    storage.rotateBackups(projectHash, content)
                    storage.writeAtomic(f, content)
                    storage.writeSnapshot(projectHash, content, snapshotKeepCount)
                    LOG.info("[ClaudeTabs] /tab persisted: $canonicalSessionId → '$newName' (restore file + snapshot + backup-1 updated)")
                }
            }
            // Persist the name to names.json regardless of whether the restore file changed.
            // names.json is the durable source of truth for tab names; the restore file is
            // the active-sessions list. They update together but for different reasons.
            try { storage.upsertName(canonicalSessionId, newName, "user") } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] names.json upsert failed for $canonicalSessionId: ${e.message}")
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
            // The Remote Control tab hosts a server, not a chat. Its Claude process — and
            // any session that process pre-creates — belongs to the server, not the tab.
            if (tab.pid in remoteControlShellPids) continue
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

        // Startup-grace gate (1.0.17): during the first 60s after this project's plugin
        // start, refuse to save IF the restore-spawn hasn't completed yet AND we'd produce
        // an empty active set. Why: in that window, the spawned tabs may not yet have
        // their Claude processes fully alive, the SessionsDirScanner returns nothing, and
        // a save with newSessions=[] would (in the bypass branch we just removed) have
        // wiped the file. saveState's empty-guard now protects against that, but adding
        // an explicit "don't even try yet" reduces noise and surface area. The TERMSESS
        // and rename fallback above still run — those are name-application, not save.
        val sinceStartupMs = System.currentTimeMillis() - c.startupAt
        val inStartupGrace = sinceStartupMs < 60_000 && !c.restoreFired && c.pendingRestores.isNotEmpty()
        if (inStartupGrace) {
            if (c.pollCount % 12 == 0) {
                LOG.info("[ClaudeTabs] Poll save SKIPPED — startup grace (${sinceStartupMs}ms < 60s, restore not yet fired, ${c.pendingRestores.size} pending)")
            }
            // Still process the rename-file watcher fallback so /tab inside the grace
            // window applies. Skip only the active-session save.
            TABS_DIR.listFiles()?.filter { it.name.startsWith("termsess-") && it.name.endsWith(".json") }?.forEach { f ->
                try {
                    val termSessionId = f.name.removePrefix("termsess-").removeSuffix(".json")
                    if ("termsess-$termSessionId" !in renamedSessions) {
                        val name = extractJsonString(f.readText(), "name") ?: return@forEach
                        handleTermSessionRename(project, termSessionId, name)
                        f.delete()
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] termsess file processing failed (grace): ${e.message}")
                }
            }
            return
        }

        // ─── pendingClose confirmation (signal 2: process dead) ───────────────
        // Two-signal close detection (1.0.17): the contentRemoveQuery listener has already
        // filtered out projectClosing + TEMPORARY_REMOVED_KEY (shuffle/drag/split). What
        // remains in pendingClose is "user did SOMETHING that removed the tab". We only
        // promote to userClosed when the Claude process for that sid is ALSO dead — that
        // confirms the removal was destructive (X-click kills the shell which kills Claude)
        // rather than something we should ignore.
        //
        // Entries that stay in pendingClose past 30s without process-death are dropped as
        // ambiguous (not real closes). 30s is generous — Rider kills child shells within
        // milliseconds of an X-click, and Claude takes ~1s to clean up.
        if (c.pendingClose.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            val pendingSnapshot = HashMap(c.pendingClose)
            // Build a set of sids whose Claude process is currently alive (cwd-scoped to
            // this project so a sid from another project window doesn't accidentally count).
            val alive = mutableSetOf<String>()
            try {
                val pendingSids = pendingSnapshot.keys
                val sessionFiles = SESSIONS_DIR.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
                for (sf in sessionFiles) {
                    val pid = sf.nameWithoutExtension.toLongOrNull() ?: continue
                    val text = try { sf.readText() } catch (_: Exception) { continue }
                    val rawSid = extractJsonString(text, "sessionId") ?: continue
                    val cwd = extractJsonString(text, "cwd") ?: continue
                    if (!ClaudeTabsHelpers.isCwdUnderProject(cwd, project.basePath)) continue
                    // Resolve canonical so we match either form the listener might have stored.
                    val startedAt = Regex(""""startedAt":(\d+)""").find(text)?.groupValues?.get(1)
                        ?.toLongOrNull() ?: nowMs
                    val canonical = canonicalSessionIdFor(pid, cwd, rawSid, startedAt)
                    val isAlive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                    if (!isAlive) continue
                    if (rawSid in pendingSids) alive.add(rawSid)
                    if (canonical in pendingSids) alive.add(canonical)
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs][close] pendingClose alive-check failed: ${e.message}")
            }
            val result = TwoSignalCloseDetector.confirmPending(
                pendingClose = pendingSnapshot,
                aliveSids = alive,
                now = nowMs,
                expiryMs = PENDING_CLOSE_EXPIRY_MS,
            )
            for (sid in result.confirmed) {
                c.pendingClose.remove(sid)
                synchronized(c.userClosedSessions) { c.userClosedSessions.add(sid) }
                try { storage.addUserClosed(projectHash(project), sid) } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] user-closed persist failed for sid=$sid: ${e.message}")
                }
                LOG.info("[ClaudeTabs][close] CONFIRMED user-close (signal 1 + signal 2) sid=$sid — process dead, persisted to user-closed-${projectHash(project)}.json")
            }
            for (sid in result.expired) {
                c.pendingClose.remove(sid)
                LOG.info("[ClaudeTabs][close] Pending-close EXPIRED (process still alive after ${PENDING_CLOSE_EXPIRY_MS}ms) sid=$sid — not recording. Likely a non-close event we false-positived on.")
            }
        }

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
        // 1.0.18: Sids discovered via this project's tab-walk. These bypass the cross-project
        // cwd filter in saveState because they're authoritatively in THIS window (regardless of
        // where the Claude process's cwd points — worktrees, sibling dirs, manual cd, etc.).
        // Also added to the global claimedByTabWalk set so other projects' scanner passes skip
        // them — only one project can own a sid at a time, and tab-walk wins over cwd matching.
        val thisProjectHash = projectHash(project)
        val tabWalkOwnedSids = mutableSetOf<String>()
        // Every sid the tab-walk resolved this poll, including ones not yet eligible for
        // saving (no transcript flushed yet). The status indicator tracks these — it has no
        // reason to wait for a transcript — so pruning is keyed off this set, not the
        // save-eligible one.
        val tabWalkSeenSids = mutableSetOf<String>()

        for (tab in tabs) {
            // The Remote Control tab hosts a server, not a chat. Its Claude process — and
            // any session that process pre-creates — belongs to the server, not the tab.
            if (tab.pid in remoteControlShellPids) continue
            val claudeProcess = findClaudeChild(tab.pid) ?: continue
            val claudePid = claudeProcess.pid()

            val sf = File(SESSIONS_DIR, "$claudePid.json")
            if (!sf.exists()) continue
            val st = try { sf.readText() } catch (_: Exception) { continue }
            val rawSessionId = extractJsonString(st, "sessionId") ?: continue
            val cwd = extractJsonString(st, "cwd") ?: continue
            // Defence in depth against resolving a tab to a background job that the tab's
            // own session spawned (they share the shell's descendant tree).
            if (!ClaudeTabsHelpers.isTerminalTabSessionKind(extractJsonString(st, "kind"))) continue
            val startedAt = Regex(""""startedAt":(\d+)""").find(st)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()

            // Resolve rotated session IDs (Claude --resume rotates in-memory id but keeps
            // appending to the ORIGINAL transcript) to their canonical (transcript-backed) id.
            val sessionId = canonicalSessionIdFor(claudePid, cwd, rawSessionId, startedAt)
            // Record the rotated→canonical alias so /tab and the title listener can resolve
            // the right cache entries when the user runs the script (which reads the rotated
            // id from sessions/<pid>.json) against a resumed session.
            if (sessionId != rawSessionId) {
                sessionAliases[rawSessionId] = sessionId
                // Mirror the name onto the rotated id in names.json too, so future bash
                // scripts that see the rotated id and look up the name find the canonical
                // entry without needing to re-do the canonicalize walk.
                try { storage.aliasName(sessionId, rawSessionId) } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] aliasName failed for $rawSessionId: ${e.message}")
                }
            }
            // Record Content → sessionId so the ContentManagerListener can identify which
            // session is in a tab that the user just closed via the X / right-click menu.
            tab.content?.let { c.contentToSid[it] = sessionId }

            // Hand the status loop a live handle to this tab. Refreshed every poll so the
            // handle tracks split/pop-out moves; registered before the transcript check
            // below so a brand-new session shows its glyph without waiting for the first
            // transcript flush.
            tabForSession[sessionId] = TrackedTab(tab, thisProjectHash)
            tabWalkSeenSids.add(sessionId)

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
                        // Same reason as in the title listener: the status loop repaints from
                        // baseNameForSession, so leaving the old value there hands the user's
                        // name back to the one it replaced at the next status change.
                        baseNameForSession[sessionId] = currentName
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
                // names.json is keyed by canonical sid — that's what the save loop reads.
                try { storage.upsertName(sessionId, name, "user") } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] names.json upsert failed for $sessionId: ${e.message}")
                }
                renameTab(project, tab, name, sessionId = sessionId)
                renamedSessions.add(sessionId)
                renamedSessions.add(rawSessionId)
                try { renameFile.delete() } catch (_: Exception) { }
            }

            // names.json → live-tab re-apply (1.0.17 follow-up). If names.json has a name
            // for this sid and the live tab title is generic (Local / Claude / etc.) or an
            // AI overlay, apply the names.json value. This catches sessions that:
            //   - were named via /tab BEFORE the user opened/restarted the project window
            //     (so the spawn used the old restore-file tabName instead of names.json)
            //   - got a name backfilled via the migration or direct names.json edit
            //   - had their name set by /tab on a different rotated sid that aliases here
            // We skip when the live title is descriptive AND differs — that's a manual
            // in-tab rename by the user (they typed it in the tab strip), which we respect.
            val nameInStore = storage.nameFor(sessionId) ?: storage.nameFor(rawSessionId)
            if (nameInStore != null) {
                val currentTitle = tab.tabName ?: ""
                val shouldApply = currentTitle != nameInStore && (
                    ClaudeTabsHelpers.isGenericTabName(currentTitle) ||
                    ClaudeTabsHelpers.isAiOverlayName(currentTitle, project.name) ||
                    currentTitle.isBlank()
                )
                if (shouldApply) {
                    LOG.info("[ClaudeTabs] Applying names.json: '${currentTitle}' → '${nameInStore}' (sid=${sessionId.take(8)})")
                    renameTab(project, tab, nameInStore, sessionId = sessionId)
                    lastAppliedName[sessionId] = nameInStore
                    renamedSessions.add(sessionId)
                }
            }

            // 1.0.18: Re-enabled tab-walk's contribution to activeSessions.
            //
            // The cell that this session is hosted in IS this project's Rider window — that's
            // what tab-walk discovered. Save it as belonging here, regardless of where the
            // Claude process's cwd is on disk. This is the fix for worktrees (cwd is the
            // worktree dir, NOT the project basePath, but the tab is in the main project's
            // window) and for any case where the user does `cd <other-dir> && claude --resume`
            // in this project's terminal.
            //
            // Bug history that previously gated this:
            //   - tab-walk's PID→sid mapping used a poisoned cache, returning STALE sids that
            //     collided with real ones (two distinct alive sids both mapping to the same
            //     canonical via Strategy 3 mtime fallback). The scanner then skipped real sids
            //     as `skipAlreadyHave`. That's now fixed in `canonicalSessionIdFor` (always
            //     re-check Strategy 1 transcript-on-disk BEFORE consulting cache).
            //
            // Name resolution, in order of how much someone meant it:
            //
            //  1. A name typed via `/tab` — an explicit choice, so nothing outranks it.
            //  2. Claude's own name for the session, when it is a summary of the
            //     conversation rather than something derived from the directory. Free to
            //     read and needs no cooperation from the conversation itself.
            //  3. The conversation's opening question, off the transcript. In practice this
            //     is the one that fires: Claude only auto-names *background* sessions, so a
            //     terminal session's own name stays directory-derived forever and step 2
            //     never produces anything. See ClaudeTabsHelpers.meaningfulSessionName.
            //  4. The pre-existing chain: names.json (any origin), the in-memory
            //     last-applied cache, the live title, the previous save.
            //
            // AI overlay titles are skipped the way the scanner skips them — that is the AI
            // Assistant's status churn, not a name.
            val userChosen = try {
                storage.loadNames()[sessionId]?.takeIf { it.setBy == "user" }?.name
                    ?: storage.loadNames()[rawSessionId]?.takeIf { it.setBy == "user" }?.name
            } catch (_: Exception) { null }
            val claudesOwnName = ClaudeTabsHelpers.meaningfulSessionName(
                extractJsonString(st, "name"),
                extractJsonString(st, "nameSource"),
            ) ?: transcriptName(cwd, sessionId)
            val nameFromStore = storage.nameFor(sessionId) ?: storage.nameFor(rawSessionId)
            val tabTitle = tab.tabName
            val title = userChosen
                ?: claudesOwnName
                ?: nameFromStore
                ?: lastAppliedName[sessionId]
                ?: lastAppliedName[rawSessionId]
                ?: tabTitle?.takeUnless { ClaudeTabsHelpers.isAiOverlayName(it, project.name) }
                ?: c.previousActive[sessionId]?.tabName
                ?: "Claude"
            // Put Claude's name on the tab, not just in the saved state.
            //
            // Applied when the tab is still showing something generic, or when it is showing
            // a name we put there ourselves — the second case is what lets the label follow
            // the conversation as Claude re-summarises it. A title someone typed in the tab
            // strip is never one of those, so it is never overwritten.
            if (claudesOwnName != null && userChosen == null) {
                val currentTitle = tab.tabName
                val oursAlready = lastAppliedName[sessionId] == currentTitle || lastAppliedName[rawSessionId] == currentTitle
                val shouldApply = currentTitle != claudesOwnName && (
                    currentTitle.isBlank() ||
                        ClaudeTabsHelpers.isGenericTabName(currentTitle) ||
                        ClaudeTabsHelpers.isAiOverlayName(currentTitle, project.name) ||
                        oursAlready
                    )
                if (shouldApply) {
                    LOG.info("[ClaudeTabs] Naming tab from Claude's own session name: '$currentTitle' → '$claudesOwnName' (sid=${sessionId.take(8)})")
                    renameTab(project, tab, claudesOwnName, sessionId = sessionId)
                    lastAppliedName[sessionId] = claudesOwnName
                    lastAppliedName[rawSessionId] = claudesOwnName
                    renamedSessions.add(sessionId)
                }
            }

            // Same resolution the status loop needs when it repaints between polls. Kept
            // bare — the glyph is added at write time, never stored.
            baseNameForSession[sessionId] = title

            // Skip sessions whose transcript hasn't been flushed yet — they get picked up next poll.
            if (!hasTranscript(cwd, sessionId)) continue
            val bypass = readPermissionMode(cwd, sessionId)
            activeSessions.add(SavedSession(sessionId, cwd, title, bypass))
            tabWalkOwnedSids.add(sessionId)
            claimedByTabWalk[sessionId] = projectHash(project)
        }

        // Retry the spare-terminal sweep while its window is open — see
        // closeIdeDefaultTerminal for why one shot at restore time isn't enough.
        if (c.restoreFired && !c.defaultTerminalSweepDone && c.restoreFiredAt > 0) {
            closeIdeDefaultTerminal(project)
        }

        // Fill in the tabs the PID walk couldn't reach, by TERM_SESSION_ID. On the reworked
        // terminal this is what actually attaches the indicator — see the method doc.
        val termAttached = attachStatusTabs(project, tabWalkSeenSids)
        if (termAttached.isNotEmpty()) {
            tabWalkSeenSids.addAll(termAttached)
            if (c.pollCount % 12 == 0) {
                LOG.info("[ClaudeTabs][status] attached ${termAttached.size} tab(s) the PID walk missed: ${termAttached.joinToString { it.take(8) }}")
            }
        }

        // Drop status bookkeeping for tabs this window no longer has, so the fast loop
        // doesn't keep poking disposed widgets and the maps don't grow without bound.
        //
        // Guarded on a non-empty walk: `getAllTabs` legitimately returns nothing on Rider
        // 2026.1 when the reworked managers withhold shell PIDs (that's exactly why STEP 6d
        // exists). Pruning on an empty result would blank every glyph on those polls.
        run {
            val gone = ClaudeTabsHelpers.sidsToUntrack(
                tracked = tabForSession.mapValues { it.value.projectHash },
                thisProjectHash = thisProjectHash,
                seenThisPoll = tabWalkSeenSids,
                tabWalkFoundTabs = tabs.isNotEmpty(),
            )
            for (sid in gone) {
                val staleTab = tabForSession.remove(sid)?.tab
                val hadStatus = appliedStatus.remove(sid)
                val base = baseNameForSession.remove(sid)
                tabSpawnedAt.remove(sid)
                sessionEverRunning.remove(sid)
                promptNameCache.remove(sid)
                // A tab whose Claude process ended but whose shell is still open keeps
                // existing as a plain terminal. Take the glyph back off it — otherwise it
                // sits there advertising a state that stopped being true, with nothing left
                // to ever update it. (The `✕` the user sees before this happens comes from
                // the SessionEnd hook, ~5s earlier.)
                if (staleTab != null && hadStatus != null && base != null) {
                    try {
                        findTerminalTitle(staleTab)?.change { userDefinedTitle = base }
                        staleTab.content?.let { it.displayName = base; it.description = base }
                        LOG.info("[ClaudeTabs][status] ${sid.take(8)} untracked — cleared glyph from '$base'")
                    } catch (e: Exception) {
                        LOG.debug("[ClaudeTabs][status] glyph cleanup failed for ${sid.take(8)}: ${e.message}")
                    }
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // STEP 6d (1.0.18): PROCESS-ANCESTRY WALK — definitive "is this in our window?"
        // ──────────────────────────────────────────────────────────────
        // Why this exists: in Rider 2026.1's reworked terminal, `extractPidFromWidget`
        // returns null for tabs that ContentManager sees (logs: `contents=4 skipNoPid=4`).
        // The platform exposes the Content but withholds the underlying shell PID, so
        // STEP 1–6c can't connect tabs → Claude processes. Result: tab-walk reports 0
        // Claude sessions even when the user has 4 tabs in the window.
        //
        // Fix: walk in reverse. Iterate every alive Claude in `~/.claude/sessions/<pid>.json`,
        // walk each one's process-parent chain, and if any ancestor matches THIS JVM's PID,
        // the Claude is hosted by a shell spawned by this Rider window — by definition it
        // belongs to this project, regardless of cwd. No platform API needed.
        //
        // This is the real fix for the worktree case: a session whose cwd is
        // `D:\Dev\ProjectAlpha-couch-mode` but whose shell was spawned by ProjectAlpha's Rider window
        // gets discovered here. Goes into `tabWalkOwnedSids` so `saveState` bypasses the
        // cross-project cwd filter (see `ClaudeTabsHelpers.ownedByProjectSave`).
        val ownJvmPid = ProcessHandle.current().pid()
        var ancestryAdded = 0
        var ancestryScanned = 0
        var ancestrySkipDead = 0
        var ancestrySkipNotOurs = 0
        var ancestrySkipAlreadyHave = 0
        var ancestrySkipNoTranscript = 0
        var ancestrySkipNotInteractive = 0
        try {
            val sessionFiles = SESSIONS_DIR.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
            for (sf in sessionFiles) {
                ancestryScanned++
                val claudePid = sf.nameWithoutExtension.toLongOrNull() ?: continue
                val claudeProc = ProcessHandle.of(claudePid).orElse(null)
                if (claudeProc == null || !claudeProc.isAlive) { ancestrySkipDead++; continue }

                // Walk up to our JVM. Logic lives in ClaudeTabsHelpers.isProcessHostedByJvm
                // (testable). Injected lambda: production uses ProcessHandle parent chain.
                val hitOurJvm = ClaudeTabsHelpers.isProcessHostedByJvm(
                    startPid = claudePid,
                    jvmPid = ownJvmPid,
                    parentOf = { pid -> ProcessHandle.of(pid).orElse(null)?.parent()?.orElse(null)?.pid() },
                )
                if (!hitOurJvm) { ancestrySkipNotOurs++; continue }

                val text = try { sf.readText() } catch (_: Exception) { continue }
                val rawSid = extractJsonString(text, "sessionId") ?: continue
                val cwd = extractJsonString(text, "cwd") ?: continue
                // A background job launched from a terminal session is a descendant of that
                // session, so it descends from this JVM too and the ancestry test above
                // cannot tell them apart. Only the kind field can.
                if (!ClaudeTabsHelpers.isTerminalTabSessionKind(extractJsonString(text, "kind"))) {
                    ancestrySkipNotInteractive++; continue
                }
                val startedAt = Regex(""""startedAt":(\d+)""").find(text)?.groupValues?.get(1)
                    ?.toLongOrNull() ?: System.currentTimeMillis()
                val sid = canonicalSessionIdFor(claudePid, cwd, rawSid, startedAt)
                if (sid in tabWalkOwnedSids) { ancestrySkipAlreadyHave++; continue }
                if (!hasTranscript(cwd, sid)) { ancestrySkipNoTranscript++; continue }

                val name = storage.nameFor(sid)
                    ?: storage.nameFor(rawSid)
                    ?: lastAppliedName[sid]
                    ?: lastAppliedName[rawSid]
                    ?: c.previousActive[sid]?.tabName
                    ?: "Claude"
                val bypass = readPermissionMode(cwd, sid)
                activeSessions.add(SavedSession(sid, cwd, name, bypass))
                tabWalkOwnedSids.add(sid)
                claimedByTabWalk[sid] = projectHash(project)
                ancestryAdded++
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] STEP 6d: ancestry walk failed: ${e.message}")
        }
        if (c.pollCount % 12 == 0) {
            LOG.info(
                "[ClaudeTabs] STEP 6d: ancestry walk — jvm=$ownJvmPid scanned=$ancestryScanned added=$ancestryAdded skipDead=$ancestrySkipDead skipNotOurs=$ancestrySkipNotOurs skipAlreadyHave=$ancestrySkipAlreadyHave skipNoTranscript=$ancestrySkipNoTranscript skipNotInteractive=$ancestrySkipNotInteractive"
            )
            logStatusHeartbeat(project, tabs.size)
        }

        // ──────────────────────────────────────────────────────────────
        // STEP 6b: SESSIONS_DIR direct scan — tab-enumeration-independent fallback.
        // ──────────────────────────────────────────────────────────────
        // Catches any session that ancestry walk missed (e.g. Claude PID dead but
        // resumed via a wrapper script that obscures the parent chain). Matches by
        // cwd-under-basePath. Skips sids already claimed by THIS project's tab-walk
        // or ancestry walk (no double-counting), and sids claimed by ANY OTHER
        // project's tab-walk this run (cross-project arbitration — tab-walk's
        // "this is in my window" beats scanner's "cwd matches my basePath").
        val skipForScanner = tabWalkOwnedSids + claimedByTabWalk.entries
            .filter { it.value != thisProjectHash }
            .map { it.key }
        val scan = SessionsDirScanner.scan(
            sessionsDir = SESSIONS_DIR,
            projectBasePath = project.basePath,
            alreadyActiveIds = skipForScanner,
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
                // Name priority for sessions found via direct scan (1.0.17 order):
                //   1. names.json — durable on-disk store, set by /tab. Beats every other
                //      source because it's the user's explicit choice and can't be
                //      corrupted by the AI Assistant overlay (we don't read titles into it).
                //   2. The widget we spawned for this session (live title — only used when
                //      names.json has nothing AND the widget title isn't an overlay shape).
                //   3. The name we last applied via /tab (in-memory cache).
                //   4. The name we tracked in previousActive.
                //   5. "Claude" default.
                storage.nameFor(sid)?.let { return@scan it }
                val fromWidget = run {
                    val w = spawnedWidgets[sid]
                    try { w?.terminalTitle?.buildTitle() } catch (_: Exception) { null }
                }
                val widgetClean = if (fromWidget != null && ClaudeTabsHelpers.isAiOverlayName(fromWidget, project.name)) null else fromWidget
                widgetClean ?: lastAppliedName[sid] ?: c.previousActive[sid]?.tabName ?: "Claude"
            },
            readBypass = ::readPermissionMode,
        )
        // The scanner uses ClaudeTabsStorage.SavedSession; production uses the
        // identically-shaped nested SavedSession in this class. Convert at the boundary.
        activeSessions.addAll(scan.added.map { SavedSession(it.sessionId, it.cwd, it.tabName, it.bypassPermissions) })
        if (c.pollCount % 12 == 0) {
            LOG.info("[ClaudeTabs] STEP 6b: SESSIONS_DIR scan — ${scan.statusLine()}")
        }

        applyConversationNames(project, activeSessions)

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
        saveState(project, toPersist, tabWalkOwnedSids = tabWalkOwnedSids)
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
     * Upsert this project into `~/.claude/intellij-claude-terminal/project-index.json` (1.0.17).
     *
     * The file is a single JSON object `{"projects":[{...}]}` — one entry per project hash.
     * The `/tabs-status` skill reads it instead of cold-starting Node (~500ms saved on
     * Windows). Idempotent; safe to call on every `runActivity`.
     *
     * Read-modify-write under a synchronized block guards against two projects opening at
     * the same instant racing each other and one's entry being lost.
     */
    @Synchronized
    private fun updateProjectIndex(project: Project) {
        val basePath = project.basePath ?: return
        val hash = projectHash(project)
        val name = project.name
        val indexFile = File(STATE_DIR, "project-index.json")

        val existing = if (indexFile.exists()) {
            try { indexFile.readText() } catch (_: Exception) { "" }
        } else {
            ""
        }

        // Parse the existing array of {"hash":"...","basePath":"...","name":"..."} objects.
        val entries = linkedMapOf<String, Triple<String, String, String>>() // hash → (hash, basePath, name)
        if (existing.isNotBlank()) {
            val re = Regex("""\{[^}]*"hash"\s*:\s*"([^"]+)"[^}]*"basePath"\s*:\s*"((?:[^"\\]|\\.)*)"[^}]*"name"\s*:\s*"((?:[^"\\]|\\.)*)"[^}]*\}""")
            for (m in re.findAll(existing)) {
                val h = m.groupValues[1]
                val b = m.groupValues[2].replace("\\\\", "\\").replace("\\\"", "\"")
                val n = m.groupValues[3].replace("\\\\", "\\").replace("\\\"", "\"")
                entries[h] = Triple(h, b, n)
            }
        }
        val before = entries[hash]
        val after = Triple(hash, basePath, name)
        if (before == after) return  // no-op write — skip the disk hit
        entries[hash] = after

        val body = entries.values.joinToString(",\n") { (h, b, n) ->
            "    {\"hash\":\"${ClaudeTabsHelpers.esc(h)}\",\"basePath\":\"${ClaudeTabsHelpers.esc(b)}\",\"name\":\"${ClaudeTabsHelpers.esc(n)}\"}"
        }
        val content = "{\n  \"projects\": [\n$body\n  ]\n}\n"
        indexFile.parentFile?.mkdirs()
        // Reuse storage's atomic write helper.
        try {
            storage.writeAtomic(indexFile, content)
            LOG.debug("[ClaudeTabs] project-index.json updated for $hash → $basePath")
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] project-index.json write failed: ${e.message}")
        }
    }

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
     * Write [sessions] to the project's restore file as the **authoritative current state**
     * with **two-poll-grace eviction** for entries that have momentarily disappeared.
     *
     * Crash-safety / drift-safety contract:
     *   - `newSessions` is authoritative. Existing entries whose sid is NOT in `newSessions`
     *     are evicted — BUT only after they've been missing for TWO consecutive polls. A
     *     single missed poll (Claude restart, PID race, scanner momentary skip) is forgiven
     *     via the `missedLastPoll` set tracked per-project.
     *   - userClosed subtraction still applies — explicit close is one-shot, no grace.
     *   - Transcript filter still applies — `claude --resume <sid>` can't succeed without it.
     *   - Writes are atomic (tmp + rename). A snapshot is written per save.
     *   - Project shutdown is NOT a close — `projectClosing` short-circuits the listener.
     */
    private fun saveState(
        project: Project,
        sessions: List<SavedSession>,
        tabWalkOwnedSids: Set<String> = emptySet(),
    ) {
        try {
            val basePath = project.basePath
            // 1.0.18: ownership = window-hosting (tab-walk) takes precedence over cwd
            // matching. See [ClaudeTabsHelpers.ownedByProjectSave] for the contract + worktree
            // case. Tested in ClaudeTabsHelpersTest.
            val (kept, leaked) = sessions.partition {
                ClaudeTabsHelpers.ownedByProjectSave(it.sessionId, it.cwd, basePath, tabWalkOwnedSids)
            }
            if (leaked.isNotEmpty()) {
                LOG.info("[ClaudeTabs] Skipping ${leaked.size} cross-project session(s) at save (cwd outside ${basePath ?: "<unknown>"}, not tab-walk-owned): ${leaked.map { "${it.tabName}@${it.cwd}" }}")
            }

            val c = ctx(project)
            val userClosed = synchronized(c.userClosedSessions) { c.userClosedSessions.toSet() }

            val newForStorage = kept.map {
                ClaudeTabsStorage.SavedSession(it.sessionId, it.cwd, it.tabName, it.bypassPermissions)
            }

            // Two-poll-grace: compute which sids are missing from `new` this poll. Entries
            // missing for the FIRST time are graced (passed as keepExistingSids); entries
            // already missing last poll get evicted by simply not being in graceSids.
            val hash = projectHash(project)
            val existingNow = (storage.loadRestoreSafe(hash) as? ClaudeTabsStorage.RestoreRead.Ok)
                ?.sessions ?: emptyList()
            val existingIds = existingNow.mapTo(mutableSetOf()) { it.sessionId }
            val newIds = newForStorage.mapTo(mutableSetOf()) { it.sessionId }
            val missingThisPoll = existingIds - newIds - userClosed
            val graceSids = missingThisPoll - c.missedLastPoll  // first-miss grace
            val evictSids = missingThisPoll intersect c.missedLastPoll  // second-miss eviction
            if (evictSids.isNotEmpty()) {
                LOG.info("[ClaudeTabs] Evicting ${evictSids.size} sid(s) after 2 consecutive misses: ${evictSids.map { it.take(8) }}")
            }
            if (graceSids.isNotEmpty() && c.pollCount % 12 == 0) {
                LOG.info("[ClaudeTabs] First-miss grace, keeping ${graceSids.size} sid(s) this poll: ${graceSids.map { it.take(8) }}")
            }

            storage.saveState(
                projectHash = hash,
                newSessions = newForStorage,
                keepCount = snapshotKeepCount,
                userClosedSessionIds = userClosed,
                transcriptCheck = { cwd, sid -> hasTranscript(cwd, sid) },
                keepExistingSids = graceSids,
            )

            // Next poll: only graceSids remain "previously missed." Evicted ones are gone
            // from the file; new entries (in newIds) reset the miss counter to 0.
            c.missedLastPoll = graceSids
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

                // The restore file is the plugin's own data, but it is plain JSON in the home
                // directory and its session ids are typed into a live terminal by
                // buildResumeCmd. Anything that isn't a plain id is dropped here rather than
                // being allowed to become a command. See ClaudeTabsHelpers.isSafeSessionId.
                val (safe, unsafe) = parsed.partition { ClaudeTabsHelpers.isSafeSessionId(it.sessionId) }
                if (unsafe.isNotEmpty()) {
                    LOG.warn("[ClaudeTabs] Refusing to restore ${unsafe.size} session(s) whose id is not a plain identifier — the id is typed into a terminal, so this is not restorable: ${unsafe.joinToString { "'" + it.sessionId.take(40) + "'" }}")
                }

                val c = ctx(project)
                c.pendingRestores.addAll(safe)
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

        // Settle — let the IDE finish restoring its own remembered tab titles before we start
        // closing/creating tabs, so we see the full leftover set.
        //
        // Waits for the tool window to stop changing rather than for a fixed five seconds:
        // the tabs are normally in place in well under a second, and the remainder was dead
        // time the user sat watching (five of the nine seconds from plugin start to the first
        // tab appearing). The old constant survives as the ceiling.
        val now = System.currentTimeMillis()
        val ageMs = if (c.pendingRestoresLoadedAt > 0) now - c.pendingRestoresLoadedAt else 0
        val contentCount = try {
            TerminalToolWindowManager.getInstance(project).toolWindow?.contentManager?.contents?.size ?: 0
        } catch (_: Exception) { 0 }
        if (contentCount != c.lastContentCount) {
            c.lastContentCount = contentCount
            c.lastContentChangeAt = now
        }
        val quietMs = if (c.lastContentChangeAt > 0) now - c.lastContentChangeAt else 0
        if (!ClaudeTabsHelpers.shouldFireRestore(ageMs, quietMs, ceilingMs = RESTORE_SETTLE_MS)) return
        LOG.info("[ClaudeTabs] Restore settling done after ${ageMs}ms (terminal contents=$contentCount, unchanged for ${quietMs}ms)")

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
        val live = liveSessionsNow()
        for (s in sessions) {
            if (s.sessionId in c.spawnedForSession) continue
            // Never resume a conversation that is already open. See
            // ClaudeTabsHelpers.shouldRestoreSession — without this, reloading the project
            // (which is what installing the plugin does) spawns a duplicate tab for every
            // session that is still running in the tab it has always been in.
            if (!ClaudeTabsHelpers.shouldRestoreSession(s.sessionId, live)) {
                c.spawnedForSession.add(s.sessionId)
                LOG.info("[ClaudeTabs] Restore SKIPPED for '${s.tabName}' (${s.sessionId.take(8)}) — that session is already running; restoring it would open a duplicate tab")
                continue
            }
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
            c.restoreFiredAt = System.currentTimeMillis()
            closeIdeDefaultTerminal(project)
        }

        // Delete restore file so a future poll's saveState doesn't see stale entries.
        try { getStateFile(project).delete() } catch (_: Exception) { }
    }

    /**
     * Reflective call to `TerminalToolWindowManager.createNewSession` to avoid a direct
     * binary reference. The method is annotated `@ApiStatus.Internal` from 2025.x onward,
     * and the Marketplace plugin verifier rejects direct invocations on those grounds. The
     * method is still public Java-visibility, so `getMethod` resolves it; only the
     * static compile-time edge is gone. Throws on signature change so the outer catch in
     * [spawnNewTabAndRestore] surfaces the failure rather than silently returning a null
     * widget.
     *
     * Signature in 2026.1+: `(String?, String?, List<String>?, Boolean, Boolean) -> TerminalWidget`.
     */
    private fun createNewSessionReflective(
        mgr: TerminalToolWindowManager,
        workingDirectory: String?,
        tabName: String?,
        shellCommand: List<String>?,
        requestFocus: Boolean,
        deferSessionStartUntilUiShown: Boolean,
    ): TerminalWidget {
        val method = mgr.javaClass.getMethod(
            "createNewSession",
            String::class.java,
            String::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        return method.invoke(
            mgr,
            workingDirectory,
            tabName,
            shellCommand,
            requestFocus,
            deferSessionStartUntilUiShown,
        ) as TerminalWidget
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
        // Decided before a tab is created: a session whose id can't be typed safely has no
        // restorable command, so opening a terminal for it would leave an empty tab.
        val resumeCmd = buildResumeCmd(s) ?: return false
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
            //
            // Invoked reflectively because createNewSession is annotated @ApiStatus.Internal
            // in 2025.x+ — Marketplace plugin verifier flags direct binary references. The
            // method is still public Java-visibility so getMethod() resolves it; only the
            // compile-time edge is broken. Reverting to createShellWidget is not viable: its
            // wrapper hard-codes deferSessionStartUntilUiShown=true, which leaves the shell
            // process queued until the tab is focused — sendCommandToExecute below would then
            // type `claude --resume` into a cold widget and the restored tab would render as
            // an empty "Local" shell. See git blame on this block for the original migration.
            val widget = createNewSessionReflective(mgr, s.cwd, s.tabName, null, false, false)
            // Record the widget for direct rename access. The platform's tab-enumeration APIs
            // can't reliably find tabs we spawned this way (see [spawnedWidgets] docs), so
            // [handleRename] looks here first before falling back to [getAllTabs].
            spawnedWidgets[s.sessionId] = widget
            tabSpawnedAt[s.sessionId] = System.currentTimeMillis()
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
            val cmd = resumeCmd
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

    // ══════════════════════════════════════════════════════════════
    // REMOTE CONTROL
    // ══════════════════════════════════════════════════════════════

    /**
     * Start `claude remote-control` for [project] in its own terminal tab, unless one is
     * already serving this directory.
     *
     * Runs once per project per IDE start, after the restore spawn has settled, so it never
     * competes with restored tabs for focus or for the terminal tool window's attention.
     *
     * A dedicated tab rather than a background process: Remote Control prints the
     * connection state and accepts keystrokes at runtime (`w` toggles worktree mode), so it
     * has to be somewhere the user can actually see and reach. It is a terminal tab like any
     * other, which keeps the promise that the plugin adds no UI of its own.
     */
    private fun startRemoteControlTab(project: Project) {
        val c = ctx(project)
        // Re-check the cheap in-run guard on the EDT. The decision was made off-thread, so
        // two windows of the same project could have raced through it; the expensive lsof
        // probe is deliberately not repeated here.
        if (c.remoteControlStarted) {
            LOG.info("[ClaudeTabs][rc] Not starting Remote Control — already started for this project in this IDE run")
            return
        }
        // Set before spawning: a spawn that throws must not leave the door open for the
        // next attempt to retry forever.
        c.remoteControlStarted = true

        if (remoteControl.isBackground) {
            startRemoteControlHeadless(project)
            return
        }

        val cmd = RemoteControlLauncher.buildCommand(remoteControl, project.name)
        try {
            val mgr = TerminalToolWindowManager.getInstance(project)
            val widget = createNewSessionReflective(mgr, project.basePath, "Remote Control", null, false, false)
            extractPidFromWidget(widget)?.let { remoteControlShellPids.add(it) }
            // Pin the title: this tab has no Claude chat whose name should surface, and
            // leaving it generic would let the names.json re-apply logic rename it.
            try {
                widget.terminalTitle.change { userDefinedTitle = "Remote Control" }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs][rc] title pin failed: ${e.message}")
            }
            ApplicationManager.getApplication().invokeLater {
                try {
                    widget.sendCommandToExecute(cmd)
                    LOG.info("[ClaudeTabs][rc] Started Remote Control for '${project.name}' at ${project.basePath} — `$cmd`. Local sessions are now controllable from claude.ai/code and the Claude mobile app; set remoteControl.enabled=false in ~/.claude/intellij-claude-terminal/config.json to stop this.")
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs][rc] sendCommandToExecute failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            LOG.warn("[ClaudeTabs][rc] Failed to start Remote Control: ${e.message}")
        }
    }

    /**
     * Start Remote Control as a detached process with no terminal tab
     * (`remoteControl.mode = "background"`).
     *
     * Output goes to `~/.claude/intellij-claude-terminal/remote-control-<projectHash>.log` — without a
     * tab there is nowhere else for the connection URL, or for whatever it says if it
     * refuses to run without a TTY, to appear. The process is killed when the project
     * closes, so a hidden server can't outlive the window that started it.
     */
    private fun startRemoteControlHeadless(project: Project) {
        val executable = resolveClaudeExecutable()
        val argv = RemoteControlLauncher.buildArgv(remoteControl, project.name, executable = executable)
        if (executable == null) {
            LOG.info("[ClaudeTabs][rc] No `claude` executable found on PATH or at any known install location — falling back to a login+interactive shell. That works, but the shell's prompt framework may leave orphaned forks behind; set remoteControl.claudePath in config.json to avoid it.")
        }
        val log = File(STATE_DIR, "remote-control-${projectHash(project)}.log")
        val lock = remoteControlLockFile(project)
        try {
            log.parentFile?.mkdirs()
            // A pty, not a plain pipe. Remote Control is an interactive program: without a
            // terminal on the other end it exits at once, which is what made the first
            // attempt at this mode look simply broken. pty4j ships with the platform (it is
            // what the IDE's own terminal runs on), so there is a real pty available; it is
            // reached reflectively so a missing or moved class degrades to the pipe rather
            // than failing the whole startup path.
            val process = startInPty(argv, File(project.basePath!!), log)
                ?: ProcessBuilder(argv)
                    .directory(File(project.basePath!!))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    .start()

            try {
                lock.parentFile?.mkdirs()
                lock.writeText("""{"pid":${process.pid()},"startedAt":${System.currentTimeMillis()},"cwd":"${esc(project.basePath!!)}"}""")
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs][rc] lock write failed: ${e.message}")
            }

            Disposer.register(project as Disposable) {
                // Kill the tree, not just the process we hold. In the shell fallback the
                // server is a *child* of what we started, so destroying only the parent
                // leaves the real server reparented to launchd — observed as `ppid=1`
                // remote-control shells surviving across IDE restarts, which then look to
                // the duplicate check like a server that is already serving.
                if (process.isAlive) {
                    val descendants = try { process.toHandle().descendants().toList() } catch (_: Exception) { emptyList() }
                    descendants.forEach { runCatching { it.destroy() } }
                    process.destroy()
                    LOG.info("[ClaudeTabs][rc] Stopped background Remote Control (pid ${process.pid()} + ${descendants.size} child process(es)) — project closed")
                }
                reapOrphanedRemoteControl(argv)
                try { lock.delete() } catch (_: Exception) { }
            }
            LOG.info("[ClaudeTabs][rc] Started background Remote Control for '${project.name}' (pid ${process.pid()}), no tab. Output: ${log.absolutePath}. Set remoteControl.enabled=false in ~/.claude/intellij-claude-terminal/config.json to stop this.")
            // A server that dies on startup (no pty, `claude` not on PATH, already-bound
            // port) would otherwise be indistinguishable from one running fine.
            if (process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("[ClaudeTabs][rc] Background Remote Control exited immediately with code ${process.exitValue()} — see ${log.absolutePath}. Falling back is not automatic; set remoteControl.mode=\"tab\" if it needs a visible terminal.")
                try { lock.delete() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs][rc] Background start failed (${argv.joinToString(" ")}): ${e.message}")
        }
    }

    /**
     * Start [argv] attached to a real pseudo-terminal, draining its output into [log].
     *
     * Returns null if pty4j isn't reachable, leaving the caller to fall back to a pipe.
     * Reflective on purpose: pty4j is a platform library rather than a declared dependency,
     * and this whole feature is optional — a class that moved between IDE builds must not
     * take the startup path down with it.
     */
    private fun startInPty(argv: List<String>, dir: File, log: File): Process? = try {
        val builderCls = Class.forName("com.pty4j.PtyProcessBuilder")
        val builder = builderCls.getConstructor(Array<String>::class.java).newInstance(argv.toTypedArray())
        builderCls.getMethod("setDirectory", String::class.java).invoke(builder, dir.absolutePath)
        builderCls.getMethod("setRedirectErrorStream", Boolean::class.javaPrimitiveType).invoke(builder, true)
        // Inherit the IDE's environment and mark it a terminal, which is what the program
        // checks for.
        val env = HashMap(System.getenv()).apply { put("TERM", "xterm-256color") }
        builderCls.getMethod("setEnvironment", Map::class.java).invoke(builder, env)
        val process = builderCls.getMethod("start").invoke(builder) as Process

        // Something has to read the pty or the buffer fills and the program blocks.
        Thread {
            try {
                process.inputStream.use { input ->
                    java.io.FileOutputStream(log, true).use { out -> input.copyTo(out) }
                }
            } catch (_: Exception) { /* process ended */ }
        }.apply { isDaemon = true; name = "ClaudeTabs-rc-drain" }.start()

        LOG.info("[ClaudeTabs][rc] Background Remote Control running under a pty (pid ${process.pid()})")
        process
    } catch (e: Throwable) {
        // Unwrap: a reflective call reports everything as InvocationTargetException, whose
        // own message is null. The first attempt logged exactly that and said nothing about
        // the actual failure, which was a missing executable.
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        LOG.info("[ClaudeTabs][rc] pty unavailable (${cause.javaClass.simpleName}: ${cause.message}) — falling back to a plain pipe, which Remote Control may refuse")
        null
    }

    /**
     * The `claude` executable to run Remote Control with, or null to fall back to a shell.
     *
     * Resolved once per start; the search is a handful of `File.canExecute()` calls.
     */
    private fun resolveClaudeExecutable(): String? {
        val exeName = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "claude.cmd" else "claude"
        val candidates = RemoteControlLauncher.candidatePaths(
            override = remoteControl.claudePath,
            home = System.getProperty("user.home"),
            pathEnv = System.getenv("PATH"),
            exeName = exeName,
        )
        val found = RemoteControlLauncher.resolveExecutable(candidates) { path ->
            try { File(path).let { it.isFile && it.canExecute() } } catch (_: Exception) { false }
        }
        if (found != null) LOG.info("[ClaudeTabs][rc] Using claude at $found — no shell needed")
        return found
    }

    /**
     * Kill processes carrying our exact [argv] that have been reparented to init.
     *
     * The shell fallback can leave these behind: an interactive shell sources the user's
     * prompt framework, and powerlevel10k's `gitstatus` daemon double-forks — the fork keeps
     * the shell's command line but is no longer in our process tree, so the descendant walk
     * above never sees it. One IDE start leaked two of them.
     *
     * Matching is on the *whole* argv joined, not on "looks like remote control": the point
     * is to reap what this project started, never a server the user is running by hand.
     * A no-op once [resolveClaudeExecutable] finds the CLI, since then there is no shell.
     */
    private fun reapOrphanedRemoteControl(argv: List<String>) {
        val signature = argv.joinToString(" ")
        try {
            val reaped = ProcessHandle.allProcesses()
                .filter { it.isAlive }
                .filter { it.parent().map { p -> p.pid() == 1L }.orElse(false) }
                .filter { it.info().commandLine().orElse("") == signature }
                .toList()
            reaped.forEach { runCatching { it.destroy() } }
            if (reaped.isNotEmpty()) {
                LOG.info("[ClaudeTabs][rc] Reaped ${reaped.size} orphaned launcher process(es) (${reaped.joinToString { it.pid().toString() }}) left behind by the shell fallback")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][rc] orphan reap failed: ${e.message}")
        }
    }

    /** `rc-<projectHash>.lock` — the pid of the background server this project started. */
    private fun remoteControlLockFile(project: Project) = File(STATE_DIR, "rc-${projectHash(project)}.lock")

    /**
     * True if the server recorded in this project's lock file is still running.
     *
     * The argv-plus-`lsof` scan was the only duplicate check, and the log shows it never
     * once fired: Remote Control was started seven times across restarts, twice within a
     * single IDE run, with no "already serving" skip. A recorded pid is decidable without
     * depending on argv being readable or on `lsof` output shape.
     */
    private fun remoteControlAlreadyRunning(project: Project): Boolean {
        val lock = remoteControlLockFile(project)
        if (!lock.exists()) return false
        return try {
            val text = lock.readText()
            val pid = Regex(""""pid"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: return false.also { lock.delete() }
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle == null || !handle.isAlive) {
                lock.delete()
                return false
            }
            // Guard against a recycled pid now belonging to something else entirely.
            val cmdLine = handle.info().commandLine().orElse("")
            val stillOurs = cmdLine.isBlank() || RemoteControlLauncher.looksLikeRemoteControl(cmdLine)
            if (!stillOurs) lock.delete()
            stillOurs
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][rc] lock check failed: ${e.message}")
            false
        }
    }

    /**
     * True if some already-running process is a Remote Control server for [basePath].
     *
     * Covers both a server the user started by hand in another terminal and one left over
     * from a previous IDE window; two servers for one directory is never wanted.
     *
     * A process's working directory isn't exposed by [ProcessHandle], so it's read via
     * `lsof` on Unix. That's a subprocess, but this runs once per project startup over the
     * handful of processes that look like Remote Control at all. On Windows there's no
     * equivalent cheap probe, so the answer is "no" and the in-run guard is what prevents
     * duplicates — the worst case there is a second server after an IDE restart.
     */
    private fun isRemoteControlServing(basePath: String?): Boolean {
        if (basePath.isNullOrBlank()) return false
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return false
        return try {
            val target = File(basePath).canonicalPath
            ProcessHandle.allProcesses()
                .filter { it.isAlive }
                .filter { h ->
                    val info = h.info()
                    SessionsDirScanner.looksLikeClaude(
                        SessionsDirScanner.ProcessInfo(
                            command = info.command().orElse(""),
                            commandLine = info.commandLine().orElse(""),
                        )
                    ) && RemoteControlLauncher.looksLikeRemoteControl(info.commandLine().orElse(""))
                }
                .anyMatch { h -> processCwd(h.pid())?.let { it == target } == true }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs][rc] running-server probe failed: ${e.message}")
            false
        }
    }

    /** Working directory of [pid] via `lsof`, or null if it can't be determined. */
    private fun processCwd(pid: Long): String? = try {
        val p = ProcessBuilder("lsof", "-a", "-p", pid.toString(), "-d", "cwd", "-Fn")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        // -Fn output is one field per line; the cwd path line starts with 'n'.
        out.lineSequence()
            .firstOrNull { it.startsWith("n/") }
            ?.removePrefix("n")
            ?.let { File(it).canonicalPath }
    } catch (e: Exception) {
        LOG.debug("[ClaudeTabs][rc] lsof for pid $pid failed: ${e.message}")
        null
    }

    /**
     * Close the empty terminal the IDE opened for itself, so reopening leaves the same
     * number of terminals there were on close.
     *
     * See [ClaudeTabsHelpers.isDisposableDefaultTerminal] for why each guard is there. The
     * short version: only a shell with nothing running in it, still under its default name,
     * and only once real tabs are back.
     */
    private fun closeIdeDefaultTerminal(project: Project) {
        val c = ctx(project)
        if (c.defaultTerminalSweepDone) return
        // Give up after the window: past that point a spare terminal is just a terminal the
        // user has been looking at, and closing it would be a surprise rather than tidying.
        if (c.restoreFiredAt > 0 && System.currentTimeMillis() - c.restoreFiredAt > DEFAULT_TERMINAL_SWEEP_WINDOW_MS) {
            c.defaultTerminalSweepDone = true
            LOG.info("[ClaudeTabs] Default-terminal sweep window closed without finding an empty default tab")
            return
        }
        try {
            val mgr = TerminalToolWindowManager.getInstance(project)
            val ourWidgets = spawnedWidgets.values.toSet()

            // Via getAllTabs, not by walking ContentManager and asking the widget for a pid.
            // The first attempt did the latter and never fired: the IDE's default tab reports
            // its pid through the *backend session*, not the widget
            // (`STEP 3b: [로컬→PID67036(sess)]` alongside `skipNoPid=2`), so the pid always
            // came back null and every tab was skipped. getAllTabs already tries both routes.
            // Every candidate is reported with the value of each guard. Two builds in a row
            // this silently did nothing, and each time the reason was a single input being
            // something other than assumed — with no way to tell which from the log.
            val tabs = getAllTabs(project)
            val verdicts = mutableListOf<String>()

            for (tab in tabs) {
                val name = tab.tabName
                val content = tab.content
                val pid = tab.pid
                if (content == null) { verdicts.add("'$name'(no-content)"); continue }
                if (pid <= 0) { verdicts.add("'$name'(no-pid)"); continue }

                val children = try {
                    ProcessHandle.of(pid).orElse(null)?.children()?.toList()
                } catch (_: Exception) { null }
                // A pid we can't inspect is treated as busy: an unclosed spare tab is a much
                // smaller problem than closing live work.
                val childCount = children?.size ?: 1
                val childNames = children?.joinToString(",") { it.info().command().orElse("?").substringAfterLast('/') } ?: "unreadable"
                val generic = isGenericTabName(name)
                val hasClaude = findClaudeChild(pid) != null
                val spawned = tab.widget != null && tab.widget in ourWidgets

                val disposable = ClaudeTabsHelpers.isDisposableDefaultTerminal(
                    restoredAny = true,
                    isGenericName = generic,
                    hasClaude = hasClaude,
                    childProcessCount = childCount,
                    isPluginSpawned = spawned,
                )
                verdicts.add("'$name'(pid=$pid generic=$generic claude=$hasClaude children=$childCount[$childNames] ours=$spawned → ${if (disposable) "CLOSE" else "keep"})")
                if (!disposable) continue

                try {
                    mgr.closeTab(content)
                    c.defaultTerminalSweepDone = true
                    LOG.info("[ClaudeTabs] Closed the IDE's empty default terminal '$name' (pid=$pid, no children) — it is created at startup and would otherwise leave one tab more than before the restart")
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] closeTab failed for '$name': ${e.message}")
                }
            }
            LOG.info("[ClaudeTabs] Default-terminal sweep — ${tabs.size} tab(s) considered: ${verdicts.joinToString(" | ").ifBlank { "none" }}")
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] default-terminal cleanup failed: ${e.message}")
        }
    }

    /**
     * Snapshot the cumulative list of sessions restored on this Rider start to
     * `~/.claude/intellij-claude-terminal/last-restore.json`. Read by `/tabs-status` so users can see
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
    /**
     * The command a restored tab runs. Returns null for a session id that must not be typed
     * into a shell — this string goes to a live terminal, so the id is the one field here
     * that becomes executable. Callers skip the tab rather than spawn it.
     *
     * The load path already filters these out; this is the second check, next to the
     * interpolation itself, so a future caller that reaches here by another route cannot
     * reintroduce the hole.
     */
    private fun buildResumeCmd(s: SavedSession): String? {
        if (!ClaudeTabsHelpers.isSafeSessionId(s.sessionId)) {
            LOG.warn("[ClaudeTabs] Refusing to build a resume command for session id '${s.sessionId.take(40)}' — not a plain identifier")
            return null
        }
        return buildString {
            append("claude --resume ${s.sessionId}")
            if (s.bypassPermissions) append(" --dangerously-skip-permissions")
        }
    }

    /**
     * True if a transcript file exists for this sessionId. Delegates to
     * [ClaudeTabsHelpers.hasTranscriptAnywhere] — kept as an instance method so call sites
     * can pass it as a method reference (`::hasTranscript`) into scanners that don't know
     * about `CLAUDE_HOME`.
     */
    private fun hasTranscript(cwd: String, sessionId: String): Boolean =
        ClaudeTabsHelpers.hasTranscriptAnywhere(File(CLAUDE_HOME, "projects"), sessionId, cwd)

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
        // Strategy 1 (highest priority, always re-checked — NOT gated by cache).
        // If currentSessionId has a transcript on disk, it IS canonical. Use it and refresh
        // the cache. This corrects any prior misresolve via Strategy 3 mtime fallback (which
        // can map an unrelated PID to a stale sid when transcripts weren't on disk yet at
        // resolve time). Without this re-check, a poisoned cache entry would stick for the
        // lifetime of the process — causing two distinct alive sids to collapse to one
        // canonical, which then makes SessionsDirScanner skip the second one as
        // `skipAlreadyHave` and the save loop drops it from the restore file.
        if (hasTranscript(cwd, currentSessionId)) {
            val cached = pidToCanonicalSession[pid]
            if (cached != null && cached != currentSessionId) {
                LOG.info("[ClaudeTabs] Cache correction for PID $pid: '$cached' → '$currentSessionId' (currentSessionId now has a transcript; prior value was a Strategy 3 mtime-fallback guess)")
            }
            pidToCanonicalSession[pid] = currentSessionId
            return currentSessionId
        }
        // Cache fallback: only honored when Strategy 1 fails (e.g., session is mid --resume
        // and transcript hasn't been flushed yet). Strategy 2/3 results live here.
        pidToCanonicalSession[pid]?.let { return it }

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

    /**
     * Name every tracked tab after its conversation, and carry that name into what gets saved.
     *
     * This exists separately from the naming block inside the tab walk because on IntelliJ
     * 2026.1's reworked terminal that block never executes: the platform's tab enumeration
     * returns nothing for tabs this plugin spawned, so the loop it lives in iterates an empty
     * list. Measured on a real 1.0.37 start — `STEP 4: Total: 0` on every poll while two
     * sessions were tracked and saved fine. [tabForSession] is the handle that does work
     * here; it is the same one the status indicator paints through.
     *
     * Runs over [sessions] (which carry cwd, needed to find the transcript) and rewrites
     * their names in place, so the restore file gets the conversation name too rather than
     * the placeholder the scanner fell back to.
     */
    private fun applyConversationNames(project: Project, sessions: MutableList<SavedSession>) {
        if (sessions.isEmpty()) return
        val thisProjectHash = projectHash(project)
        val names = try { storage.loadNames() } catch (_: Exception) { emptyMap() }

        for (i in sessions.indices) {
            val s = sessions[i]
            val sid = s.sessionId
            // A name someone typed is a decision, not a guess — never overwrite it, and never
            // let the derived name replace it in the save either.
            val userChosen = names[sid]?.takeIf { it.setBy == "user" }?.name
                ?: names[rawIdFor(sid)]?.takeIf { it.setBy == "user" }?.name
            if (userChosen != null) continue

            val derived = transcriptName(s.cwd, sid) ?: continue
            if (s.tabName != derived) sessions[i] = s.copy(tabName = derived)
            baseNameForSession[sid] = derived

            val tracked = tabForSession[sid] ?: continue
            if (tracked.projectHash != thisProjectHash) continue
            val current = tracked.tab.tabName
            // Apply while the tab shows a placeholder, or a name we put there ourselves —
            // never over a title the user typed into the tab strip.
            val oursAlready = lastAppliedName[sid] == current || lastAppliedName[rawIdFor(sid)] == current
            val shouldApply = current != derived && (
                current.isNullOrBlank() ||
                    ClaudeTabsHelpers.isGenericTabName(current) ||
                    ClaudeTabsHelpers.isAiOverlayName(current, project.name) ||
                    oursAlready
                )
            if (!shouldApply) continue
            LOG.info("[ClaudeTabs] Naming tab from the conversation: '$current' → '$derived' (sid=${sid.take(8)})")
            renameTab(project, tracked.tab, derived, sessionId = sid)
            lastAppliedName[sid] = derived
            lastAppliedName[rawIdFor(sid)] = derived
        }
    }

    /**
     * A tab label built from the conversation's opening question — the fallback for what
     * Claude's own `name` field can't give an interactive session. See
     * [ClaudeTabsHelpers.firstPromptName].
     *
     * Cached forever per session: the first user turn is by definition immutable, and the
     * poll runs every 60s over every tab. Only the head of the transcript is read, so a
     * megabyte-scale conversation costs the same as a fresh one.
     */
    private fun transcriptName(cwd: String?, sessionId: String): String? {
        val f = ClaudeTabsHelpers.findTranscript(File(CLAUDE_HOME, "projects"), sessionId, cwd) ?: return null
        val cached = promptNameCache[sessionId]
        // A name, once found, is final — it comes from the first user turn, which never
        // changes. Re-reading would only burn IO.
        cached?.name?.let { return it }
        // A miss is *not* final, and treating it as one was a bug worth naming: a terminal
        // opened a moment ago has a transcript with no user turn in it yet, so the first
        // look finds nothing. Caching that permanently left a live conversation's tab
        // called '로컬' for as long as it stayed open. Re-read whenever the file has
        // changed since the last unsuccessful look, which costs one `lastModified` per poll
        // in the steady state.
        val mtime = f.lastModified()
        if (cached != null && cached.mtime == mtime) return null
        val name = try {
            BufferedReader(FileReader(f)).use { r ->
                ClaudeTabsHelpers.firstPromptName(generateSequence { r.readLine() }.take(PROMPT_SCAN_LINES))
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] transcript name read failed: ${e.message}")
            null
        }
        promptNameCache[sessionId] = PromptName(name, mtime)
        return name
    }

    /**
     * Whether a restored tab's `claude --resume` should carry
     * `--dangerously-skip-permissions`, i.e. whether this session runs with permission
     * prompts bypassed.
     *
     * Read from the transcript, which records it — except after `/clear`, which starts a new
     * transcript with no `permission-mode` line while the process, and therefore the mode,
     * carries on unchanged. Without the fallback a session that had bypass on came back
     * without it after a restart, which is what this fixes.
     *
     * The fallback is deliberately narrow: it applies only when the record is *absent* and
     * only to a session known to have replaced another one in the same process. It carries
     * a mode the user already chose across a `/clear`; it never turns bypass on for a session
     * that didn't have it, and an explicitly recorded non-bypass mode always wins.
     */
    private fun readPermissionMode(cwd: String, sessionId: String): Boolean {
        val recorded = transcriptPermissionMode(cwd, sessionId)
        if (recorded != null) return recorded == "bypassPermissions"
        return inheritedPermissionMode[sessionId] == "bypassPermissions"
    }

    /** The `permission-mode` record in [sessionId]'s transcript, or null if it has none. */
    private fun transcriptPermissionMode(cwd: String?, sessionId: String): String? {
        val f = ClaudeTabsHelpers.findTranscript(File(CLAUDE_HOME, "projects"), sessionId, cwd) ?: return null
        return try {
            BufferedReader(FileReader(f)).use { r ->
                ClaudeTabsHelpers.permissionModeFrom(generateSequence { r.readLine() }.take(PERMISSION_SCAN_LINES))
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] session jsonl read failed: ${e.message}")
            null
        }
    }

    /**
     * `sessionId → ` the permission mode inherited from the session it replaced.
     *
     * Only reachable while the predecessor's hook file and the shared process are both still
     * around, which is why it is captured as soon as the supersession is observed rather
     * than looked up on demand. Once captured it reaches the restore file through the next
     * poll's save, so it survives the restart that would otherwise drop it.
     */
    private val inheritedPermissionMode = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ══════════════════════════════════════════════════════════════
    // CLAUDE DETECTION
    // ══════════════════════════════════════════════════════════════

    /**
     * Every Claude session running on this machine right now, with both the id its process
     * reports and the canonical id it resolves to.
     *
     * Used by the restore path to avoid resuming a conversation that is already open. Reads
     * the same `~/.claude/sessions/<pid>.json` files as everything else and keeps only live,
     * genuinely-Claude processes — a stale file from a crashed process must not make a
     * restorable session look alive.
     */
    private fun liveSessionsNow(): List<ClaudeTabsHelpers.LiveSession> = liveSessionsNow(includeNonInteractive = true)

    /**
     * @param includeNonInteractive when true, background agents / daemon / desktop / remote
     * sessions are included. The restore path wants them: a live background agent is a
     * reason NOT to restore its session, even though it could never have been a tab.
     */
    private fun liveSessionsNow(includeNonInteractive: Boolean): List<ClaudeTabsHelpers.LiveSession> {
        val out = mutableListOf<ClaudeTabsHelpers.LiveSession>()
        try {
            for (sf in SESSIONS_DIR.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()) {
                val pid = sf.nameWithoutExtension.toLongOrNull() ?: continue
                val handle = ProcessHandle.of(pid).orElse(null) ?: continue
                if (!handle.isAlive) continue
                val info = handle.info()
                val looksClaude = SessionsDirScanner.looksLikeClaude(
                    SessionsDirScanner.ProcessInfo(
                        command = info.command().orElse(""),
                        commandLine = info.commandLine().orElse(""),
                    )
                )
                if (!looksClaude) continue
                val text = try { sf.readText() } catch (_: Exception) { continue }
                val rawSid = extractJsonString(text, "sessionId") ?: continue
                val cwd = extractJsonString(text, "cwd") ?: continue
                if (!includeNonInteractive &&
                    !ClaudeTabsHelpers.isTerminalTabSessionKind(extractJsonString(text, "kind"))
                ) continue
                val startedAt = Regex(""""startedAt":(\d+)""").find(text)?.groupValues?.get(1)
                    ?.toLongOrNull() ?: System.currentTimeMillis()
                out.add(
                    ClaudeTabsHelpers.LiveSession(rawSid, canonicalSessionIdFor(pid, cwd, rawSid, startedAt))
                )
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] liveSessionsNow failed: ${e.message}")
        }
        return out
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

    /**
     * Worker for [findClaudeChild]. Matches `claude[.exe|.cmd]` or `node` + `claude` args.
     *
     * Breadth-first, so the **shallowest** Claude under the shell wins. That is always the
     * one the user is typing into: anything a session spawns for itself (a background job,
     * its daemon supervisor, a nested `claude -p`) hangs *below* it in the tree. The
     * previous depth-first walk would descend into a child's whole subtree before checking
     * the shell's remaining direct children, so it could return a background job's process
     * and attribute that job's session — and its status — to the tab.
     */
    private fun findClaudeRec(h: ProcessHandle): ProcessHandle? {
        val queue = ArrayDeque(h.children().toList())
        var visited = 0
        while (queue.isNotEmpty() && visited < 512) {
            val c = queue.removeFirst()
            visited++
            val cmd = c.info().command().orElse(""); val line = c.info().commandLine().orElse("")
            if ((cmd.contains("claude", true) || line.contains("claude", true)) &&
                (cmd.endsWith("claude") || cmd.endsWith("claude.exe") || cmd.endsWith("claude.cmd") ||
                        line.contains("@anthropic", true) || line.contains("claude-code", true) ||
                        (cmd.contains("node", true) && line.contains("claude", true)))) return c
            queue.addAll(c.children().toList())
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
            deployResource("claude-integration/rename-tab.sh", File(CLAUDE_HOME, "intellij-claude-terminal/rename-tab.sh"))
            deployResource("claude-integration/session-start-hook.sh", File(CLAUDE_HOME, "intellij-claude-terminal/session-start-hook.sh"))
            deployResource("claude-integration/status-hook.sh", File(CLAUDE_HOME, "intellij-claude-terminal/status-hook.sh"))
            statusStore.statusDir.mkdirs()
            removeRetiredSlashCommands()

            removeClaudeMdSection()
            patchClaudeSettings()
        } catch (e: Exception) { LOG.warn("[ClaudeTabs] Deploy failed: ${e.message}") }
    }

    /**
     * Register the plugin's hooks and Bash permissions in `~/.claude/settings.json`.
     *
     * Goes through [ClaudeSettingsPatcher], which parses the file and edits the tree. The
     * pre-1.0.19 approach was regex string surgery, which was already brittle for the single
     * `SessionStart` entry and could not survive adding five events: it assumed a specific
     * shape and silently produced invalid JSON when the user's file differed — and a
     * settings.json Claude Code can't parse takes Claude down with it.
     *
     * A missing file is created; an unparseable one is left strictly alone.
     */
    private fun patchClaudeSettings() {
        val sf = File(CLAUDE_HOME, "settings.json")
        try {
            val before = if (sf.exists()) sf.readText() else null
            val after = ClaudeSettingsPatcher.patch(before, PERMISSION_ENTRIES, RETIRED_PERMISSION_ENTRIES)
            if (after == null) {
                if (before != null && !before.isBlank()) {
                    try {
                        MiniJson.parse(before)
                        LOG.debug("[ClaudeTabs] settings.json already up to date")
                    } catch (_: MiniJson.ParseException) {
                        LOG.warn("[ClaudeTabs] settings.json is not valid JSON — leaving it untouched. Hooks and permissions were NOT installed; fix the file and restart the IDE.")
                    }
                }
                return
            }
            // Keep a one-shot copy of whatever was there before the first rewrite, so a bad
            // patch is recoverable by hand.
            val backup = File(CLAUDE_HOME, "intellij-claude-terminal/settings.json.bak")
            if (before != null && !backup.exists()) {
                try { backup.parentFile?.mkdirs(); backup.writeText(before) } catch (_: Exception) { }
            }
            writeAtomic(sf, after)
            LOG.info("[ClaudeTabs] settings.json updated — status hooks (${ClaudeSettingsPatcher.STATUS_EVENTS.joinToString(", ")}) and ${PERMISSION_ENTRIES.size} permissions ensured")
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] settings.json patch failed: ${e.message}")
        }
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
