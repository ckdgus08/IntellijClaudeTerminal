<!-- Canonical source: src/main/resources/claude-integration/tab-history.md (deployed from JAR). Keep in sync. -->
Browse past Claude Code sessions saved by the Rider plugin and optionally resume one.

1. Run this to display session history:

```bash
if [ -f ~/.claude/rider-plugin/history.json ]; then echo "=== Session History (newest first) ===" && echo "" && python3 -c "
import json, time, os
with open(os.path.expanduser('~/.claude/rider-plugin/history.json')) as f:
    entries = json.load(f)
entries.sort(key=lambda e: e.get('closedAt', 0), reverse=True)
for i, e in enumerate(entries, 1):
    ts = e.get('closedAt', 0) / 1000
    age = time.time() - ts
    if age < 3600: ago = f'{int(age/60)}m ago'
    elif age < 86400: ago = f'{int(age/3600)}h ago'
    else: ago = f'{int(age/86400)}d ago'
    date = time.strftime('%Y-%m-%d %H:%M', time.localtime(ts))
    cwd = e.get('cwd','').replace('\\\\\\\\','/').replace('\\\\','/')
    proj = cwd.split('/')[-1] if cwd else '?'
    print(f'  {i}. [{ago}] {e.get(\"tabName\",\"?\")}  ({proj})  — {date}')
    print(f'     session: {e.get(\"sessionId\",\"?\")[:12]}...')
" 2>/dev/null || grep -oP '"tabName":"[^"]*"' ~/.claude/rider-plugin/history.json | sed 's/"tabName":"//;s/"$//' | nl -ba; else echo "No session history found."; fi
```

2. Show the user the numbered list. Each entry shows: age, tab name, project, date, and session ID prefix.

3. If `$ARGUMENTS` specifies a number or tab name, resume that session using:
   `claude --resume <sessionId>` (add `--dangerously-skip-permissions` if `bypassPermissions` is true)
   Then rename the tab: `bash ~/.claude/rider-plugin/rename-tab.sh "<tabName>"`

4. If `$ARGUMENTS` is empty, just display the list and let the user pick.
