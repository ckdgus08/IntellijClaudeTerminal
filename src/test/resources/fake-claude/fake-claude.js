#!/usr/bin/env node
// Minimal Claude Code stub for UI tests. Does NOT call Anthropic APIs.
//
// Usage: node fake-claude.js <session-id> [cwd]
//
// 1. Writes ~/.claude/sessions/<own-PID>.json with the given session ID so the plugin's
//    findClaudeChild + session-file lookup treats this as a real Claude instance.
// 2. Optionally writes session-map/<TERM_SESSION_ID> → <sessionId> to mimic the real
//    session-start-hook.sh, so /tab can resolve the right tab.
// 3. Reads stdin forever so the process stays alive (the plugin walks the tab's process
//    tree to find it).
// 4. On SIGTERM / SIGINT: removes the session file and exits cleanly.
//
// Intentionally tiny — this is a test fixture, not a real Claude.

const fs = require('fs');
const path = require('path');
const os = require('os');

const sessionId = process.argv[2] || `test-${Date.now()}`;
const cwd = process.argv[3] || process.cwd();
const pid = process.pid;

const sessionsDir = path.join(os.homedir(), '.claude', 'sessions');
fs.mkdirSync(sessionsDir, { recursive: true });
const sessionFile = path.join(sessionsDir, `${pid}.json`);
fs.writeFileSync(sessionFile, JSON.stringify({
  pid,
  sessionId,
  cwd,
  startedAt: Date.now(),
  kind: 'interactive',
  entrypoint: 'cli',
}));

// If we're launched inside a JetBrains terminal, mimic the session-start-hook behaviour
// so /tab's TERM_SESSION_ID lookup finds us.
const termSid = process.env.TERM_SESSION_ID;
if (termSid) {
  const mapDir = path.join(os.homedir(), '.claude', 'intellij-claude-terminal', 'session-map');
  fs.mkdirSync(mapDir, { recursive: true });
  fs.writeFileSync(path.join(mapDir, termSid), sessionId);
}

// Print a banner that looks like real Claude so the user can see the stub is running.
console.log(`fake-claude stub  pid=${pid}  session=${sessionId}`);
console.log(`cwd=${cwd}`);
console.log('Stub ready. Reading stdin forever. SIGTERM to exit.');

// Stay alive.
process.stdin.resume();

function cleanup() {
  try { fs.unlinkSync(sessionFile); } catch (_) {}
  process.exit(0);
}
process.on('SIGTERM', cleanup);
process.on('SIGINT', cleanup);
