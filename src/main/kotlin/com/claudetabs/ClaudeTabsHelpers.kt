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
        return n == "Local" || n.matches(Regex("Local \\(\\d+\\)")) ||
            n == "bash" || n == "pwsh" || n == "PowerShell" || n == "cmd" ||
            n.matches(Regex("bash \\(\\d+\\)")) || n.matches(Regex("pwsh \\(\\d+\\)"))
    }

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
