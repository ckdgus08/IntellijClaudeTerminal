package com.claudetabs

/**
 * Decides whether — and how — to start `claude remote-control` for a project.
 *
 * Remote Control is a persistent server that lets you drive local Claude sessions from
 * claude.ai/code or the Claude mobile app. It is one server per directory hosting N
 * sessions, which is a different shape from this plugin's one-Claude-per-tab model, so it
 * gets its own dedicated terminal tab rather than being folded into the restore flow.
 *
 * All of the decision logic lives here, free of IntelliJ types, so the "don't start a
 * second one" rules can be tested without an IDE. The caller supplies the observations
 * (what's already running) and performs the spawn.
 */
internal object RemoteControlLauncher {

    /** `~/.claude/rider-plugin/config.json` → `remoteControl` block. */
    data class Config(
        /**
         * Whether to start a server on IDE start.
         *
         * Note what enabling this means: the server exposes control of this machine's
         * Claude sessions to your Claude account, for as long as the IDE is open. It is
         * on by default because the plugin's whole point is that the tabs are managed for
         * you, but it is one line in config.json to turn off.
         */
        val enabled: Boolean,
        /** `same-dir` (default), `worktree`, or `session`. Passed through to `--spawn`. */
        val spawnMode: String?,
        /** Anything else to append to the command line, verbatim. */
        val extraArgs: String?,
        /**
         * `tab` (default) puts the server in a visible terminal tab; `background` runs it as
         * a detached process with its output going to a log file and no tab at all.
         *
         * Background trades away what the tab is for: Remote Control prints its connection
         * state and accepts runtime keystrokes (`w` toggles worktree mode), and it may want
         * a TTY. If the process exits immediately in this mode, that's the reason — the log
         * file will say so.
         */
        val mode: String,
    ) {
        val isBackground get() = mode == "background"

        companion object {
            val DEFAULT = Config(enabled = true, spawnMode = null, extraArgs = null, mode = "tab")
        }
    }

    /** Why the launcher did or didn't act. The reason string goes straight to idea.log. */
    sealed class Decision {
        object Start : Decision()
        data class Skip(val reason: String) : Decision()
    }

    /**
     * Parse the `remoteControl` block out of config.json. Lenient in the same way as
     * [ClaudeTabsHelpers.parseConfig]: anything missing or malformed falls back to the
     * default rather than failing the startup path.
     */
    fun parseConfig(text: String?): Config {
        if (text.isNullOrBlank()) return Config.DEFAULT
        // Narrow the search to the remoteControl object so a top-level `"enabled"` for some
        // future unrelated feature can't be misread as ours.
        val block = Regex(""""remoteControl"\s*:\s*\{([^}]*)\}""").find(text)?.groupValues?.get(1)
            ?: return Config.DEFAULT
        val enabled = Regex(""""enabled"\s*:\s*(true|false)""").find(block)?.groupValues?.get(1)
            ?.toBooleanStrictOrNull() ?: Config.DEFAULT.enabled
        val spawnMode = ClaudeTabsHelpers.extractJsonString(block, "spawnMode")
            ?.takeIf { it in setOf("same-dir", "worktree", "session") }
        val extraArgs = ClaudeTabsHelpers.extractJsonString(block, "extraArgs")?.takeIf { it.isNotBlank() }
        val mode = ClaudeTabsHelpers.extractJsonString(block, "mode")
            ?.takeIf { it == "tab" || it == "background" }
            ?: Config.DEFAULT.mode
        return Config(enabled, spawnMode, extraArgs, mode)
    }

    /**
     * The background-mode argv: the command run through a **login, interactive** shell —
     * the same `-l -i` the IDE's own terminal tab uses.
     *
     * Not `["claude", "remote-control", …]` directly. An IDE launched from the Dock or
     * Finder inherits a minimal PATH without `~/.local/bin`, where the CLI installs itself,
     * so the first attempt at this mode died with
     * `Cannot run program "claude" … error=2 (No such file or directory)`.
     *
     * `-l` alone is not enough either, which is the part worth remembering: measured against
     * a stripped environment, `zsh -l -c 'command -v claude'` still fails. A login shell
     * reads `.zprofile`, but the PATH entry lives in `.zshrc`, which only an *interactive*
     * shell sources. Tab mode works precisely because its shell is `zsh --login -i`, so
     * background mode uses the same flags rather than a guessed list of install locations.
     */
    fun buildArgv(
        config: Config,
        sessionName: String,
        shell: String? = System.getenv("SHELL"),
    ): List<String> = listOf(
        shell?.takeIf { it.isNotBlank() } ?: "/bin/sh",
        "-l",
        "-i",
        "-c",
        buildCommand(config, sessionName),
    )

    /**
     * Should this project window start a server?
     *
     * [alreadyStartedThisRun] guards against a second spawn inside one IDE run.
     * [externalServerForThisDir] is true when a remote-control process is already serving
     * this directory — whether the user started it by hand in another terminal, or it
     * survived from somewhere else. Starting a second server for the same directory is
     * never what anyone wants.
     */
    fun decide(
        config: Config,
        alreadyStartedThisRun: Boolean,
        externalServerForThisDir: Boolean,
        projectBasePath: String?,
    ): Decision = when {
        !config.enabled -> Decision.Skip("disabled via config.json remoteControl.enabled=false")
        projectBasePath.isNullOrBlank() -> Decision.Skip("project has no base path")
        alreadyStartedThisRun -> Decision.Skip("already started for this project in this IDE run")
        externalServerForThisDir -> Decision.Skip("a remote-control server is already serving this directory")
        else -> Decision.Start
    }

    /**
     * True if [commandLine] looks like a running Remote Control server.
     *
     * `rc` is an alias for `remote-control`, so argv can carry either. The alias is matched
     * as a standalone token — a bare `contains("rc")` would match any path with "rc" in it,
     * `src/` being the obvious one.
     */
    fun looksLikeRemoteControl(commandLine: String?): Boolean {
        if (commandLine.isNullOrBlank()) return false
        if (commandLine.contains("remote-control")) return true
        return Regex("""(^|\s)rc(\s|$)""").containsMatchIn(commandLine)
    }

    /**
     * The command typed into the spawned tab's shell.
     *
     * [sessionName] shows up in claude.ai/code as the name of this machine's server, so it
     * is the project name — that's what makes several machines/projects distinguishable on
     * the phone.
     */
    fun buildCommand(config: Config, sessionName: String): String = buildString {
        append("claude remote-control")
        append(" --name ").append(shellQuote(sessionName))
        config.spawnMode?.let { append(" --spawn ").append(it) }
        config.extraArgs?.let { append(' ').append(it) }
    }

    /**
     * POSIX single-quoting. Project names reach the shell verbatim and can contain spaces,
     * quotes, `$`, and backticks — this is a command line, so anything less is a command
     * injection through a directory name.
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
