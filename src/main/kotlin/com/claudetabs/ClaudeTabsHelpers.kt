package com.claudetabs

/**
 * Pure helper functions extracted from [ClaudeTabWatcherStartup] so they can be unit-tested
 * without needing an IntelliJ [com.intellij.openapi.project.Project] instance or a running IDE.
 *
 * Nothing in here touches the filesystem, threads, reflection, or the IntelliJ platform.
 */
internal object ClaudeTabsHelpers {

    // ══════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Extract a string-valued field from [json] by [key], handling standard JSON string escapes.
     * Returns null if the key is missing or malformed.
     *
     * Intentionally hand-rolled (instead of Gson/Jackson) to keep the plugin zero-deps.
     * Only supports flat objects — entries in the plugin's files never have nested objects.
     */
    fun extractJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }

    /** Escape backslashes and double-quotes for embedding into a JSON string literal. */
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ══════════════════════════════════════════════════════════════
    // TAB NAME CLASSIFICATION
    // ══════════════════════════════════════════════════════════════

    /**
     * True if [name] looks like a default JetBrains terminal tab name ("Local", "Local (2)",
     * "bash", "pwsh", etc.). Generic names are never saved for restore or preserved across
     * resume operations — only user-chosen or Claude-assigned names are.
     */
    fun isGenericTabName(name: String): Boolean {
        val n = name.trim()
        if (n == "Local" || n.matches(Regex("Local \\(\\d+\\)")) ||
            n == "bash" || n == "pwsh" || n == "PowerShell" || n == "cmd" ||
            n.matches(Regex("bash \\(\\d+\\)")) || n.matches(Regex("pwsh \\(\\d+\\)"))
        ) return true

        // The IDE's default terminal name is localised, and this list used to be English
        // only. On a Korean IDE the default tab is called 로컬, which read as a name someone
        // had deliberately chosen — so the plugin treated an untouched terminal as
        // meaningful and left it alone everywhere this predicate is consulted. Caught by the
        // spare-terminal sweep reporting `'로컬'(… generic=false …)` with every other guard
        // passing.
        return (localizedDefaultNames + BUNDLED_LOCALIZED_DEFAULTS).any { base ->
            base.isNotBlank() && (n == base || n.matches(Regex("${Regex.escape(base)} \\(\\d+\\)")))
        }
    }

    /**
     * The IDE's own localised default terminal name, resolved at runtime from the terminal
     * plugin's message bundle (`local.terminal.default.name`) so it matches whatever
     * language the IDE is actually running in. Set once at startup; empty until then.
     */
    @Volatile
    var localizedDefaultNames: Set<String> = emptySet()

    /**
     * Values for the language packs JetBrains ships with the IDE, read out of those plugins'
     * bundles. A fallback for when the runtime lookup fails — narrow and checked, rather
     * than a guess at every language that might exist.
     */
    private val BUNDLED_LOCALIZED_DEFAULTS = setOf("로컬", "ローカル", "本地")

    /** Status/spinner glyph prefix the AI Assistant terminal overlay puts on tab names while
     *  a Claude session is active. Covers:
     *   - Braille spinner block `⠁-⠿` (U+2800-U+28FF) — the rotating spinner frames.
     *   - Middle-dot (`·`), plain asterisk (`*`), bullet (`•`), play-arrow (`⏵`).
     *   - Dingbats heavy-asterisk family `✱-✿` (U+2731-U+273F) — the static "Claude is doing
     *     something" glyph. `✳` (U+2733) is the one Claude Code currently emits ("✳ Claude
     *     Code"); the rest cover sibling glyphs the AI host has used historically and could
     *     swap to in any release.
     *
     *  Detected as a literal first-character class. Add new prefixes here when a future
     *  Claude / AI Assistant release leaks a new glyph (file an issue with the offending
     *  byte sequence and we extend). */
    private val AI_OVERLAY_PREFIX = Regex("^[·*•⏵\\u2731-\\u273F\\u2800-\\u28FF]+\\s+\\S")

    /**
     * True if [name] looks like the JetBrains AI Assistant terminal overlay's auto-naming.
     * Matches in two cases:
     *
     *  1. **Status-glyph prefix** — any leading status glyph followed by whitespace and a
     *     non-space character. Catches both project-named (`⠂ rider-claude-tab-namer`) and
     *     topic-detection-named (`* fix-auth-flow`, `* Claude Code`) overlays.
     *  2. **Bare project name** — when the AI Assistant strips its prefix during idle state
     *     and just shows the project folder name verbatim.
     *
     * Used by the title listener to distinguish AI overlay churn (re-apply our name) from
     * a real user manual rename (accept it as the new desired name).
     */
    fun isAiOverlayName(name: String?, projectName: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        if (AI_OVERLAY_PREFIX.containsMatchIn(trimmed)) return true
        if (!projectName.isNullOrBlank() && trimmed.equals(projectName, ignoreCase = true)) return true
        return false
    }

    /**
     * True if [newName] is close enough to [currentName] that renaming is just churn.
     *
     * Used to skip redundant renames on `claude --resume` (where Claude's CLAUDE.md
     * instruction triggers a fresh rename with a similar-but-not-identical name).
     *
     * Rules:
     *  - Current null/blank/generic → never redundant (always allow the rename).
     *  - Exact match (case-insensitive, whitespace-normalised) → redundant.
     *  - Word-set Jaccard ≥ 0.6 when both names have ≥ 2 alphanumeric tokens → redundant.
     */
    fun isRenameRedundant(currentName: String?, newName: String): Boolean {
        if (currentName.isNullOrBlank() || isGenericTabName(currentName)) return false

        val normalised: (String) -> String = { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        if (normalised(currentName) == normalised(newName)) return true

        val tokens: (String) -> Set<String> = { s ->
            s.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 1 }
                .toSet()
        }
        val cur = tokens(currentName)
        val new = tokens(newName)
        if (cur.size < 2 || new.size < 2) return false

        val intersection = cur.intersect(new).size.toDouble()
        val union = cur.union(new).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union
        return jaccard >= 0.6
    }

    // ══════════════════════════════════════════════════════════════
    // NAMES STORE — single source of truth for sessionId → tab name
    // ══════════════════════════════════════════════════════════════

    /**
     * Persistent record of a user-given (or hook-assigned) tab name for a Claude session.
     *
     * Lives in `~/.claude/rider-plugin/names.json` keyed by sessionId. This file is the
     * authoritative source of truth for "what should this tab be called" — the save loop
     * reads from here, never from the live terminal widget title (which gets clobbered by
     * the AI Assistant overlay and was the recurring cause of restored-with-wrong-name bugs).
     *
     *  - `setBy = "user"` — written via `/tab` slash command (explicit user choice).
     *  - `setBy = "hook"` — written by [com.claudetabs.session-start-hook.sh] at session
     *     start with a default-ish topic name.
     *  - `setBy = "alias"` — written by the plugin to mirror a canonical sessionId's name
     *     onto a rotated sid (after `claude --resume`), so future lookups under either
     *     id resolve to the same name.
     */
    data class NameEntry(val name: String, val setBy: String, val setAt: Long)

    // ══════════════════════════════════════════════════════════════
    // CONFIG PARSING
    // ══════════════════════════════════════════════════════════════

    /** Parsed config values with defaults already applied. */
    data class Config(
        val historyMaxAgeMs: Long,
        val snapshotKeepCount: Int,
    ) {
        companion object {
            /** Defaults: 90-day history, 10 snapshots. */
            val DEFAULT = Config(90L * 24 * 60 * 60 * 1000, 10)
        }
    }

    /**
     * Parse a config.json blob leniently — any missing or malformed field falls back to the
     * corresponding default from [Config.DEFAULT]. Unknown fields are ignored.
     *
     * Accepts:
     *  - `historyMaxAgeDays` (int) — converted to ms internally
     *  - `snapshotKeepCount` (int ≥ 0)
     */
    fun parseConfig(text: String?): Config {
        var history = Config.DEFAULT.historyMaxAgeMs
        var snapshots = Config.DEFAULT.snapshotKeepCount
        if (text.isNullOrBlank()) return Config(history, snapshots)

        try {
            Regex(""""historyMaxAgeDays"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let {
                if (it > 0) history = it * 24 * 60 * 60 * 1000
            }
            Regex(""""snapshotKeepCount"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it >= 0) snapshots = it
            }
        } catch (_: Exception) { /* fall through to defaults */ }

        return Config(history, snapshots)
    }

    // ══════════════════════════════════════════════════════════════
    // SHELL DETECTION
    // ══════════════════════════════════════════════════════════════

    /** Process names recognised as terminal shells. */
    private val SHELL_NAMES = setOf(
        "bash", "bash.exe", "sh", "sh.exe", "zsh", "fish",
        "pwsh", "pwsh.exe", "powershell", "powershell.exe", "cmd.exe"
    )

    /**
     * True if [cmd] (absolute or relative path) ends in a known shell executable name.
     * Case-insensitive; handles both `/` and `\` as path separators.
     */
    fun isShellCommand(cmd: String): Boolean {
        val name = cmd.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name in SHELL_NAMES
    }

    // ══════════════════════════════════════════════════════════════
    // PROJECT HASH
    // ══════════════════════════════════════════════════════════════

    /**
     * Derive a stable filesystem-safe identifier from a project base path.
     * Used as the suffix of per-project state files (`restore-<hash>.json`, snapshots, etc.).
     */
    fun projectHashForPath(basePath: String?): String =
        (basePath ?: "default").replace("\\", "/").replace(":/", "--").replace("/", "-")

    /**
     * True if [cwd] is the project base path or a descendant of it.
     *
     * Why this matters: sessions are claude-resumable only from the cwd they ran in. If
     * project A's restore file contains a session whose cwd is project B's tree (because
     * the user `cd`-ed there in A's terminal), `claude --resume <sid>` from A's project
     * root looks at `~/.claude/projects/<A-hash>/<sid>.jsonl` and fails — the transcript
     * lives under `<B-hash>/`. Filter cross-project entries at save / prune / load time so
     * we never even attempt those restores.
     *
     * Both paths are normalised: backslashes → forward slashes, lowercased on Windows-like
     * inputs (drive letters), trailing slashes stripped. Ancestor check is byte-prefix on
     * the normalised path with a `/` boundary so `/repos/MyApp` does NOT match
     * `/repos/MyApp-mobile` (sibling projects with a common path prefix).
     */
    fun isCwdUnderProject(cwd: String?, projectBasePath: String?): Boolean {
        if (cwd.isNullOrBlank()) return false
        // Defensive: if we can't resolve the project base path (rare — only happens for
        // detached/default Rider projects), don't filter. Letting all entries through is
        // safer than silently dropping every session in the file.
        if (projectBasePath.isNullOrBlank()) return true
        val n1 = cwd.replace("\\", "/").trimEnd('/').lowercase()
        val n2 = projectBasePath.replace("\\", "/").trimEnd('/').lowercase()
        if (n1 == n2) return true
        return n1.startsWith("$n2/")
    }

    /**
     * 1.0.18: returns true if a session should be saved into [projectBasePath]'s restore
     * file. The window-state model: a session belongs to whichever Rider window's tab-walk
     * discovered it (authoritative), regardless of cwd. Path-prefix matching remains the
     * fallback for sessions the tab-walk missed (Scanner-discovered).
     *
     * Worktree case (the original bug): a session whose Claude process runs in
     * `D:\Dev\MyApp-feature-branch` (a git worktree) is hosted in a tab inside the main
     * `D:\Dev\MyApp` Rider window. Path-prefix says "different project, drop." Window-
     * hosting says "it's right here, keep." This function lets the latter override.
     */
    fun ownedByProjectSave(
        sessionId: String,
        cwd: String?,
        projectBasePath: String?,
        tabWalkOwnedSids: Set<String>,
    ): Boolean {
        if (sessionId in tabWalkOwnedSids) return true
        return isCwdUnderProject(cwd, projectBasePath)
    }

    /**
     * 1.0.18 ancestry walk: returns true if walking the process-parent chain from
     * [startPid] reaches [jvmPid] within [maxDepth] hops.
     *
     * Why it exists: Rider 2026.1's reworked terminal manager hides shell PIDs from the
     * reflection APIs (`extractPidFromWidget` returns null even when ContentManager sees
     * the tab). So top-down "which tabs are in our window" fails. Bottom-up "which Claude
     * processes have OUR Rider JVM as an ancestor" is reliable and doesn't depend on any
     * platform API. Typical chain on Windows: `claude.exe → pwsh.exe → rider64.exe` = 3
     * hops; on macOS/Linux: `claude → bash/zsh → idea / rider` = 3. Default depth 8 covers
     * unusual wrappers (e.g. `claude` → `node` → `pwsh` → `cmd` → `rider64`).
     *
     * [parentOf] returns the parent PID for a given PID, or null if no parent. Tests can
     * inject a deterministic fake; production uses `ProcessHandle.of(pid).parent().pid()`.
     */
    fun isProcessHostedByJvm(
        startPid: Long,
        jvmPid: Long,
        parentOf: (Long) -> Long?,
        maxDepth: Int = 8,
    ): Boolean {
        var current: Long? = startPid
        for (i in 0 until maxDepth) {
            current = current?.let(parentOf) ?: return false
            if (current == jvmPid) return true
        }
        return false
    }

    // ══════════════════════════════════════════════════════════════
    // CLAUDE'S OWN SESSION NAME
    // ══════════════════════════════════════════════════════════════

    /**
     * Claude's own name for a session, if it means anything.
     *
     * Claude Code keeps a `name` in `~/.claude/sessions/<pid>.json` and says where it came
     * from in `nameSource`:
     *
     *  - `derived` — mechanically built from the working directory (`riderclaudetabs-29`,
     *    `projects-68`). No better than the tab's default, so it is ignored.
     *  - `auto` — Claude's own summary of what the conversation is about
     *    (`설치 스크립트 초기 설정 프로세스 개선`). This is the good one.
     *  - `user` — set deliberately.
     *
     * Reading this is free and needs no cooperation from the conversation. It is strictly
     * better than the alternative the plugin shipped with, which asks Claude — via an
     * instruction injected into `~/.claude/CLAUDE.md` — to spend a turn calling
     * `rename-tab.sh` at the start of every conversation.
     *
     * Returns null when there is nothing worth showing, so callers can fall through.
     */
    fun meaningfulSessionName(name: String?, nameSource: String?): String? {
        if (name.isNullOrBlank()) return null
        if (nameSource != "auto" && nameSource != "user") return null
        val trimmed = name.trim()
        return trimmed.takeUnless { it.isEmpty() || isGenericTabName(it) }
    }

    // ══════════════════════════════════════════════════════════════
    // THE IDE'S OWN DEFAULT TERMINAL
    // ══════════════════════════════════════════════════════════════

    /**
     * Whether a tab is the empty terminal the IDE opens for itself, safe to close after a
     * restore has put the real ones back.
     *
     * When the terminal tool window is active at startup the IDE creates a default tab —
     * observed 68ms after the plugin starts and three seconds before restore fires. Restore
     * then adds the saved sessions on top, so reopening the IDE leaves one more terminal
     * than there were when it closed.
     *
     * Closing someone's terminal is not something to do on a guess, so every one of these
     * has to hold:
     *
     *  - [restoredAny] — a restore actually happened. With nothing restored, a lone empty
     *    terminal is just the terminal, and closing it would leave an empty tool window.
     *  - [isGenericName] — still called "Local" / "로컬" / "bash". A renamed tab is one
     *    someone cared about.
     *  - [hasClaude] is false — nothing of ours is running in it.
     *  - [childProcessCount] is zero — the shell is idle. Anything running (a build, an ssh
     *    session, a paused editor) means it is in use, whatever it is called.
     *  - [isPluginSpawned] is false — never close our own restored tabs.
     *
     * The child-process check is why this is safe rather than merely likely: an untouched
     * default terminal has a shell and nothing else.
     */
    fun isDisposableDefaultTerminal(
        restoredAny: Boolean,
        isGenericName: Boolean,
        hasClaude: Boolean,
        childProcessCount: Int,
        isPluginSpawned: Boolean,
    ): Boolean = restoredAny && isGenericName && !hasClaude && childProcessCount == 0 && !isPluginSpawned

    // ══════════════════════════════════════════════════════════════
    // RESTORE TIMING
    // ══════════════════════════════════════════════════════════════

    /**
     * Whether the restore spawn should fire yet.
     *
     * Restore has to wait for the IDE to finish putting back the tabs it remembered from
     * `workspace.xml`, so the empty leftovers can be closed before fresh ones are spawned.
     * That used to be a flat five-second sleep, chosen as "plenty". It is: the tabs are
     * usually in place in well under a second, and the rest is dead time the user watches —
     * five of the nine seconds between the plugin starting and the first tab appearing.
     *
     * So wait for the tool window to stop changing instead of for the clock: fire once the
     * terminal content count has held steady for [quietMs], and fire regardless once
     * [ceilingMs] has passed so a tool window that never settles can't block restore forever.
     *
     * [lastChangeMs] is how long ago the content count last changed.
     */
    fun shouldFireRestore(
        ageMs: Long,
        lastChangeMs: Long,
        quietMs: Long = 800,
        ceilingMs: Long = 5_000,
    ): Boolean {
        if (ageMs >= ceilingMs) return true
        return lastChangeMs >= quietMs
    }

    // ══════════════════════════════════════════════════════════════
    // RESTORE ELIGIBILITY
    // ══════════════════════════════════════════════════════════════

    /**
     * A session observed running right now: the id its process reports, and the canonical
     * (transcript-backed) id it resolves to. After `claude --resume` these differ.
     */
    data class LiveSession(val rawSessionId: String, val canonicalSessionId: String)

    /**
     * True if [sessionId] should be spawned into a fresh terminal tab.
     *
     * The one rule that was missing: **a session that is already running must never be
     * restored.** Restoring means `claude --resume <id>` in a new tab, so doing it to a live
     * session produces a second process attached to the same conversation — the original tab
     * stays open and a duplicate appears beside it.
     *
     * This is not hypothetical. Installing the plugin reloads the project without killing
     * anything, so the restore file written seconds earlier still lists every session, and
     * every one of them is still alive in the tab it has always been in. The result was N
     * duplicate tabs on install, each resuming a conversation that was already open.
     *
     * Matching is on both ids because the restore file stores canonical ids while a live
     * resumed process reports a rotated one; comparing only one side would miss the very
     * case that produces duplicates.
     */
    fun shouldRestoreSession(sessionId: String, liveSessions: Collection<LiveSession>): Boolean =
        liveSessions.none { it.rawSessionId == sessionId || it.canonicalSessionId == sessionId }

    // ══════════════════════════════════════════════════════════════
    // SESSION KIND — which sessions can be a terminal tab at all
    // ══════════════════════════════════════════════════════════════

    /**
     * True if a session with Claude's `kind` field set to [kind] is one that can live in an
     * IDE terminal tab.
     *
     * Claude Code 2.1.x tags every session in `~/.claude/sessions/<pid>.json` with a kind:
     * `interactive` for a CLI session someone is typing into, and `bg`, `daemon`,
     * `daemon-worker`, `desktop`, `rc`, `bridge` or `remote` for everything else. Only the
     * first is ever a terminal tab.
     *
     * This matters because non-interactive sessions are not off in some other process tree —
     * a background job launched from a terminal session is a *descendant of that session*,
     * so it descends from the IDE's JVM and its cwd is under the project, which is all the
     * ancestry walk and the sessions-dir scanner check. Without this filter a `bg` job gets
     * saved as if it were a tab and comes back on the next IDE start as a terminal running
     * `claude --resume` against a background job's transcript.
     *
     * A null/absent kind is allowed: CLI versions before the field existed wrote nothing,
     * and every session they wrote was interactive.
     */
    fun isTerminalTabSessionKind(kind: String?): Boolean =
        kind == null || kind.isBlank() || kind == "interactive"

    // ══════════════════════════════════════════════════════════════
    // STATUS TRACKING — which sessions this window should stop tracking
    // ══════════════════════════════════════════════════════════════

    /**
     * Decide which sessions the status indicator should forget at the end of a poll.
     *
     * [tracked] maps sessionId → the project hash of the window that registered it. The map
     * is shared by every open project window (the platform runs one instance of the startup
     * activity across all of them), so two rules apply:
     *
     *  1. **Only ever untrack your own.** Without this, each window's poll would drop the
     *     other windows' sessions on every cycle and the two would delete each other's
     *     tracking in a loop — the glyphs would flicker off every few seconds for anyone
     *     with more than one project open.
     *  2. **Never untrack on an empty tab-walk.** `getAllTabs` legitimately returns nothing
     *     on the reworked terminal when the platform withholds shell PIDs; treating that as
     *     "every tab closed" would blank the indicator on those polls.
     *
     * [seenThisPoll] is every sid this window's tab-walk resolved, including ones not yet
     * eligible for saving — the indicator has no reason to wait for a transcript flush.
     */
    fun sidsToUntrack(
        tracked: Map<String, String>,
        thisProjectHash: String,
        seenThisPoll: Set<String>,
        tabWalkFoundTabs: Boolean,
    ): Set<String> {
        if (!tabWalkFoundTabs) return emptySet()
        return tracked.entries
            .filter { it.value == thisProjectHash && it.key !in seenThisPoll }
            .map { it.key }
            .toSet()
    }

    // ══════════════════════════════════════════════════════════════
    // TRANSCRIPT LOOKUP
    // ══════════════════════════════════════════════════════════════

    /**
     * True if a transcript file `<sessionId>.jsonl` exists anywhere under [projectsDir]
     * (which is `~/.claude/projects/` in production).
     *
     * Fast path: check the cwd-derived subdir (`<projectsDir>/<encoded-cwd>/<sessionId>.jsonl`)
     * since that's where Claude writes for fresh sessions.
     *
     * Fallback: scan all immediate subdirs. Needed when a session originally started in
     * cwd A is later resumed by sid from cwd B — Claude keeps appending to the original
     * transcript path (under A's encoded dir), not the one derived from B. Without the
     * fallback, every cross-cwd resume (most commonly: resuming a session inside a git
     * worktree shell) was rejected by `skipNoTranscript`.
     *
     * The cwd → subdir encoding matches Claude's: backslash → forward-slash, then `:/` →
     * `--`, then `/` → `-`.
     */
    fun hasTranscriptAnywhere(projectsDir: java.io.File, sessionId: String, cwd: String?): Boolean {
        if (sessionId.isBlank()) return false
        if (!cwd.isNullOrBlank()) {
            val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
            if (java.io.File(java.io.File(projectsDir, h), "$sessionId.jsonl").exists()) return true
        }
        val dirs = projectsDir.listFiles { f -> f.isDirectory } ?: return false
        for (d in dirs) {
            if (java.io.File(d, "$sessionId.jsonl").exists()) return true
        }
        return false
    }

    // ══════════════════════════════════════════════════════════════
    // ARGV PARSING — canonical session id from --resume flag
    // ══════════════════════════════════════════════════════════════

    private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * Parse a Claude process's argv and return the UUID following `--resume` / `-r` /
     * `--resume=<uuid>`, or null if no resume flag is present (fresh session) or the
     * value isn't a UUID.
     *
     * The canonical session id under which Claude appends to `~/.claude/projects/<cwd>/<id>.jsonl`
     * after `claude --resume <id>` is **literally the argv value** — Claude may rotate its
     * in-memory id afterwards, but the on-disk transcript stays under the resume target.
     * This is the only fully-reliable signal when multiple resumed sessions are
     * concurrently active (the mtime heuristic can't distinguish them).
     */
    fun extractResumeIdFromArgs(args: Array<String>?): String? {
        if (args == null) return null
        for (i in args.indices) {
            val a = args[i]
            if (a == "--resume" || a == "-r") {
                return args.getOrNull(i + 1)?.takeIf { it.matches(UUID_REGEX) }
            }
            if (a.startsWith("--resume=")) {
                return a.substringAfter('=').takeIf { it.matches(UUID_REGEX) }
            }
        }
        return null
    }
}
