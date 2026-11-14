// The Scripts page's two state machines, kept free of the DOM so they can be
// tested: how a script's status reads, and what an open editor buffer does
// when it is edited, saved, or its file changes on disk.

import type { ScriptInfo } from "./api";

// ---- a script's status ----

/** How a script's status shows: a class for its dot and colour, a short text, and more for a tooltip. */
export interface StatusView {
  cls: "running" | "idle" | "stale" | "failed" | "stopped";
  text: string;
  detail?: string;
}

const STOPPED: Record<string, string> = {
  "not-started": "not started",
  user: "stopped",
  disabled: "disabled",
  deleted: "deleted",
  "wrong-dist": "not for this side",
};

export function statusView(s: ScriptInfo): StatusView {
  const st = s.status;
  switch (st.state) {
    case "running": {
      if (st.rejected) {
        return { cls: "stale", text: "running an older version", detail: `error: ${st.rejected.error}` };
      }
      if (s.hash && st.hash !== s.hash) {
        return { cls: "stale", text: "running an older version", detail: "the file changed since it started" };
      }
      const parts = [];
      if (st.listeners) parts.push(`${st.listeners} listener${st.listeners === 1 ? "" : "s"}`);
      if (st.timers) parts.push(`${st.timers} timer${st.timers === 1 ? "" : "s"}`);
      return parts.length ? { cls: "running", text: `running: ${parts.join(", ")}` } : { cls: "idle", text: "ran, nothing registered" };
    }
    case "failed":
      return { cls: "failed", text: "failed; retries when the file changes", detail: st.error };
    case "stopped":
      return { cls: "stopped", text: STOPPED[st.why] ?? "stopped" };
  }
}

/** Whether Stop applies: a running script, or a failed one still waiting to retry. */
export function stoppable(s: ScriptInfo): boolean {
  return s.status.state !== "stopped";
}

// ---- an open buffer ----

/**
 * One open script. `saved` is the source as last loaded or saved and `base`
 * its hash, which a save sends so a stale write is refused.
 *
 * - clean: the editor holds `saved`.
 * - dirty: it holds unsaved edits.
 * - conflict: unsaved edits, and the file changed on disk to `theirs`
 *   ("" if deleted). Saving waits for Load theirs or Keep mine.
 * - saving: `sent` is on its way.
 */
export type Buffer<S> =
  | { kind: "clean"; state: S; saved: string; base: string }
  | { kind: "dirty"; state: S; saved: string; base: string }
  | { kind: "conflict"; state: S; saved: string; base: string; theirs: string }
  | { kind: "saving"; state: S; saved: string; base: string; sent: string };

export function loaded<S>(state: S, source: string, hash: string): Buffer<S> {
  return { kind: "clean", state, saved: source, base: hash };
}

/** Unsaved edits (or a save in flight, or a conflict, which hold edits too). */
export function isDirty<S>(b: Buffer<S>): boolean {
  return b.kind !== "clean";
}

/** The editor changed to `text`. */
export function edited<S>(b: Buffer<S>, state: S, text: string): Buffer<S> {
  if (b.kind === "clean" || b.kind === "dirty") return { kind: text === b.saved ? "clean" : "dirty", state, saved: b.saved, base: b.base };
  return { ...b, state };
}

/**
 * The file on disk now has `hash`. A clean buffer should load it ("reload");
 * one with edits goes into conflict; a save in flight settles it itself.
 */
export function onDisk<S>(b: Buffer<S>, hash: string): { buffer: Buffer<S>; reload: boolean } {
  switch (b.kind) {
    case "clean":
      return { buffer: b, reload: hash !== b.base };
    case "dirty":
      return { buffer: hash === b.base ? b : { kind: "conflict", state: b.state, saved: b.saved, base: b.base, theirs: hash }, reload: false };
    case "conflict":
      // Back to where the edits started from: no conflict after all.
      if (hash === b.base) return { buffer: { kind: "dirty", state: b.state, saved: b.saved, base: b.base }, reload: false };
      return { buffer: { ...b, theirs: hash }, reload: false };
    case "saving":
      return { buffer: b, reload: false };
  }
}

/** Keep mine: the edits are now based on their version, so saving overwrites it. */
export function keepMine<S>(b: Buffer<S>): Buffer<S> {
  if (b.kind !== "conflict") return b;
  return { kind: "dirty", state: b.state, saved: b.saved, base: b.theirs };
}

/** Starts saving `text`; null if there is nothing to save or a conflict must be settled first. */
export function startSave<S>(b: Buffer<S>, text: string): Buffer<S> | null {
  if (b.kind !== "dirty" || text === b.saved) return null;
  return { kind: "saving", state: b.state, saved: b.saved, base: b.base, sent: text };
}

/** The save landed with `hash`. The editor now holds `text`, which may have moved on since. */
export function saveDone<S>(b: Buffer<S>, hash: string, text: string): Buffer<S> {
  if (b.kind !== "saving") return b;
  return { kind: text === b.sent ? "clean" : "dirty", state: b.state, saved: b.sent, base: hash };
}

/** The save was refused because the file changed to `theirs` ("" if deleted). */
export function saveConflict<S>(b: Buffer<S>, theirs: string): Buffer<S> {
  if (b.kind !== "saving") return b;
  return { kind: "conflict", state: b.state, saved: b.saved, base: b.base, theirs };
}

/** The save failed for another reason: back to plain edits. */
export function saveFailed<S>(b: Buffer<S>): Buffer<S> {
  if (b.kind !== "saving") return b;
  return { kind: "dirty", state: b.state, saved: b.saved, base: b.base };
}
