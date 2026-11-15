#!/usr/bin/env python3
"""A stand-in for sculk's control server, for working on the web UI without
Minecraft. It serves the UI from web/ (app.js is bundled by esbuild on each
change, so edits show up on reload) and answers the HTTP API with a made-up
game: a client connected to a modded server, with steady vanilla traffic,
mod payloads, now and then a dropped, replaced or injected packet, script
output, and the odd round-trip mismatch or script error.

    nix develop -c python3 web/tools/mock-server.py [--port 8765] [--rate 40]

then open the URL it prints. The token is "mock". In the REPL, a few
expressions answer like the real thing (sculk.connections(),
sculk.payloadTypes(), sculk.log(...), sculk.emit(...), arithmetic), and
mock.burst(n), mock.disconnect(), mock.connect(), mock.rate(n),
mock.pause() and mock.resume() steer the made-up traffic. Scripts live in
memory; running one "registers" a listener per sculk.on( in it, logs its
top-level sculk.log('...') lines, and fails on unbalanced brackets, the
word SYNTAX, or a throw.

The filter language is a port of core/.../control/EventFilter.java.
"""
import argparse
import json
import random
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent
ROOT = WEB.parent
TOKEN = "mock"
MAX_EVENTS = 20_000
LOG_FRAME_BYTES = 512

# ---------------------------------------------------------------- filter


class FilterError(ValueError):
    pass


def compile_filter(source):
    if not source or not source.strip():
        return lambda e: True
    p = _Parser(source)
    pred = p.or_()
    if p.pos < len(p.tokens):
        raise FilterError(f"unexpected '{p.tokens[p.pos][0]}'")

    def safe(e):
        try:
            return pred(e)
        except Exception:
            return False

    return safe


class _Parser:
    def __init__(self, s):
        self.tokens = []  # (text, quoted)
        self.pos = 0
        i = 0
        while i < len(s):
            c = s[i]
            if c.isspace():
                i += 1
            elif c in "\"'":
                out, j = [], i + 1
                while True:
                    if j >= len(s):
                        raise FilterError("unterminated string")
                    d = s[j]
                    j += 1
                    if d == c:
                        break
                    if d == "\\" and j < len(s):
                        d = s[j]
                        j += 1
                    out.append(d)
                self.tokens.append(("".join(out), True))
                i = j
            elif c in "()":
                self.tokens.append((c, False))
                i += 1
            elif c in "=!<>&|~":
                j = i + 1
                if j < len(s) and s[j] in "=&|":
                    j += 1
                self.tokens.append((s[i:j], False))
                i = j
            else:
                j = i
                while j < len(s) and not s[j].isspace() and s[j] not in "()=!<>&|~\"'":
                    j += 1
                self.tokens.append((s[i:j], False))
                i = j

    def peek(self, *opts):
        return self.pos < len(self.tokens) and not self.tokens[self.pos][1] and self.tokens[self.pos][0] in opts

    def take(self, *opts):
        if self.peek(*opts):
            self.pos += 1
            return True
        return False

    def or_(self):
        left = self.and_()
        while self.take("||", "or"):
            l, r = left, self.and_()
            left = lambda e, l=l, r=r: l(e) or r(e)
        return left

    def and_(self):
        left = self.unary()
        while self.take("&&", "and"):
            l, r = left, self.unary()
            left = lambda e, l=l, r=r: l(e) and r(e)
        return left

    def unary(self):
        if self.take("!", "not"):
            inner = self.unary()
            return lambda e: not inner(e)
        if self.take("("):
            inner = self.or_()
            if not self.take(")"):
                raise FilterError("missing ')'")
            return inner
        return self.comparison()

    def comparison(self):
        if self.pos >= len(self.tokens):
            raise FilterError("expected a field name")
        tok, q = self.tokens[self.pos]
        if q:
            raise FilterError("expected a field name, not a string")
        self.pos += 1
        path = tok.split(".")
        if self.pos < len(self.tokens) and not self.tokens[self.pos][1]:
            op = self.tokens[self.pos][0]
            tests = {
                "==": lambda a, b: _cmp(a, b) == 0,
                "!=": lambda a, b: _cmp(a, b) != 0,
                "<": lambda a, b: a is not None and _cmp(a, b) < 0,
                "<=": lambda a, b: a is not None and _cmp(a, b) <= 0,
                ">": lambda a, b: a is not None and _cmp(a, b) > 0,
                ">=": lambda a, b: a is not None and _cmp(a, b) >= 0,
                "contains": lambda a, b: a is not None and str(b) in _text(a),
                "matches": lambda a, b: a is not None and b.search(_text(a)) is not None,
            }
            tests["~"] = tests["matches"]
            if op in tests:
                self.pos += 1
                if self.pos >= len(self.tokens):
                    raise FilterError(f"expected a value after {op}")
                value = _value(*self.tokens[self.pos])
                self.pos += 1
                if op in ("matches", "~"):
                    try:
                        value = re.compile(str(value))
                    except re.error as ex:
                        raise FilterError(str(ex))
                whole = op in ("contains", "matches", "~")
                test = tests[op]
                return lambda e: _any(_resolve(e, path), value, test, whole)
        return lambda e: _any(_resolve(e, path), None, lambda a, b: a is not None and a is not False, True)


def _value(tok, quoted):
    if quoted:
        return tok
    if tok == "true":
        return True
    if tok == "false":
        return False
    if tok == "null":
        return None
    n = _number(tok)
    return n if n is not None else tok


SHORTHANDS = {"dir": ["frame.dir"], "len": ["frame.len"], "side": ["conn.side"], "type": ["packet.type"],
              "channel": ["mod.channel"], "owner": ["mod.owner", "packet.owner"]}


def _resolve(e, path):
    if len(path) == 1 and path[0] not in e and path[0] in SHORTHANDS:
        for full in SHORTHANDS[path[0]]:
            found = _lookup(e, full.split("."), 0)
            if found is not None:
                return found
        return None
    return _lookup(e, path, 0)


def _lookup(value, path, i):
    if i == len(path) or value is None:
        return value
    key = path[i]
    if isinstance(value, dict):
        return _lookup(value.get(key), path, i + 1)
    if isinstance(value, list):
        if key.isdigit():
            k = int(key)
            return _lookup(value[k], path, i + 1) if k < len(value) else None
        out = []
        for o in value:
            found = _lookup(o, path, i)
            if isinstance(found, list):
                out.extend(found)
            elif found is not None:
                out.append(found)
        return out
    return None


