Clear the Rider tab plugin's restore cache for **the current project** (or all projects with `--all`).

This removes the project's `restore-<hash>.json` and any per-project snapshots so the next save starts clean. It does **not** touch `history.json` (history is meant to persist across clears so you can still resume past sessions).

## Steps

1. Decide scope:
   - If `$ARGUMENTS` contains `--all` (case-insensitive), wipe globally:
     ```bash
     rm -f ~/.claude/rider-plugin/restore-*.json ~/.claude/rider-plugin/snapshots/*.json ~/.claude/rider-plugin/tabs/*.json && echo "Tab cache cleared (all projects)"
     ```
     Stop here.

2. Otherwise, resolve the current project:
   ```bash
   node ~/.claude/rider-plugin/current-project.js
   ```
   Capture the `hash` value from the JSON output.

3. Wipe just this project's cache. Two snapshot patterns are accepted because v1.0.6 changed the delimiter from `-` to `__` (legacy single-dash files may still be on disk):
   ```bash
   rm -f ~/.claude/rider-plugin/restore-<hash>.json \
         ~/.claude/rider-plugin/snapshots/<hash>__*.json \
         ~/.claude/rider-plugin/snapshots/<hash>-*.json && \
     echo "Tab cache cleared for <name>"
   ```
   Substitute `<hash>` and `<name>` from step 2's output.

4. Confirm to the user: which project was cleared, and a reminder that `history.json` was preserved.
