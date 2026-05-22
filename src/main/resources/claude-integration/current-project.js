// Resolves the current Rider project from process.cwd().
// Walks up looking for .idea/; falls back to cwd itself if no .idea/ ancestor exists.
// Mirrors ClaudeTabsHelpers.projectHashForPath() so the resulting `hash` matches the
// plugin's restore-<hash>.json / <hash>__<ts>.json filenames exactly.
//
// Usage:   node current-project.js
// Output:  {"root":"/repos/MyApp","hash":"repos-MyApp","name":"MyApp"}

const fs = require('fs');
const path = require('path');

let cur = process.cwd();
let root = cur;
while (true) {
  try {
    if (fs.existsSync(path.join(cur, '.idea'))) { root = cur; break; }
  } catch {}
  const parent = path.dirname(cur);
  if (parent === cur) break; // filesystem root
  cur = parent;
}

// Mirror Kotlin: basePath.replace("\\", "/").replace(":/", "--").replace("/", "-")
const hash = root
  .replace(/\\/g, '/')
  .replace(/:\//, '--')
  .replace(/\//g, '-');

console.log(JSON.stringify({ root, hash, name: path.basename(root) }));
