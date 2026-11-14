#!/usr/bin/env bash
# Boot a platform's dev server, open one connection to it, and check that
# sculk initialised and saw the connection, and that the control server
# answers scripts/sculkctl.py. Run inside `nix develop`.
#
#   scripts/smoke-server.sh neoforge-1.21.1
set -euo pipefail

platform=${1:?usage: smoke-server.sh <platform-dir-name>}
port=${2:-25599}
root=$(cd "$(dirname "$0")/.." && pwd)
run="$root/platforms/$platform/run/server"
log="$run/smoke.log"
control="$run/sculk-control.json"

mkdir -p "$run"
echo "eula=true" > "$run/eula.txt"
cat > "$run/server.properties" <<PROPS
server-port=$port
online-mode=false
level-type=minecraft\:flat
PROPS

cd "$root"
rm -f "$control"
# The server console reads this pipe, so the test can type commands into it.
console="$run/smoke.console"
rm -f "$console"; mkfifo "$console"
setsid gradle --console=plain -Psculk.control=0 ":platforms:$platform:runServer" > "$log" 2>&1 < "$console" &
pgid=$!
exec 3> "$console"
trap 'exec 3>&-; kill -- -"$pgid" 2>/dev/null || true; rm -f "$console"' EXIT

for _ in $(seq 1 180); do
    grep -q 'Done (' "$log" && break
    if grep -q 'BUILD FAILED' "$log" || ! kill -0 "$pgid" 2>/dev/null; then
        tail -40 "$log"; exit 1
    fi
    sleep 2
done

python3 "$root/scripts/status-ping.py" "$port"
sleep 2

grep -E '[[(]sculk[)/]' "$log" || true
ok=1
grep -q 'sculk core initialised' "$log" || { echo "FAIL: core did not initialise"; ok=0; }
grep -q 'scripting: rhino' "$log" || { echo "FAIL: Rhino did not load"; ok=0; }
grep -q '\[SERVER\] connection' "$log" || { echo "FAIL: channel hook did not fire"; ok=0; }
ctl() { python3 "$root/scripts/sculkctl.py" --file "$control" "$@"; }
if [ -f "$control" ]; then
    ctl status > /dev/null || { echo "FAIL: control server /status"; ok=0; }
    [ -n "$(ctl events 'kind == connection && side == server')" ] || { echo "FAIL: control server has no connection event"; ok=0; }
    [ "$(ctl eval '20 + 22')" = 42 ] || { echo "FAIL: control server /eval"; ok=0; }
    # Installed scripts: in the server's config dir, started and stopped one at a time.
    printf '// ==Sculk==\n// @run manual\n// ==/Sculk==\nsculk.log("smoke " + sculk.dist); 6 * 7\n' \
        | ctl script push - --name smoke.js > /dev/null || { echo "FAIL: script push"; ok=0; }
    [ -f "$run/config/sculk/scripts/smoke.js" ] || { echo "FAIL: scripts are not in config/sculk/scripts"; ok=0; }
    [ "$(ctl script run smoke.js)" = 42 ] || { echo "FAIL: script run"; ok=0; }
    [ -n "$(ctl events 'kind == script_log && script == smoke.js && message == "smoke server"')" ] \
        || { echo "FAIL: script_log (or dist) from smoke.js"; ok=0; }
    ctl script rm smoke.js > /dev/null || { echo "FAIL: script rm"; ok=0; }
    # /sculk mark, typed at the server console (an operator).
    echo 'sculk mark smoke console' >&3
    for _ in $(seq 1 20); do
        [ -n "$(ctl events 'kind == mark && text == "smoke console" && by == command')" ] && break
        sleep 0.5
    done
    [ -n "$(ctl events 'kind == mark && text == "smoke console" && by == command')" ] || { echo "FAIL: /sculk mark"; ok=0; }
    python3 - "$control" <<'PY' || { echo "FAIL: control server /mcp"; ok=0; }
import json, sys, urllib.request
info = json.load(open(sys.argv[1]))
def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(info["mcp"], body, {"Authorization": "Bearer " + info["token"],
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    return json.load(urllib.request.urlopen(req, timeout=10))["result"]
rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "smoke", "version": "0"}})
r = rpc("tools/call", {"name": "sculk_events", "arguments": {"filter": "kind == connection", "limit": 1}})
assert not r["isError"] and '"kind":"connection"' in r["content"][0]["text"], r
r = rpc("tools/call", {"name": "sculk_stats", "arguments": {"filter": "kind == packet", "by": "type"}})
stats = json.loads(r["content"][0]["text"])
assert not r["isError"] and stats["matched"] > 0 and stats["groups"], r
# The capture: events by position, and their frames read back whole.
req = lambda path: urllib.request.urlopen(urllib.request.Request(info["url"] + path,
    headers={"Authorization": "Bearer " + info["token"]}), timeout=10)
page = json.load(req("/events/page?filter=kind%20%3D%3D%20packet&from=0&count=5"))
assert page["total"] > 0 and page["events"] and page["events"][0]["kind"] == "packet", page
status = json.load(req("/status"))
assert status["capture"]["firstId"] >= 1 and status["capture"].get("diskBytes") is not None, status["capture"]
PY
    python3 - "$control" <<'PY' || { echo "FAIL: control server web UI"; ok=0; }
import json, sys, urllib.request
url = json.load(open(sys.argv[1]))["url"]
for name, needle in [("", b"<title>sculk</title>"), ("app.js", b"sculk"), ("monocraft.woff2", b"wOF2")]:
    body = urllib.request.urlopen(f"{url}/ui/{name}", timeout=10).read()
    assert needle in body[:65536], f"/ui/{name} does not contain {needle}"
# The editor's style elements need the page's CSP nonce.
r = urllib.request.urlopen(f"{url}/ui/", timeout=10)
page, csp = r.read().decode(), r.headers["Content-Security-Policy"]
nonce = page.split('name="csp-nonce" content="')[1].split('"')[0]
assert nonce and "{{" not in nonce and f"'nonce-{nonce}'" in csp, (nonce, csp)
PY
    if grep -q 'and http://\[::1\]' "$log"; then
        python3 - "$control" <<'PY' || { echo "FAIL: control server on ::1"; ok=0; }
import json, sys, urllib.request
info = json.load(open(sys.argv[1]))
req = urllib.request.Request(f"http://[::1]:{info['port']}/status", headers={"Authorization": "Bearer " + info["token"]})
assert json.load(urllib.request.urlopen(req, timeout=10))["platform"]
PY
    fi
else
    echo "FAIL: no control file at $control"; ok=0
fi
[ "$ok" = 1 ] && echo "PASS: $platform" || { echo "full log: $log"; exit 1; }
