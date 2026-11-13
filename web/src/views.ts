// Read-only renderings for the detail panel: a JSON tree and a hex dump.

import { el } from "./dom";

const OPEN_DEPTH = 2;

/** A collapsible tree of a JSON value. */
export function jsonTree(value: unknown, depth = 0): HTMLElement {
  if (value === null || typeof value !== "object") return primitive(value);
  const entries: [string, unknown][] = Array.isArray(value)
    ? value.map((v, i) => [String(i), v])
    : Object.entries(value as Record<string, unknown>);
  const bracket = Array.isArray(value) ? ["[", "]"] : ["{", "}"];
  if (entries.length === 0) return el("span", "j-punct", bracket.join(""));

  const details = el("details", "j-node");
  details.open = depth < OPEN_DEPTH;
  const summary = el("summary", "j-summary");
  summary.append(
    el("span", "j-punct", bracket[0]),
    el("span", "j-count", Array.isArray(value) ? ` ${entries.length} items ` : ` ${entries.length} keys `),
    el("span", "j-punct", bracket[1]),
  );
  details.append(summary);
  const list = el("ul", "j-list");
  for (const [k, v] of entries) {
    const li = el("li");
    li.append(el("span", "j-key", k), el("span", "j-punct", ": "), jsonTree(v, depth + 1));
    list.append(li);
  }
  details.append(list);
  return details;
}

function primitive(value: unknown): HTMLElement {
  if (value === null || value === undefined) return el("span", "j-null", "null");
  if (typeof value === "string") return el("span", "j-str", JSON.stringify(value));
  if (typeof value === "number") return el("span", "j-num", String(value));
  if (typeof value === "boolean") return el("span", "j-bool", String(value));
  return el("span", "j-str", String(value));
}

const HEX_LIMIT = 16 * 1024;

/** Offset, hex and ASCII columns; hovering a byte highlights it in both. */
export function hexDump(bytes: Uint8Array): HTMLElement {
  const root = el("div", "hexdump");
  const shown = Math.min(bytes.length, HEX_LIMIT);
  const hexCol = el("div", "hx-hex");
  const asciiCol = el("div", "hx-ascii");
  const offsets = el("div", "hx-off");
  for (let line = 0; line < shown; line += 16) {
    offsets.append(el("div", undefined, line.toString(16).padStart(6, "0")));
    const hexLine = el("div");
    const asciiLine = el("div");
    for (let i = line; i < Math.min(line + 16, shown); i++) {
      const b = bytes[i] as number;
      const h = el("span", b === 0 ? "hx-b hx-zero" : "hx-b", b.toString(16).padStart(2, "0"));
      h.dataset.i = String(i);
      if (i % 16 === 7) h.classList.add("hx-gap");
      const printable = b >= 0x20 && b < 0x7f;
      const a = el("span", printable ? "hx-b" : "hx-b hx-dot", printable ? String.fromCharCode(b) : "·");
      a.dataset.i = String(i);
      hexLine.append(h);
      asciiLine.append(a);
    }
    hexCol.append(hexLine);
    asciiCol.append(asciiLine);
  }
  const grid = el("div", "hx-grid");
  grid.append(offsets, hexCol, asciiCol);
  root.append(grid);
  if (bytes.length > shown) root.append(el("p", "hint", `showing ${shown} of ${bytes.length} bytes`));

  let lit: HTMLElement[] = [];
  grid.addEventListener("mouseover", (e) => {
    const target = e.target as HTMLElement;
    const i = target.dataset.i;
    for (const x of lit) x.classList.remove("hx-lit");
    lit = i === undefined ? [] : Array.from(grid.querySelectorAll<HTMLElement>(`[data-i="${i}"]`));
    for (const x of lit) x.classList.add("hx-lit");
  });
  grid.addEventListener("mouseleave", () => {
    for (const x of lit) x.classList.remove("hx-lit");
    lit = [];
  });
  return root;
}
