# Claude Terminal Tab Persistence

A JetBrains Rider / IntelliJ plugin for running several [Claude Code](https://claude.com/claude-code) sessions in the
IDE's own terminal. It shows what each session is doing on its tab, and brings the tabs back after a restart.

It adds no UI of its own: no tool window, no chat panel, no change to how you start `claude`. Everything happens on the
terminal tab strip you already use.

## What it does

- **Shows each session's state on its tab**, so you can tell at a glance which one needs you:

  ```
  ● backend    ⚠ frontend    ✓ infra    ○ test
  ```

  | | State | Meaning |
  |---|---|---|
  | `●` | Working | Claude is running a turn — thinking, calling tools, running a shell command |
  | `⚠` | Waiting for input | Blocked on you: a permission prompt or an input request |
  | `✓` | Finished | A turn completed; Claude is back at the prompt |
  | `○` | Idle | Session is up but hasn't run a turn yet |
  | `✕` | Exited | The Claude process is gone |

  Hover a tab for the state in words. Updates land within a fraction of a second — see
  [How the status is detected](#how-the-status-is-detected).

- Saves your tabs when the IDE closes and restores them on reopen — each restored tab runs `claude --resume` for its
  session.
- **Names tabs from Claude's own summary of the conversation** — read straight out of
  `~/.claude/sessions/<pid>.json`, so it costs the conversation nothing. Right-click → Rename Session to set one by
  hand; a title you type there is never overwritten.
- Keeps a history of past sessions, so a closed one can still be resumed with `claude --resume`.

## Install

**Settings → Plugins → Marketplace → search "Claude Terminal Tab Persistence" → Install → restart.**

Or build it yourself and use **Settings → Plugins → ⚙ → Install Plugin from Disk…**:

```bash
./gradlew buildPlugin      # → build/distributions/rider-claude-tabs-<version>.zip
```

Everything else (scripts, hooks, CLAUDE.md section, permissions) is set up on first start.

## How the status is detected

Two signals, reconciled — neither is sufficient alone.

1. **Claude Code hooks** are the primary signal. The plugin registers `SessionStart`, `UserPromptSubmit`,
   `Notification`, `Stop` and `SessionEnd` in `~/.claude/settings.json`; each writes one line to
   `~/.claude/rider-plugin/status/{sessionId}.json` at the moment the state changes. `SubagentStop` is deliberately not
   subscribed — a subagent finishing is not the turn finishing.
2. **Claude Code's own session file** (`~/.claude/sessions/{pid}.json`) carries a `status` field
   (`busy` / `shell` / `idle` / `waiting`). It's read alongside the hooks and covers what hooks structurally can't:
   sessions that started before the hooks were installed, permission prompts that emit no `Notification`, and a Claude
   that was killed rather than exiting cleanly.

The newer signal wins, with one exception: a stale `idle` from the session file can't downgrade a hook-established
`Finished`, because Claude's own field has no way to express "a turn just completed".

Terminal ↔ session mapping reuses the `TERM_SESSION_ID` ↔ Claude `sessionId` bridge the rename feature already relies
on, so any number of tabs stay independent.

The status glyph is written to the live tab title only, and stripped everywhere a title is read back — `names.json`, the
restore files and history all keep bare names, so a restored tab never comes back called `● backend`.

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

Remote Control is a control plane: sessions still run locally under the same account and model, so it does not change
token usage for the same work.

## Config

Optional. `~/.claude/rider-plugin/config.json`:

```json
{
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10,
  "remoteControl": {
    "enabled": true,
    "mode": "tab",
    "spawnMode": "same-dir",
    "extraArgs": ""
  }
}
```

`mode` is `tab` (default, a visible terminal tab) or `background` (no tab; output goes to
`~/.claude/rider-plugin/remote-control-<project>.log` and the server is stopped when the project closes).
`spawnMode` is `same-dir` (default), `worktree`, or `session`; `extraArgs` is appended to the `claude remote-control`
command verbatim. Restart the IDE after editing.

## Compatibility

- Rider / IntelliJ 2026.1+ (build 261; verified against IntelliJ IDEA 2026.1.3, IU-261.25134.95)
- macOS and Windows are exercised; Linux should work but is less tested.
- Requires Claude Code CLI. The status indicator and the automatic tab naming both read
  `~/.claude/sessions/{pid}.json`, which 2.1.x writes; on older builds the hooks alone still drive the status.

## Files it writes

All under `~/.claude/rider-plugin/`:

```
rename-tab.sh, session-start-hook.sh   # shell integration
status-hook.sh                         # status hook (all 5 events)
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

It also edits `~/.claude/settings.json` (hooks + Bash permissions) and appends a marked section to
`~/.claude/CLAUDE.md`. Both edits are idempotent and reversible; the settings file is parsed and rewritten as JSON, and
is left untouched if it can't be parsed.

## Running the tests

```bash
./gradlew test        # Unit + storage + status + settings patcher (394 tests, <10s)
./gradlew verifyPlugin # IntelliJ Plugin Verifier against IntelliJ IDEA 2026.1.3
./gradlew uiTest      # UI tests via Remote Robot (optional, needs a sandbox IDE)
```

For the UI tests, start a sandbox IDE in another terminal first:

```bash
./gradlew runIdeForUiTests
```

Then run `uiTest` — it connects over RMI on port 8082. If no sandbox is running, UI tests skip gracefully (so a plain `./gradlew test` stays green without one).

No tests require network access, Anthropic API keys, or a real Claude install.

## Uninstall

Plugins page → Uninstall → restart. All deployed files are removed.

## License

Licensed under the [Mozilla Public License 2.0](LICENSE). Free for personal and commercial use. You can modify, distribute, and bundle it; changes to MPL-covered files must remain under MPL-2.0 and be made available, but proprietary code that *uses* the plugin is unaffected.

## Issues / PRs

[github.com/Ragnaraven/RiderClaudeTabs](https://github.com/Ragnaraven/RiderClaudeTabs)
