"""The browser half of scripts/smoke-remote.sh: a headless Chromium, with a
profile kept between visits, opens a game's remote access link.

  remote-browser.py <link> <profile dir> <console fifo> <first|again>

first: the page must wait for approval, and show the code the game prompted
with; this types `sculk remote accept 1 remember` at the server console.
again: the same browser must get straight in. Either way, the game's own UI
must then load in the sandboxed frame and show the game's status, and the
live event stream must come through the tunnel.
"""
import asyncio
import glob
import os
import sys

from playwright.async_api import async_playwright

link, profile, console, mode = sys.argv[1:5]


async def text(page) -> str:
    return await page.inner_text("body")


async def main() -> int:
    chrome = glob.glob(os.environ["PLAYWRIGHT_BROWSERS_PATH"] + "/chromium-*/chrome-linux*/chrome")[0]
    async with async_playwright() as pw:
        ctx = await pw.chromium.launch_persistent_context(profile, headless=True, executable_path=chrome)
        page = await ctx.new_page()
        page.on("console", lambda m: print(f"  console: {m.text}", flush=True))
        page.on("pageerror", lambda e: print(f"  page error: {e}", flush=True))
        await page.goto(link)
        asked = False
        for _ in range(240):
            t = await text(page)
            if "Waiting for approval" in t and not asked:
                code = t.split("shows ")[1][:6]
                print(f"page shows code {code}", flush=True)
                if mode == "again":
                    print("FAIL: a remembered browser was asked again")
                    return 1
                with open(console, "w") as c:
                    c.write("sculk remote accept 1 remember\n")
                asked = True
            if "Connected" in t or await page.locator("iframe").count() > 0:
                break
            if "refused" in t or "wrong" in t or "Could not connect" in t:
                print(f"FAIL: {t!r}")
                return 1
            await asyncio.sleep(0.5)
        else:
            print(f"FAIL: never connected: {await text(page)!r}")
            return 1
        if mode == "first" and not asked:
            print("FAIL: a new browser was let in without asking")
            return 1
        frame = page.frame_locator("iframe")
        for _ in range(120):
            try:
                inner = await frame.locator("body").inner_text(timeout=1000)
            except Exception:
                inner = ""
            if "did not load" in inner:
                print(f"FAIL: {inner!r}")
                return 1
            status = await frame.locator("#status").inner_text(timeout=1000) if inner else ""
            if "not reachable" in status:
                print(f"FAIL: the UI loaded but cannot reach the game: {status!r}")
                return 1
            if status and "connecting" not in status:
                print(f"the game's UI loaded; its status line: {status!r}", flush=True)
                break
            await asyncio.sleep(0.5)
        else:
            print(f"FAIL: the game's UI did not load: {inner!r}")
            return 1
        # The live event stream through the tunnel, gzipped and decoded as it
        # arrives: an event, or the keepalive the game sends after 10 s of none.
        ui = next(f for f in page.frames if f != page.main_frame)
        got = await ui.evaluate("""async () => {
            const r = await sculkEmbedding.fetch("/api/events/stream?after=0", {});
            const reader = r.body.getReader();
            let t = "";
            const until = Date.now() + 20000;
            while (!/^(data: |: keepalive)/m.test(t) && Date.now() < until) {
                const { value, done } = await reader.read();
                if (done) break;
                t += new TextDecoder().decode(value);
            }
            await reader.cancel();
            return t;
        }""")
        if "data: " not in got and ": keepalive" not in got:
            print(f"FAIL: the event stream did not come through: {got[:200]!r}")
            return 1
        print(f"the event stream comes through: {got.strip().splitlines()[0][:80]!r}", flush=True)
        await ctx.close()
        return 0


sys.exit(asyncio.run(main()))
