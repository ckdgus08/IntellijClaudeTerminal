package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlLauncherTest {

    private fun decide(
        config: RemoteControlLauncher.Config = RemoteControlLauncher.Config.DEFAULT,
        started: Boolean = false,
        external: Boolean = false,
        basePath: String? = "/repo",
    ) = RemoteControlLauncher.decide(config, started, external, basePath)

    // ── Decision ──────────────────────────────────────────────────

    @Test fun startsByDefault() {
        assertTrue(decide() is RemoteControlLauncher.Decision.Start)
    }

    @Test fun neverStartsASecondServerForTheSameDirectory() {
        // Two servers for one directory is never wanted, whoever started the first.
        assertTrue(decide(external = true) is RemoteControlLauncher.Decision.Skip)
        assertTrue(decide(started = true) is RemoteControlLauncher.Decision.Skip)
    }

    @Test fun respectsTheOffSwitch() {
        val off = RemoteControlLauncher.Config(enabled = false, spawnMode = null, extraArgs = null, mode = "tab")
        val d = decide(config = off)
        assertTrue(d is RemoteControlLauncher.Decision.Skip)
        assertTrue((d as RemoteControlLauncher.Decision.Skip).reason.contains("enabled=false"))
    }

    @Test fun skipsWhenTheProjectHasNoDirectory() {
        // Remote Control is inherently per-directory; there is nothing to serve.
        assertTrue(decide(basePath = null) is RemoteControlLauncher.Decision.Skip)
        assertTrue(decide(basePath = "") is RemoteControlLauncher.Decision.Skip)
    }

    @Test fun skipReasonsAreLoggableAndDistinct() {
        val reasons = listOf(
            decide(config = RemoteControlLauncher.Config(false, null, null, "tab")),
            decide(started = true),
            decide(external = true),
            decide(basePath = null),
        ).map { (it as RemoteControlLauncher.Decision.Skip).reason }
        assertEquals("each skip path must be distinguishable in the log", reasons.size, reasons.toSet().size)
    }

    // ── Command building ──────────────────────────────────────────

    @Test fun buildsTheMinimalCommand() {
        assertEquals(
            "claude remote-control --name 'my-project'",
            RemoteControlLauncher.buildCommand(RemoteControlLauncher.Config.DEFAULT, "my-project"),
        )
    }

    @Test fun passesThroughSpawnModeAndExtraArgs() {
        val cfg = RemoteControlLauncher.Config(true, "worktree", "--capacity 4", "tab")
        assertEquals(
            "claude remote-control --name 'app' --spawn worktree --capacity 4",
            RemoteControlLauncher.buildCommand(cfg, "app"),
        )
    }

    @Test fun projectNamesCannotInjectShellCommands() {
        // The project name is a directory name — attacker-controllable in the sense that
        // cloning a repo shouldn't be able to run commands. It reaches a real shell.
        val nasty = "'; rm -rf ~; echo '"
        val cmd = RemoteControlLauncher.buildCommand(RemoteControlLauncher.Config.DEFAULT, nasty)
        assertFalse("quoting must not leave the string", cmd.contains("; rm -rf ~; echo ;"))
        assertEquals("""claude remote-control --name ''\''; rm -rf ~; echo '\'''""", cmd)
    }

    @Test fun shellQuoteHandlesTheAwkwardCharacters() {
        assertEquals("'a b'", RemoteControlLauncher.shellQuote("a b"))
        assertEquals("""'it'\''s'""", RemoteControlLauncher.shellQuote("it's"))
        assertEquals("""'$(whoami)'""", RemoteControlLauncher.shellQuote("$(whoami)"))
        assertEquals("""'`id`'""", RemoteControlLauncher.shellQuote("`id`"))
    }

    // ── Detecting an existing server ──────────────────────────────

    @Test fun recognisesBothSpellingsOfTheSubcommand() {
        assertTrue(RemoteControlLauncher.looksLikeRemoteControl("/usr/bin/claude remote-control --name x"))
        assertTrue(RemoteControlLauncher.looksLikeRemoteControl("claude rc"))
        assertTrue(RemoteControlLauncher.looksLikeRemoteControl("claude rc --name x"))
    }

    @Test fun doesNotMistakePathsContainingRcForTheSubcommand() {
        // The reason `rc` is matched as a whole token: almost every repo has a src/ dir.
        assertFalse(RemoteControlLauncher.looksLikeRemoteControl("claude --add-dir /repo/src"))
        assertFalse(RemoteControlLauncher.looksLikeRemoteControl("/Users/x/.rcconfig/claude"))
        assertFalse(RemoteControlLauncher.looksLikeRemoteControl("claude --resume abc"))
        assertFalse(RemoteControlLauncher.looksLikeRemoteControl(null))
        assertFalse(RemoteControlLauncher.looksLikeRemoteControl(""))
    }

    // ── Config parsing ────────────────────────────────────────────

    @Test fun missingConfigMeansDefaults() {
        assertEquals(RemoteControlLauncher.Config.DEFAULT, RemoteControlLauncher.parseConfig(null))
        assertEquals(RemoteControlLauncher.Config.DEFAULT, RemoteControlLauncher.parseConfig(""))
        assertEquals(RemoteControlLauncher.Config.DEFAULT, RemoteControlLauncher.parseConfig("""{"historyMaxAgeDays":90}"""))
    }

    @Test fun parsesTheBlock() {
        val cfg = RemoteControlLauncher.parseConfig(
            """{"remoteControl":{"enabled":false,"spawnMode":"worktree","extraArgs":"--capacity 2"}}"""
        )
        assertFalse(cfg.enabled)
        assertEquals("worktree", cfg.spawnMode)
        assertEquals("--capacity 2", cfg.extraArgs)
    }

    @Test fun onlyReadsEnabledFromInsideOurBlock() {
        // A top-level `enabled` belonging to some other feature must not flip ours.
        val cfg = RemoteControlLauncher.parseConfig("""{"enabled":false,"remoteControl":{"spawnMode":"session"}}""")
        assertTrue(cfg.enabled)
        assertEquals("session", cfg.spawnMode)
    }

    @Test fun rejectsAnInvalidSpawnMode() {
        // Passing an unknown value straight through would make the command fail at runtime
        // in a tab the user has to go read.
        assertEquals(null, RemoteControlLauncher.parseConfig("""{"remoteControl":{"spawnMode":"nonsense"}}""").spawnMode)
    }

    @Test fun blankExtraArgsAreDropped() {
        assertEquals(null, RemoteControlLauncher.parseConfig("""{"remoteControl":{"extraArgs":""}}""").extraArgs)
    }

    @Test fun theTemplateConfigParsesToTheDefaults() {
        // The template written on first start must not silently change behaviour.
        val template = """{
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10,
  "remoteControl": {
    "enabled": true,
    "spawnMode": "same-dir",
    "extraArgs": ""
  }
}"""
        val cfg = RemoteControlLauncher.parseConfig(template)
        assertTrue(cfg.enabled)
        assertEquals("same-dir", cfg.spawnMode)
        assertEquals(null, cfg.extraArgs)
    }
}

