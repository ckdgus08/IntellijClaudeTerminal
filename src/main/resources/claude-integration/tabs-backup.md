Manually snapshot currently-active Claude sessions for **the current project** into history.json so they appear in /tabs-history.

Useful when you want to checkpoint your sessions without closing Rider or waiting for tabs to close naturally.

By default this only backs up sessions belonging to the project you're currently in. Pass `--all` to back up every project's active sessions at once.

## Steps

1. Decide scope:
   - If `$ARGUMENTS` contains `--all` (case-insensitive, anywhere in args), skip step 2 — back up every project.
   - Otherwise, resolve the current project hash:
     ```bash
     node ~/.claude/rider-plugin/current-project.js
     ```
     The output is JSON with `root`, `hash`, `name`. Capture the `hash` value.

2. Run the backup helper. **Pass the project hash so only the current project's restore file is read** (omit the flag entirely for `--all`):
   ```bash
   node ~/.claude/rider-plugin/backup-active.js --hash=<hash>
   ```

3. Show the user the one-line output. If the user passed `--all`, mention that explicitly so they know nothing was filtered.