def _any(left, right, test, whole):
    if isinstance(left, list):
        if whole and test(left, right):
            return True
        return any(test(o, right) for o in left)
    return test(left, right)


def _cmp(a, b):
    if a is None or b is None:
        return 0 if a is b else (-1 if a is None else 1)
    x, y = _number(a), _number(b)
    if x is not None and y is not None:
        return (x > y) - (x < y)
    s, t = _text(a), _text(b)
    return (s > t) - (s < t)


def _number(o):
    if isinstance(o, bool):
        return None
    if isinstance(o, (int, float)):
        return float(o)
    if not isinstance(o, str) or not o:
        return None
    try:
        if o[:2] in ("0x", "0X"):
            return float(int(o[2:], 16))
        return float(o)
    except ValueError:
        return None


def _text(o):
    if isinstance(o, (dict, list)):
        return json.dumps(o, separators=(",", ":"))
    if o is True:
        return "true"
    if o is False:
        return "false"
    return str(o)


# ---------------------------------------------------------------- the store


class Store:
    def __init__(self):
        self.events = []  # (id, fields, json, frame)
        self.last_id = 0
        self.cond = threading.Condition()
        self.match_cache = {}

    def add(self, kind, fields, frame=None):
        with self.cond:
            self.last_id += 1
            e = {"id": self.last_id, "kind": kind, "t": int(time.time() * 1000)}
            e.update(fields)
            self.events.append((self.last_id, e, json.dumps(e, separators=(",", ":")), frame))
            if len(self.events) > MAX_EVENTS:
                del self.events[: len(self.events) - MAX_EVENTS]
            self.cond.notify_all()
            return e

    def query(self, pred, after, limit):
        with self.cond:
            snapshot = list(self.events)
        out = []
        for ev in snapshot:
            if after is not None and ev[0] <= after:
                continue
            if not pred(ev[1]):
                continue
            out.append(ev)
            if after is not None and len(out) >= limit:
                break
        return out[-limit:] if after is None and len(out) > limit else out

    def matches(self, source, pred):
        """The ids matching a filter, kept up to date like EventStore.matches: (list, removed)."""
        import bisect
        with self.cond:
            snapshot = list(self.events)
            m = self.match_cache.setdefault(source, {"ids": [], "removed": 0, "scanned": 0})
            first = snapshot[0][0] if snapshot else self.last_id + 1
            k = bisect.bisect_left(m["ids"], first)
            if k:
                del m["ids"][:k]
                m["removed"] += k
            for ev in snapshot:
                if ev[0] > m["scanned"] and pred(ev[1]):
                    m["ids"].append(ev[0])
            if snapshot:
                m["scanned"] = max(m["scanned"], snapshot[-1][0])
            return list(m["ids"]), m["removed"], {ev[0]: ev for ev in snapshot}

    def page(self, source, pred, frm, count):
        ids, removed, by_id = self.matches(source, pred)
        lo = max(frm, removed)
        hi = min(frm + count, removed + len(ids))
        events = [by_id[ids[i - removed]][2] for i in range(lo, hi) if ids[i - removed] in by_id]
        return '{"start":%d,"total":%d,"from":%d,"events":[%s]}' % (removed, removed + len(ids), lo, ",".join(events))

    def position(self, source, pred, id_):
        import bisect
        ids, removed, _ = self.matches(source, pred)
        return {"index": removed + bisect.bisect_left(ids, id_), "start": removed, "total": removed + len(ids)}

    def get(self, id_):
        with self.cond:
            for ev in self.events:
                if ev[0] == id_:
                    return ev
        return None


# ---------------------------------------------------------------- the made-up game

PLATFORM = "neoforge 21.1.77 · minecraft 1.21.1 · client"
SCRIPT_DIR = "/home/player/.minecraft/config/sculk/scripts"
SCRIPT_NAME = re.compile(r"[A-Za-z0-9_][A-Za-z0-9 _.()+,-]{0,95}\.js")
SEED_SCRIPTS = {
    "log-waystones.js": (True, """// ==Sculk==
// @name        Log waystones
// @description Logs every Waystones payload, both ways
// @run         startup
// ==/Sculk==

sculk.on('packet', e => {
  if (e.owner === 'waystones') sculk.log(e.dir + ' ' + e.channel);
});
"""),
    "fuzz-backpacks.js": (True, """// ==Sculk==
// @name        Fuzz backpacks
// @description Replays Sophisticated Backpacks payloads with a bumped slot
// @run         startup
// ==/Sculk==

sculk.on('packet', e => {
  if (e.channel !== 'sophisticatedbackpacks:sync_slot' || e.dir !== 'outbound') return;
  const data = sculk.with(e.mod.data, 'slot', e.mod.data.slot + 1);
  sculk.schedule(50, () => e.conn.injectPacket('outbound', data));
});
"""),
    "dump-recipes.js": (True, """// ==Sculk==
// @name        Dump recipes
// @description One-shot: how many recipe packets came in
// @run         manual
// ==/Sculk==

sculk.log('counting');
42
"""),
    "server-only.js": (False, """// ==Sculk==
// @name        Server only
// @dist        server
// ==/Sculk==
sculk.on('connection', c => sculk.log(c.remote));
"""),
}


