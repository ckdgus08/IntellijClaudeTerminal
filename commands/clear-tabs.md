<!-- Canonical source: src/main/resources/claude-integration/clear-tabs.md (deployed from JAR). Keep in sync. -->
Clear all Rider terminal tab rename cache and restore files:

```bash
rm -f ~/.claude/rider-plugin/tabs/*.json ~/.claude/rider-plugin/restore-*.json && echo "Tab cache cleared"
```
