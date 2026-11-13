#!/usr/bin/env python3
"""Command-line client for sculk's control server (-Dsculk.control=PORT).

Finds the server through the control file it writes (port and per-launch
token): --file, else $SCULK_CONTROL, else ./sculk-control.json, else the dev
run directories (platforms/*/run/*/sculk-control.json). When several dev runs
are up, pick one with --target, a substring of the file's path such as
"server" or "neoforge-1.21.1/run/client".

  sculkctl.py status
  sculkctl.py events [FILTER] [--after ID] [--limit N]     JSON Lines
  sculkctl.py tail [FILTER] [--after ID]                    live, JSON Lines
  sculkctl.py frame ID                                      whole frame as hex
  sculkctl.py eval CODE | -                                 - reads stdin
  sculkctl.py repl
  sculkctl.py reload
  sculkctl.py mcp-config                                    MCP client setup for this server
  sculkctl.py ui [--print]                                  open the web UI, signed in

FILTER is the event filter language, for example
  'kind == packet && dir == inbound && owner != minecraft'
  'channel matches "^waystones:" or payload contains "../"'
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def find_control_file(explicit, target):
    if explicit:
        return Path(explicit)
    if os.environ.get("SCULK_CONTROL"):
        return Path(os.environ["SCULK_CONTROL"])
    local = Path("sculk-control.json")
    if local.exists() and not target:
        return local
    found = sorted(ROOT.glob("platforms/*/run/*/sculk-control.json"))
    if local.exists():
        found.insert(0, local.resolve())
    if target:
        found = [f for f in found if target in str(f)]
    if len(found) == 1:
        return found[0]
    if not found:
        sys.exit("sculkctl: no control file found; is the game running with -Dsculk.control=PORT? (or pass --file)")
    sys.exit("sculkctl: several control files; choose one with --target or --file:\n  "
             + "\n  ".join(str(f) for f in found))


class Client:
    def __init__(self, control_file):
        try:
            info = json.loads(Path(control_file).read_text())
        except OSError as e:
            sys.exit(f"sculkctl: cannot read {control_file}: {e}")
        self.url = info["url"]
        self.token = info["token"]
        self.mcp_url = info.get("mcp", self.url + "/mcp")

    def request(self, method, path, query=None, body=None, timeout=60):
        url = self.url + path
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        req = urllib.request.Request(url, method=method, data=body.encode() if body is not None else None,
                                     headers={"Authorization": f"Bearer {self.token}"})
        try:
            return urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace").strip()
            try:
                text = json.loads(text)["error"]
            except (ValueError, KeyError, TypeError):
                pass
            sys.exit(f"sculkctl: {e.code}: {text}")
        except urllib.error.URLError as e:
            sys.exit(f"sculkctl: cannot reach {self.url}: {e.reason} (stale control file?)")

    def json(self, method, path, query=None, body=None):
        with self.request(method, path, query, body) as r:
            return json.loads(r.read())


def print_json(value):
    print(json.dumps(value, indent=2, ensure_ascii=False))


def cmd_status(c, a):
    print_json(c.json("GET", "/status"))


def cmd_events(c, a):
    with c.request("GET", "/events", {"filter": a.filter, "after": a.after, "limit": a.limit}) as r:
        for line in r:
            sys.stdout.write(line.decode())


def cmd_tail(c, a):
    try:
        with c.request("GET", "/events/stream", {"filter": a.filter, "after": a.after}, timeout=None) as r:
            for raw in r:
                line = raw.decode().rstrip("\n")
                if line.startswith("data: "):
                    print(line[6:], flush=True)
    except KeyboardInterrupt:
        pass


def cmd_frame(c, a):
    print(c.json("GET", "/frame", {"id": a.id})["hex"])


def show_eval(result):
    if result.get("ok"):
        value = result.get("result")
        print(value if isinstance(value, str) else json.dumps(value, indent=2, ensure_ascii=False))
        return True
    print(f"error: {result.get('error')}", file=sys.stderr)
    return False


def cmd_eval(c, a):
    code = sys.stdin.read() if a.code == "-" else a.code
    sys.exit(0 if show_eval(c.json("POST", "/eval", body=code)) else 1)


def cmd_repl(c, a):
    try:
        import readline  # noqa: F401  (line editing and history for input())
    except ImportError:
        pass
    print(f"sculk repl on {c.url}; end a line with \\ to continue it, Ctrl-D to quit")
    while True:
        try:
            lines = [input("sculk> ")]
            while lines[-1].endswith("\\"):
                lines[-1] = lines[-1][:-1]
                lines.append(input("  ...> "))
        except EOFError:
            print()
            return
        except KeyboardInterrupt:
            print()
            continue
        code = "\n".join(lines)
        if code.strip():
            show_eval(c.json("POST", "/eval", body=code))


def cmd_reload(c, a):
    result = c.json("POST", "/reload", body="")
    print_json(result)
    sys.exit(0 if result.get("ok") else 1)


def cmd_ui(c, a):
    # The token travels in the fragment, which browsers never send to the
    # server; the page moves it to session storage and strips it from the URL.
    url = f"{c.url}/ui/#token={c.token}"
    if a.print:
        print(url)
        return
    import webbrowser
    if not webbrowser.open(url):
        print(url)


def cmd_mcp_config(c, a):
    config = {"mcpServers": {a.name: {"type": "http", "url": c.mcp_url,
                                      "headers": {"Authorization": f"Bearer {c.token}"}}}}
    print("# .mcp.json (or another MCP client's configuration):")
    print_json(config)
    print("\n# or, for Claude Code:")
    print(f"claude mcp add --transport http {a.name} {c.mcp_url} --header 'Authorization: Bearer {c.token}'")
    print("\n# The token changes on every launch unless the game runs with -Dsculk.controlToken=...", file=sys.stderr)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--file", help="control file to use")
    p.add_argument("--target", "-t", help="pick the dev run whose control file path contains this")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    s = sub.add_parser("events")
    s.add_argument("filter", nargs="?")
    s.add_argument("--after", type=int)
    s.add_argument("--limit", type=int)
    s.set_defaults(fn=cmd_events)
    s = sub.add_parser("tail")
    s.add_argument("filter", nargs="?")
    s.add_argument("--after", type=int)
    s.set_defaults(fn=cmd_tail)
    s = sub.add_parser("frame")
    s.add_argument("id", type=int)
    s.set_defaults(fn=cmd_frame)
    s = sub.add_parser("eval")
    s.add_argument("code")
    s.set_defaults(fn=cmd_eval)
    sub.add_parser("repl").set_defaults(fn=cmd_repl)
    sub.add_parser("reload").set_defaults(fn=cmd_reload)
    s = sub.add_parser("ui")
    s.add_argument("--print", action="store_true", help="print the URL instead of opening a browser")
    s.set_defaults(fn=cmd_ui)
    s = sub.add_parser("mcp-config")
    s.add_argument("--name", default="sculk", help="server name in the client configuration")
    s.set_defaults(fn=cmd_mcp_config)
    a = p.parse_args()
    a.fn(Client(find_control_file(a.file, a.target)), a)


if __name__ == "__main__":
    main()
