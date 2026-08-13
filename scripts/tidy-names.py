#!/usr/bin/env python3
"""
Repair and prune the plugin's tab-name store (`names.json`).

Two things go wrong in that file, both consequences of a session id being replaced
in a tab without the plugin noticing:

  RECOVER  A name you typed is filed under a session that has since ended. The tab
           is still open and still yours, but everything that reads a name for it
           keys off the *new* session id — which has no entry — so the tab reverted
           to an auto-derived name. Observed for real:

             names.json   b38f686a… -> "시뮬레이션"   (setBy=user)
             status/      b38f686a… -> SessionEnd at 20:38:32, pid 24373 gone
             queue        09776e86… started 20:38:34, same project directory
             restore      09776e86… is the session in that tab now, and unnamed

           The fix in the plugin stops this happening again; it cannot go back and
           re-file names that were already stranded. That is what this does.

  PRUNE    Entries for sessions whose transcript is gone. Those conversations can
           never be resumed, so the name can never apply to anything again.

Read-only by default: it prints what it would do and changes nothing. Pass --apply
to write, which backs the file up first and refuses if anything else has touched it
since it was read.

Usage:
    scripts/tidy-names.py                     # report only
    scripts/tidy-names.py --apply             # carry stranded names + drop orphans
    scripts/tidy-names.py --apply --no-prune  # carry only
    scripts/tidy-names.py --window 300        # widen the succession window
"""

from __future__ import annotations

import argparse
import datetime
import glob
import json
import os
import shutil
import sys

CLAUDE_HOME = os.path.expanduser("~/.claude")
STATE_DIR = os.path.join(CLAUDE_HOME, "intellij-claude-terminal")
NAMES_FILE = os.path.join(STATE_DIR, "names.json")

# How long after one session ends another may start and still count as having taken
# over the same terminal. The real gap is the time it takes to type `claude` again —
# two seconds in the case above. Generous by default; nothing else within the window
# may match, or the link is reported as ambiguous instead of guessed.
DEFAULT_WINDOW_S = 120

# A stranded name can be stranded more than once (restart, restart again). Following
# the chain is bounded so a pathological queue can't spin.
MAX_HOPS = 8


# ── reading the state the plugin leaves on disk ───────────────────────────────


def load_names(path):
    """`names.json` -> {sid: {name, setBy, setAt}}, in file order."""
    if not os.path.exists(path):
        return {}, 0
    mtime = os.path.getmtime(path)
    with open(path, encoding="utf-8") as f:
        text = f.read().strip()
    if not text or text == "{}":
        return {}, mtime
    return json.loads(text), mtime


def serialise_names(entries):
    """Reproduce ClaudeTabsStorage.serialiseNames byte for byte.

    Matching matters: the plugin's parser is a regex over this exact shape, and a
    file it round-trips differently would show up as spurious churn on the next
    upsert. Only backslash and quote are escaped (ClaudeTabsHelpers.esc), and
    non-ASCII stays raw, so names in any script survive.
    """
    if not entries:
        return "{}"

    def esc(s):
        return str(s).replace("\\", "\\\\").replace('"', '\\"')

    lines = [
        '  "%s":{"name":"%s","setBy":"%s","setAt":%d}'
        % (esc(sid), esc(e["name"]), esc(e.get("setBy", "unknown")), int(e.get("setAt", 0)))
        for sid, e in entries.items()
    ]
    return "{\n" + ",\n".join(lines) + "\n}"