/** `remoteControl.mode` — the visible-tab vs no-tab choice. */
class RemoteControlModeTest {

    @Test fun defaultsToAVisibleTab() {
        assertEquals("tab", RemoteControlLauncher.Config.DEFAULT.mode)
        assertFalse(RemoteControlLauncher.Config.DEFAULT.isBackground)
    }

    @Test fun backgroundModeParses() {
        val cfg = RemoteControlLauncher.parseConfig("""{"remoteControl":{"mode":"background"}}""")
        assertTrue(cfg.isBackground)
    }

    @Test fun anUnknownModeFallsBackToTheVisibleTab() {
        // Silently running headless because of a typo would hide the server completely.
        assertEquals("tab", RemoteControlLauncher.parseConfig("""{"remoteControl":{"mode":"hidden"}}""").mode)
    }

    @Test fun backgroundRunsTheExecutableDirectlyWhenItCanBeFound() {
        // No shell at all: the process tree becomes `pty → claude`, which is what makes the
        // teardown's descendant walk sufficient. See fallsBackToAShellOnlyWhenItHasTo.
        val cfg = RemoteControlLauncher.Config(true, "worktree", "--capacity 4", "background")
        val argv = RemoteControlLauncher.buildArgv(cfg, "my project", executable = "/Users/x/.local/bin/claude")
        assertEquals(
            listOf("/Users/x/.local/bin/claude", "remote-control", "--name", "my project",
                   "--spawn", "worktree", "--capacity", "4"),
            argv,
        )
    }

    /**
     * Without a shell there is no quoting problem to have — the name is one argv element,
     * so the shell metacharacters that [shellQuote] exists to neutralise never reach a
     * parser at all.
     */
    @Test fun theDirectFormNeedsNoQuoting() {
        val argv = RemoteControlLauncher.buildArgv(
            RemoteControlLauncher.Config.DEFAULT, "'; rm -rf ~; echo '", executable = "/bin/claude",
        )
        assertEquals("'; rm -rf ~; echo '", argv[3])
        assertFalse(argv.any { it.contains("rm -rf ~;") && it != "'; rm -rf ~; echo '" })
    }