class MockScripts:
    """A stand-in for ScriptManager.java: scripts in memory, "running" by pattern."""

    def __init__(self, store):
        self.store = store
        self.lock = threading.Lock()
        self.files = {name: {"source": src, "enabled": on} for name, (on, src) in SEED_SCRIPTS.items()}
        # name -> status, as ScriptManager reports it: running, failed or stopped.
        self.status = {}

    @staticmethod
    def meta(source):
        out, inside = {}, False
        for line in source.split("\n")[:200]:
            t = line.strip()
            if re.fullmatch(r"//\s*==Sculk==", t):
                inside = True
            elif re.fullmatch(r"//\s*==/Sculk==", t):
                break
            elif inside:
                m = re.match(r"^\s*//\s*@([A-Za-z][\w-]*)(?:\s+(.*?))?\s*$", line)
                if m:
                    out[m.group(1).lower()] = m.group(2) or ""
            elif t and not t.startswith("//"):
                break
        return out

    @staticmethod
    def hash(source):
        import hashlib
        return hashlib.sha256(source.encode()).hexdigest()[:24]

    def error(self, status, message):
        e = ValueError(message)
        e.status = status
        return e

    def get(self, name):
        if not name:
            raise self.error(400, "name is required")
        if name not in self.files:
            raise self.error(404, f"no script named {name}")
        return self.files[name]

    def check_name(self, name):
        if not name or not SCRIPT_NAME.fullmatch(name) or ".." in name:
            raise self.error(400, f"bad script name '{name}': use letters, digits, spaces and _ . ( ) + , -, ending in .js")

    def info(self, name):
        f = self.files[name]
        meta = self.meta(f["source"])
        out = {"name": name, "origin": "dir", "path": f"{SCRIPT_DIR}/{name}{'' if f['enabled'] else '.disabled'}",
               "enabled": f["enabled"], "run": "manual" if meta.get("run", "").strip() == "manual" else "startup",
               "title": meta.get("name", ""), "description": meta.get("description", ""), "meta": meta,
               "hash": self.hash(f["source"]), "size": len(f["source"]), "modified": int(time.time() * 1000),
               "status": self.status.get(name, {"state": "stopped", "why": "not-started"})}
        if meta.get("dist", "").strip() == "server":
            out["skip"] = "only for a dedicated server"
        return out

    def list(self):
        with self.lock:
            return {"dir": SCRIPT_DIR, "dist": "client", "scripts": [self.info(n) for n in sorted(self.files, key=str.lower)]}

    def source(self, name):
        with self.lock:
            self.get(name)
            return {**self.info(name), "source": self.files[name]["source"]}

    def problems(self, source):
        # Unbalanced brackets, roughly where Rhino would complain; "syntax error" lines on request.
        out, stack = [], []
        for i, line in enumerate(source.split("\n"), 1):
            if "SYNTAX" in line:
                out.append({"line": i, "column": line.index("SYNTAX") + 1, "message": "syntax error", "severity": "error"})
            for j, c in enumerate(line):
                if c in "([{":
                    stack.append((c, i, j + 1))
                elif c in ")]}":
                    if not stack or "([{"[")]}".index(c)] != stack[-1][0]:
                        out.append({"line": i, "column": j + 1, "message": f"syntax error: unexpected {c}", "severity": "error"})
                    else:
                        stack.pop()
        if stack:
            c, i, j = stack[-1]
            out.append({"line": i, "column": j, "message": f"missing closing bracket for {c}", "severity": "error"})
        return out

    def check(self, source):
        p = self.problems(source)
        return {"ok": not any(x["severity"] == "error" for x in p), "problems": p}

    def audit(self, name, action, to=None):
        self.store.add("script_file", {"script": name, "action": action, "by": "api", **({"to": to} if to else {})})

    def state(self, name):
        return self.status.get(name, {"state": "stopped", "why": "not-started"})["state"]

    def startup(self, name):
        i = self.info(name)
        return i["enabled"] and i["run"] == "startup" and "skip" not in i

    def start(self, name, cause="user"):
        """Lifecycle.java's Start, or its refusal of a source that does not compile."""
        src = self.files[name]["source"]
        h, now = self.hash(src), int(time.time() * 1000)
        p = [x for x in self.problems(src) if x["severity"] == "error"]
        if p:
            err = f"{p[0]['message']} ({name}:{p[0]['line']})"
            cur = self.status.get(name, {})
            if cur.get("state") == "running":
                cur["rejected"] = {"hash": h, "error": err}
                self.store.add("script_error", {"script": name, "callback": "compile", "cause": cause, "error": err, "kept": True})
                return {"ok": False, "error": err, "kept": "the previous version is still running"}
            self.status[name] = {"state": "failed", "hash": h, "error": err, "at": now}
            self.store.add("script_error", {"script": name, "callback": "compile", "cause": cause, "error": err})
            return {"ok": False, "error": err}
        self.stop_quietly(name, cause)
        if "throw " in src:
            err = f"Error: {src.split('throw ', 1)[1].splitlines()[0]} ({name}:1)"
            self.status[name] = {"state": "failed", "hash": h, "error": err, "at": now}
            self.store.add("script_error", {"script": name, "callback": "start", "cause": cause, "error": err})
            return {"ok": False, "error": err}
        self.status[name] = {"state": "running", "hash": h, "since": now, "listeners": src.count("sculk.on("), "timers": 0}
        self.store.add("script_run", {"script": name, "cause": cause})
        for m in re.finditer(r"^sculk\.log\('([^']*)'\)", src, re.M):
            self.store.add("script_log", {"script": name, "message": m.group(1)})
        last = [l.strip().rstrip(";") for l in src.splitlines() if l.strip() and not l.strip().startswith("//")]
        result = None
        if last and re.fullmatch(r"\d+", last[-1]):
            result = int(last[-1])
        return {"ok": True, "result": result}

    def stop_quietly(self, name, cause, why=None):
        if self.state(name) == "running":
            self.store.add("script_stop", {"script": name, "cause": cause})
        if why:
            self.status[name] = {"state": "stopped", "why": why}

    def save(self, name, source, base):
        with self.lock:
            created = name not in self.files
            if not created:
                if base is not None:
                    if base == "":
                        raise self.error(409, f"{name} already exists")
                    if base != self.hash(self.files[name]["source"]):
                        raise self.error(409, f"{name} was changed by someone else")
                self.files[name]["source"] = source
            else:
                if base:
                    raise self.error(409, f"{name} was deleted by someone else")
                self.check_name(name)
                self.files[name] = {"source": source, "enabled": True}
            self.audit(name, "create" if created else "save")
            out = {"created": created}
            st = self.status.get(name, {})
            # Lifecycle.fileChanged: a wanted script follows its file; a new startup script starts.
            if st.get("state") in ("running", "failed") and st.get("hash") != self.hash(source):
                out["restart"] = self.start(name, "file")
            elif created and self.startup(name):
                out["restart"] = self.start(name, "file")
            return {**out, **self.info(name)}

    def run(self, name):
        with self.lock:
            self.get(name)
            r = self.start(name)
            return {**r, **self.info(name)}

    def stop(self, name):
        with self.lock:
            if self.state(name) == "stopped":
                raise self.error(409, f"{name} is not running")
            self.stop_quietly(name, "user", "user")
            return {"ok": True, "name": name, "status": self.status[name]}

    def delete(self, name):
        with self.lock:
            self.get(name)
            self.stop_quietly(name, "file")
            del self.files[name]
            self.status.pop(name, None)
            self.audit(name, "delete")
            return {"ok": True, "name": name}

    def rename(self, name, to):
        with self.lock:
            self.get(name)
            self.check_name(to)
            if to in self.files and to.lower() != name.lower():
                raise self.error(409, f"{to} already exists")
            wanted = self.state(name) != "stopped"
            old = self.status.pop(name, None)
            if old and old["state"] == "running":
                self.store.add("script_stop", {"script": name, "cause": "file"})
            self.files[to] = self.files.pop(name)
            self.audit(name, "rename", to)
            if not wanted:
                if old:
                    self.status[to] = old
                return self.info(to)
            return {"restart": self.start(to, "file"), **self.info(to)}

    def enable(self, name, enabled):
        with self.lock:
            f = self.get(name)
            out = {}
            if f["enabled"] != enabled:
                f["enabled"] = enabled
                self.audit(name, "enable" if enabled else "disable")
                if not enabled:
                    self.stop_quietly(name, "file", "disabled")
                elif self.state(name) == "stopped":
                    if self.startup(name):
                        out["restart"] = self.start(name, "file")
                    else:
                        self.status[name] = {"state": "stopped", "why": "not-started"}
            return {**out, **self.info(name)}

    def reload(self):
        with self.lock:
            for n in list(self.status):
                self.stop_quietly(n, "launch")
            self.status = {}
            loaded, errors, skipped = [], [], []
            for n in sorted(self.files, key=str.lower):
                i = self.info(n)
                if not i["enabled"]:
                    self.status[n] = {"state": "stopped", "why": "disabled"}
                    continue
                if "skip" in i:
                    self.status[n] = {"state": "stopped", "why": "wrong-dist"}
                    skipped.append({"script": n, "reason": i["skip"]})
                    continue
                if i["run"] == "manual":
                    continue
                r = self.start(n, "launch")
                (loaded.append(n) if r["ok"] else errors.append({"script": n, "error": r["error"]}))
            return {"ok": not errors, "loaded": loaded, "skipped": skipped, "errors": errors}

    def running_names(self):
        with self.lock:
            return [n for n, st in self.status.items() if st["state"] == "running"]