def session_starts():
    """`session-queue/<nanotime>` -> [(started_ms, sid)], chronological.

    The hook drops one file per session start, named with a nanosecond clock. It is
    the only ordered record of which conversation began when.
    """
    out = []
    for f in glob.glob(os.path.join(STATE_DIR, "session-queue", "*")):
        try:
            nanos = int(os.path.basename(f))
        except ValueError:
            continue
        try:
            with open(f, encoding="utf-8") as fh:
                sid = fh.read().strip()
        except OSError:
            continue
        if sid:
            out.append((nanos // 1_000_000, sid))
    out.sort()
    return out


def session_ends():
    """`status/<sid>.json` -> {sid: ended_ms} for the sessions whose last edge was SessionEnd."""
    out = {}
    for f in glob.glob(os.path.join(STATE_DIR, "status", "*.json")):
        if os.path.basename(f).startswith("termsess-"):
            continue
        try:
            with open(f, encoding="utf-8") as fh:
                rec = json.load(fh)
        except (OSError, ValueError):
            continue
        if rec.get("event") == "SessionEnd" and rec.get("sessionId"):
            out[rec["sessionId"]] = rec.get("ts", 0)
    return out


def transcripts():
    """{sid: (project_dir, path, mtime_ms)} for every conversation still on disk."""
    out = {}
    for f in glob.glob(os.path.join(CLAUDE_HOME, "projects", "*", "*.jsonl")):
        sid = os.path.basename(f)[:-6]
        try:
            mtime = os.path.getmtime(f) * 1000
        except OSError:
            continue
        out[sid] = (os.path.basename(os.path.dirname(f)), f, mtime)
    return out


def live_sessions():
    """Sids that are running right now — an alive pid in `sessions/<pid>.json`."""
    out = set()
    for f in glob.glob(os.path.join(CLAUDE_HOME, "sessions", "*.json")):
        try:
            pid = int(os.path.basename(f)[:-5])
        except ValueError:
            continue
        try:
            os.kill(pid, 0)
        except (OSError, ProcessLookupError, PermissionError) as e:
            if isinstance(e, ProcessLookupError):
                continue
            if isinstance(e, PermissionError):
                pass  # exists, owned by someone else
            else:
                continue
        try:
            with open(f, encoding="utf-8") as fh:
                sid = json.load(fh).get("sessionId")
        except (OSError, ValueError):
            continue
        if sid:
            out.add(sid)
    return out


def restorable_sessions():
    """Sids listed in any `restore-*.json` — the tabs a project would bring back."""
    out = set()
    for f in glob.glob(os.path.join(STATE_DIR, "restore-*.json")):
        try:
            with open(f, encoding="utf-8") as fh:
                for s in json.load(fh):
                    if s.get("sessionId"):
                        out.add(s["sessionId"])
        except (OSError, ValueError):
            continue
    return out


# `history.json` looks like it should say which tabs were closed, and it can't: it
# records every session that *ended*, which includes each one replaced in a tab that
# is still open. Membership there says nothing about whether a tab survives. The
# successor chain is the only thing that does — a name whose chain reaches a session
# running now belongs to a tab that is still on screen; one whose trail goes cold
# belongs to a tab that isn't.


# ── working out who took over a terminal ─────────────────────────────────────


def find_successor(sid, ends, starts, tinfo, window_ms):
    """The session that replaced [sid] in its tab, or a reason it can't be told.

    Returns (successor_sid, reason, candidates) — exactly one of the first two is
    set, and `candidates` is every session that could have been the replacement, so
    a caller reporting an ambiguity can weigh them itself.

    The evidence is circumstantial by necessity — the bridge file that names a
    terminal's session keeps only the newest id, so a hand-over that already
    happened leaves no direct record. What is left is timing and place: the
    replacement starts within seconds of its predecessor ending, in the same
    project directory, because it is the same person typing `claude` again in the
    same tab.

    Anything less clear-cut than a single candidate is refused rather than guessed.
    """
    ended = ends.get(sid)
    if ended is None:
        # No SessionEnd on record — the status file is pruned after 24h. The last
        # transcript write is when the conversation stopped, which is the same
        # moment for this purpose.
        if sid not in tinfo:
            return None, "no end time and no transcript", []
        ended = int(tinfo[sid][2])

    project = tinfo.get(sid, (None,))[0]
    if project is None:
        return None, "transcript gone, can't place it in a project", []

    candidates = [
        s
        for (started, s) in starts
        if ended < started <= ended + window_ms
        and s != sid
        and tinfo.get(s, (None,))[0] == project
    ]
    # The same session can be queued more than once (a resume re-announces it).
    candidates = list(dict.fromkeys(candidates))

    if not candidates:
        return None, "nothing started in that project within the window", []
    if len(candidates) > 1:
        return None, "ambiguous — %s all started in the window" % ", ".join(
            c[:8] for c in candidates
        ), candidates
    return candidates[0], None, candidates


def follow_chain(sid, names, ends, starts, tinfo, settled, window_ms):
    """Walk successors from [sid] until reaching the session that holds its tab now.

    Returns (final_sid, hops, reason, candidates). A name can be stranded repeatedly
    — quit and restart twice and it is two hops behind — so this follows the chain
    rather than looking one step ahead. `candidates` describes the step that stopped
    the walk, for a caller reporting why.
    """
    seen = {sid}
    current = sid
    hops = []
    for _ in range(MAX_HOPS):
        nxt, why, candidates = find_successor(current, ends, starts, tinfo, window_ms)
        if nxt is None:
            return None, hops, why, candidates
        if nxt in seen:
            return None, hops, "succession loops back on itself", []
        seen.add(nxt)
        hops.append(nxt)
        if nxt in settled:
            return nxt, hops, None, candidates
        current = nxt
    return None, hops, "chain longer than %d hops" % MAX_HOPS, []


# ── reporting ────────────────────────────────────────────────────────────────


def when(ms):
    if not ms:
        return "-"
    return datetime.datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")


def main():
    ap = argparse.ArgumentParser(
        description="Repair and prune the RiderClaudeTabs tab-name store.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("--apply", action="store_true", help="write the changes (default: report only)")
    ap.add_argument("--no-carry", action="store_true", help="skip re-filing stranded names")
    ap.add_argument("--no-prune", action="store_true", help="skip dropping entries whose transcript is gone")
    ap.add_argument(
        "--window",
        type=int,
        default=DEFAULT_WINDOW_S,
        metavar="SECONDS",
        help="how soon after one session ends another may start and count as its "
        "replacement (default: %d)" % DEFAULT_WINDOW_S,
    )
    ap.add_argument("--names-file", default=NAMES_FILE, help="override the names.json path")
    args = ap.parse_args()

    try:
        names, mtime = load_names(args.names_file)
    except ValueError as e:
        print("names.json is not readable as JSON (%s)." % e, file=sys.stderr)
        print("Refusing to touch it — repair or restore it by hand first.", file=sys.stderr)
        return 2

    if not names:
        print("names.json holds no entries — nothing to do.")
        return 0

    starts = session_starts()
    ends = session_ends()
    tinfo = transcripts()
    live = live_sessions()
    restorable = restorable_sessions()
    settled = live | restorable  # a session that still has a tab, now or on next start
    window_ms = args.window * 1000

    carries = []       # (old_sid, new_sid, name, hops)   — actionable
    needs_you = []     # (old_sid, name, reason)          — a tab is involved, but ambiguous
    cold = 0           # trail went cold: the tab is gone, the entry is just history
    orphans = []       # (sid, name, setBy)
    held = 0

    for sid, entry in names.items():
        name = entry.get("name", "")
        set_by = entry.get("setBy", "unknown")

        if sid in settled:
            held += 1
            continue

        # A name nobody typed is derived from the conversation, and the plugin will
        # derive it again for whatever is in the tab now. Only a deliberate choice is
        # worth chasing across a hand-over.
        if set_by == "user" and not args.no_carry:
            final, hops, why, candidates = follow_chain(
                sid, names, ends, starts, tinfo, settled, window_ms
            )
            if final is not None:
                if names.get(final, {}).get("setBy") == "user":
                    needs_you.append((sid, name, "%s already has a name you set" % final[:8]))
                else:
                    carries.append((sid, final, name, hops))
                continue
            # A fork we can't resolve still matters if any branch is a tab that
            # exists; otherwise the whole lineage is closed and this is just an old
            # name sitting harmlessly in the file.
            if any(c in settled for c in candidates):
                needs_you.append((sid, name, why))
            else:
                cold += 1
            # fall through: it may still be an orphan worth pruning

        if sid not in tinfo and not args.no_prune:
            orphans.append((sid, name, set_by))

    # ── report ──
    print("names.json: %s" % args.names_file)
    print("  %d entries — %d name a session that still has a tab" % (len(names), held))
    print("  %d Claude session(s) running right now\n" % len(live))
    if live and held == 0:
        print("  Note: not one entry names a session that is open. That is the symptom —")
        print("  every name is filed under an id the tabs have already moved on from.\n")

    if carries:
        print("STRANDED — a name you set, filed under a session that has ended:")
        for old, new, name, hops in carries:
            via = "" if len(hops) == 1 else "  (via %s)" % " → ".join(h[:8] for h in hops[:-1])
            state = "running" if new in live else "restorable"
            print("  '%s'" % name)
            print("    %s  ended %s" % (old, when(ends.get(old))))
            print("    %s  %s%s" % (new, state, via))
        print()

    if needs_you:
        print("STRANDED but not re-filed — a tab is involved, so decide these by hand:")
        for sid, name, why in needs_you:
            print("  '%s'  %s\n    %s" % (name, sid[:8], why))
        print()

    if cold:
        print("%d further name(s) belong to tabs that are no longer open — left alone.\n" % cold)

    if orphans:
        print("ORPHANED — transcript gone, so the conversation can never be resumed:")
        for sid, name, set_by in orphans:
            print("  %s  setBy=%-7s '%s'" % (sid[:8], set_by, name))
        print()

    if not carries and not orphans:
        print("Nothing to change.")
        return 0

    if not args.apply:
        print("Report only. Re-run with --apply to make these changes.")
        return 0

    # ── apply ──
    updated = dict(names)
    for old, new, name, _hops in carries:
        updated[new] = {"name": name, "setBy": "user", "setAt": names[old].get("setAt", 0)}
        del updated[old]
    for sid, _name, _set_by in orphans:
        updated.pop(sid, None)

    # The plugin writes this file too. If it moved while we were deciding, our copy
    # is stale and writing it would drop whatever changed.
    if os.path.exists(args.names_file) and os.path.getmtime(args.names_file) != mtime:
        print("names.json changed while this ran — most likely the IDE is open and "
              "renamed a tab.\nNothing written. Close the IDE and try again.", file=sys.stderr)
        return 3

    backup = args.names_file + ".bak-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    shutil.copy2(args.names_file, backup)

    tmp = args.names_file + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(serialise_names(updated))
    os.replace(tmp, args.names_file)

    print("Wrote %s — %d name(s) re-filed, %d orphan(s) dropped, %d entries remain."
          % (args.names_file, len(carries), len(orphans), len(updated)))
    print("Backup: %s" % backup)
    if live:
        print("\nThe IDE is running. It caches this file by mtime and will pick the "
              "change up on its next poll,\nbut a tab already showing the old name "
              "only repaints on its next status change.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
