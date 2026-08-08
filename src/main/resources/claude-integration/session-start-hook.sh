#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# session-start-hook.sh — Claude Code SessionStart hook
# ─────────────────────────────────────────────────────────────────────
# Claude invokes this hook when a new session begins, passing the session
# metadata as JSON on stdin. We extract the session_id and record the
# mapping TERM_SESSION_ID → session_id.
#
# Why the mapping exists:
#   The rename-tab.sh script knows TERM_SESSION_ID (a JetBrains per-tab
#   UUID in the environment) but not the Claude session ID. The plugin
#   watches for rename files named "{sessionId}.json". This hook bridges
#   the two: each tab writes its own unique mapping file, so the rename
#   script can look up the correct session ID without any shared state
#   or FIFO queue — eliminating the race condition that caused tabs to
#   cross-wire when multiple instances called /tab simultaneously.
#
# Files written:
#   ~/.claude/intellij-claude-terminal/session-map/{TERM_SESSION_ID} → session_id
#   ~/.claude/intellij-claude-terminal/session-queue/{timestamp}    → session_id
#     (legacy queue kept for back-compat with older rename-tab.sh versions)
# ─────────────────────────────────────────────────────────────────────

INPUT=$(cat)
SID=$(echo "$INPUT" | sed -n 's/.*"session_id":"\([^"]*\)".*/\1/p')

if [ -n "$SID" ]; then
  MAP_DIR="$HOME/.claude/intellij-claude-terminal/session-map"
  mkdir -p "$MAP_DIR"

  # Primary: per-tab mapping — race-condition free
  TERM_SID="${TERM_SESSION_ID}"
  if [ -n "$TERM_SID" ]; then
    echo "$SID" > "$MAP_DIR/$TERM_SID"
  fi

  # Legacy queue — kept so older rename-tab.sh versions keep working
  # across a plugin upgrade. New code paths should prefer the map above.
  QUEUE_DIR="$HOME/.claude/intellij-claude-terminal/session-queue"
  mkdir -p "$QUEUE_DIR"
  TIMESTAMP=$(date +%s%N 2>/dev/null || date +%s)
  echo "$SID" > "$QUEUE_DIR/$TIMESTAMP"
fi
