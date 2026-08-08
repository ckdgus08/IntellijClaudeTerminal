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
- Names terminal tabs via a slash command or auto-naming so you can tell sessions apart.
- Keeps a history of past sessions you can resume later.

## Install

**Settings → Plugins → Marketplace → search "Claude Terminal Tab Persistence" → Install → restart.**

Or build it yourself and use **Settings → Plugins → ⚙ → Install Plugin from Disk…**:

```bash
./gradlew buildPlugin      # → build/distributions/rider-claude-tabs-<version>.zip
```

Everything else (scripts, hooks, commands, CLAUDE.md section, permissions) is set up on first start.

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

## Slash commands

| Command | What it does |
|---|---|
| `/tab [name]` | Renames this tab and snapshots it to history. If no name is given, Claude picks one (3–5 words) based on the current conversation. |
| `/tabs-status` | Shows every active Claude session grouped by project, with session IDs. |
| `/tabs-backup` | Writes currently-active sessions into history so you can resume them later, without waiting for the tab to close. |
| `/tabs-history` | Numbered list of past closed/backed-up sessions (newest first). Pick one to resume. |
| `/tabs-restore` | Shows what's in the auto-restore file (the set of tabs that will come back next Rider start). |
| `/tabs-clear` | Clears the rename cache and per-project restore files. Doesn't touch history. |

## Config

Optional. `~/.claude/rider-plugin/config.json`:

```json
{
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10
}
```

Restart Rider after editing.

## Compatibility

- Rider / IntelliJ 2026.1+ (build 261; verified against IntelliJ IDEA 2026.1.3, IU-261.25134.95)
- macOS and Windows are exercised; Linux should work but is less tested.
- Requires Claude Code CLI (provides the `node` runtime the plugin's scripts use). The status indicator needs a CLI
  recent enough to write `~/.claude/sessions/{pid}.json` — 2.1.x does; on older builds the hooks alone still drive it.

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
./gradlew test        # Unit + storage + status + slash-command scripts (323 tests, <10s)
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