def varint(n):
    out = bytearray()
    n &= 0xFFFFFFFF
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def mc_string(s):
    b = s.encode()
    return varint(len(b)) + b


def hexs(b, limit):
    h = b[:limit].hex()
    return h + ("..." if len(b) > limit else "")


# (type, packetId, flow, size range)
VANILLA = {
    "inbound": [
        ("minecraft:keep_alive", 0x26, (9, 9), 2),
        ("minecraft:set_time", 0x64, (17, 17), 4),
        ("minecraft:move_entity_pos", 0x2E, (10, 12), 30),
        ("minecraft:move_entity_pos_rot", 0x2F, (12, 14), 25),
        ("minecraft:rotate_head", 0x48, (5, 7), 20),
        ("minecraft:set_entity_motion", 0x5A, (9, 11), 15),
        ("minecraft:set_entity_data", 0x58, (8, 60), 10),
        ("minecraft:level_chunk_with_light", 0x27, (4000, 24000), 2),
        ("minecraft:block_update", 0x09, (11, 13), 4),
        ("minecraft:sound", 0x68, (25, 40), 3),
        ("minecraft:system_chat", 0x6C, (40, 180), 1),
        ("minecraft:container_set_slot", 0x15, (12, 80), 2),
        ("minecraft:update_attributes", 0x75, (30, 120), 2),
        ("minecraft:bundle_delimiter", 0x00, (1, 1), 2),
    ],
    "outbound": [
        ("minecraft:keep_alive", 0x18, (9, 9), 2),
        ("minecraft:move_player_pos_rot", 0x1B, (34, 34), 30),
        ("minecraft:move_player_pos", 0x1A, (26, 26), 20),
        ("minecraft:client_tick_end", 0x0B, (1, 1), 40),
        ("minecraft:swing", 0x36, (2, 2), 3),
        ("minecraft:use_item_on", 0x38, (20, 22), 1),
        ("minecraft:player_action", 0x24, (12, 14), 2),
        ("minecraft:container_click", 0x0E, (14, 60), 1),
    ],
}

WAYSTONES = ["Spawn", "Base", "Nether Hub", "Mesa Outpost", "../../../etc/passwd", "Ocean Monument"]


