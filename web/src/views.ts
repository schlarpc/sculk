// Read-only renderings for the detail panel: a JSON tree and a hex dump.

import { el } from "./dom";
import { shortClass, type Span } from "./event";

/**
 * A collapsible tree of a JSON value, opened {@code open} levels deep.
 *
 * Understands the object trees the engine makes of Java objects
 * (ObjectModel.java): {@code $class} becomes a label on the brace (full name
 * on hover) rather than a key, {@code $bytes} shows as hex, and an object
 * that could not be read ({@code $string}, {@code $reflectionError}) shows
 * as its string.
 *
 * With a {@code path} (the value's field path, such as {@code mod.data}),
 * each entry is marked with its own path for the context menu: list indices
 * are left out, since a filter path through a list matches any element.
 */
export function jsonTree(value: unknown, open = 2, path: string | null = null, depth = 0, inList = false): HTMLElement {
  if (value === null || typeof value !== "object") return primitive(value);
  const classLabel = (cls: string) => {
    const label = classTag(cls);
    if (path !== null) mark(label, `${path ? path + "." : ""}$class`, cls, inList);
    return label;
  };
  if (!Array.isArray(value)) {
    const obj = value as Record<string, unknown>;
    if (typeof obj.$bytes === "string" && Object.keys(obj).length === 1) {
      const span = el("span", "j-bytes", obj.$bytes);
      span.prepend(el("span", "j-class", "bytes "));
      return span;
    }
    if (typeof obj.$string === "string") {
      const span = el("span", "j-str", JSON.stringify(obj.$string));
      if (typeof obj.$class === "string") span.prepend(classLabel(obj.$class));
      if (typeof obj.$reflectionError === "string") span.title = obj.$reflectionError;
      return span;
    }
  }
  const cls = !Array.isArray(value) && typeof (value as Record<string, unknown>).$class === "string"
    ? ((value as Record<string, unknown>).$class as string)
    : null;
  const entries: [string, unknown][] = Array.isArray(value)
    ? value.map((v, i) => [String(i), v])
    : Object.entries(value as Record<string, unknown>).filter(([k]) => k !== "$class");
  const bracket = Array.isArray(value) ? ["[", "]"] : ["{", "}"];
  if (entries.length === 0) {
    const empty = el("span", "j-punct", bracket.join(""));
    if (cls) empty.prepend(classLabel(cls));
    return empty;
  }

  const details = el("details", "j-node");
  details.open = depth < open;
  const summary = el("summary", "j-summary");
  if (cls) summary.append(classLabel(cls));
  // Collapsed, a node shows a one-line preview of its contents (or a count, if that is too long).
  const peek = preview(entries, Array.isArray(value), 72);
  summary.append(
    el("span", "j-punct", bracket[0]),
    el("span", "j-count", peek ?? (Array.isArray(value) ? ` ${entries.length} items ` : ` ${entries.length} fields `)),
    el("span", "j-punct j-close", bracket[1]),
  );
  details.append(summary);
  const list = el("ul", "j-list");
  const isArray = Array.isArray(value);
  for (const [k, v] of entries) {
    const li = el("li");
    const childPath = path === null ? null : isArray ? path : /^[\w$-]+$/.test(k) ? (path ? `${path}.${k}` : k) : null;
    if (childPath) mark(li, childPath, v, inList || isArray);
    li.append(el("span", "j-key", k), el("span", "j-punct", ": "), jsonTree(v, open, childPath, depth + 1, inList || isArray));
    list.append(li);
  }
  details.append(list);
  return details;
}

/** `a: 1, b: "x"` for a node, or null when it would not fit in {@code budget} characters. */
function preview(entries: [string, unknown][], isArray: boolean, budget: number): string | null {
  const parts: string[] = [];
  let used = 0;
  for (const [k, v] of entries) {
    let text: string;
    if (v === null || typeof v !== "object") text = v === undefined ? "null" : JSON.stringify(v);
    else if (Array.isArray(v)) text = v.length ? `[…${v.length}]` : "[]";
    else {
      const o = v as Record<string, unknown>;
      text = typeof o.$string === "string" ? JSON.stringify(o.$string)
        : typeof o.$class === "string" ? `${shortClass(o.$class)} {…}` : "{…}";
    }
    const part = isArray ? text : `${k}: ${text}`;
    used += part.length + 2;
    if (used > budget) return null;
    parts.push(part);
  }
  return ` ${parts.join(", ")} `;
}

function classTag(cls: string): HTMLElement {
  const label = el("span", "j-class", shortClass(cls) + " ");
  label.title = cls;
  return label;
}

