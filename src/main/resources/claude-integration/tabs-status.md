Show a status report of currently-active Claude Code sessions tracked by the Rider plugin.

**Do NOT shell out to Node here** (except the one-shot project-resolver helper below). Read the JSON yourself — the user has explicitly asked for minimal terminal noise.

By default this is scoped to the **current project**. Pass `--all` to see active sessions across every project.

## Steps

1. Decide scope:
   - If `$ARGUMENTS` contains `--all` (case-insensitive, anywhere in the args), load every project's restore file (the original "all projects" behavior).
   - Otherwise, resolve the current project. **Fast path (1.0.17+):** read `~/.claude/rider-plugin/project-index.json` with the **Read** tool. It's a JSON object `{"projects":[{"hash":"...","basePath":"...","name":"..."}, ...]}` maintained by the plugin on every project open/close. Find the entry whose `basePath` is an ancestor of (or equals) your current working directory (`pwd` from shell), pick its `hash` and `name`. This is ~5ms vs ~500-800ms for the Node fallback.

     **Slow fallback** only when the index is missing OR no ancestor matches (e.g. cwd is outside any tracked project):
     ```bash
     node ~/.claude/rider-plugin/current-project.js
     ```
     Capture `hash` and `name` from the JSON output. Read only `~/.claude/rider-plugin/restore-<hash>.json` with the **Read** tool.

2. **Project-scoped read** — if the file doesn't exist or is empty, tell the user *"No active Claude sessions tracked for `<name>`. Run `/tabs-status --all` to see other projects."* and stop.

   **--all read** — use the **Glob** tool to find every `~/.claude/rider-plugin/restore-*.json`. If none match, tell the user *"No active Claude sessions tracked."* and stop. Each file is an array of `{ sessionId, cwd, tabName, bypassPermissions? }`. Group entries by project (basename of `cwd`).

3. Print a **markdown table per project**, one row per session. Markdown tables are the only thing in Claude Code's renderer that gives us proper column alignment and inline styling — use them, not flat text. Bold the tab name, render the session prefix in `inline code`, and only include a `Mode` cell value when `bypassPermissions` is true (cell shows `bypass`, otherwise empty). Project name as a `## Heading` above each table:

   ```markdown
   ## MyApp (3 tabs)

   | # | Tab                         | Mode   | Session     |
   |--:|:----------------------------|:-------|:------------|
   | 1 | **Login Bug**               |        | `7740bd36`  |
   | 2 | **Cert Renewal**            | bypass | `a0000001`  |
   | 3 | **Settings UI**             |        | `f20472c8`  |
   ```

   When project-scoped, you'll only emit one table — that's fine.

4. After the per-project tables, **also read** `~/.claude/rider-plugin/last-restore.json` (Windows: `%USERPROFILE%\.claude\rider-plugin\last-restore.json`) **with the Read tool** if it exists. The file is a single JSON object: `{ "restoredAt": <ms>, "projectName": "...", "count": N, "sessions": [{"tabName":"...","sessionId":"..."}, ...] }`. If present, print one extra line right before the totals line:

   `Sessions restored on this Rider start: <count> ({projectName}).`

   When project-scoped, only show this line if `projectName` matches the current project name (case-insensitive). If the file is missing, malformed, or for a different project, skip the line silently.

5. Last line (plain text):
   - **Project-scoped**: `Total: <N> active session(s) for <name>.`
   - **--all**: `Total: <N> active sessions across <P> projects.`
