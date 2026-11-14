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
mock.pause() and mock.resume() steer the made-up traffic.

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

    def get(self, id_):
        with self.cond:
            for ev in self.events:
                if ev[0] == id_:
                    return ev
        return None


# ---------------------------------------------------------------- the made-up game

PLATFORM = "neoforge 21.1.77 · minecraft 1.21.1 · client"
SCRIPTS = ["sculk-scripts/autoload/log-waystones.js", "sculk-scripts/autoload/fuzz-backpacks.js"]


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

    # -- events

    def start(self):
        self.store.add("start", {"platform": PLATFORM})
        for m in payload_types():
            self.store.add("payload_type", {"mod": m})
        self.store.add("script", {"data": "log-waystones.js loaded; watching waystones:*"})
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
            self.store.add("script", {"data": {"injected": mod[0], "note": "teleport without a known waystone"}})
        elif x < 0.997:
            self.store.add("script", {"data": random.choice([
                "backpack fuzz round %d" % random.randint(1, 400),
                {"step": "done", "took": random.randint(3, 900)},
                {"waystone": random.choice(WAYSTONES), "dimension": "minecraft:the_nether"}])})
        else:
            self.store.add("script_error", {
                "callback": "on(packet)", "packet": "PacketEvent[inbound sophisticatedbackpacks:backpack_contents]",
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
            self.store.add("script", {"data": data})
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
        ok = random.random() < 0.8
        result = {"ok": ok, "loaded": SCRIPTS if ok else SCRIPTS[:1],
                  "errors": [] if ok else [{"script": SCRIPTS[1], "error": "SyntaxError: missing ; before statement (fuzz-backpacks.js#17)"}]}
        self.store.add("script_reload", result)
        return result

    def status(self):
        with self.lock:
            conns = [{"id": c, "side": "client", "remote": r, "open": True} for c, r in self.open]
            conns += [{"id": c, "side": "client", "remote": r, "open": False} for c, r in self.closed[-3:]]
        return {"platform": PLATFORM, "pid": 424242, "events": len(self.store.events),
                "lastEventId": self.store.last_id, "scripts": SCRIPTS, "connections": sorted(conns, key=lambda c: c["id"])}


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
            if method == "GET" and path in ("/", "/ui"):
                return self.send(302, "text/plain", b"", {"Location": "/ui/"})
            if method == "GET" and path.startswith("/ui/"):
                return self.static(path[4:] or "index.html")
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
                known = path in ("/status", "/events", "/events/stream", "/frame", "/eval", "/reload")
                return self.json(405 if known else 404, {"error": f"wrong method for {path}" if known else f"no such endpoint {path}"})
            except ValueError as e:  # FilterError included
                return self.json(400, {"error": str(e)})

        def static(self, name):
            if name == "app.js":
                return self.send(200, TYPES["js"], bundle.get())
            if not re.fullmatch(r"[a-zA-Z0-9._-]+", name):
                return self.send(404, "text/plain", b"not found\n")
            for base in filter(None, (WEB / "static", bundle.fonts)):
                p = base / name
                if p.is_file():
                    return self.send(200, TYPES.get(name.rsplit(".", 1)[-1], "application/octet-stream"), p.read_bytes())
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
    print(f"mock control server: http://127.0.0.1:{args.port}/ui/#token={TOKEN}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
