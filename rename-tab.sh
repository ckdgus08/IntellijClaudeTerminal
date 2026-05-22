#!/bin/bash
# Renames the Rider terminal tab for the current Claude Code session.
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name"
#
# Strategy 1: TERM_SESSION_ID → session-map lookup (race-condition free)
# Strategy 2: Fallback — newest alive Claude session in CWD

NAME="$1"
if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\""
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
MAP_DIR="$HOME/.claude/rider-plugin/session-map"
mkdir -p "$TABS_DIR"

# ── Strategy 1: Look up Claude session ID via TERM_SESSION_ID mapping ──
# The session-start-hook.sh writes: session-map/{TERM_SESSION_ID} → claude-session-id
# Each tab has its own unique TERM_SESSION_ID, so no race condition.
TERM_SID="${TERM_SESSION_ID}"
if [ -n "$TERM_SID" ] && [ -f "$MAP_DIR/$TERM_SID" ]; then
  sid=$(cat "$MAP_DIR/$TERM_SID")
  if [ -n "$sid" ]; then
    echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$sid.json"
    exit 0
  fi
fi

# ── Strategy 2 (fallback): Find newest alive Claude session in CWD ──
SESSIONS_DIR="$HOME/.claude/sessions"
CWD_WIN="$(pwd -W 2>/dev/null || pwd)"
norm_cwd=$(echo "$CWD_WIN" | sed 's|\\|/|g')
best_sid=""
best_time=0

for sf in "$SESSIONS_DIR/"*.json; do
  [ -f "$sf" ] || continue
  pid=$(basename "$sf" .json)
  if ! tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"; then continue; fi
  file_cwd=$(grep -o '"cwd":"[^"]*"' "$sf" | head -1 | sed 's/"cwd":"//;s/"$//')
  norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')
  [ "$norm_cwd" != "$norm_file" ] && continue
  sid=$(grep -o '"sessionId":"[^"]*"' "$sf" | head -1 | sed 's/"sessionId":"//;s/"$//')
  [ -z "$sid" ] && continue
  started=$(grep -o '"startedAt":[0-9]*' "$sf" | head -1 | sed 's/"startedAt"://')
  if [ -n "$started" ] && [ "$started" -gt "$best_time" ] 2>/dev/null; then
    best_time="$started"; best_sid="$sid"
  fi
done

if [ -n "$best_sid" ]; then
  echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$best_sid.json"
  exit 0
fi

echo "No Claude session found" >&2
exit 1
