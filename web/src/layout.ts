// The events table's geometry, as pure functions: which rows a scroll
// offset shows, and where to scroll to show a row. No DOM here, so it is
// unit tested (web/test/layout.test.ts).
//
// Rows are numbered from 0 within the rows held; "top" is the fractional
// row at the top of the view. Past maxHeight pixels the scrollbar is scaled:
// it stands for all the rows without each having its own pixels, because
// browsers stop scrolling an element somewhere past 17 million pixels.

export interface Geometry {
  /** Rows held. */
  count: number;
  /** Pixels per row. */
  rowH: number;
  /** Pixels of the view the rows show in (under the header). */
  viewH: number;
  /** The tallest the content may be. */
  maxHeight: number;
}

/**
 * What the view holds on to as rows come and go: the newest rows (and it
 * follows new ones), or a row, by its absolute position, at the top.
 * Positions are absolute (counted from the start of the capture), so the
 * same rows stay in view when older ones are dropped from the front.
 */
export type Anchor = { kind: "tail" } | { kind: "row"; top: number };

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export function contentHeight(g: Geometry): number {
  return Math.min(g.count * g.rowH, g.maxHeight);
}

export function viewRows(g: Geometry): number {
  return g.viewH / g.rowH;
}

/** The greatest top row: the last rows fill the view. */
export function lastTop(g: Geometry): number {
  return Math.max(0, g.count - viewRows(g));
}

export function scaled(g: Geometry): boolean {
  return g.count * g.rowH > g.maxHeight;
}

/** How far the view can scroll. */
export function scrollRange(g: Geometry): number {
  return Math.max(0, contentHeight(g) - g.viewH);
}

/** The top row a scroll offset shows. */
export function topAt(g: Geometry, scrollTop: number): number {
  if (!scaled(g)) return clamp(scrollTop / g.rowH, 0, lastTop(g));
  const range = scrollRange(g);
  return range > 0 ? clamp(scrollTop / range, 0, 1) * lastTop(g) : 0;
}

/** The scroll offset that puts row {@code top} at the top of the view. */
export function scrollTopFor(g: Geometry, top: number): number {
  const t = clamp(top, 0, lastTop(g));
  if (!scaled(g)) return t * g.rowH;
  const last = lastTop(g);
  return last > 0 ? (t / last) * scrollRange(g) : 0;
}

/** Where to scroll for an anchor, given the absolute position of row 0. */
export function scrollFor(g: Geometry, anchor: Anchor, start: number): number {
  return anchor.kind === "tail" ? scrollRange(g) : scrollTopFor(g, anchor.top - start);
}

/** The anchor a scroll offset stands for: the newest rows when scrolled to the bottom. */
export function anchorFor(g: Geometry, scrollTop: number, start: number): Anchor {
  if (scrollTop >= scrollRange(g) - 1) return { kind: "tail" };
  return { kind: "row", top: start + topAt(g, scrollTop) };
}

/** The top row an anchor stands for, within the rows held. */
export function topOf(g: Geometry, anchor: Anchor, start: number): number {
  return anchor.kind === "tail" ? lastTop(g) : clamp(anchor.top - start, 0, lastTop(g));
}

/**
 * The rows to render for a scroll offset, {@code overscan} either side:
 * rows {@code first} to {@code last - 1}, the first of them {@code y}
 * pixels down the content.
 */
export function windowRows(g: Geometry, scrollTop: number, overscan: number): { first: number; last: number; y: number } {
  const top = topAt(g, scrollTop);
  const first = Math.max(0, Math.floor(top) - overscan);
  const last = Math.min(g.count, Math.ceil(top + viewRows(g)) + overscan);
  // Unscaled, a row sits at its own offset. Scaled, rows are laid out from the view's top,
  // less the part of the top row scrolled past.
  const y = scaled(g) ? Math.max(0, scrollTopFor(g, top) - (top - first) * g.rowH) : first * g.rowH;
  return { first, last: Math.max(first, last), y };
}

/**
 * The top row that brings {@code row} into view from {@code top}: centred,
 * or the least move ("nearest"), which is null when it is in view already.
 */
export function revealTop(g: Geometry, top: number, row: number, where: "center" | "nearest"): number | null {
  const rows = viewRows(g);
  let target: number;
  if (where === "center") target = row - rows / 2 + 0.5;
  else if (row < top) target = row;
  else if (row + 1 > top + rows) target = row + 1 - rows;
  else return null;
  return clamp(target, 0, lastTop(g));
}
