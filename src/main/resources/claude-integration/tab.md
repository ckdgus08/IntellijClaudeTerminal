Rename your Rider terminal tab AND snapshot this tab to /tabs-history so you can resume it later.

If no name was provided, pick a concise name (3-5 words) based on the current conversation topic.

**Speed-critical:** This is one bash command, not multiple sequential tool calls. Each Claude tool round-trip costs ~500ms; chaining the rename + snapshot + confirmation into a single script invocation cuts /tab's first-run wall time roughly in half.

1. Run the combined handler. The script prints its own confirmation on success so no extra LLM step is needed:
   ```bash
   bash ~/.claude/rider-plugin/tab.sh "$ARGUMENTS"
   ```

2. Show the script's output verbatim. The success line is `Tab renamed to '<name>' and backed up to history.` — relay that. If the script printed an error to stderr, show that instead.