def mod_payloads():
    """(channel, owner, registeredVia, dir, payloadClass, weight, payload-maker)"""
    r = random.random
    return [
        ("waystones:waystone_teleport", "waystones", ["waystones"], "outbound",
         "net.blay09.mods.waystones.network.message.TeleportToWaystoneMessage", 1,
         lambda: {"$class": "net.blay09.mods.waystones.network.message.TeleportToWaystoneMessage",
                  "waystoneUid": "6f1c1f0e-3b7e-4a43-9c22-%012x" % random.getrandbits(48),
                  "warpMode": random.choice(["INVENTORY_BUTTON", "WAYSTONE_TO_WAYSTONE", "WARP_STONE"]),
                  "hand": "MAIN_HAND", "fromWaystone": None}),
        ("waystones:player_waystones", "waystones", ["waystones"], "inbound",
         "net.blay09.mods.waystones.network.message.KnownWaystonesMessage", 1,
         lambda: {"$class": "net.blay09.mods.waystones.network.message.KnownWaystonesMessage",
                  "waystoneType": {"$class": "net.minecraft.resources.ResourceLocation", "namespace": "waystones", "path": "waystone"},
                  "waystones": [{"$class": "net.blay09.mods.waystones.core.Waystone",
                                 "name": n, "dimension": "minecraft:overworld",
                                 "pos": {"x": random.randint(-3000, 3000), "y": random.randint(40, 120), "z": random.randint(-3000, 3000)},
                                 "global": r() < 0.3} for n in random.sample(WAYSTONES, 3)]}),
        ("create:contraption_motion", "create", ["create"], "inbound",
         "com.simibubi.create.content.contraptions.ContraptionMotionPacket", 12,
         lambda: {"$class": "com.simibubi.create.content.contraptions.ContraptionMotionPacket",
                  "entityID": random.randint(100, 900), "motion": {"x": round(r() - .5, 4), "y": 0.0, "z": round(r() - .5, 4)},
                  "angle": round(r() * 360, 2), "seated": False}),
        ("create:sync_tile_entity", "create", ["create"], "inbound",
         "com.simibubi.create.foundation.networking.BlockEntityDataPacket", 6,
         lambda: {"$class": "com.simibubi.create.foundation.networking.BlockEntityDataPacket",
                  "pos": {"x": random.randint(-200, 200), "y": 64, "z": random.randint(-200, 200)},
                  "tag": {"Speed": round(r() * 256, 1), "Network": random.getrandbits(32)}}),
        ("ae2:gui_data_sync", "ae2", ["ae2"], "inbound",
         "appeng.core.network.clientbound.GuiDataSyncPacket", 3,
         lambda: {"$class": "appeng.core.network.clientbound.GuiDataSyncPacket",
                  "containerId": random.randint(1, 9), "data": {"$bytes": random.randbytes(random.randint(4, 40)).hex()}}),
        ("ae2:inventory_action", "ae2", ["ae2"], "outbound",
         "appeng.core.network.serverbound.InventoryActionPacket", 1,
         lambda: {"$class": "appeng.core.network.serverbound.InventoryActionPacket",
                  "action": random.choice(["PICKUP_OR_SET_DOWN", "SHIFT_CLICK", "CRAFT_ITEM", "AUTO_CRAFT"]),
                  "slot": random.randint(0, 80), "id": random.randint(-1, 4)}),
        ("sophisticatedbackpacks:backpack_contents", "sophisticatedbackpacks", ["sophisticatedcore", "sophisticatedbackpacks"], "inbound",
         "net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackContentsPayload", 1,
         lambda: {"$class": "net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackContentsPayload",
                  "backpackUuid": "a2b4%04x-%04x-4%03x-%04x-%012x" % (random.getrandbits(16), random.getrandbits(16), random.getrandbits(12), random.getrandbits(16), random.getrandbits(48)),
                  "backpackContents": {"inventory": {"Items": [{"Slot": i, "id": random.choice(["minecraft:diamond", "minecraft:cobblestone", "create:andesite_alloy"]), "count": random.randint(1, 64)} for i in range(random.randint(1, 5))]}}}),
        ("jei:recipe_transfer", "jei", ["jei"], "outbound",
         "mezz.jei.common.network.packets.PacketRecipeTransfer", 1,
         lambda: {"$class": "mezz.jei.common.network.packets.PacketRecipeTransfer",
                  "craftingSlots": [1, 2, 3, 4, 5], "inventorySlots": [10, 11, 36], "maxTransfer": r() < 0.5, "requireCompleteSets": True}),
        ("neoforge:register", "neoforge", ["neoforge"], "outbound",
         "net.neoforged.neoforge.network.payload.MinecraftRegisterPayload", 0, None),
    ]


def payload_types():
    """The mod layers of payload_type events (Sculk.payloadTypes)."""
    return [{"channel": ch, "owner": o, "registeredVia": v, "javaClass": cls, "codecClass": cls + "$1",
             "protocol": "play", "flow": "clientbound" if d == "inbound" else "serverbound", "discriminator": None}
            for ch, o, v, d, cls, _w, _p in mod_payloads()]


def varint_at(b, off):
    """(value, length) of the VarInt at off, or (None, -1)."""
    value = 0
    for i in range(5):
        if off + i >= len(b):
            return None, -1
        value |= (b[off + i] & 0x7F) << (7 * i)
        if not b[off + i] & 0x80:
            return value, i + 1
    return None, -1


def spans(frame, channel):
    """FrameLayer.spans in Python: packet.id, then mod.channel + mod.data, or packet.body."""
    out = []
    _, n = varint_at(frame, 0)
    if n < 0:
        return out
    out.append({"field": "packet.id", "off": 0, "len": n})
    pos = n
    if channel:
        strlen, prefix = varint_at(frame, pos)
        name = channel.encode()
        if prefix > 0 and strlen == len(name) and frame[pos + prefix:pos + prefix + strlen] == name:
            out.append({"field": "mod.channel", "off": pos, "len": prefix + strlen})
            pos += prefix + strlen
            if pos < len(frame):
                out.append({"field": "mod.data", "off": pos, "len": len(frame) - pos})
            return out
    if pos < len(frame):
        out.append({"field": "packet.body", "off": pos, "len": len(frame) - pos})
    return out


def encode_tree(v):
    """Something that looks like a mod's own binary encoding of a payload tree."""
    import struct
    if isinstance(v, dict):
        if "$bytes" in v:
            b = bytes.fromhex(v["$bytes"])
            return varint(len(b)) + b
        return b"".join(encode_tree(x) for k, x in v.items() if k != "$class")
    if isinstance(v, list):
        return varint(len(v)) + b"".join(encode_tree(x) for x in v)
    if isinstance(v, bool):
        return bytes([v])
    if isinstance(v, int):
        return varint(v)
    if isinstance(v, float):
        return struct.pack(">d", v)
    if v is None:
        return b"\x00"
    return mc_string(str(v))


