// Snapshots THIS terminal tab to ~/.claude/rider-plugin/history.json so /tabs-history can resume it later.
// Resolves the Claude session ID via the per-tab session-map (written by session-start-hook.sh keyed by
// TERM_SESSION_ID), with a fallback that matches by tab name in active restore files.
//
// Usage: TERM_SESSION_ID=<id> node tab-backup.js "<tab name>"
// Stays silent on success — /tab itself confirms the rename.

const fs = require('fs');
const path = require('path');
const os = require('os');

const name = (process.argv[2] || '').trim();
const termSid = (process.env.TERM_SESSION_ID || '').trim();
const home = path.join(os.homedir(), '.claude', 'rider-plugin');

let sid = null;
let cwd = '';
let bypass = false;

// Strategy 1: TERM_SESSION_ID -> session-map -> Claude session ID.
if (termSid) {
  const mapPath = path.join(home, 'session-map', termSid);
  if (fs.existsSync(mapPath)) {
    try { sid = fs.readFileSync(mapPath, 'utf8').trim(); } catch {}
  }
}

const restoreFiles = fs.existsSync(home)
  ? fs.readdirSync(home).filter((f) => f.startsWith('restore-') && f.endsWith('.json'))
  : [];

// Strategy 2: match by tab name in restore files (legacy fallback).
if (!sid && name) {
  for (const f of restoreFiles) {
    let sessions;
    try { sessions = JSON.parse(fs.readFileSync(path.join(home, f), 'utf8')); }
    catch { continue; }
    const match = sessions.find((s) => s.tabName === name);
    if (match) { sid = match.sessionId; cwd = match.cwd || ''; bypass = !!match.bypassPermissions; break; }
  }
}

// Nothing to record yet — the tab will be captured by a future /tabs-backup or close.
if (!sid) process.exit(0);

// Fill in cwd / bypass if we got the sid from session-map but not from a restore file.
if (!cwd) {
  for (const f of restoreFiles) {
    let sessions;
    try { sessions = JSON.parse(fs.readFileSync(path.join(home, f), 'utf8')); }
    catch { continue; }
    const match = sessions.find((s) => s.sessionId === sid);
    if (match) { cwd = match.cwd || ''; bypass = !!match.bypassPermissions; break; }
  }
}

const historyPath = path.join(home, 'history.json');
let history = [];
if (fs.existsSync(historyPath)) {
  try { history = JSON.parse(fs.readFileSync(historyPath, 'utf8')); }
  catch { history = []; }
}

const now = Date.now();
history = history.filter((e) => e.sessionId !== sid);
history.push({
  sessionId: sid,
  cwd,
  tabName: name,
  bypassPermissions: bypass,
  closedAt: now,
  backedUp: true,
});

// Prune entries older than 90 days.
const cutoff = now - 90 * 24 * 60 * 60 * 1000;
history = history.filter((e) => (e.closedAt || 0) > cutoff);

fs.mkdirSync(home, { recursive: true });
fs.writeFileSync(historyPath, JSON.stringify(history, null, 2));
