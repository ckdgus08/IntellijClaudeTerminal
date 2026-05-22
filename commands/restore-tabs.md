<!-- Canonical source: src/main/resources/claude-integration/restore-tabs.md (deployed from JAR). Keep in sync. -->
List saved Claude sessions from the Rider plugin restore files and help the user resume them.

1. Run this to find and display all saved sessions:

```bash
echo "=== Saved Sessions ===" && for f in ~/.claude/rider-plugin/restore-*.json; do [ -f "$f" ] || continue; project=$(basename "$f" .json | sed 's/^restore-//'); echo ""; echo "Project: $project"; grep -oP '"tabName":"[^"]*"' "$f" | sed 's/"tabName":"//;s/"$//' | nl -ba; done && echo "" && echo "=== Session Details ===" && cat ~/.claude/rider-plugin/restore-*.json 2>/dev/null || echo "No saved sessions found."
```

2. Show the user the list of saved sessions with their tab names and project.

3. If `$ARGUMENTS` specifies a tab name or number, resume that specific session. If `$ARGUMENTS` is empty or "all", offer to resume all sessions. To resume a session, run:
   - `claude --resume <sessionId>` (add `--dangerously-skip-permissions` if `bypassPermissions` is true for that session)

4. After resuming, rename the tab: `bash ~/.claude/rider-plugin/rename-tab.sh "<tabName>"`
