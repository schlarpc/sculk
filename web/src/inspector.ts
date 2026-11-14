// The detail panel: one event, most useful part first.
//
// Packets: a header line (direction, name, owner, size, what sculk did),
// then the decoded payload with its round-trip result, the frame as a hex
// dump tinted by field, the layers as a compact table, and the raw event.
// Other kinds lead with what matters to them (script data, an error, eval
// source). Sections the user opened or closed stay that way.

import type { SculkEvent } from "./api";
import { el } from "./dom";
import { conn, field, frame, mod, packet, roundTripBad, shortClass } from "./event";
import { hexDump, jsonTree } from "./views";

export type FrameLoader = (id: number) => Promise<Uint8Array | null | undefined>;

/** Whether each collapsible section is open, by name, for this page's life. */
const sectionOpen = new Map<string, boolean>();

function section(title: string | HTMLElement, ...body: (HTMLElement | null)[]): HTMLElement {
  const s = el("section", "detail-section");
  const h = el("h2");
  h.append(title);
  s.append(h, ...body.filter((b): b is HTMLElement => b !== null));
  return s;
}

/** A section that can be folded; it remembers being folded across events. */
function foldable(name: string, defaultOpen: boolean, ...body: HTMLElement[]): HTMLElement {
  const d = el("details", "detail-section detail-fold");
  d.open = sectionOpen.get(name) ?? defaultOpen;
  d.addEventListener("toggle", () => sectionOpen.set(name, d.open));
  d.append(el("summary", undefined, name), ...body);
  return d;
}

type Row = [label: string, value: unknown, path?: string];

/** Label/value rows in groups; each label's tooltip is its path, as filters and scripts name it. */
function fieldTable(groups: [string, Row[]][]): HTMLElement {
  const table = el("div", "kv");
  for (const [group, rows] of groups) {
    const present = rows.filter(([, v]) => v !== undefined && v !== null && v !== "");
    if (!present.length) continue;
    table.append(el("div", "kv-group", group));
    for (const [label, value, path] of present) {
      const k = el("div", "kv-key", label);
      k.title = path ?? `${group}.${label}`;
      const v = el("div", "kv-val");
      if (value instanceof HTMLElement) v.append(value);
      else v.textContent = Array.isArray(value) ? value.join(" → ") : String(value);
      table.append(k, v);
    }
  }
  return table;
}

function badge(text: string, cls: string, title?: string): HTMLElement {
  const b = el("span", `badge ${cls}`, text);
  if (title) b.title = title;
  return b;
}

function roundTripBadge(rt: string): HTMLElement {
  const word = rt.split(/[: ]/)[0] ?? rt;
  const cls = roundTripBad(rt) ? "b-bad" : word === "ok" ? "b-ok" : "b-meh";
  return badge(`round trip: ${word}`, cls, rt);
}

function classTag(cls: string | null | undefined): HTMLElement | null {
  if (!cls) return null;
  const t = el("span", "detail-class", shortClass(cls));
  t.title = cls;
  return t;
}

function header(e: SculkEvent, time: string): HTMLElement {
  const head = el("div", "detail-head");
  const top = el("div", "detail-top");
  top.append(el("span", "detail-kind", e.kind), el("span", "detail-id", `#${e.id} · ${time}`));
  head.append(top);
  if (e.kind !== "packet") return head;

  const dir = field(e, "dir");
  const line = el("div", "detail-line");
  const arrow = dir === "inbound" ? "◀ in" : dir === "outbound" ? "▶ out" : "";
  if (arrow) line.append(el("span", `detail-dir d-${String(dir)}`, arrow));
  const name = String(field(e, "channel") ?? field(e, "type") ?? "?");
  line.append(el("span", "detail-name", name));
  head.append(line);

  const badges = el("div", "detail-badges");
  const owner = field(e, "owner");
  if (owner) badges.append(badge(String(owner), "b-owner", mod(e) ? "mod.owner" : "packet.owner"));
  const len = frame(e)?.len;
  if (typeof len === "number") badges.append(badge(`${len} B`, "b-plain", "frame.len"));
  const state = packet(e)?.state;
  if (state && state !== "play") badges.append(badge(state, "b-plain", "packet.state"));
  if (e.injected) badges.append(badge("✦ injected", "b-inj", "sent by a script, not the game"));
  if (e.action === "drop") badges.append(badge("✕ dropped", "b-bad", "a script dropped it"));
  if (e.action === "replace") badges.append(badge("↻ replaced", "b-meh", "a script replaced it"));
  const rt = mod(e)?.roundTrip;
  if (rt) badges.append(roundTripBadge(rt));
  head.append(badges);
  return head;
}

