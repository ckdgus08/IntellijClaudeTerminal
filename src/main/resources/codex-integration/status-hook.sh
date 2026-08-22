#!/bin/bash
# Codex hook → Rider terminal-tab status record.
# Codex sends the documented hook payload on stdin. Keep it nested and untouched: fields
# such as `prompt` can contain arbitrary escaped JSON and must not be flattened with regex.

EVENT="$1"
INPUT=$(cat 2>/dev/null)

json_str() {
  printf '%s' "$INPUT" | tr -d '\n' \
    | grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
    | head -1 \
    | sed 's/^[^:]*:[[:space:]]*"//; s/"$//'
}

SID=$(json_str session_id)
[ -n "$SID" ] || SID=$(json_str sessionId)
[ -n "$EVENT" ] || EVENT=$(json_str hook_event_name)

is_safe_id() {
  case "$1" in ''|.|..|*[!A-Za-z0-9._-]*) return 1 ;; esac
  [ "${#1}" -le 128 ]
}

is_safe_id "$SID" || exit 0
case "$EVENT" in ''|*[!A-Za-z]*) exit 0 ;; esac

TERM_SID="${TERM_SESSION_ID}"
is_safe_id "$TERM_SID" || TERM_SID=""

now_ms() {
  local ms
  ms=$(date +%s%3N 2>/dev/null)
  case "$ms" in ''|*[!0-9]*) ;; *) printf '%s' "$ms"; return ;; esac
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf("%d", time()*1000)' 2>/dev/null && return
  fi
  printf '%s000' "$(date +%s)"
}
TS=$(now_ms)
case "$TS" in ''|*[!0-9]*) TS=0 ;; esac

# Hooks are descendants of the interactive Codex process. Record that ancestor so stale
# SessionEnd-less files can be rejected when Rider reconstructs its active session set.
CODEX_PID=0
parent="$PPID"
depth=0
while [ -n "$parent" ] && [ "$parent" -gt 1 ] 2>/dev/null && [ "$depth" -lt 8 ]; do
  info=$(ps -o ppid=,comm= -p "$parent" 2>/dev/null) || break
  [ -n "$info" ] || break
  # Intentional field split: `ps` returns "<ppid> <command...>" and the remainder is
  # rejoined below solely for process-name matching.
  # shellcheck disable=SC2086
  set -- $info
  grandparent="$1"
  shift
  case "$*" in *codex*) CODEX_PID="$parent"; break ;; esac
  parent="$grandparent"
  depth=$((depth + 1))
done

STATUS_DIR="$HOME/.codex/rider-agent-tabs/status"
mkdir -p "$STATUS_DIR" 2>/dev/null || exit 0

write_record() {
  target="$1"
  tmp="$target.$$.tmp"
  {
    printf '{"event":"%s","termSessionId":"%s","ts":%s,"pid":%s,"payload":' "$EVENT" "$TERM_SID" "$TS" "$CODEX_PID"
    printf '%s' "$INPUT"
    printf '}\n'
  } > "$tmp" 2>/dev/null || return
  mv -f "$tmp" "$target" 2>/dev/null || rm -f "$tmp" 2>/dev/null
}

write_record "$STATUS_DIR/$SID.json"
if [ "$EVENT" = "UserPromptSubmit" ] && [ ! -f "$STATUS_DIR/prompt-$SID.json" ]; then
  write_record "$STATUS_DIR/prompt-$SID.json"
fi
if [ -n "$TERM_SID" ]; then
  write_record "$STATUS_DIR/termsess-$TERM_SID.json"
fi

exit 0
