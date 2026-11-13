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
setsid gradle --console=plain -Psculk.control=0 ":platforms:$platform:runServer" > "$log" 2>&1 < /dev/null &
pgid=$!
trap 'kill -- -"$pgid" 2>/dev/null || true' EXIT

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
else
    echo "FAIL: no control file at $control"; ok=0
fi
[ "$ok" = 1 ] && echo "PASS: $platform" || { echo "full log: $log"; exit 1; }
