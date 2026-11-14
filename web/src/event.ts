// The shape of events (see "Events" in docs/ARCHITECTURE.md) and the same
// path lookup and shorthands as the filter language (EventFilter.java), so
// the UI names fields exactly as filters and scripts do.

import type { SculkEvent } from "./api";

export interface Span {
  field: string;
  off: number;
  len: number;
}

export interface ConnLayer {
  id?: number;
  side?: string;
  remote?: string;
  pipeline?: string[];
}

export interface FrameLayer {
  seq?: number;
  dir?: string;
  len?: number | null;
  hex?: string | null;
  spans?: Span[];
}

export interface PacketLayer {
  id?: number | null;
  state?: string;
  flow?: string;
  type?: string;
  javaClass?: string | null;
  owner?: string;
}

export interface ModLayer {
  channel?: string;
  owner?: string;
  registeredVia?: string[];
  javaClass?: string | null;
  roundTrip?: string;
  data?: unknown;
  codecClass?: string | null;
  protocol?: string;
  flow?: string;
  discriminator?: number | null;
}

export const conn = (e: SculkEvent) => e.conn as ConnLayer | undefined;
export const frame = (e: SculkEvent) => e.frame as FrameLayer | undefined;
export const packet = (e: SculkEvent) => e.packet as PacketLayer | undefined;
export const mod = (e: SculkEvent) => e.mod as ModLayer | undefined;

/** Shorthand fields: each stands for the first of its paths that is present. */
const SHORTHANDS: Record<string, string[]> = {
  dir: ["frame.dir"],
  len: ["frame.len"],
  side: ["conn.side"],
  type: ["packet.type"],
  channel: ["mod.channel"],
  owner: ["mod.owner", "packet.owner"],
};

function walk(value: unknown, path: string[]): unknown {
  let v = value;
  for (const key of path) {
    if (v === null || typeof v !== "object") return undefined;
    v = (v as Record<string, unknown>)[key];
  }
  return v;
}

/** Looks up a dotted field, expanding a shorthand the event has no top-level key for. */
export function field(e: SculkEvent, name: string): unknown {
  const expansions = !name.includes(".") && !(name in e) ? SHORTHANDS[name] : undefined;
  if (!expansions) return walk(e, name.split("."));
  for (const full of expansions) {
    const v = walk(e, full.split("."));
    if (v !== undefined && v !== null) return v;
  }
  return undefined;
}

/** True when a round-trip result says edited objects would not be sent faithfully. */
export function roundTripBad(rt: unknown): boolean {
  return typeof rt === "string" && /^(mismatch|error)/.test(rt);
}

/** The last segment of a Java class name, inner classes included: a.b.Outer$Inner → Outer$Inner. */
export function shortClass(name: string): string {
  return name.slice(name.lastIndexOf(".") + 1);
}
