#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# rename-tab.sh — Renames the Rider terminal tab for the current
#                 Claude Code session.
# ─────────────────────────────────────────────────────────────────────
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name"
#
# How it works:
#   Writes a rename directive as "{sessionId}.json" into the plugin's
#   tabs directory. The plugin watches this dir and applies the name to
#   the matching terminal tab on the next poll.
#
# Strategies (in order):
#   1. TERM_SESSION_ID → session-map lookup (race-condition free).
#      The SessionStart hook pre-writes the mapping when Claude starts.
#   2. Fallback: scan Claude session files for the newest alive process
#      whose cwd matches this terminal's cwd. Unreliable with multiple
#      tabs in the same project, but useful as a safety net.
#
# Cross-platform notes:
#   Strategy 2's process-liveness check uses `tasklist` on Windows
#   (Git Bash / MSYS2) and `kill -0` on macOS / Linux.
# ─────────────────────────────────────────────────────────────────────

NAME="$1"
if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\""
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
MAP_DIR="$HOME/.claude/rider-plugin/session-map"
mkdir -p "$TABS_DIR"

# ── Detect OS for process-liveness check ───────────────────────────
# Returns 0 if a PID is currently alive, 1 otherwise.
is_pid_alive() {
  local pid="$1"
  case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*)
      tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"
      ;;
    *)
      kill -0 "$pid" 2>/dev/null
      ;;
  esac
}

# ── Strategy 1: TERM_SESSION_ID → session-map lookup ──────────────
# The session-start-hook.sh writes: session-map/{TERM_SESSION_ID} → claude-session-id
# Each tab has its own unique TERM_SESSION_ID, so there is no shared state
# and no race condition between concurrent renames.
TERM_SID="${TERM_SESSION_ID}"
if [ -n "$TERM_SID" ] && [ -f "$MAP_DIR/$TERM_SID" ]; then
  sid=$(cat "$MAP_DIR/$TERM_SID")
  if [ -n "$sid" ]; then
    echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$sid.json"
    exit 0
  fi
fi

# ── Strategy 2 (fallback): newest alive Claude session in this cwd ──
# Used when TERM_SESSION_ID isn't set (non-JetBrains terminal) or when
# the mapping doesn't exist yet (hook hasn't run, fresh session, etc.).
#
# Speed-critical for first-rename UX: we cache the live-PID set with ONE
# `tasklist` call on Windows (vs one per session file = ~150ms each),
# and process session files in mtime-descending order so the newest
# match wins on the first hit and we can break the loop early.
SESSIONS_DIR="$HOME/.claude/sessions"
CWD_WIN="$(pwd -W 2>/dev/null || pwd)"
norm_cwd=$(echo "$CWD_WIN" | sed 's|\\|/|g')

# Cache the live-PID set once. ALIVE_PIDS contains a newline-separated
# list of PIDs; `is_pid_alive_cached` greps that instead of forking.
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    ALIVE_PIDS=$(tasklist //FO CSV //NH 2>/dev/null | awk -F',' '{gsub(/"/,"",$2); print $2}')
    ;;
  *)
    # ps -A on macOS/Linux is a single fork — much cheaper than per-PID kill -0
    ALIVE_PIDS=$(ps -A -o pid= 2>/dev/null | tr -d ' ')
    ;;
esac

is_pid_alive_cached() {
  local pid="$1"
  printf '%s\n' "$ALIVE_PIDS" | grep -q "^${pid}$"
}

# Sort session files by mtime descending so the newest one is checked first.
# This lets us break out of the loop on the first match (almost always the
# session we want), instead of scanning every file. `ls -t` works on all
# our target shells.
best_sid=""
shopt -s nullglob 2>/dev/null
mapfile -t SESSION_FILES < <(ls -t "$SESSIONS_DIR/"*.json 2>/dev/null)
shopt -u nullglob 2>/dev/null

for sf in "${SESSION_FILES[@]}"; do
  [ -f "$sf" ] || continue
  pid=$(basename "$sf" .json)
  is_pid_alive_cached "$pid" || continue

  file_cwd=$(grep -o '"cwd":"[^"]*"' "$sf" | head -1 | sed 's/"cwd":"//;s/"$//')
  norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')
  [ "$norm_cwd" != "$norm_file" ] && continue

  sid=$(grep -o '"sessionId":"[^"]*"' "$sf" | head -1 | sed 's/"sessionId":"//;s/"$//')
  [ -z "$sid" ] && continue

  best_sid="$sid"
  break  # newest match wins (files were sorted by mtime desc)
done

if [ -n "$best_sid" ]; then
  echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$best_sid.json"
  exit 0
fi

echo "No Claude session found" >&2
exit 1
