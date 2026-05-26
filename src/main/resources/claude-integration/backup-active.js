// /tabs-backup — back up every currently-alive Claude session, not just whatever
// happens to be in the restore-<hash>.json files.
//
// Why: the restore files are maintained by the plugin's poll loop, which can lag,
// drop, or fail to track alive sessions (canonical-id collisions, transient races,
// project-not-open). Reading them as the source of truth means /tabs-backup misses
// any alive session the plugin didn't manage to track. The actual source of truth
// for "what Claude processes are alive" is ~/.claude/sessions/<pid>.json (Claude
// writes these directly; one file per live Claude process).
//
// What this does:
//   1. Scan ~/.claude/sessions/*.json — every alive Claude session, with cwd + sid.
//   2. Filter by --hash=<projectHash> if given (scoped to one project), else all.
//   3. For each alive session, ensure it's in the project's restore file with a
//      sensible tabName (names.json > existing restore tabName > "Claude").
//   4. Merge every backed-up session into history.json with closedAt=now.
//
// Usage: node backup-active.js [--hash=<projectHash>]

const fs = require('fs');
const path = require('path');
const os = require('os');

const hashArg = process.argv.slice(2).find((a) => a.startsWith('--hash='));
const projectHash = hashArg ? hashArg.slice('--hash='.length) : null;

const home = path.join(os.homedir(), '.claude', 'rider-plugin');
const sessionsDir = path.join(os.homedir(), '.claude', 'sessions');
const projectsDir = path.join(os.homedir(), '.claude', 'projects');
const historyPath = path.join(home, 'history.json');
const namesPath = path.join(home, 'names.json');
const projectIndexPath = path.join(home, 'project-index.json');

function readJsonSafe(p, fallback) {
  if (!fs.existsSync(p)) return fallback;
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); }
  catch { return fallback; }
}

function atomicWrite(p, content) {
  const tmp = p + '.tmp';
  fs.writeFileSync(tmp, content);
  fs.renameSync(tmp, p);
}

function normaliseForCompare(p) {
  return p.replace(/\\/g, '/').replace(/\/$/, '').toLowerCase();
}

function isCwdUnder(cwd, base) {
  if (!cwd || !base) return false;
  const a = normaliseForCompare(cwd);
  const b = normaliseForCompare(base);
  return a === b || a.startsWith(b + '/');
}

function hasTranscript(cwd, sid) {
  // Match the plugin's projectHashForPath: replace [\\/:] with - and trim leading dashes
  const dir = cwd.replace(/[\\/:]/g, '-').replace(/^-+/, '').replace(/-+$/, '');
  return fs.existsSync(path.join(projectsDir, dir, sid + '.jsonl'));
}

// 1. Read alive sessions
if (!fs.existsSync(sessionsDir)) {
  console.log('No ~/.claude/sessions/ dir — nothing alive to back up.');
  process.exit(0);
}

const alive = [];
for (const f of fs.readdirSync(sessionsDir)) {
  if (!f.endsWith('.json')) continue;
  try {
    const d = JSON.parse(fs.readFileSync(path.join(sessionsDir, f), 'utf8'));
    if (!d.sessionId || !d.cwd) continue;
    alive.push(d);
  } catch { /* race / partial write */ }
}

if (alive.length === 0) {
  console.log('No alive Claude sessions to back up.');
  process.exit(0);
}

// 2. Map cwd → projectHash via project-index.json
const projectIndex = readJsonSafe(projectIndexPath, { projects: [] });
function projectForCwd(cwd) {
  for (const p of projectIndex.projects || []) {
    if (isCwdUnder(cwd, p.basePath)) return p;
  }
  return null;
}

const names = readJsonSafe(namesPath, {});

// 3. Group alive sessions by projectHash (or skip if not in index)
const byProject = new Map();
let unmapped = 0;
for (const sess of alive) {
  const proj = projectForCwd(sess.cwd);
  if (!proj) { unmapped++; continue; }
  if (projectHash && proj.hash !== projectHash) continue;
  if (!hasTranscript(sess.cwd, sess.sessionId)) continue;
  if (!byProject.has(proj.hash)) byProject.set(proj.hash, { proj, sessions: [] });
  byProject.get(proj.hash).sessions.push(sess);
}

if (byProject.size === 0) {
  if (projectHash) {
    console.log('No alive sessions for project hash ' + projectHash + ' to back up.');
  } else {
    console.log('No alive sessions match any known project (unmapped=' + unmapped + ').');
  }
  process.exit(0);
}

// 4. For each project, update the restore file to include all alive sessions
let restoreAdded = 0;
let restoreKept = 0;
for (const { proj, sessions } of byProject.values()) {
  const restorePath = path.join(home, 'restore-' + proj.hash + '.json');
  const existing = readJsonSafe(restorePath, []);
  const existingById = new Map();
  for (const e of existing) existingById.set(e.sessionId, e);

  // Build authoritative list: every alive session for this project, with name resolved
  const entries = sessions.map((s) => {
    const prior = existingById.get(s.sessionId);
    const nameFromStore = names[s.sessionId] && names[s.sessionId].name;
    const tabName = nameFromStore || (prior && prior.tabName) || 'Claude';
    if (!prior) restoreAdded++; else restoreKept++;
    return {
      sessionId: s.sessionId,
      cwd: s.cwd,
      tabName,
      bypassPermissions: (prior && prior.bypassPermissions) || false,
    };
  });
  const content = '[\n' + entries.map((e) => '  ' + JSON.stringify(e)).join(',\n') + '\n]\n';
  atomicWrite(restorePath, content);
}

// 5. Merge into history.json
let history = readJsonSafe(historyPath, []);
const now = Date.now();
let histAdded = 0;
let histUpdated = 0;
for (const { sessions } of byProject.values()) {
  for (const s of sessions) {
    const sid = s.sessionId;
    const wasPresent = history.some((e) => e.sessionId === sid);
    history = history.filter((e) => e.sessionId !== sid);
    const nameFromStore = names[sid] && names[sid].name;
    history.push({
      sessionId: sid,
      cwd: s.cwd,
      tabName: nameFromStore || 'Claude',
      bypassPermissions: false,
      closedAt: now,
      backedUp: true,
    });
    if (wasPresent) histUpdated++; else histAdded++;
  }
}

// Prune history older than 90 days
const cutoff = now - 90 * 24 * 60 * 60 * 1000;
history = history.filter((e) => (e.closedAt || 0) > cutoff);

fs.mkdirSync(home, { recursive: true });
atomicWrite(historyPath, JSON.stringify(history, null, 2));

const scope = projectHash ? 'project ' + projectHash : (byProject.size + ' project(s)');
console.log(
  'Backup: ' + scope +
  ' — restore: ' + restoreAdded + ' added, ' + restoreKept + ' kept' +
  ' | history: ' + histAdded + ' new, ' + histUpdated + ' updated (' + history.length + ' total)'
);
