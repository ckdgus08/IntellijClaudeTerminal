# Claude Terminal Tab Persistence

A JetBrains Rider / IntelliJ plugin that persists [Claude Code](https://claude.com/claude-code) terminal sessions across IDE restarts — your tabs come back with their names and auto-resume their conversations.

## What it does

- Saves your tabs when Rider closes and restores them on reopen — each restored tab runs `claude --resume` for its session.
- Names terminal tabs via a slash command or auto-naming so you can tell sessions apart.
- Keeps a history of past sessions you can resume later.

## Install

**Settings → Plugins → Marketplace → search "Claude Terminal Tab Persistence" → Install → restart.**

Everything else (scripts, commands, CLAUDE.md section, permissions) is set up on first start.

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

- Rider / IntelliJ 2026.1+ (build 261; verified against IntelliJ IDEA 2026.1.3)
- Windows primary. macOS / Linux should work but less tested.
- Requires Claude Code CLI (provides the `node` runtime the plugin's scripts use).

## Files it writes

All under `~/.claude/rider-plugin/`:

```
rename-tab.sh, session-start-hook.sh   # shell integration
tabs/{sessionId}.json                  # rename requests
session-map/{TERM_SESSION_ID}          # per-tab session mapping
restore-<project>.json                 # current state (auto-restore target)
snapshots/<project>-<timestamp>.json   # rolling backups
history.json                           # closed sessions (90d default)
config.json                            # user overrides
```

## Running the tests

```bash
./gradlew test        # Unit + storage + slash-command scripts (~60 tests, <10s)
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
