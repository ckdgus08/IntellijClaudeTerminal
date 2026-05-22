// Merges active sessions from ~/.claude/rider-plugin/restore-*.json into history.json.
// Emits a one-line summary so /tabs-backup feels instant rather than dumping a script.
//
// Usage: node backup-active.js [--hash=<projectHash>]
//
// Without --hash: backs up every project's active sessions (legacy --all behavior).
// With --hash=X: only reads restore-<X>.json — current-project scope.

const fs = require('fs');
const path = require('path');
const os = require('os');

const hashArg = process.argv.slice(2).find((a) => a.startsWith('--hash='));
const projectHash = hashArg ? hashArg.slice('--hash='.length) : null;

const home = path.join(os.homedir(), '.claude', 'rider-plugin');
const historyPath = path.join(home, 'history.json');

let history = [];
if (fs.existsSync(historyPath)) {
  try { history = JSON.parse(fs.readFileSync(historyPath, 'utf8')); }
  catch { history = []; }
}

const active = [];
if (fs.existsSync(home)) {
  const files = projectHash
    ? [`restore-${projectHash}.json`].filter((n) => fs.existsSync(path.join(home, n)))
    : fs.readdirSync(home).filter((n) => n.startsWith('restore-') && n.endsWith('.json'));
  for (const f of files) {
    try { active.push(...JSON.parse(fs.readFileSync(path.join(home, f), 'utf8'))); }
    catch {}
  }
}

if (!active.length) {
  console.log(projectHash
    ? 'No active sessions for this project to back up.'
    : 'No active sessions to back up.');
  process.exit(0);
}

const now = Date.now();
let added = 0;
let updated = 0;
for (const s of active) {
  const sid = s.sessionId;
  if (!sid) continue;
  const wasPresent = history.some((e) => e.sessionId === sid);
  history = history.filter((e) => e.sessionId !== sid);
  history.push({
    sessionId: sid,
    cwd: s.cwd || '',
    tabName: s.tabName || '',
    bypassPermissions: !!s.bypassPermissions,
    closedAt: now,
    backedUp: true,
  });
  if (wasPresent) updated++; else added++;
}

const cutoff = now - 90 * 24 * 60 * 60 * 1000;
history = history.filter((e) => (e.closedAt || 0) > cutoff);

fs.mkdirSync(home, { recursive: true });
fs.writeFileSync(historyPath, JSON.stringify(history, null, 2));

console.log(`Backup: ${added} new, ${updated} updated, ${history.length} total in history.`);
