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

    /** `~/.claude/intellij-claude-terminal/config.json` → `remoteControl` block. */
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
        /**
         * Absolute path to the `claude` executable, when the search in [candidatePaths]
         * doesn't find it. Setting this is what keeps background mode off the interactive
         * shell — see [buildArgv].
         */
        val claudePath: String? = null,
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
        val claudePath = ClaudeTabsHelpers.extractJsonString(block, "claudePath")?.takeIf { it.isNotBlank() }
        return Config(enabled, spawnMode, extraArgs, mode, claudePath)
    }

    /**
     * Where the `claude` executable is looked for, in order, before falling back to a shell.
     *
     * An IDE launched from the Dock or Finder inherits a minimal PATH without
     * `~/.local/bin`, where the CLI installs itself — running `claude` directly under that
     * environment dies with `Cannot run program "claude" … error=2`. The first fix for that
     * was to run the command through `$SHELL -l -i -c`, because the PATH entry lives in
     * `.zshrc` and only an *interactive* shell sources it (`-l` alone genuinely is not
     * enough; that was measured).
     *
     * That worked, and cost more than it looked like. An interactive shell also sources the
     * user's whole prompt framework, and a framework like powerlevel10k starts a `gitstatus`
     * daemon that double-forks to `ppid=1`. Those forks inherit the shell's argv, so each
     * IDE start left two stray `zsh -l -i -c claude remote-control …` processes that the
     * shutdown path — which kills the process *tree* — structurally could not see, because
     * they had already been reparented away from it.
     *
     * Finding the executable ourselves removes the shell from the picture entirely: the
     * process tree becomes `pty → claude` and the existing teardown covers all of it.
     */
    fun candidatePaths(
        override: String?,
        home: String?,
        pathEnv: String?,
        exeName: String = "claude",
    ): List<String> {
        val out = LinkedHashSet<String>()
        override?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        // The IDE's own PATH first: if the user launched from a terminal it already has the
        // right entry, and it beats guessing.
        pathEnv?.split(java.io.File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?.forEach { out.add(it.trimEnd('/', '\\') + java.io.File.separator + exeName) }
        home?.takeIf { it.isNotBlank() }?.let { h ->
            val base = h.trimEnd('/', '\\')
            out.add("$base/.local/bin/$exeName")
            out.add("$base/.claude/local/$exeName")
            out.add("$base/.bun/bin/$exeName")
        }
        out.add("/opt/homebrew/bin/$exeName")
        out.add("/usr/local/bin/$exeName")
        out.add("/usr/bin/$exeName")
        return out.toList()
    }

    /** First candidate that exists and can be run, or null to fall back to the shell. */
    fun resolveExecutable(candidates: List<String>, isExecutable: (String) -> Boolean): String? =
        candidates.firstOrNull(isExecutable)

    /**
     * The background-mode argv.
     *
     * With [executable] resolved, this is the program and its arguments directly — no shell,
     * so no quoting, no rc files, and no orphaned prompt-framework forks. See
     * [candidatePaths] for why that matters.
     *
     * Without it, fall back to the **login, interactive** shell that made this mode work in
     * the first place. It is worse for the reasons above, but a working server with two
     * stray shells beats no server at all.
     */
    fun buildArgv(
        config: Config,
        sessionName: String,
        shell: String? = System.getenv("SHELL"),
        executable: String? = null,
    ): List<String> {
        if (executable != null && executable.isNotBlank()) {
            val argv = mutableListOf(executable, "remote-control", "--name", sessionName)
            config.spawnMode?.let { argv.add("--spawn"); argv.add(it) }
            config.extraArgs?.let { argv.addAll(splitArgs(it)) }
            return argv
        }
        return listOf(
            shell?.takeIf { it.isNotBlank() } ?: "/bin/sh",
            "-l",
            "-i",
            "-c",
            buildCommand(config, sessionName),
        )
    }

    /**
     * Split `extraArgs` the way a shell would, honouring quotes.
     *
     * The setting is documented as "appended verbatim to the command line", and in the shell
     * path it is exactly that. Without a shell there is nothing to do the splitting, and
     * passing the whole string as one argv element would hand `claude` a single nonsense
     * argument.
     */
    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        for (ch in s) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; started = true }
                ch.isWhitespace() -> {
                    if (started) { out.add(current.toString()); current.clear(); started = false }
                }
                else -> { current.append(ch); started = true }
            }
        }
        if (started) out.add(current.toString())
        return out
    }

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
