# Claude & Codex Terminal Tab Persistence

A JetBrains Rider / IntelliJ plugin for running several [Claude Code](https://claude.com/claude-code) and
[OpenAI Codex](https://learn.chatgpt.com/docs/hooks) sessions in the IDE's own terminal. It shows what each session is
doing on its tab, and brings the tabs back after a restart.

It adds no UI of its own: no tool window, no chat panel, no change to how you start `claude` or `codex`. Everything
happens on the terminal tab strip you already use.

## What it does

- **Shows each session's state on its tab**, so you can tell at a glance which one needs you:

  | Icon | State | Meaning |
  |---|---|---|
  | blue disc | Working | The agent is running a turn — thinking, calling tools, running a shell command |
  | amber triangle | Waiting for input | Blocked on you: a permission prompt or an input request |
  | green tick | Finished | A turn completed; the agent is back at the prompt |
  | grey ring | Idle | Session is up but hasn't run a turn yet |
  | red cross | Exited | The agent process is gone |

  Each state has its own shape as well as its own colour, so the tab reads without relying on hue.

  Hover a tab for the state in words. Updates land within a fraction of a second — see
  [How the status is detected](#how-the-status-is-detected).

- Saves your tabs when the IDE closes and restores them on reopen — using `claude --resume` or `codex resume` for the
  session's original provider.
- **Names each tab after what the conversation is about** — from Claude's transcript or Codex's first prompt hook.
  Right-click → Rename Session to set one by hand; a title you type there is never overwritten.
  See [How tabs get their names](#how-tabs-get-their-names).
- Keeps the existing history and snapshot safety net for both providers.

## Install

**Settings → Plugins → Marketplace → search "Claude & Codex Terminal Tabs" → Install → restart.**

Or build it yourself and use **Settings → Plugins → ⚙ → Install Plugin from Disk…**:

```bash
./gradlew buildPlugin      # → build/distributions/intellij-claude-terminal-<version>.zip
```

Everything else (scripts, hooks, permissions) is set up on first start. Codex treats user hooks as untrusted until you
approve them; after the first Codex launch, open `/hooks` and trust the installed `rider-agent-tabs` hooks.

## How the status is detected

Claude uses two reconciled signals — neither is sufficient alone.

1. **Claude Code hooks** are the primary signal. The plugin registers `SessionStart`, `UserPromptSubmit`,
   `Notification`, `Stop`, `SubagentStop`, `StopFailure` and `SessionEnd` in `~/.claude/settings.json`; state-changing
   events write one line to
   `~/.claude/intellij-claude-terminal/status/{sessionId}.json` at the moment the state changes. `StopFailure` matters
   because a turn that ends on an API error — rate limit, overloaded, billing — fires *that* and never a `Stop`.
   `SubagentStop` updates only a separate count record and never replaces the main state edge.

   Current Claude Code versions include an authoritative `background_tasks` array in `Stop` and `SubagentStop` hook
   payloads. The hook stores only its count in `background-{sessionId}.json`; task ids, descriptions, prompts and shell
   commands are never persisted. This is displayed separately from the main state (`✓` plus a `bg1` badge), so a
   completed response with a Codex review or another background command still running no longer looks wholly finished.
   A subagent completion refreshes the badge immediately through `SubagentStop`. Claude Code does not currently expose
   a dedicated CLI hook for a standalone background Bash process exiting, so that case keeps the last observed count
   until the next `Stop` snapshot (for example after `/codex:status`) or `SessionEnd`.

   Only the notifications that mean someone is actually blocked are recorded (`permission_prompt`,
   `worker_permission_prompt`, `elicitation_dialog`, `agent_needs_input`). The rest are Claude telling you something —
   `agent_completed` when a background agent finishes, `auth_success` after a login — and since the file holds one edge
   per session, recording them would overwrite the state underneath.
2. **Claude Code's own session file** (`~/.claude/sessions/{pid}.json`) carries a `status` field
   (`busy` / `shell` / `idle` / `waiting`). It's read alongside the hooks and covers what hooks structurally can't:
   sessions that started before the hooks were installed, permission prompts that emit no `Notification`, and a Claude
   that was killed rather than exiting cleanly.

The newer signal wins, with two exceptions, one in each direction:

- A stale `idle` from the session file can't downgrade a hook-established `Finished`, because Claude's own field has no
  way to express "a turn just completed".
- A `busy` from the session file can't be overridden by a `Stop` when no authoritative background count is available,
  because `Stop` is the end of a *response*, not necessarily of the session's work. With a positive count, the two facts
  are shown separately as main `Finished` plus `bgN`; older CLIs retain the conservative `Working` behaviour.

A `SessionEnd` is read together with its reason: `clear` and `resume` are in-place replacements where the process, the
terminal and the tab all survive and only the session id rotates, so they hand the tab over instead of marking it dead.

Terminal ↔ session mapping reuses the `TERM_SESSION_ID` ↔ Claude `sessionId` bridge the rename feature already relies
on, so any number of tabs stay independent.

Codex uses its official user hooks in `~/.codex/hooks.json`: `SessionStart`, `UserPromptSubmit`, `PermissionRequest`,
`PreToolUse`, `PostToolUse`, `Stop`, and `SessionEnd`. They write the same small state/terminal bridge under
`~/.codex/rider-agent-tabs/status/`. `PermissionRequest` maps directly to Waiting; tool hooks keep the tab Working;
`Stop` marks the turn Finished. Hook payloads provide the stable session ID and cwd used for `codex resume`; Codex
transcripts are deliberately not parsed because their format is not a stable API.

The state rides on the tab's **icon**, not its name. Active background work adds a compact `bgN` pill beside the main
state icon. A tab whose `Content` the platform won't hand over can't show an icon and falls back to a decorated title
(`✓ · bg1 backend`); the whole prefix is stripped everywhere a title is read back, so `names.json`, restore files and
history all keep bare names.

Keeping the glyph off the title matters more than it looks: writing `userDefinedTitle` doesn't stay on the frontend — the
platform propagates it to the backend tab name too, which is what `workspace.xml` persists. A glyph there outlives the
session it describes and comes back stale on the next start.

A tab restored on IDE start doesn't flash `✕` while its resume command is still booting: for the first 20 seconds
after the plugin spawns a tab, an "exited" reading for a session that has never been seen running is treated as "not
started yet". A session that really does die still shows `✕` immediately.

## How tabs get their names

In order of how much someone meant it:

1. **A name you set** — via `/tab`, or by typing one in the tab strip. Nothing overwrites it.
2. **Claude's own name** for the session, when it summarises the conversation (`nameSource` of `auto` or `user` in
   `~/.claude/sessions/<pid>.json`). Worth noting this effectively never fires today: Claude Code 2.1.x only auto-names
   *background* sessions, so a session running in a terminal keeps a directory-derived name (`riderclaudetabs-4f`) for
   its whole life.
3. **The conversation's opening question** — read from Claude's transcript or the Codex `UserPromptSubmit` hook. It's
   what you actually see. For Claude, it is the same
   text Claude's own `--resume` picker shows, so it reads like a summary without anything having to summarise it. The
   injected `<system-reminder>` context, slash-command echoes and tool results are all skipped, as is anything that
   looks like a pasted credential — a tab name ends up in `names.json` and the restore file, which is no place for a
   token.
4. Whatever the tab was called before: `names.json`, the live title, the previous save.

Codex's raw first-prompt hook record is only a short-lived buffer. Rider derives the compact label, applies the same
credential-shaped-text filter used for Claude names, stores only that label, and deletes the raw prompt buffer.

## Remote Control

On IDE start the plugin opens one terminal tab per project running `claude remote-control`, so the sessions on this
machine can be driven from [claude.ai/code](https://claude.ai/code) and the Claude mobile app with no setup.

**This exposes control of local Claude sessions to your Claude account for as long as the IDE is open.** If that isn't
what you want, turn it off:

```json
{ "remoteControl": { "enabled": false } }
```

It never starts a second server for a directory that already has one — including one you started by hand in another
terminal. The Remote Control tab is deliberately not tracked as a chat session, so it is never saved for auto-restore.

Don't want the tab? Set `"mode": "background"` and it runs with no tab at all. That trades away what the tab is for,
though: the connection state, and the runtime `w` key that toggles worktree mode.

In background mode the plugin looks for the `claude` executable itself — `PATH`, then `~/.local/bin`, `~/.claude/local`,
Homebrew, `/usr/local/bin` — and runs it directly. Only if it can't be found does it fall back to `$SHELL -l -i -c`,
which works but drags in your whole interactive shell: a prompt framework like powerlevel10k starts a `gitstatus` daemon
that double-forks away from the process tree, leaving stray shells behind on every IDE start. Set
`remoteControl.claudePath` if your install lives somewhere unusual.

Remote Control is a control plane: sessions still run locally under the same account and model, so it does not change
token usage for the same work.

## Config

Optional. `~/.claude/intellij-claude-terminal/config.json`:

```json
{
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10,
  "remoteControl": {
    "enabled": true,
    "mode": "tab",
    "spawnMode": "same-dir",
    "extraArgs": "",
    "claudePath": ""
  }
}
```

`mode` is `tab` (default, a visible terminal tab) or `background` (no tab; output goes to
`~/.claude/intellij-claude-terminal/remote-control-<project>.log` and the server is stopped when the project closes).
`spawnMode` is `same-dir` (default), `worktree`, or `session`; `extraArgs` is appended to the `claude remote-control`
command verbatim. `claudePath` overrides where the executable is looked for in background mode. Restart the IDE after
editing.

## Compatibility

- Rider / IntelliJ 2026.1+ (build 261; verified against IntelliJ IDEA 2026.1.3, IU-261.25134.95)
- macOS and Windows are exercised; Linux should work but is less tested.
- Requires Claude Code CLI and/or Codex CLI. Claude reconciliation reads `~/.claude/sessions/{pid}.json`; Codex uses
  its supported hooks and `codex resume <session-id>` contract.

## Files it writes

All under `~/.claude/intellij-claude-terminal/`:

```
rename-tab.sh, session-start-hook.sh   # shell integration
status-hook.sh                         # Claude status hooks
status/{sessionId}.json                # last status edge per session
tabs/{sessionId}.json                  # rename requests
session-map/{TERM_SESSION_ID}          # per-tab session mapping
restore-<project>.json                 # current state (auto-restore target)
snapshots/<project>-<timestamp>.json   # rolling backups
history.json                           # closed sessions (90d default)
names.json                             # sessionId → tab name
config.json                            # user overrides
settings.json.bak                      # one-shot copy of settings.json before first edit
```

It also edits `~/.claude/settings.json` (hooks + Bash permissions). The edit is idempotent and reversible; the file is
parsed and rewritten as JSON, and left untouched if it can't be parsed. Hooks left by an older version under a
different script directory are removed rather than accumulated — a hook whose script has moved makes Claude run a
missing path on every event.

For Codex it deploys `~/.codex/rider-agent-tabs/status-hook.sh` and `status-hook.ps1`, stores state under that directory,
and edits `~/.codex/hooks.json`. That edit is likewise parsed, idempotent, reversible, and preserves unrelated hooks.

Earlier versions also appended a section to `~/.claude/CLAUDE.md` telling Claude how to name a tab. That is gone: names
come from the conversation now and need no instruction, and the section is deleted on start if an older install left
one behind. An instruction there is read on every turn of every session on the machine, so a stale one is not free.

## Running the tests

```bash
./gradlew test        # Unit + storage + status + settings/hooks patchers (537 tests, <10s)
./gradlew verifyPlugin # IntelliJ Plugin Verifier against IntelliJ IDEA 2026.1.3
./gradlew uiTest      # UI tests via Remote Robot (optional, needs a sandbox IDE)
```

For the UI tests, start a sandbox IDE in another terminal first:

```bash
./gradlew runIdeForUiTests
```

Then run `uiTest` — it connects over RMI on port 8082. If no sandbox is running, UI tests skip gracefully (so a plain `./gradlew test` stays green without one).

No tests require network access, API keys, or a real Claude/Codex install.

## Uninstall

Plugins page → Uninstall → restart. All deployed files are removed.

## License

Licensed under the [Mozilla Public License 2.0](LICENSE). Free for personal and commercial use. You can modify, distribute, and bundle it; changes to MPL-covered files must remain under MPL-2.0 and be made available, but proprietary code that *uses* the plugin is unaffected.

## Credits

A fork of [RiderClaudeTabs](https://github.com/Ragnaraven/RiderClaudeTabs) by Ragnaraven, rebuilt for IntelliJ 2026.1's
reworked terminal and extended since. Same MPL-2.0 licence as the original.

## Issues / PRs

[github.com/ckdgus08/IntellijClaudeTerminal](https://github.com/ckdgus08/IntellijClaudeTerminal)
