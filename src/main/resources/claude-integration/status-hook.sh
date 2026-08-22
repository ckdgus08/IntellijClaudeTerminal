#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# status-hook.sh — Claude Code status hook (tab state indicator)
# ─────────────────────────────────────────────────────────────────────
# Usage: bash ~/.claude/intellij-claude-terminal/status-hook.sh <EventName>
#
# Registered by ClaudeSettingsPatcher for seven events, with the event
# name passed as the single argument so one script covers all of them:
#
#   SessionStart · UserPromptSubmit · Notification · Stop · SubagentStop
#   · StopFailure · SessionEnd
#
# Claude passes the event payload as JSON on stdin. We flatten it into
# one small record and drop it in the plugin's status directory, where
# ClaudeStatusStore picks it up on its next poll:
#
#   ~/.claude/intellij-claude-terminal/status/{sessionId}.json
#     The status edge itself. One file per session, always overwritten,
#     so it holds the LAST edge — see the idle-nudge note below for why
#     that matters. StatusResolver.fromHookEvent maps the event to the
#     glyph on the tab (● working, ⚠ waiting, ✓ finished, ○ idle, ✕ exited).
#
#   ~/.claude/intellij-claude-terminal/status/{TERM_SESSION_ID}.json
#     (written with a "termsess" name prefix) The PID-free bridge from a
#     JetBrains terminal tab to the session running inside it. On the
#     2026.1 terminal this is the only mapping that works, so it is
#     refreshed by EVERY event, including ones whose status we discard.
#
#   ~/.claude/intellij-claude-terminal/status/background-{sessionId}.json
#     Count only — never task ids, descriptions, prompts, or shell commands. `Stop`
#     and `SubagentStop` expose Claude's authoritative `background_tasks` array;
#     keeping it separate prevents a subagent event from overwriting the main edge.
#
# Record shape — flat, one line, no nesting, because the plugin reads it
# with regexes rather than a JSON parser:
#
#   {"event":"Stop","source":"","notificationType":"","reason":"",
#    "sessionId":"…","ts":1786214613166,"pid":77877}
#
# The hook has a 5s timeout and runs on every prompt and every turn end,
# so everything here is shell built-ins plus at most one small fork.
# ─────────────────────────────────────────────────────────────────────

EVENT="$1"
INPUT=$(cat 2>/dev/null)

# ── JSON field extraction ──────────────────────────────────────────
# First occurrence of a top-level string field. `grep -o` rather than a
# greedy `sed` because `.*"key":"…"` matches the LAST occurrence, and
# the payloads nest (a transcript excerpt can carry its own "source").
# Tolerates whitespace around the colon: Claude has emitted both compact
# and pretty-printed hook input across versions.
json_str() {
  printf '%s' "$INPUT" | tr -d '\n' \
    | grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
    | head -1 \
    | sed 's/^[^:]*:[[:space:]]*"//; s/"$//'
}

# Count top-level objects in the top-level `background_tasks` array without
# persisting its contents. The scanner honours JSON strings and escapes, so a
# command or assistant message containing braces cannot inflate the count.
# Prints -1 when the field is absent (older Claude Code versions).
json_background_count() {
  printf '%s' "$INPUT" | awk '
    { data = data $0 "\n" }
    END {
      key = "background_tasks"
      in_string = 0; escaped = 0; object_depth = 0; array_start = 0
      token = ""; token_depth = 0

      for (i = 1; i <= length(data); i++) {
        ch = substr(data, i, 1)
        if (in_string) {
          if (escaped) { escaped = 0; token = token ch; continue }
          if (ch == "\\") { escaped = 1; continue }
          if (ch == "\"") {
            in_string = 0
            if (token == key && token_depth == 1) {
              j = i + 1
              while (j <= length(data) && substr(data, j, 1) ~ /[ \t\r\n]/) j++
              if (substr(data, j, 1) != ":") continue
              j++
              while (j <= length(data) && substr(data, j, 1) ~ /[ \t\r\n]/) j++
              if (substr(data, j, 1) == "[") { array_start = j; break }
            }
            continue
          }
          token = token ch
          continue
        }
        if (ch == "\"") { in_string = 1; token = ""; token_depth = object_depth; continue }
        if (ch == "{") object_depth++
        else if (ch == "}") object_depth--
      }

      if (array_start == 0) { print -1; exit }
      in_string = 0; escaped = 0; depth = 0; count = 0
      for (i = array_start; i <= length(data); i++) {
        ch = substr(data, i, 1)
        if (in_string) {
          if (escaped) escaped = 0
          else if (ch == "\\") escaped = 1
          else if (ch == "\"") in_string = 0
          continue
        }
        if (ch == "\"") { in_string = 1; continue }
        if (ch == "[") { depth++; continue }
        if (ch == "]") {
          depth--
          if (depth == 0) { print count; exit }
          continue
        }
        if (ch == "{" && depth == 1) count++
      }
      print -1
    }
  '
}