    @Test fun extraArgsAreSplitTheWayAShellWould() {
        assertEquals(listOf("--capacity", "4"), RemoteControlLauncher.splitArgs("--capacity 4"))
        assertEquals(listOf("--label", "my box"), RemoteControlLauncher.splitArgs("""--label "my box""""))
        assertEquals(listOf("--label", "my box"), RemoteControlLauncher.splitArgs("--label 'my box'"))
        assertEquals(emptyList<String>(), RemoteControlLauncher.splitArgs("   "))
        assertEquals(listOf("a", "b"), RemoteControlLauncher.splitArgs("  a   b  "))
    }

    // ── Finding the executable ────────────────────────────────────

    @Test fun looksWhereTheCliActuallyInstalls() {
        val paths = RemoteControlLauncher.candidatePaths(null, "/Users/x", "/usr/bin:/bin")
        assertTrue(paths.contains("/Users/x/.local/bin/claude"))
        assertTrue(paths.contains("/usr/bin/claude"))
        assertTrue(paths.contains("/opt/homebrew/bin/claude"))
    }

    @Test fun theIdesOwnPathWinsOverGuesses() {
        // If the IDE was launched from a terminal it already has the right entry, and that
        // beats a hard-coded list that can go stale.
        val paths = RemoteControlLauncher.candidatePaths(null, "/Users/x", "/opt/mine/bin")
        assertTrue(paths.indexOf("/opt/mine/bin/claude") < paths.indexOf("/Users/x/.local/bin/claude"))
    }

    @Test fun anExplicitConfigPathOutranksEverything() {
        val paths = RemoteControlLauncher.candidatePaths("/custom/claude", "/Users/x", "/usr/bin")
        assertEquals("/custom/claude", paths.first())
    }

    @Test fun readsTheOverrideOutOfConfigJson() {
        val cfg = RemoteControlLauncher.parseConfig("""{"remoteControl":{"claudePath":"/opt/claude"}}""")
        assertEquals("/opt/claude", cfg.claudePath)
        assertEquals(null, RemoteControlLauncher.parseConfig("""{"remoteControl":{}}""").claudePath)
    }

    @Test fun picksTheFirstCandidateThatIsActuallyThere() {
        val present = setOf("/usr/local/bin/claude")
        assertEquals(
            "/usr/local/bin/claude",
            RemoteControlLauncher.resolveExecutable(
                listOf("/nope/claude", "/usr/local/bin/claude", "/usr/bin/claude"),
            ) { it in present },
        )
        assertEquals(null, RemoteControlLauncher.resolveExecutable(listOf("/nope/claude")) { false })
    }

    @Test fun fallsBackToAShellOnlyWhenItHasTo() {
        // The shell path stays: a working server with two stray forks beats no server.
        val argv = RemoteControlLauncher.buildArgv(
            RemoteControlLauncher.Config.DEFAULT, "app", shell = "/bin/zsh", executable = null,
        )
        assertEquals(listOf("/bin/zsh", "-l", "-i", "-c"), argv.take(4))
    }

    @Test fun backgroundRunsThroughALoginInteractiveShell() {
        // An IDE launched from the Dock inherits a minimal PATH without ~/.local/bin, where
        // the CLI installs itself, so running `claude` directly died with
        // `Cannot run program "claude" … error=2 (No such file or directory)`.
        //
        // `-i` is not decoration: measured against a stripped environment,
        // `zsh -l -c 'command -v claude'` still fails, because a login shell reads
        // .zprofile while the PATH entry lives in .zshrc — which only an interactive shell
        // sources. The visible tab works because its shell is `zsh --login -i`.
        val cfg = RemoteControlLauncher.Config(true, "worktree", "--capacity 4", "background")
        val argv = RemoteControlLauncher.buildArgv(cfg, "my project", shell = "/bin/zsh")
        assertEquals(listOf("/bin/zsh", "-l", "-i", "-c"), argv.take(4))
        assertEquals("claude remote-control --name 'my project' --spawn worktree --capacity 4", argv[4])
    }

    @Test fun fallsBackToAShellThatAlwaysExists() {
        assertEquals("/bin/sh", RemoteControlLauncher.buildArgv(RemoteControlLauncher.Config.DEFAULT, "app", shell = null)[0])
        assertEquals("/bin/sh", RemoteControlLauncher.buildArgv(RemoteControlLauncher.Config.DEFAULT, "app", shell = "")[0])
    }

    @Test fun theShellCommandIsStillQuotedAgainstInjection() {
        // It reaches a real shell now, so the quoting matters more, not less.
        val argv = RemoteControlLauncher.buildArgv(
            RemoteControlLauncher.Config.DEFAULT, "'; rm -rf ~; echo '", shell = "/bin/zsh",
        )
        assertEquals(
            RemoteControlLauncher.buildCommand(RemoteControlLauncher.Config.DEFAULT, "'; rm -rf ~; echo '"),
            argv[4],
        )
        assertTrue("the name must stay inside quotes", argv[4].contains("""--name ''\''"""))
    }
}
