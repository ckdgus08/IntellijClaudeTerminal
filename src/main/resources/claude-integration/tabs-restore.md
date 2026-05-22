List saved Claude sessions from the Rider plugin's restore files and print a resume command for one of them.

**Do NOT shell out to Node here** (except the one-shot project-resolver helper below). Read the JSON yourself and present a clean choice menu — the user has explicitly asked for minimal terminal noise.

By default this is scoped to the **current project**. Pass `--all` to see saved sessions across every project the plugin knows about.

## Steps

1. Decide scope:
   - If `$ARGUMENTS` contains `--all` (case-insensitive, anywhere in the args), skip step 1a; load every restore file. Strip `--all` from `$ARGUMENTS` before doing further matching in step 4.
   - Otherwise, resolve the current project:
     ```bash
     node ~/.claude/rider-plugin/current-project.js
     ```
     The output is JSON with `root`, `hash`, `name`. Capture them.

   1a. **Project-scoped read** — read only `~/.claude/rider-plugin/restore-<hash>.json` with the **Read** tool. If the file doesn't exist or is empty, tell the user *"No saved sessions for `<name>`. Run `/tabs-restore --all` to see other projects' saved sessions."* and stop.

   1b. **--all read** — use the **Glob** tool to find every `~/.claude/rider-plugin/restore-*.json` (Windows: `%USERPROFILE%\.claude\rider-plugin\restore-*.json`). If no files match, tell the user *"No saved sessions. Open Rider with the plugin active to populate this."* and stop.

2. Read the restore file(s) with the **Read** tool. Each file is an array of `{ sessionId, cwd, tabName, bypassPermissions? }`. Flatten them into one combined list. Drop entries with no `sessionId`.

3. Build a one-line label for each entry:
   - Project = basename of `cwd` (split on `/` or `\`, take the last segment).
   - Bypass = ` [bypass]` if `bypassPermissions` is true, else empty.
   - Label = `<tabName> · <project><bypass>` — keep under ~60 chars.

4. **If `$ARGUMENTS` is provided** — match it against tab name (case-insensitive contains) or 1-based number from the flattened list. If exactly one entry matches, skip to step 6. Otherwise fall through to step 5.

5. **Present the menu via AskUserQuestion**:
   - Show the **first 3 entries** as options (labels from step 3).
   - If there are 4+ entries, the 4th option is `"Show all saved sessions"`.
   - Question: `"Which session would you like to resume?"`
   - Header: `"Resume which?"`

   **If the user picks "Show all saved sessions":** print the full list as a **markdown table** grouped by project, one row per entry. Markdown tables are the only thing in Claude Code's renderer that gives us proper column alignment and inline styling — use them, not flat text. Bold the tab name, render the session prefix in `inline code`, and only include a `Mode` cell value when `bypassPermissions` is true (cell shows `bypass`, otherwise empty). One table per project, project name as a `## Heading` above each. Format:

   ```markdown
   ## MyApp

   | # | Tab                         | Mode   | Session     |
   |--:|:----------------------------|:-------|:------------|
   | 1 | **Login Bug**               |        | `7740bd36`  |
   | 2 | **Cert Renewal**            | bypass | `a0000001`  |
   ```

   After the tables, ask the user (plain text): *"Which one? Enter a number or tab name."* Once they reply, continue with step 6.

6. **Print the resume command** for the chosen entry. **Do not execute it** — nested `claude --resume` always fails. Output:
   ```
   claude --resume <sessionId>[ --dangerously-skip-permissions]
   ```
   Append `--dangerously-skip-permissions` only when the entry's `bypassPermissions` is true.

   Tell the user: *"Run this in a fresh terminal, or prefix with `!` to run it in this session."*

7. Mention (one short line, only if relevant): *"After resume the plugin auto-renames the tab. If it doesn't, run `bash ~/.claude/rider-plugin/rename-tab.sh "<tabName>"`."*