SID=$(json_str session_id)
[ -n "$SID" ] || SID=$(json_str sessionId)

# The argument is authoritative; the payload is the fallback for a hook
# entry that lost its argument (hand-edited settings.json, older patcher).
[ -n "$EVENT" ] || EVENT=$(json_str hook_event_name)

# SessionStart only: startup | resume | clear | compact. Decides whether a
# restart means "nothing has run yet" (○) or "picked an existing chat back
# up" (✓) — see StatusResolver.fromHookEvent.
SOURCE=$(json_str source)

# Notification only. Claude has used both spellings across versions.
NOTIF_TYPE=$(json_str notification_type)
[ -n "$NOTIF_TYPE" ] || NOTIF_TYPE=$(json_str notificationType)

# SessionEnd only: clear | resume | logout | prompt_input_exit |
# bypass_permissions_disabled | other. The first two are in-place replacements
# rather than endings — see StatusResolver.fromHookEvent.
REASON=$(json_str reason)

# ── Validation ─────────────────────────────────────────────────────
# These become filenames and land inside a JSON string unquoted, so
# anything that a path parser or the plugin's regexes treat specially is
# dropped rather than escaped. Mirrors ClaudeTabsHelpers.isSafeSessionId.
is_safe_id() {
  case "$1" in
    ''|.|..) return 1 ;;
    *[!A-Za-z0-9._-]*) return 1 ;;
  esac
  [ "${#1}" -le 128 ]
}

is_safe_id "$SID" || SID=""
# Event names are alphabetic; a value that isn't would corrupt the record.
case "$EVENT" in ''|*[!A-Za-z]*) EVENT="" ;; esac
case "$SOURCE" in *[!A-Za-z]*) SOURCE="" ;; esac
case "$NOTIF_TYPE" in *[!A-Za-z_]*) NOTIF_TYPE="" ;; esac
case "$REASON" in *[!A-Za-z_]*) REASON="" ;; esac

# Nothing identifiable — no record to write, and no bridge to refresh.
[ -n "$EVENT" ] && [ -n "$SID" ] || exit 0

