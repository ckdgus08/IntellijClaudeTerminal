#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# status-hook.sh — Claude Code status hook
# ─────────────────────────────────────────────────────────────────────
# Usage (from ~/.claude/settings.json):
#   bash ~/.claude/intellij-claude-terminal/status-hook.sh <EventName>
#
# Registered for SessionStart, UserPromptSubmit, Notification, Stop and
# SessionEnd. Claude passes the hook payload as JSON on stdin; we only
# need session_id out of it.
#
# Each invocation records one edge:
#   ~/.claude/intellij-claude-terminal/status/{session_id}.json
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

# SessionStart carries how the session began: startup | resume | clear | compact.
# It is the difference between "brand new, no turns yet" and "picked an existing
# conversation back up", which look identical from the event name alone — and
# getting that wrong makes a finished chat come back as idle after an IDE restart.
SOURCE=$(echo "$INPUT" | sed -n 's/.*"source":"\([^"]*\)".*/\1/p')

# Notification carries what kind it is, in the same slot SessionStart uses for
# `source`. Claude's own hook-payload builder:
#   case "Notification": a = n.notification_type
#   case "SessionStart": a = n.source
NOTIF_TYPE=$(echo "$INPUT" | sed -n 's/.*"notification_type":"\([^"]*\)".*/\1/p')

STATUS_DIR="$HOME/.claude/intellij-claude-terminal/status"
mkdir -p "$STATUS_DIR" 2>/dev/null || exit 0

# Milliseconds since epoch. `date +%s%N` is GNU-only; on macOS (BSD date)
# it prints a literal N, so fall back to seconds * 1000. The plugin only
# compares this against Claude's statusUpdatedAt, which is also in ms.
NOW_NS=$(date +%s%N 2>/dev/null)
case "$NOW_NS" in
  *[!0-9]*|"") TS=$(( $(date +%s) * 1000 )) ;;
  *)           TS=$(( NOW_NS / 1000000 )) ;;
esac

PAYLOAD="{\"event\":\"$EVENT\",\"source\":\"$SOURCE\",\"notificationType\":\"$NOTIF_TYPE\",\"sessionId\":\"$SID\",\"ts\":$TS,\"pid\":$PPID}"

# `idle_prompt` is Claude nudging you 60s after it went idle — "Claude is waiting
# for your input". It is not a permission prompt and nothing about the turn has
# changed, so it must not overwrite the edge already on record.
#
# This is filtered here rather than in the plugin because the file holds one edge
# per session: writing this event would destroy the `Stop` under it, and there
# would be nothing left to fall back to. Recording it and ignoring it later is
# not the same thing.
#
# Observed: a finished session showed ✓, then flipped to ⚠ exactly 60s later and
# stayed there. Claude's own status said `idle` the whole time.
SKIP_STATUS_WRITE=""
if [ "$EVENT" = "Notification" ] && [ "$NOTIF_TYPE" = "idle_prompt" ]; then
  SKIP_STATUS_WRITE=1
fi

if [ -n "$SID" ] && [ -z "$SKIP_STATUS_WRITE" ]; then
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