class Game:
    def __init__(self, store, rate):
        self.store = store
        self.rate = rate
        self.paused = False
        self.conn = 0
        self.open = []  # [(id, remote)]
        self.closed = []
        self.seq = 0
        self.mods = [m for m in mod_payloads() if m[5] > 0]
        self.lock = threading.Lock()
        self.drops = []  # (dir, field, value) from sculk.on('packet', ...) drop snippets
        self.scripts = MockScripts(store)

    # -- events

    def start(self):
        self.store.add("start", {"platform": PLATFORM})
        for m in payload_types():
            self.store.add("payload_type", {"mod": m})
        self.scripts.reload()
        self.store.add("script_log", {"script": "log-waystones.js", "message": "watching waystones:*"})
        self.connect()
        for _ in range(1500):
            self.tick_one()

    def connect(self):
        with self.lock:
            self.conn += 1
            remote = f"mc.example.net/203.0.113.{random.randint(2, 250)}:25565"
            self.open.append((self.conn, remote))
            cid = self.conn
        self.store.add("connection", {"conn": {"id": cid, "side": "client", "remote": remote,
                                               "pipeline": ["timeout", "splitter", "decompress", "decoder", "prepender", "compress", "encoder", "packet_handler"]}})
        for state, t, pid, d in [("handshake", "minecraft:intention", 0, "outbound"),
                                 ("login", "minecraft:hello", 0, "outbound"),
                                 ("login", "minecraft:login_compression", 3, "inbound"),
                                 ("login", "minecraft:login_finished", 2, "inbound"),
                                 ("configuration", "minecraft:select_known_packs", 0x0E, "inbound"),
                                 ("configuration", "minecraft:registry_data", 0x07, "inbound"),
                                 ("configuration", "minecraft:finish_configuration", 0x03, "inbound"),
                                 ("play", "minecraft:login", 0x2B, "inbound")]:
            self.vanilla(cid, d, t, pid, random.randint(8, 400), state=state)

    def disconnect(self):
        with self.lock:
            if not self.open:
                return None
            cid, remote = self.open.pop()
            self.closed.append((cid, remote))
        self.store.add("disconnect", {"conn": {"id": cid, "side": "client", "remote": remote}})
        return cid

    def conn_layer(self, cid):
        remote = next((r for c, r in self.open + self.closed if c == cid), "?")
        return {"id": cid, "side": "client", "remote": remote}

    def packet_event(self, cid, d, state, t, frame, cls, owner="minecraft", channel=None, injected=False, action="pass", mod=None):
        """A packet event shaped as Sculk.java logs it (see PacketEvent.toJson)."""
        if any(d == rd and (channel if rf == "channel" else t) == rv for rd, rf, rv in self.drops):
            action = "drop"
        self.seq += 1
        e = {
            "conn": self.conn_layer(cid),
            "frame": {"seq": self.seq, "dir": d, "len": len(frame),
                      "hex": hexs(frame, LOG_FRAME_BYTES if channel else 256), "spans": spans(frame, channel)},
            "packet": {"id": varint_at(frame, 0)[0], "state": state,
                       "flow": "clientbound" if d == "inbound" else "serverbound", "type": t,
                       "javaClass": cls, "owner": owner},
        }
        if mod is not None:
            e["mod"] = mod
        e["injected"] = injected
        e["action"] = action
        self.store.add("packet", e, frame)

    def vanilla(self, cid, d, t, pid, size, state="play", **kw):
        frame = varint(pid) + random.randbytes(max(0, size - len(varint(pid))))
        name = "".join(p.title() for p in t.split(":")[1].split("_"))
        side = "Clientbound" if d == "inbound" else "Serverbound"
        self.packet_event(cid, d, state, t, frame, f"net.minecraft.network.protocol.game.{side}{name}Packet", **kw)

    def payload(self, cid, mod, injected=False, action="pass", roundtrip=None):
        ch, owner, via, d, cls, _w, make = mod
        tree = make()
        pid = 0x19 if d == "inbound" else 0x14
        frame = varint(pid) + mc_string(ch) + encode_tree(tree)
        if roundtrip is None:
            x = random.random()
            roundtrip = "ok" if x < 0.9 else "equivalent" if x < 0.985 else \
                "mismatch: payload " + random.randbytes(24).hex()
        layer = {"channel": ch, "owner": owner, "registeredVia": via, "javaClass": cls, "roundTrip": roundtrip, "data": tree}
        side = "Clientbound" if d == "inbound" else "Serverbound"
        self.packet_event(cid, d, "play", "minecraft:custom_payload", frame,
                          f"net.minecraft.network.protocol.common.{side}CustomPayloadPacket",
                          channel=ch, injected=injected, action=action, mod=layer)

    def tick_one(self):
        with self.lock:
            if not self.open:
                return
            cid = self.open[-1][0]
        x = random.random()
        if x < 0.82:
            d = "inbound" if random.random() < 0.6 else "outbound"
            choices = VANILLA[d]
            t, pid, (lo, hi), _ = random.choices(choices, weights=[c[3] for c in choices])[0]
            action = "pass"
            if t == "minecraft:move_player_pos" and random.random() < 0.05:
                action = random.choice(["drop", "replace"])
            self.vanilla(cid, d, t, pid, random.randint(lo, hi), action=action)
        elif x < 0.985:
            mod = random.choices(self.mods, weights=[m[5] for m in self.mods])[0]
            action = "pass"
            if mod[0].startswith("sophisticatedbackpacks") and random.random() < 0.3:
                action = "replace"
            self.payload(cid, mod, action=action)
        elif x < 0.992:
            mod = next(m for m in self.mods if m[0] == "waystones:waystone_teleport")
            self.payload(cid, mod, injected=True)
            self.store.add("script", {"script": "log-waystones.js",
                                      "data": {"injected": mod[0], "note": "teleport without a known waystone"}})
        elif x < 0.997:
            self.store.add("script", {"script": "fuzz-backpacks.js", "data": random.choice([
                "backpack fuzz round %d" % random.randint(1, 400),
                {"step": "done", "took": random.randint(3, 900)},
                {"waystone": random.choice(WAYSTONES), "dimension": "minecraft:the_nether"}])})
        else:
            self.store.add("script_error", {
                "script": "fuzz-backpacks.js", "callback": "packet", "packet": "PacketEvent[inbound sophisticatedbackpacks:backpack_contents]",
                "error": "org.mozilla.javascript.EcmaError: TypeError: Cannot read property \"Items\" from undefined (fuzz-backpacks.js#42)"})

    def run(self):
        while True:
            if self.paused or self.rate <= 0:
                time.sleep(0.2)
                continue
            # Bursty, like a real game: quiet stretches, then a flurry.
            n = max(1, int(random.expovariate(1 / max(1, self.rate / 10))))
            for _ in range(n):
                self.tick_one()
            time.sleep(n / self.rate)

    # -- the toy REPL

    def eval(self, code):
        self.store.add("eval", {"source": code[:4096] + ("..." if len(code) > 4096 else "")})
        src = re.sub(r"^\s*//.*$", "", code, flags=re.M).strip().rstrip(";")
        # The web UI's "Replay…" snippet: inject a frame again.
        m = re.fullmatch(r"sculk\.connection\((\d+)\)\.inject\('(\w+)', sculk\.unhex\('([0-9a-f]*)'\)\)", src)
        if m:
            cid, d, h = int(m.group(1)), m.group(2), m.group(3)
            if not any(c == cid for c, _ in self.open):
                return {"ok": False, "error": "TypeError: Cannot call method \"inject\" of null (repl:1)"}
            original = next((e for e in reversed(self.store.events) if e[3] is not None and e[3].hex() == h), None)
            if original is None:
                return {"ok": False, "error": "mock backend: replay only knows frames it made"}
            e = json.loads(original[2])
            for k in ("id", "kind", "t"):
                e.pop(k)
            self.seq += 1
            e["frame"]["seq"] = self.seq
            e["frame"]["dir"] = d
            e["injected"] = True
            e["action"] = "pass"
            self.store.add("packet", e, original[3])
            return {"ok": True, "result": None}
        # The web UI's "Drop packets like this…" snippet.
        m = re.fullmatch(r"sculk\.on\('packet', e => \{\s*if \(e\.dir === '(\w+)' && e\.(channel|type) === '([^']+)'\) e\.drop\(\);\s*\}\)", src)
        if m:
            self.drops.append((m.group(1), m.group(2), m.group(3)))
            return {"ok": True, "result": None}
        m = re.fullmatch(r"mock\.(\w+)\((.*)\)", src, re.S)
        if m:
            name, arg = m.group(1), m.group(2).strip()
            if name == "burst":
                for _ in range(int(arg or 200)):
                    self.tick_one()
                return {"ok": True, "result": f"added {int(arg or 200)} events"}
            if name == "disconnect":
                cid = self.disconnect()
                return {"ok": True, "result": f"closed #{cid}" if cid else "no open connection"}
            if name == "connect":
                self.connect()
                return {"ok": True, "result": f"opened #{self.conn}"}
            if name == "rate":
                self.rate = float(arg)
                return {"ok": True, "result": self.rate}
            if name in ("pause", "resume"):
                self.paused = name == "pause"
                return {"ok": True, "result": name + "d"}
        if src == "sculk.connections()":
            return {"ok": True, "result": [f"SculkConnection[#{c} client {r} open]" for c, r in self.open]}
        if src == "sculk.payloadTypes()":
            return {"ok": True, "result": payload_types()}
        m = re.fullmatch(r"sculk\.(log|emit)\((.*)\)", src, re.S)
        if m:
            arg = m.group(2).strip()
            try:
                data = json.loads(arg.replace("'", '"'))
            except ValueError:
                data = arg
            self.store.add("script_log" if m.group(1) == "log" else "script",
                           {"script": "console", **({"message": str(data)} if m.group(1) == "log" else {"data": data})})
            return {"ok": True, "result": None}
        if re.fullmatch(r"[\d\s+\-*/%().]+", src):
            try:
                return {"ok": True, "result": eval(src, {"__builtins__": {}})}  # digits and operators only
            except Exception as e:
                return {"ok": False, "error": f"SyntaxError: {e} (<repl>:1)"}
        if re.fullmatch(r"(['\"]).*\1", src, re.S):
            return {"ok": True, "result": src[1:-1]}
        if src.startswith("throw"):
            return {"ok": False, "error": f"Error: {src[5:].strip()} (<repl>:1)"}
        if src in ("undefined", "") or src.startswith(("let ", "const ", "var ", "function ", "sculk.on(")):
            return {"ok": True, "result": None}
        if src.endswith("("):
            return {"ok": False, "error": "SyntaxError: syntax error (<repl>#1)"}
        return {"ok": False, "error": f"ReferenceError: \"{src.split('(')[0].split('.')[0]}\" is not defined. "
                                      "(mock backend: try sculk.connections(), sculk.log(\"hi\"), 1+2, mock.burst(500))"}

    def reload(self):
        self.drops.clear()
        result = self.scripts.reload()
        self.store.add("script_reload", result)
        return result

    def status(self):
        with self.lock:
            conns = [{"id": c, "side": "client", "remote": r, "open": True} for c, r in self.open]
            conns += [{"id": c, "side": "client", "remote": r, "open": False} for c, r in self.closed[-3:]]
        return {"platform": PLATFORM, "pid": 424242, "events": len(self.store.events),
                "lastEventId": self.store.last_id, "scripts": self.scripts.running_names(), "connections": sorted(conns, key=lambda c: c["id"])}


