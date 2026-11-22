#!/usr/bin/env bash
# Remote access, end to end: boot a platform's dev server with remote = true,
# serve the shell locally, and have a headless Chromium open the game's link
# (scripts/remote-browser.py). The first visit waits for approval, which this
# types at the server console (with remember); the game's own UI must then
# load in the shell's sandboxed frame and show the game's status. A second
# visit, with the same browser profile, must get straight in, and so must a
# third from the shell's list of saved games, without the link.
#
# Needs the network (Nostr relays, STUN). Run inside `nix develop`.
#
#   scripts/smoke-remote.sh neoforge-1.21.1
#   SCULK_SHELL=~/sculk-ui.github.io/result scripts/smoke-remote.sh neoforge-1.21.1
set -euo pipefail

platform=${1:?usage: smoke-remote.sh <platform-dir-name>}
port=${2:-25598}
shell_port=${3:-8139}
root=$(cd "$(dirname "$0")/.." && pwd)
run="$root/platforms/$platform/run/server"
log="$run/smoke-remote.log"

mkdir -p "$run"
echo "eula=true" > "$run/eula.txt"
cat > "$run/server.properties" <<PROPS
server-port=$port
online-mode=false
level-type=minecraft\:flat
PROPS
# Start from a game that remembers no browser, and from a browser with no key.
rm -rf "$run/config/sculk/remote" "$run/smoke-remote-profile"

cd "$root"
shell=${SCULK_SHELL:-$(nix build .#shell --no-link --print-out-paths)}
python_env=$(nix build .#smoke-browser --no-link --print-out-paths)
export PLAYWRIGHT_BROWSERS_PATH=$(nix build .#playwright-browsers --no-link --print-out-paths)

console="$run/smoke-remote.console"
rm -f "$console"; mkfifo "$console"
setsid gradle --console=plain -Psculk.control=0 -Psculk.remote=true ":platforms:$platform:runServer" \
    > "$log" 2>&1 < "$console" &
pgid=$!
exec 3> "$console"
(cd "$shell" && exec python3 -m http.server "$shell_port" --bind 127.0.0.1 > /dev/null 2>&1) &
web=$!
trap 'exec 3>&-; kill -- -"$pgid" 2>/dev/null || true; kill "$web" 2>/dev/null || true; rm -f "$console"' EXIT

for _ in $(seq 1 180); do
    grep -q 'Done (' "$log" && break
    if grep -q 'BUILD FAILED' "$log" || ! kill -0 "$pgid" 2>/dev/null; then
        tail -40 "$log"; exit 1
    fi
    sleep 2
done
key=$(grep -o 'remote access: https://sculk-ui.github.io/#[A-Za-z0-9_-]*' "$log" | head -1 | sed 's/.*#//')
[ -n "$key" ] || { echo "FAIL: the game printed no remote access link"; grep -i remote "$log" | tail; exit 1; }
link="http://127.0.0.1:$shell_port/#$key"
echo "link: $link"

ok=1
browse() { "$python_env/bin/python3" -I "$root/scripts/remote-browser.py" "$link" "$run/smoke-remote-profile" "$console" "$1"; }
echo "== first visit"
browse first || ok=0
echo "== again, as the same browser"
[ "$ok" = 1 ] && { browse again || ok=0; }
echo "== from the saved games, without the link"
[ "$ok" = 1 ] && { browse saved || ok=0; }

grep -E 'remote access' "$log" | sed 's/^.*\(remote access\)/  \1/' | head -10
[ -f "$run/config/sculk/remote/browsers.json" ] || { echo "FAIL: nothing remembered"; ok=0; }
if [ "$ok" = 1 ]; then echo "remote access OK"; else echo "remote access FAILED"; exit 1; fi
