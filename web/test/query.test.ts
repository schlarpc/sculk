import assert from "node:assert/strict";
import { test } from "node:test";
import type { Page, Position, SculkEvent } from "../src/api";
import { PAGE, Query, type Source } from "../src/query";

/**
 * A capture like the server's: events numbered from 1, a filter's matches
 * kept in a list with a count of those dropped from the front.
 */
class FakeServer implements Source {
  events: SculkEvent[] = [];
  removed = 0;
  calls = 0;
  /** Requests answer only when released, to order races by hand. */
  gate: Promise<void> | null = null;

  add(n: number): void {
    const next = (this.events.at(-1)?.id ?? 0) + 1;
    for (let i = 0; i < n; i++) this.events.push({ id: next + i, kind: (next + i) % 3 === 0 ? "script" : "packet", t: 0 });
  }

  drop(n: number): void {
    this.events.splice(0, n);
    this.removed += n;
  }

  private matches(filter: string): SculkEvent[] {
    return filter === "kind == script" ? this.events.filter((e) => e.kind === "script") : this.events;
  }

  async page(filter: string, from: number, count: number, _p: unknown, signal: AbortSignal): Promise<Page> {
    this.calls++;
    if (this.gate) await this.gate;
    if (signal.aborted) throw new DOMException("aborted", "AbortError");
    if (filter === "bad ==") throw Object.assign(new Error("expected a value after =="), { status: 400 });
    // Only "everything" is numbered from the true start in this fake; enough for these tests.
    const list = this.matches(filter);
    const start = filter === "" ? this.removed : 0;
    const lo = Math.max(from, start);
    const hi = Math.min(from + count, start + list.length);
    return { start, total: start + list.length, from: lo, events: list.slice(lo - start, Math.max(lo, hi) - start) };
  }

  async position(filter: string, id: number): Promise<Position> {
    const list = this.matches(filter);
    const start = filter === "" ? this.removed : 0;
    const i = list.findIndex((e) => e.id >= id);
    return { index: start + (i < 0 ? list.length : i), start, total: start + list.length };
  }
}

test("open learns the extent; load fills a page; rows are found by position and id", async () => {
  const s = new FakeServer();
  s.add(1000);
  const q = new Query("", s);
  assert.equal(await q.open(), true);
  assert.deepEqual([q.start, q.total, q.count], [0, 1000, 1000]);
  assert.equal(q.at(5), undefined);
  await q.load(0);
  assert.equal(q.at(5)?.id, 6);
  assert.equal(q.positionOf(6), 5);
});

test("concurrent loads of one page share a request", async () => {
  const s = new FakeServer();
  s.add(1000);
  const q = new Query("", s);
  await q.open();
  const before = s.calls;
  await Promise.all([q.load(2), q.load(2), q.load(2)]);
  assert.equal(s.calls - before, 1);
});

test("tail reports what was appended, and shares one request when nudged repeatedly", async () => {
  const s = new FakeServer();
  s.add(450);
  const q = new Query("", s);
  await q.open();
  s.add(30);
  const before = s.calls;
  const [a, b] = await Promise.all([q.tail(), q.tail()]);
  assert.equal(s.calls - before, 1);
  assert.deepEqual(a, { grew: 30, dropped: 0 });
  assert.equal(a, b);
  assert.equal(q.total, 480);
  assert.equal(q.at(479)?.id, 480);
});

test("rows dropped from the front move the start, and their pages are forgotten", async () => {
  const s = new FakeServer();
  s.add(1000);
  const q = new Query("", s);
  await q.open();
  await q.load(0);
  await q.load(3);
  s.drop(450);
  const d = await q.load(4);
  assert.deepEqual(d, { grew: 0, dropped: 450 });
  assert.equal(q.start, 450);
  assert.equal(q.at(5), undefined, "page 0 forgotten");
  assert.equal(q.positionOf(6), undefined);
  assert.equal(q.at(650)?.id, 651, "page 3 straddles the front and stays");
});

test("loadFrom at a position the front has passed gets the first row held", async () => {
  const s = new FakeServer();
  s.add(1000);
  const q = new Query("", s);
  await q.open();
  s.drop(300);
  await q.loadFrom(100);
  assert.equal(q.start, 300);
  assert.equal(q.at(300)?.id, 301);
});

test("a closed query takes no more rows: an old filter's answers cannot land in a new one", async () => {
  const s = new FakeServer();
  s.add(1000);
  const old = new Query("", s);
  await old.open();
  let release!: () => void;
  s.gate = new Promise((r) => (release = r));
  const late = old.load(1);
  old.close();
  release();
  s.gate = null;
  assert.deepEqual(await late, { grew: 0, dropped: 0 });
  assert.equal(old.at(PAGE), undefined);
  // A new query for another filter starts clean.
  const fresh = new Query("kind == script", s);
  await fresh.open();
  assert.equal(fresh.total, 333);
});

test("open rejects for a bad filter, and when closed meanwhile", async () => {
  const s = new FakeServer();
  await assert.rejects(new Query("bad ==", s).open());
  let release!: () => void;
  s.gate = new Promise((r) => (release = r));
  const q = new Query("", s);
  const opening = q.open();
  q.close();
  release();
  await assert.rejects(opening);
});

test("locate finds an event's position, or nothing past the end", async () => {
  const s = new FakeServer();
  s.add(1000);
  const q = new Query("kind == script", s);
  await q.open();
  // Script events are the ids divisible by 3; the first at or after 301 is 303, the 101st.
  assert.equal(await q.locate(301), 100);
  assert.equal(await q.locate(3), 0);
  assert.equal(await q.locate(5000), undefined);
});

test("trim keeps the pages in view and the most recent others", async () => {
  const s = new FakeServer();
  s.add(PAGE * 100);
  const q = new Query("", s);
  await q.open();
  for (let p = 0; p < 80; p++) await q.load(p);
  q.trim(new Set([0, 1]));
  assert.ok(q.pageCount <= 62, `${q.pageCount}`);
  assert.ok(q.at(0) && q.at(PAGE), "the pages in view stay");
  assert.ok(q.at(79 * PAGE), "the most recent stays");
  assert.equal(q.at(10 * PAGE), undefined, "an old one goes");
});
