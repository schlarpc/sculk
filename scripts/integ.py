#!/usr/bin/env python3
"""End-to-end test of one platform against real mods.

Starts a dev server and a headless dev client (software GL under Xvfb), both
running sculk plus the platform's integration mods (-Psculk.integ). Each
process runs a test script from tests/integ/ and writes a JSON Lines event
log. The client joins the server via Quick Play; when its script reports
"done", both are stopped and the logs are checked.

Usage: scripts/integ.py <platform> [--timeout SECONDS]
Run inside `nix develop`.
"""
import argparse
import collections
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def read_jsonl(path: Path) -> list:
    if not path.exists():
        return []
    out = []
    for line in path.read_text().splitlines():
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return out


def wait_for(predicate, timeout, procs, what):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return
        for name, p in procs.items():
            if p.poll() is not None:
                raise SystemExit(f"{name} exited early (code {p.returncode}) while waiting for {what}")
        time.sleep(1)
    raise SystemExit(f"timed out after {timeout}s waiting for {what}")


def gradle(platform, task, props, log, env=None, integ=True):
    cmd = ["gradle", "--console=plain", f":platforms:{platform}:{task}"]
    if integ:
        cmd.append("-Psculk.integ=true")
    cmd += [f"-P{k}={v}" for k, v in props.items()]
    return subprocess.Popen(
        cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        env=env, start_new_session=True)


def stop(p):
    if p and p.poll() is None:
        try:
            os.killpg(p.pid, signal.SIGTERM)
            p.wait(20)
        except Exception:
            os.killpg(p.pid, signal.SIGKILL)


