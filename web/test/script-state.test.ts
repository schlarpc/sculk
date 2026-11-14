import assert from "node:assert/strict";
import { test } from "node:test";
import type { ScriptInfo, ScriptStatus } from "../src/api";
import * as buf from "../src/script-state";
import { statusView, stoppable } from "../src/script-state";

const info = (status: ScriptStatus, hash = "h1"): ScriptInfo => ({ name: "x.js", origin: "dir", enabled: true, hash, status });
const running = (extra: Partial<Extract<ScriptStatus, { state: "running" }>> = {}): ScriptStatus =>
  ({ state: "running", hash: "h1", since: 0, listeners: 1, timers: 0, ...extra });

test("status: running, idle, older version, failed, stopped", () => {
  assert.deepEqual(statusView(info(running())), { cls: "running", text: "running: 1 listener" });
  assert.equal(statusView(info(running({ listeners: 0 }))).cls, "idle");
  const rejected = statusView(info(running({ rejected: { hash: "h2", error: "missing ;" } }), "h2"));
  assert.equal(rejected.cls, "stale");
  assert.ok(/does not compile: missing ;/.test(rejected.detail ?? ""));
  assert.equal(statusView(info(running(), "h2")).cls, "stale", "the file moved on without the watcher");
  const failed = statusView(info({ state: "failed", hash: "h1", error: "boom", at: 0 }));
  assert.equal(failed.cls, "failed");
  assert.equal(failed.detail, "boom");
  assert.equal(statusView(info({ state: "stopped", why: "disabled" })).text, "disabled");
});

test("stop applies to running and failed scripts", () => {
  assert.equal(stoppable(info(running())), true);
  assert.equal(stoppable(info({ state: "failed", hash: "h1", error: "e", at: 0 })), true);
  assert.equal(stoppable(info({ state: "stopped", why: "user" })), false);
});

test("buffer: edits make it dirty, undoing them makes it clean", () => {
  const b = buf.loaded("s0", "a", "h1");
  const d = buf.edited(b, "s1", "ab");
  assert.equal(d.kind, "dirty");
  assert.equal(buf.edited(d, "s2", "a").kind, "clean");
});

test("buffer: a change on disk reloads a clean buffer and conflicts a dirty one", () => {
  const clean = buf.loaded("s", "a", "h1");
  assert.deepEqual(buf.onDisk(clean, "h1"), { buffer: clean, reload: false });
  assert.equal(buf.onDisk(clean, "h2").reload, true);

  const dirty = buf.edited(clean, "s", "ab");
  const { buffer: conflict, reload } = buf.onDisk(dirty, "h2");
  assert.equal(reload, false);
  assert.equal(conflict.kind, "conflict");
  assert.equal(buf.startSave(conflict, "ab"), null, "settle the conflict first");
  assert.equal(buf.onDisk(conflict, "h1").buffer.kind, "dirty", "the file went back: no conflict");

  const kept = buf.keepMine(conflict);
  assert.equal(kept.kind, "dirty");
  assert.equal(kept.base, "h2", "saving now overwrites their version");
});

test("buffer: saving", () => {
  const dirty = buf.edited(buf.loaded("s", "a", "h1"), "s", "ab");
  const saving = buf.startSave(dirty, "ab");
  assert.ok(saving);
  assert.equal(buf.onDisk(saving, "h9").buffer, saving, "a save in flight settles itself");
  assert.deepEqual(buf.saveDone(saving, "h2", "ab"), { kind: "clean", state: "s", saved: "ab", base: "h2" });
  assert.equal(buf.saveDone(saving, "h2", "abc").kind, "dirty", "typed more while it saved");
  const refused = buf.saveConflict(saving, "h3");
  assert.equal(refused.kind, "conflict");
  assert.equal(buf.saveFailed(saving).kind, "dirty");
  assert.equal(buf.startSave(buf.loaded("s", "a", "h1"), "a"), null, "nothing to save");
});
