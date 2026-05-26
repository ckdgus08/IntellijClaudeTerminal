Back up **every currently-alive Claude session** by ensuring it's in the restore file AND archived to `history.json`.

The source of truth is `~/.claude/sessions/<pid>.json` (which Claude writes itself), NOT the plugin-maintained restore files. So this catches sessions the plugin's poll lagged on or dropped via canonical-id collisions.

By default this only backs up sessions whose cwd is under the project you're currently in. Pass `--all` to back up every alive session across every known project.

## Steps

1. Decide scope:
   - If `$ARGUMENTS` contains `--all` (case-insensitive, anywhere in args), skip step 2 — back up all projects.
   - Otherwise, resolve the current project hash:
     ```bash
     node ~/.claude/rider-plugin/current-project.js
     ```
     The output is JSON with `root`, `hash`, `name`. Capture the `hash` value.

2. Run the backup helper. **Pass the project hash to scope to current project** (omit the flag entirely for `--all`):
   ```bash
   node ~/.claude/rider-plugin/backup-active.js --hash=<hash>
   ```

3. Show the user the one-line output. The output reports counts for both the restore-file update (`restore: N added, M kept`) AND the history.json merge (`history: X new, Y updated`).