def start_xvfb():
    """Starts a private X server; returns (process, display)."""
    for n in range(90, 140):
        if not Path(f"/tmp/.X11-unix/X{n}").exists() and not Path(f"/tmp/.X{n}-lock").exists():
            p = subprocess.Popen(["Xvfb", f":{n}", "-screen", "0", "1280x720x24", "-nolisten", "tcp"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            time.sleep(1)
            if p.poll() is None:
                return p, f":{n}"
    raise SystemExit("could not start Xvfb")


def screenshot(display, path):
    subprocess.run(f"xwd -root -display {display} | magick xwd:- {path}", shell=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if path.exists():
        print(f"[integ] client screenshot: {path}")


def software_gl_env():
    env = dict(os.environ)
    mesa = env.get("SCULK_MESA")
    if not mesa:
        raise SystemExit("SCULK_MESA is not set; run inside `nix develop`")
    env["__GLX_VENDOR_LIBRARY_NAME"] = "mesa"
    env["LIBGL_ALWAYS_SOFTWARE"] = "1"
    env["LIBGL_DRIVERS_PATH"] = f"{mesa}/lib/dri"
    env["LD_LIBRARY_PATH"] = f"{mesa}/lib:" + env.get("LD_LIBRARY_PATH", "")
    icds = sorted((Path(mesa) / "share/vulkan/icd.d").glob("lvp_icd.*.json"))
    if icds:
        env["VK_ICD_FILENAMES"] = str(icds[0])
    return env


def seed_run_dirs(run: Path, port: int):
    """Writes server.properties (offline, flat, on `port`) and client options for a quiet headless join."""
    (run / "server").mkdir(parents=True, exist_ok=True)
    (run / "server" / "eula.txt").write_text("eula=true\n")
    (run / "server" / "server.properties").write_text(
        f"server-port={port}\nonline-mode=false\nlevel-type=minecraft\\:flat\n"
        "spawn-protection=0\nenforce-secure-profile=false\nsync-chunk-writes=false\n"
        # Newer versions whitelist by default.
        "white-list=false\nenforce-whitelist=false\n")
    # A fresh client opens the accessibility onboarding screen before Quick
    # Play, and its blurred background is very slow under software GL. Seed
    # options that skip it and keep rendering cheap.
    (run / "client").mkdir(parents=True, exist_ok=True)
    (run / "client" / "options.txt").write_text("\n".join([
        "onboardAccessibility:false", "menuBackgroundBlurriness:0", "renderDistance:2",
        "simulationDistance:5", "maxFps:20", "graphicsMode:0", "renderClouds:\"false\"",
        "particles:2", "narrator:0", "tutorialStep:none", "skipMultiplayerWarning:true",
        "joinedFirstServer:true", "pauseOnLostFocus:false", "soundCategory_master:0.0",
    ]) + "\n")


# ---------------------------------------------------------------- assertions

class Report:
    def __init__(self):
        self.results = []

    def check(self, name, ok, detail=""):
        self.results.append((name, bool(ok), detail))

    def print(self):
        for name, ok, detail in self.results:
            print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  -- {detail}" if detail and not ok else ""))
        return all(ok for _, ok, _ in self.results)


def check(server, client):
    r = Report()
    pk = lambda log: [e for e in log if e["kind"] == "packet"]
    script = lambda log, step: [e for e in log if e["kind"] == "script" and e["data"].get("step") == step]
    # Layers of a packet event (see docs/ARCHITECTURE.md, "Events").
    mod = lambda e: e.get("mod") or {}
    dir_ = lambda e: e["frame"]["dir"]
    hex_ = lambda e: e["frame"].get("hex")

    r.check("both sides started", any(e["kind"] == "start" for e in server) and any(e["kind"] == "start" for e in client))
    r.check("server saw a server-side connection", any(e["kind"] == "connection" and e["conn"]["side"] == "server" for e in server))
    r.check("client saw a client-side connection", any(e["kind"] == "connection" and e["conn"]["side"] == "client" for e in client))

    errors = [e for e in server + client if e["kind"] in ("script_error", "log_error")]
    r.check("no script errors", not errors, json.dumps(errors[:3]))

    # Registry and attribution.
    types = [e["mod"] for e in server if e["kind"] == "payload_type"]
    owners = collections.Counter(t["owner"] for t in types)
    r.check("server registry lists appleskin payloads", owners["appleskin"] > 0, dict(owners))
    r.check("server registry lists waystones payloads", owners["waystones"] > 0, dict(owners))
    # Waystones registers its payloads through Balm, a cross-loader library:
    # the owner must still be waystones, with balm reported as the intermediary.
    via = {tuple(t["registeredVia"]) for t in types if t["owner"] == "waystones"}
    r.check("waystones payloads attributed through balm", via and all("balm" in v for v in via), via)

    # Decoding with the mods' own codecs, and faithful re-encoding.
    apple_in = [e for e in pk(client) if dir_(e) == "inbound" and mod(e).get("owner") == "appleskin"]
    r.check("client captured inbound appleskin payloads", apple_in)
    r.check("appleskin payloads decoded to mod objects",
            apple_in and all(mod(e).get("javaClass") for e in apple_in),
            [mod(e).get("javaClass") for e in apple_in[:3]])
    custom = [e for e in pk(server) + pk(client) if mod(e).get("channel")]
    outcomes = collections.Counter(str(mod(e).get("roundTrip")).split(":")[0].split(" ")[0] for e in custom)
    bad = [(e["conn"]["side"], dir_(e), mod(e)["channel"], mod(e)["roundTrip"]) for e in custom
           if outcomes and str(mod(e).get("roundTrip")).split(":")[0] in ("mismatch", "error")]
    r.check(f"all {len(custom)} custom payloads re-encode faithfully {dict(outcomes)}", custom and not bad, bad[:5])
    apple_bad = [mod(e)["roundTrip"] for e in custom
                 if mod(e).get("owner") == "appleskin" and not str(mod(e)["roundTrip"]).startswith("ok")]
    r.check("appleskin payloads re-encode byte-for-byte", not apple_bad, apple_bad[:3])
    # A payload with no body (an empty "ack" message) has no mod.data span: the channel ends the frame.
    def split(e):
        spans = e["frame"]["spans"]
        fields = [s["field"] for s in spans]
        if fields == ["packet.id", "mod.channel", "mod.data"]:
            return True
        return fields == ["packet.id", "mod.channel"] and spans[1]["off"] + spans[1]["len"] == e["frame"]["len"]
    unsplit = [mod(e)["channel"] for e in custom if not split(e)]
    r.check("custom payload frames split into id, channel and data", not unsplit, unsplit[:3])

    # Wire integrity: every custom payload frame the client received was sent by the server.
    sent = collections.Counter(hex_(e) for e in pk(server) if dir_(e) == "outbound" and "mod" in e)
    got = collections.Counter(hex_(e) for e in pk(client) if dir_(e) == "inbound" and "mod" in e)
    missing = got - sent
    r.check("client inbound payload frames == server outbound frames", got and not missing,
            f"{sum(missing.values())} unmatched frames")

    # Server-side injection (raw replay of a mod payload) reached the client.
    replays = script(server, "server_replayed")
    r.check("server replayed an appleskin payload", replays)
    if replays:
        h = replays[0]["data"]["hex"]
        server_count = sum(1 for e in pk(server) if dir_(e) == "outbound" and hex_(e) == h)
        client_count = sum(1 for e in pk(client) if dir_(e) == "inbound" and hex_(e) == h)
        injected = [e for e in pk(server) if dir_(e) == "outbound" and hex_(e) == h and e.get("injected")]
        r.check("server recorded the replay as injected", injected)
        r.check("client received the replay", client_count >= 2 and client_count == server_count,
                f"server sent {server_count}, client got {client_count}")

    # Client-side injection (edited raw movement frame) reached the server.
    inj = script(client, "client_injected")
    r.check("client injected an edited movement frame", inj, json.dumps(script(client, "client_inject_skipped")))
    if inj:
        h = inj[0]["data"]["hex"]
        natural = [e for e in pk(client) if dir_(e) == "outbound" and hex_(e) == h and not e.get("injected")]
        arrived = [e for e in pk(server) if dir_(e) == "inbound" and hex_(e) == h]
        r.check("edited frame was not one the client sent itself", not natural)
        r.check("server decoded the injected frame",
                arrived and arrived[0]["packet"]["type"] == inj[0]["data"]["type"],
                f"{len(arrived)} matching frames")
    return r


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("platform")
    ap.add_argument("--timeout", type=int, default=600)
    args = ap.parse_args()

    run = ROOT / "platforms" / args.platform / "run"
    out = run / "integ"
    out.mkdir(parents=True, exist_ok=True)
    port = free_port()
    seed_run_dirs(run, port)
    server_log, client_log = out / "server.jsonl", out / "client.jsonl"
    (out / "client-failure.png").unlink(missing_ok=True)
    for f in (server_log, client_log):
        f.unlink(missing_ok=True)

    procs = {}
    xvfb, display = start_xvfb()
    finished = False
    try:
        with open(out / "server.out", "w") as so, open(out / "client.out", "w") as co:
            print(f"[integ] {args.platform}: starting server on port {port}")
            procs["server"] = gradle(args.platform, "runServer", {
                "sculk.eventLog": server_log, "sculk.scripts": ROOT / "tests/integ/server.js"}, so)
            wait_for(lambda: "Done (" in (out / "server.out").read_text(errors="replace"),
                     args.timeout, procs, "server startup")
            print("[integ] server up; starting headless client")
            env = software_gl_env()
            env["DISPLAY"] = display
            procs["client"] = gradle(args.platform, "runClient", {
                "sculk.eventLog": client_log, "sculk.scripts": ROOT / "tests/integ/client.js",
                "sculk.quickPlay": f"localhost:{port}"}, co, env=env)
            def client_finished():
                events = read_jsonl(client_log)
                if any(e["kind"] == "disconnect" for e in events):
                    reasons = [l for l in (run / "server/logs/latest.log").read_text(errors="replace").splitlines()
                               if "lost connection" in l or "Disconnecting" in l]
                    raise SystemExit("client was disconnected before finishing: " + "; ".join(reasons[-2:]))
                return any(e["kind"] == "script" and e["data"].get("step") == "done" for e in events)
            wait_for(client_finished, args.timeout, procs, "client script to finish")
            finished = True
    finally:
        if not finished and "client" in procs:
            screenshot(display, out / "client-failure.png")
        for p in procs.values():
            stop(p)
        stop(xvfb)

    server, client = read_jsonl(server_log), read_jsonl(client_log)
    print(f"[integ] {args.platform}: {len(server)} server events, {len(client)} client events")
    ok = check(server, client).print()
    print(f"[integ] {'PASS' if ok else 'FAIL'}: {args.platform} (logs in {out})")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
