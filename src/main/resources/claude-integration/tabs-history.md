Browse past Claude Code sessions saved by the Rider plugin and resume one.

**Do NOT shell out to Node here.** Read the JSON yourself and present a clean choice menu — the user has explicitly asked for minimal terminal noise.

## Steps

1. Read `~/.claude/rider-plugin/history.json` with the **Read** tool. (On Windows, this resolves to `%USERPROFILE%\.claude\rider-plugin\history.json`.)
   - If the file doesn't exist or is empty, tell the user: *"No session history yet. Run `/tabs-backup` to snapshot active sessions."* and stop.
   - If the file is corrupt JSON, tell the user it's corrupt and stop.

2. Parse the array. Each entry has: `sessionId`, `tabName`, `cwd`, `closedAt` (ms epoch), optional `bypassPermissions`, optional `backedUp`. Sort newest-first by `closedAt`.

   **Filter by current project by default.** History is stored globally but should behave like Claude's own session list — scoped to the current project root.

   - If `$ARGUMENTS` contains `--all` (case-insensitive, anywhere in the args): show every project. Skip the filter, strip `--all` from `$ARGUMENTS` before further matching in step 4, and add a `Project` column to the "Show all" table so the user can tell entries apart.
   - Otherwise, resolve the current project:
     ```bash
     node ~/.claude/rider-plugin/current-project.js
     ```
     Capture `root` and `name`. Filter entries: keep an entry if its `cwd`, after normalising both sides (replace `\` with `/`, lowercase, strip trailing `/`), starts with the normalised `root` (so subdirectories of the project also match).
   - After filtering, if the result is empty AND there are entries from other projects, tell the user: *"No history for `<name>`. Run `/tabs-history --all` to see N entries from other projects."* and stop.
   - After filtering, if total history is empty across the board, fall back to the standard "No session history yet" message above.
   - When showing project-scoped results, mention in one short line at the top: *"Showing N entries from `<name>`. K hidden from other projects — run `/tabs-history --all` to see them."* (omit the second sentence if K is zero.)

3. Build a one-line label for each entry:
   - Project = basename of `cwd` (split on `/` or `\`, take the last segment).
   - Age — relative to now, picked from these buckets so very recent entries are precise. **Do not append "ago"** — the column header makes it implicit:
     - `< 60s`  → `Ns` (e.g. `42s`)
     - `< 60m`  → `Nm Ss` (e.g. `7m 12s`)
     - `< 24h`  → `Nh Mm` (e.g. `3h 04m`)
     - `≥ 24h` → `Nd Hh` (e.g. `5d 21h`)
     - Drop the trailing component when it's zero (so `5m 0s` becomes `5m`, `3h 0m` becomes `3h`).
   - Label for the menu options = `<tabName> · <project> · <age>` — keep it under ~60 chars. (The `ago` suffix is fine in the menu labels since there's no header there — but never inside the table cells.)

4. **If `$ARGUMENTS` is provided** — match it against tab name (case-insensitive contains) or 1-based number from the sorted list. If exactly one entry matches, skip to step 6. If none / multiple match, fall through to step 5.

5. **Present the menu via AskUserQuestion** (this is what the user sees instead of a script dump):
   - Show the **top 3 newest** entries as options. Each label = the one-line label from step 3.
   - If there are 4+ entries total, the 4th option is `"Show all sessions"`.
   - If there are exactly 3 or fewer, no 4th option needed (Claude Code adds Cancel automatically).
   - Question: `"Which session would you like to resume?"`
   - Header: `"Resume which?"`

   **If the user picks "Show all sessions":** print the full sorted list as a **markdown table**, one row per entry. Markdown tables are the only thing in Claude Code's renderer that gives us proper column alignment and inline styling — use them, not flat text. Bold the tab name, render the session prefix in `inline code`, and only include a `Mode` cell value when `bypassPermissions` is true (cell shows `bypass`, otherwise empty). Format:

   ```markdown
   | # | Age      | Tab                         | Project | Closed           | Mode   | Session     |
   |--:|:---------|:----------------------------|:--------|:-----------------|:-------|:------------|
   | 1 | 42s      | **Login Bug**               | MyApp   | 2026-04-25 14:02 |        | `7740bd36`  |
   | 2 | 7m 12s   | **Settings UI**             | MyApp   | 2026-04-25 13:55 |        | `f20472c8`  |
   | 3 | 3h 04m   | **Cert Renewal**            | MyApp   | 2026-04-25 11:02 | bypass | `a0000001`  |
   | 4 | 5d 21h   | **Form Validation**         | MyApp   | 2026-04-20 17:51 |        | `2ae0c009`  |
   ```

   After the table, ask the user (plain text): *"Which one? Enter a number or tab name."* Once they reply, continue with step 6.

6. **Print the resume command** for the chosen entry. **Do not execute it** — nested `claude --resume` from inside an active session always fails. Output:
   ```
   claude --resume <sessionId>[ --dangerously-skip-permissions]
   ```
   Append `--dangerously-skip-permissions` only when the entry's `bypassPermissions` is true.

   Tell the user: *"Run this in a fresh terminal, or prefix with `!` to run it in this session."*

7. Mention (one short line, only if relevant): *"After resume the plugin auto-renames the tab. If it doesn't, run `bash ~/.claude/rider-plugin/rename-tab.sh "<tabName>"`."*