/**
 * Marks an element as standing for a field, for the context menu:
 * {@code data-path}, the value as JSON when it is a plain value, and
 * whether the path went through a list.
 */
export function mark(node: HTMLElement, path: string, value: unknown, inList = false): void {
  node.dataset.path = path;
  if (value === null || typeof value !== "object") node.dataset.value = JSON.stringify(value ?? null);
  else delete node.dataset.value;
  if (inList) node.dataset.inList = "";
}

function primitive(value: unknown): HTMLElement {
  if (value === null || value === undefined) return el("span", "j-null", "null");
  if (typeof value === "string") return el("span", "j-str", JSON.stringify(value));
  if (typeof value === "number") return el("span", "j-num", String(value));
  if (typeof value === "boolean") return el("span", "j-bool", String(value));
  return el("span", "j-str", String(value));
}

const HEX_LIMIT = 16 * 1024;

/** The bytes behind each hex dump, for the context menu. */
export const frameOf = new WeakMap<HTMLElement, Uint8Array>();

/** A short CSS-safe name for a span's field, for its colour: mod.channel → mod-channel. */
export const spanClass = (span: Span) => `sp-${span.field.replace(/[^a-z0-9]+/gi, "-")}`;

/**
 * Offset, hex and ASCII columns, with the frame's {@code spans} tinted and
 * listed in a legend above. Hovering a byte names its field and highlights
 * it in both columns; hovering a legend entry highlights the whole field.
 */
export function hexDump(bytes: Uint8Array, spans: Span[] = []): HTMLElement {
  const root = el("div", "hexdump");
  frameOf.set(root, bytes);
  const shown = Math.min(bytes.length, HEX_LIMIT);
  const spanAt: (Span | undefined)[] = new Array(shown);
  for (const s of spans) for (let i = s.off; i < Math.min(s.off + s.len, shown); i++) spanAt[i] = s;

  const legend = el("div", "hx-legend");
  for (const s of spans) {
    const chip = el("span", `hx-chip ${spanClass(s)}`);
    chip.append(el("span", "hx-swatch"), el("span", undefined, s.field), el("span", "hx-chip-len", ` ${s.len} B`));
    chip.dataset.field = s.field;
    chip.title = `bytes ${s.off}–${s.off + s.len - 1}`;
    legend.append(chip);
  }
  const where = el("div", "hx-where hint", spans.length ? "hover a byte or a field" : "");

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
      if (i % 16 === 7) h.classList.add("hx-gap");
      const printable = b >= 0x20 && b < 0x7f;
      const a = el("span", printable ? "hx-b" : "hx-b hx-dot", printable ? String.fromCharCode(b) : "·");
      const s = spanAt[i];
      for (const x of [h, a]) {
        x.dataset.i = String(i);
        if (s) {
          x.classList.add(spanClass(s));
          x.dataset.field = s.field;
        }
      }
      hexLine.append(h);
      asciiLine.append(a);
    }
    hexCol.append(hexLine);
    asciiCol.append(asciiLine);
  }
  const grid = el("div", "hx-grid");
  grid.append(offsets, hexCol, asciiCol);
  if (spans.length) root.append(legend, where);
  root.append(grid);
  if (bytes.length > shown) root.append(el("p", "hint", `showing ${shown} of ${bytes.length} bytes`));

  let lit: HTMLElement[] = [];
  const light = (selector: string | null) => {
    for (const x of lit) x.classList.remove("hx-lit");
    lit = selector ? Array.from(root.querySelectorAll<HTMLElement>(selector)) : [];
    for (const x of lit) x.classList.add("hx-lit");
  };
  grid.addEventListener("mouseover", (e) => {
    const i = (e.target as HTMLElement).dataset.i;
    if (i === undefined) return light(null);
    light(`.hx-grid [data-i="${i}"]`);
    const n = Number(i);
    const s = spanAt[n];
    const b = bytes[n] as number;
    where.textContent = `byte ${n} (0x${n.toString(16)}) = 0x${b.toString(16).padStart(2, "0")} = ${b}` +
      (s ? ` · ${s.field}, byte ${n - s.off + 1} of ${s.len}` : "");
  });
  legend.addEventListener("mouseover", (e) => {
    const chip = (e.target as HTMLElement).closest<HTMLElement>(".hx-chip");
    if (chip?.dataset.field) light(`.hx-grid [data-field="${chip.dataset.field}"]`);
  });
  for (const area of [grid, legend]) area.addEventListener("mouseleave", () => light(null));
  return root;
}