function packetSections(e: SculkEvent, loadFrame: FrameLoader, stillShown: () => boolean): HTMLElement[] {
  const out: HTMLElement[] = [];
  const c = conn(e), f = frame(e), p = packet(e), m = mod(e);

  if (m) {
    const title = el("span");
    title.append("Payload ");
    const tag = classTag(m.javaClass);
    if (tag) title.append(tag);
    const body = m.data === undefined || m.data === null
      ? el("p", "hint", "not decoded: no codec for this channel on this side")
      : jsonTree(m.data, 4);
    const rt = m.roundTrip && roundTripBad(m.roundTrip) ? el("p", "error detail-rt", m.roundTrip) : null;
    out.push(section(title, rt, body));
  }

  if (typeof f?.len === "number" && f.len > 0) {
    const frameSection = section(`Frame · ${f.len} bytes`, el("p", "hint", "loading…"));
    out.push(frameSection);
    void loadFrame(e.id).then(
      (bytes) => {
        if (!stillShown() || bytes === undefined) return;
        frameSection.lastElementChild?.replaceWith(
          bytes ? hexDump(bytes, f.spans ?? []) : el("p", "hint", "the game no longer holds this frame"),
        );
      },
      (err: Error) => frameSection.lastElementChild?.replaceWith(el("p", "error", err.message)),
    );
  }

  const pid = p?.id;
  out.push(foldable("Layers", !m, fieldTable([
    ["conn", [["id", c?.id === undefined ? undefined : `#${c.id}`], ["side", c?.side], ["remote", c?.remote]]],
    ["frame", [["dir", f?.dir], ["len", f?.len], ["seq", f?.seq]]],
    ["packet", [
      ["type", p?.type], ["id", typeof pid === "number" ? `${pid} (0x${pid.toString(16)})` : pid],
      ["state", p?.state], ["flow", p?.flow], ["owner", p?.owner], ["javaClass", p?.javaClass],
    ]],
    ["mod", m ? [
      ["channel", m.channel], ["owner", m.owner], ["registeredVia", m.registeredVia],
      ["javaClass", m.javaClass], ["roundTrip", m.roundTrip],
    ] : []],
  ])));
  return out;
}

function otherSections(e: SculkEvent): HTMLElement[] {
  switch (e.kind) {
    case "script":
      return [section("Data", jsonTree(e.data, 4))];
    case "script_error":
      return [
        section("Error", el("pre", "error detail-pre", String(e.error ?? ""))),
        fieldTable([["where", [["script", e.script, "script"], ["callback", e.callback, "callback"], ["packet", e.packet, "packet"]]]]),
      ];
    case "eval":
      return [section("Source", el("pre", "detail-pre", String(e.source ?? "")))];
    case "script_reload": {
      const errors = Array.isArray(e.errors) ? (e.errors as { script: string; error: string }[]) : [];
      return [
        section(e.ok ? "Reloaded" : "Reload failed",
          fieldTable([["scripts", [["loaded", Array.isArray(e.loaded) && e.loaded.length ? (e.loaded as string[]).join("\n") : "none", "loaded"]]]]),
          errors.length ? el("pre", "error detail-pre", errors.map((x) => `${x.script}: ${x.error}`).join("\n")) : null),
      ];
    }
    case "connection":
    case "disconnect": {
      const c = conn(e);
      return [fieldTable([["conn", [
        ["id", c?.id === undefined ? undefined : `#${c.id}`], ["side", c?.side], ["remote", c?.remote],
        ["pipeline", c?.pipeline?.join(", ")],
      ]]])];
    }
    case "payload_type": {
      const m = mod(e);
      return [fieldTable([["mod", [
        ["channel", m?.channel], ["owner", m?.owner], ["registeredVia", m?.registeredVia], ["flow", m?.flow],
        ["protocol", m?.protocol], ["discriminator", m?.discriminator], ["javaClass", m?.javaClass], ["codecClass", m?.codecClass],
      ]]])];
    }
    case "start":
      return [fieldTable([["game", [["platform", e.platform, "platform"]]]])];
    default:
      return [jsonTree(e, 3)];
  }
}

/** Fills {@code container} with the inspector for {@code e}. */
export function inspect(container: HTMLElement, e: SculkEvent, time: string, loadFrame: FrameLoader,
    stillShown: () => boolean): void {
  const body = e.kind === "packet" ? packetSections(e, loadFrame, stillShown) : otherSections(e);
  container.replaceChildren(header(e, time), ...body, foldable("Raw event", false, jsonTree(e, 1)));
}