# ---------------------------------------------------------------- the UI files


class Bundle:
    """app.js, rebuilt by esbuild when web/src changes."""

    def __init__(self, fonts):
        self.fonts = fonts
        self.lock = threading.Lock()
        self.stamp = None
        self.js = b""
        self.error = None

    def get(self):
        with self.lock:
            stamp = max((p.stat().st_mtime_ns for p in (WEB / "src").glob("*.ts")), default=0)
            if stamp != self.stamp:
                r = subprocess.run(["esbuild", str(WEB / "src/main.ts"), "--bundle", "--sourcemap=inline", "--target=es2022"],
                                   capture_output=True)
                self.stamp = stamp
                if r.returncode == 0:
                    self.js, self.error = r.stdout, None
                else:
                    self.error = r.stderr.decode()
                    print(self.error, file=sys.stderr)
            if self.error:
                msg = json.dumps("esbuild failed:\n" + self.error)
                return f"document.body.innerHTML='<pre style=\"color:#f66;padding:1em;white-space:pre-wrap\"></pre>';document.body.firstChild.textContent={msg};".encode()
            return self.js


CSP = ("default-src 'none'; script-src 'self'; style-src 'self' {nonce}; img-src 'self' data:; "
       "font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")

TYPES = {"html": "text/html; charset=utf-8", "css": "text/css; charset=utf-8", "js": "text/javascript; charset=utf-8",
         "svg": "image/svg+xml", "woff2": "font/woff2", "txt": "text/plain; charset=utf-8", "map": "application/json"}


