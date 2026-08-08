#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# status-hook.sh — Claude Code status hook
# ─────────────────────────────────────────────────────────────────────
# Usage (from ~/.claude/settings.json):
#   bash ~/.claude/rider-plugin/status-hook.sh <EventName>
#
# Registered for SessionStart, UserPromptSubmit, Notification, Stop and
# SessionEnd. Claude passes the hook payload as JSON on stdin; we only
# need session_id out of it.
#
# Each invocation records one edge:
#   ~/.claude/rider-plugin/status/{session_id}.json
#     {"event":"Stop","sessionId":"...","ts":1786179029939,"pid":12345}
#
# The plugin maps that session id to a terminal tab and prefixes the tab
# title with a status glyph. Events are recorded rather than states so the
# plugin owns the event → glyph mapping and can change it without the
# script having to be redeployed in lockstep.
#
# Why hooks and not just polling: Claude maintains its own status field in
# ~/.claude/sessions/{pid}.json (busy|shell|idle|waiting), but that field
# cannot distinguish "a turn just finished" from "sitting idle since start",
# and it lags a clean SessionEnd. The plugin reconciles both signals — see
# StatusResolver.
#
# This script is on the hot path of every prompt submission, so it stays
# free of subprocesses beyond the one `date` call and never blocks: a
# failure to write must not stall Claude.
# ─────────────────────────────────────────────────────────────────────

EVENT="$1"
[ -z "$EVENT" ] && exit 0

INPUT=$(cat)
SID=$(echo "$INPUT" | sed -n 's/.*"session_id":"\([^"]*\)".*/\1/p')

STATUS_DIR="$HOME/.claude/rider-plugin/status"
mkdir -p "$STATUS_DIR" 2>/dev/null || exit 0

# Milliseconds since epoch. `date +%s%N` is GNU-only; on macOS (BSD date)
# it prints a literal N, so fall back to seconds * 1000. The plugin only
# compares this against Claude's statusUpdatedAt, which is also in ms.
NOW_NS=$(date +%s%N 2>/dev/null)
case "$NOW_NS" in
  *[!0-9]*|"") TS=$(( $(date +%s) * 1000 )) ;;
  *)           TS=$(( NOW_NS / 1000000 )) ;;
esac

PAYLOAD="{\"event\":\"$EVENT\",\"sessionId\":\"$SID\",\"ts\":$TS,\"pid\":$PPID}"

if [ -n "$SID" ]; then
  echo "$PAYLOAD" > "$STATUS_DIR/$SID.json"
fi

# Secondary key: the terminal this session runs in. Written so the very
# first SessionStart is attributable to a tab even before the plugin has
# resolved the tab's Claude session id. Same TERM_SESSION_ID that
# session-start-hook.sh uses for the rename mapping.
if [ -n "$TERM_SESSION_ID" ]; then
  echo "$PAYLOAD" > "$STATUS_DIR/termsess-$TERM_SESSION_ID.json"
fi

exit 0