# ── Events that must not overwrite the edge underneath ──────────────
# The status file holds ONE edge per session, so writing an event that
# says nothing about the turn destroys the one that did.
#
#   Notification — only four of Claude's dozen notification types mean a
#     person is blocked, and they are listed positively below. The rest are
#     Claude telling you something, and every one of them used to land as ⚠
#     on top of a live edge:
#
#       agent_completed        "<label> finished" — a BACKGROUND AGENT ended.
#                              A fan-out of five produced five false ⚠.
#       elicitation_complete   the end of a wait, recorded as the start of one,
#       elicitation_response   so the tab stayed ⚠ after you had answered.
#       auth_success           "Claude Code login successful".
#       idle_prompt            the 60s "waiting for your input" nudge, fired
#                              after a session goes idle. Nothing changed.
#       computer_use_enter/exit, push_notification — informational.
#
#     An unknown type is skipped too. A blocking one we haven't listed is
#     still caught by Claude's own session file (which reports `waiting` for
#     permission dialogs, elicitation and sandbox prompts), so a miss
#     self-corrects — while a false ⚠ destroys what it was written over.
#
#   SessionStart / compact — fires mid-turn when the context is compacted.
#     StatusResolver reads it as "establishes nothing, leave the current
#     state alone", which only holds if the previous edge survives it.
#
# All of them are still recognised by the resolver, for files written before
# this filter existed.
SKIP_STATUS_WRITE=0
if [ "$EVENT" = "Notification" ]; then
  case "$NOTIF_TYPE" in
    # Blank: an older Claude that sends no type. Kept, so its meaning
    # doesn't change under a CLI that predates the field.
    ""|permission_prompt|worker_permission_prompt|elicitation_dialog|agent_needs_input) ;;
    *) SKIP_STATUS_WRITE=1 ;;
  esac
fi
if [ "$EVENT" = "SessionStart" ] && [ "$SOURCE" = "compact" ]; then
  SKIP_STATUS_WRITE=1
fi
# A subagent finishing updates only the background count. It must never replace
# the main turn edge with an event that StatusResolver deliberately ignores.
if [ "$EVENT" = "SubagentStop" ]; then
  SKIP_STATUS_WRITE=1
fi

# ── Timestamp ───────────────────────────────────────────────────────
# Milliseconds, not seconds. StatusResolver rule 2 is "the newer signal
# wins", comparing this against `statusUpdatedAt` in Claude's own session
# file — which is in ms. Truncating to whole seconds rounds a `Stop`
# backwards past the `idle` that Claude wrote a fraction of a second
# earlier, and the tab shows ○ instead of ✓.
now_ms() {
  local ms
  ms=$(date +%s%3N 2>/dev/null)
  case "$ms" in
    ''|*[!0-9]*) ;;                       # BSD date: emits a literal "N"
    *) printf '%s' "$ms"; return ;;
  esac
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf("%d", time()*1000)' 2>/dev/null && return
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time;print(int(time.time()*1000))' 2>/dev/null && return
  fi
  printf '%s000' "$(date +%s)"            # last resort: second precision
}
TS=$(now_ms)
case "$TS" in ''|*[!0-9]*) TS=0 ;; esac

# ── The Claude PID ──────────────────────────────────────────────────
# Joins this record to `~/.claude/sessions/{pid}.json`. It is what lets
# ClaudeStatusStore.supersededSessions tell `/clear` (same process, new
# session id — hand the tab over) apart from a real exit (✕): a
# `SessionEnd` whose pid still hosts a different live session was
# replaced, not ended. 0 means unknown, which reads as "no successor".
CLAUDE_PID=""

# 1. The messaging socket path is /tmp/cc-socks/{pid}.sock — exact, free.
if [ -n "$CLAUDE_CODE_MESSAGING_SOCKET" ]; then
  cand=${CLAUDE_CODE_MESSAGING_SOCKET##*/}
  cand=${cand%.sock}
  case "$cand" in ''|*[!0-9]*) ;; *) CLAUDE_PID="$cand" ;; esac
fi

# 2. Walk up from this script. Claude spawns hooks directly, so it is
#    almost always $PPID; the loop covers an intervening wrapper shell.
if [ -z "$CLAUDE_PID" ]; then
  parent="$PPID"
  depth=0
  while [ -n "$parent" ] && [ "$parent" -gt 1 ] 2>/dev/null && [ "$depth" -lt 6 ]; do
    info=$(ps -o ppid=,comm= -p "$parent" 2>/dev/null) || break
    [ -n "$info" ] || break
    # Intentional split: ps prints "<ppid> <command>" and the first field is the join key.
    # shellcheck disable=SC2086
    set -- $info
    grandparent="$1"
    shift
    case "$*" in *claude*) CLAUDE_PID="$parent"; break ;; esac
    parent="$grandparent"
    depth=$((depth + 1))
  done
fi