def make_handler(store, game, bundle):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send(self, status, ctype, body, extra=None):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def json(self, status, obj):
            self.send(status, "application/json", json.dumps(obj).encode())

        def do_GET(self):
            self.route("GET")

        def do_POST(self):
            self.route("POST")

        def route(self, method):
            url = urllib.parse.urlsplit(self.path)
            q = dict(urllib.parse.parse_qsl(url.query, keep_blank_values=True))
            path = url.path
            if method == "GET" and not path.startswith("/api/") and path != "/mcp":
                name = path[1:]
                # The page's own paths load index.html, as on the real server.
                if name in ("", "events", "scripts") or name.startswith("scripts/"):
                    name = "index.html"
                return self.static(name)
            # The API's endpoints, as below, without their /api prefix.
            path = path[4:] if path.startswith("/api/") else ""
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
            if self.headers.get("Authorization", "") != f"Bearer {TOKEN}":
                return self.json(401, {"error": "missing or wrong bearer token"})
            # A little latency, so loading states are visible.
            time.sleep(random.uniform(0.02, 0.12))
            try:
                key = f"{method} {path}"
                if key == "GET /status":
                    return self.json(200, game.status())
                if key == "GET /events":
                    pred = compile_filter(q.get("filter"))
                    after = int(q["after"]) if q.get("after") else None
                    out = "".join(e[2] + "\n" for e in store.query(pred, after, int(q.get("limit", 200))))
                    return self.send(200, "application/x-ndjson", out.encode())
                if key == "GET /events/page":
                    src = (q.get("filter") or "").strip()
                    pred = compile_filter(src)
                    return self.scanning(src, lambda: store.page(src, pred, int(q.get("from") or 0), min(int(q.get("count") or 200), 2000)))
                if key == "GET /events/position":
                    src = (q.get("filter") or "").strip()
                    if not q.get("id"):
                        raise ValueError("id is required")
                    pred = compile_filter(src)
                    return self.scanning(src, lambda: json.dumps(store.position(src, pred, int(q["id"]))))
                if key == "GET /events/stream":
                    return self.stream(compile_filter(q.get("filter")), int(q["after"]) if q.get("after") else None)
                if key == "GET /frame":
                    if "id" not in q:
                        raise ValueError("id is required")
                    e = store.get(int(q["id"]))
                    if not e or e[3] is None:
                        return self.json(404, {"error": "no such event (it may have been dropped)" if not e else "event has no frame"})
                    return self.json(200, {"id": e[0], "len": len(e[3]), "hex": e[3].hex()})
                if key == "POST /eval":
                    time.sleep(random.uniform(0.05, 0.4))
                    return self.json(200, game.eval(body))
                if key == "POST /reload":
                    time.sleep(0.5)
                    return self.json(200, game.reload())
                sc, name = game.scripts, q.get("name")
                routes = {
                    "GET /scripts": lambda: sc.list(),
                    "GET /scripts/source": lambda: sc.source(name),
                    "POST /scripts/save": lambda: sc.save(name, body, q.get("base")),
                    "POST /scripts/check": lambda: sc.check(body),
                    "POST /scripts/run": lambda: sc.run(name),
                    "POST /scripts/stop": lambda: sc.stop(name),
                    "POST /scripts/delete": lambda: sc.delete(name),
                    "POST /scripts/rename": lambda: sc.rename(name, q.get("to")),
                    "POST /scripts/enable": lambda: sc.enable(name, q.get("enabled", "true") != "false"),
                }
                if key in routes:
                    try:
                        return self.json(200, routes[key]())
                    except ValueError as e:
                        return self.json(getattr(e, "status", 400), {"error": str(e)})
                known = path in ("/status", "/events", "/events/stream", "/events/page", "/events/position", "/frame", "/eval", "/reload") or \
                    any(r.split(" ", 1)[1] == path for r in routes)
                return self.json(405 if known else 404, {"error": f"wrong method for {path}" if known else f"no such endpoint {path}"})
            except ValueError as e:  # FilterError included
                return self.json(400, {"error": str(e)})

        def scanning(self, src, work):
            """Like ControlServer.scanning: SSE progress then the result, when asked for. A filter
            the mock has not scanned before that reads a deep field (mod.data, frame.hex) pretends
            to be slow, so the UI's progress shows."""
            if "text/event-stream" not in (self.headers.get("Accept") or ""):
                return self.send(200, "application/json", work().encode())
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            try:
                if src not in store.match_cache and re.search(r"mod\.data|frame\.hex", src):
                    total = 3_400_000
                    for i in range(1, 13):
                        time.sleep(0.2)
                        self.wfile.write(f"event: progress\ndata: {{\"done\":{total * i // 12},\"total\":{total}}}\n\n".encode())
                        self.wfile.flush()
                self.wfile.write(f"event: result\ndata: {work()}\n\n".encode())
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                print(f"scan of {src!r} stopped: the browser went away", file=sys.stderr)

        def static(self, name):
            # The real server's CSP (ControlServer.CSP), so the UI is tried under the same rules.
            csp = {"Content-Security-Policy": CSP.format(nonce="").replace("  ", " ")}
            if name == "app.js":
                return self.send(200, TYPES["js"], bundle.get(), csp)
            if not re.fullmatch(r"[a-zA-Z0-9._-]+", name):
                return self.send(404, "text/plain", b"not found\n")
            for base in filter(None, (WEB / "static", bundle.fonts)):
                p = base / name
                if p.is_file():
                    data = p.read_bytes()
                    if name == "index.html":
                        import base64, os
                        nonce = base64.b64encode(os.urandom(18)).decode()
                        data = data.replace(b"{{CSP_NONCE}}", nonce.encode())
                        csp = {"Content-Security-Policy": CSP.format(nonce=f"'nonce-{nonce}'")}
                    return self.send(200, TYPES.get(name.rsplit(".", 1)[-1], "application/octet-stream"), data, csp)
            return self.send(404, "text/plain", b"not found\n")

        def stream(self, pred, after):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            last = after if after is not None else store.last_id
            try:
                while True:
                    with store.cond:
                        store.cond.wait_for(lambda: store.last_id > last, timeout=10)
                    batch = store.query(lambda e: True, last, 10_000)
                    if not batch:
                        self.wfile.write(b": keepalive\n\n")
                    for e in batch:
                        last = e[0]
                        if pred(e[1]):
                            self.wfile.write(f"id: {e[0]}\ndata: {e[2]}\n\n".encode())
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

    return H


def find_fonts(explicit):
    if explicit:
        return Path(explicit)
    if shutil.which("nix"):
        print("building fonts: nix build .#web …", file=sys.stderr)
        r = subprocess.run(["nix", "build", f"{ROOT}#web", "--no-link", "--print-out-paths"], capture_output=True, text=True)
        if r.returncode == 0:
            return Path(r.stdout.strip().splitlines()[-1])
        print(r.stderr, file=sys.stderr)
    print("no fonts; the UI falls back to a system monospace font", file=sys.stderr)
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--rate", type=float, default=40, help="made-up events per second (default 40)")
    ap.add_argument("--fonts", help="a built web UI directory to take the fonts from (default: nix build .#web)")
    ap.add_argument("--seed", type=int)
    args = ap.parse_args()
    if not shutil.which("esbuild"):
        sys.exit("esbuild is not on PATH; run this under `nix develop`")
    if not (WEB / "node_modules").is_dir():
        sys.exit("web/node_modules is missing; run `npm ci` in web/ (under `nix develop`) first")
    if args.seed is not None:
        random.seed(args.seed)

    store = Store()
    game = Game(store, args.rate)
    game.start()
    threading.Thread(target=game.run, daemon=True).start()
    bundle = Bundle(find_fonts(args.fonts))
    bundle.get()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(store, game, bundle))
    server.daemon_threads = True
    print(f"mock control server: http://127.0.0.1:{args.port}/#token={TOKEN}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
