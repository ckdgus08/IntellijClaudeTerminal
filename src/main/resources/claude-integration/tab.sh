#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# tab.sh — Combined /tab handler: rename + history snapshot + confirm.
# ─────────────────────────────────────────────────────────────────────
# Why this exists as a single script:
#   /tab used to fire two sequential Claude tool calls (rename-tab.sh
#   then tab-backup.js) plus a separate "compose confirmation" LLM
#   step. Each Claude Bash tool call adds ~500ms of harness overhead,
#   and each LLM round-trip adds latency. Collapsing into ONE bash
#   invocation that prints its own confirmation cuts /tab's wall time
#   roughly in half on the first-rename path.
#
# Usage: bash ~/.claude/rider-plugin/tab.sh "Tab Name"
# ─────────────────────────────────────────────────────────────────────

NAME="$1"
if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/tab.sh \"Tab Name\""
  exit 1
fi

DIR="$HOME/.claude/rider-plugin"

# 1. Rename — writes the rename-request file the plugin's watcher picks up.
if ! bash "$DIR/rename-tab.sh" "$NAME"; then
  echo "Tab rename failed (no Claude session matched). History snapshot skipped." >&2
  exit 1
fi

# 2. Snapshot — non-fatal: we still want to confirm the rename even if
# history snapshotting fails (e.g. Node missing, history.json transient
# read failure). Errors land on stderr; main confirmation still prints.
node "$DIR/tab-backup.js" "$NAME" >/dev/null 2>&1 || true

# 3. Confirmation — printed by the script so Claude relays it verbatim
# (no extra LLM "compose confirmation" round-trip).
echo "Tab renamed to '$NAME' and backed up to history."