# 3. Last resort: the session file that names this session id.
if [ -z "$CLAUDE_PID" ]; then
  for sf in "$HOME/.claude/sessions/"*.json; do
    [ -f "$sf" ] || continue
    case "$(cat "$sf" 2>/dev/null)" in
      *"\"sessionId\":\"$SID\""*) CLAUDE_PID=${sf##*/}; CLAUDE_PID=${CLAUDE_PID%.json}; break ;;
    esac
  done
  case "$CLAUDE_PID" in *[!0-9]*) CLAUDE_PID="" ;; esac
fi

: "${CLAUDE_PID:=0}"

# ── Write ───────────────────────────────────────────────────────────
STATUS_DIR="$HOME/.claude/intellij-claude-terminal/status"
mkdir -p "$STATUS_DIR" 2>/dev/null || exit 0

RECORD="{\"event\":\"$EVENT\",\"source\":\"$SOURCE\",\"notificationType\":\"$NOTIF_TYPE\",\"reason\":\"$REASON\",\"sessionId\":\"$SID\",\"ts\":$TS,\"pid\":$CLAUDE_PID}"

# Background state is deliberately count-only. `Stop` supplies the complete
# in-flight array. `SubagentStop` supplies the updated parent-session array on
# current CLIs; on older versions, decrement the prior count as a safe fallback.
BACKGROUND_COUNT=-1
case "$EVENT" in
  Stop)
    BACKGROUND_COUNT=$(json_background_count)
    [ "$BACKGROUND_COUNT" -ge 0 ] 2>/dev/null || BACKGROUND_COUNT=0
    ;;
  SubagentStop)
    BACKGROUND_COUNT=$(json_background_count)
    if ! [ "$BACKGROUND_COUNT" -ge 0 ] 2>/dev/null; then
      PREVIOUS_COUNT=$(grep -o '"count"[[:space:]]*:[[:space:]]*[0-9]*' "$STATUS_DIR/background-$SID.json" 2>/dev/null \
        | head -1 | sed 's/.*://; s/[[:space:]]//g')
      case "$PREVIOUS_COUNT" in ''|*[!0-9]*) PREVIOUS_COUNT=0 ;; esac
      if [ "$PREVIOUS_COUNT" -gt 0 ]; then BACKGROUND_COUNT=$((PREVIOUS_COUNT - 1)); else BACKGROUND_COUNT=0; fi
    fi
    ;;
  SessionStart|SessionEnd)
    BACKGROUND_COUNT=0
    ;;
esac
if [ "$BACKGROUND_COUNT" -gt 999 ] 2>/dev/null; then BACKGROUND_COUNT=999; fi

# Written to a temp file and renamed, so the plugin — which polls this
# directory every 400ms — never reads a half-written record.
write_record() {
  tmp="$1.$$.tmp"
  payload="${2:-$RECORD}"
  if printf '%s\n' "$payload" > "$tmp" 2>/dev/null; then
    mv -f "$tmp" "$1" 2>/dev/null || rm -f "$tmp" 2>/dev/null
  fi
}

if [ "$BACKGROUND_COUNT" -ge 0 ] 2>/dev/null; then
  BACKGROUND_RECORD="{\"sessionId\":\"$SID\",\"count\":$BACKGROUND_COUNT,\"ts\":$TS}"
  write_record "$STATUS_DIR/background-$SID.json" "$BACKGROUND_RECORD"
fi

if [ "$SKIP_STATUS_WRITE" -eq 0 ]; then
  write_record "$STATUS_DIR/$SID.json"
fi

# The tab bridge is a mapping, not a status. Every event refreshes it,
# including the ones whose status was just discarded above — the terminal
# still hosts this session either way, and a tab that loses its mapping
# stops being tracked entirely.
if [ -n "$TERM_SESSION_ID" ] && is_safe_id "$TERM_SESSION_ID"; then
  write_record "$STATUS_DIR/termsess-$TERM_SESSION_ID.json"
fi

exit 0
