// The events table: a window onto every match of a filter in the whole
// capture. Only the rows in view (and a few either side) are in the page,
// fetched by position; the scrollbar stands for all of them.
//
// The state, explicitly:
//   query     the filter's data (query.ts); replaced whole by a new filter,
//             so nothing from an old filter can reach the new one
//   anchor    what the view holds on to: the newest rows (following), or a
//             row by absolute position (layout.ts). Rendering derives the
//             scroll offset from it; only the user's scrolling (an offset
//             the table did not set) changes it from the scroll side
//   selection the selected event and its position
//   holding   rows stand still while a menu is open over them

import type { Api, Progress, SculkEvent } from "./api";
import { el } from "./dom";
import { anchorFor, contentHeight, revealTop, scrollFor, topOf, windowRows, type Anchor, type Geometry } from "./layout";
import { PAGE, Query, type Delta, type Source } from "./query";

const OVERSCAN = 12;
/** Browsers stop scrolling an element somewhere past 17 million pixels (Firefox); stay well under. */
const MAX_HEIGHT = 8_000_000;

export interface TableHooks {
  source: Source;
  guard<T>(call: (api: Api) => Promise<T>): Promise<T | undefined>;
  /** Builds the row for an event. */
  row(e: SculkEvent): HTMLTableRowElement;
  /** The selection moved (null: cleared). */
  selected(e: SculkEvent | null): void;
  /** Counts, following and the like changed. */
  changed(): void;
  /** A scan on the server is under way (null: it finished). */
  progress(p: { done: number; total: number } | null): void;
  /** New events arrived on the live stream. */
  pulse(): void;
  live(on: boolean): void;
}

export class EventTable {
  /** Rows added while not following. */
  unseen = 0;

  private query: Query | null = null;
  /** A filter being opened, not yet shown. */
  private opening: Query | null = null;
  private anchor: Anchor = { kind: "tail" };
  private selection: { id: number; pos: number } | null = null;
  private holding = false;
  private rowH = 21;
  /** The scroll offset the table last set; any other is the user's. */
  private applied = Number.NaN;
  private renderQueued = false;
  private nudgeQueued = false;

  constructor(
    private readonly wrap: HTMLDivElement,
    private readonly body: HTMLTableSectionElement,
    private readonly hooks: TableHooks,
  ) {
    wrap.addEventListener("scroll", () => this.scrolled());
    new ResizeObserver(() => this.queueRender()).observe(wrap);
  }

  // ---- what the rest of the page reads ----

  get following(): boolean {
    return this.anchor.kind === "tail";
  }

  get count(): number {
    return this.query?.count ?? 0;
  }

  get start(): number {
    return this.query?.start ?? 0;
  }

  get total(): number {
    return this.query?.total ?? 0;
  }

  get selectedId(): number | null {
    return this.selection?.id ?? null;
  }

  /** The event with this id, if it is loaded. */
  event(id: number): SculkEvent | undefined {
    const q = this.query;
    const pos = q?.positionOf(id);
    return pos === undefined ? undefined : q?.at(pos);
  }

  rowOf(id: number): HTMLTableRowElement | null {
    return this.body.querySelector<HTMLTableRowElement>(`tr[data-id="${id}"]`);
  }

  // ---- changes ----

  /**
   * Shows a filter: the newest rows, following, or with {@code revealId} the
   * row of that event, centred and selected. Throws the server's ApiError for
   * a bad filter, leaving the table as it was.
   */
  async setFilter(filter: string, opts: { revealId?: number } = {}): Promise<void> {
    this.opening?.close();
    const q = new Query(filter, this.hooks.source);
    this.opening = q;
    const progress: Progress = (done, total) => {
      if (this.opening === q) this.hooks.progress({ done, total });
    };
    try {
      if (!(await q.open(progress))) return;
    } catch (e) {
      if (q.closed) return; // Replaced by a newer filter.
      throw e;
    } finally {
      if (this.opening === q) this.hooks.progress(null);
    }
    if (q.closed) return;
    this.opening = null;
    this.query?.close();
    this.query = q;
    this.unseen = 0;
    this.selection = null;
    this.anchor = { kind: "tail" };
    if (opts.revealId !== undefined) await this.reveal(q, opts.revealId);
    else this.hooks.selected(null);
    this.render();
    this.hooks.changed();
    void this.listen(q);
  }

  private async reveal(q: Query, id: number): Promise<void> {
    const pos = await q.locate(id, (done, total) => {
      if (q === this.query) this.hooks.progress({ done, total });
    });
    this.hooks.progress(null);
    if (pos === undefined || q !== this.query) return;
    await q.loadFrom(Math.max(q.start, pos - PAGE / 2));
    const e = q.at(pos);
    if (!e || q !== this.query) return;
    this.selection = { id: e.id, pos };
    const g = this.geometry(q);
    this.anchor = { kind: "row", top: q.start + (revealTop(g, 0, pos - q.start, "center") ?? 0) };
    this.hooks.selected(e);
  }

  /** Follows the newest rows, or stops where the view is. */
  setFollowing(on: boolean): void {
    const q = this.query;
    if (!q) return;
    if (on) {
      this.anchor = { kind: "tail" };
      this.unseen = 0;
      this.nudge();
    } else {
      this.anchor = { kind: "row", top: q.start + topOf(this.geometry(q), this.anchor, q.start) };
    }
    this.hooks.changed();
    this.render();
  }

  /** Holds the rows still while a menu is open over them; releasing catches up. */
  hold(on: boolean): void {
    this.holding = on;
    if (!on) this.queueRender();
  }

  /**
   * Selects the row at a position, loading it if need be. With {@code scroll},
   * brings it into view: the last row follows the newest, any other stops
   * following there.
   */
  async selectPos(pos: number, scroll = true): Promise<void> {
    const q = this.query;
    if (!q || q.count === 0) return;
    pos = Math.min(q.total - 1, Math.max(q.start, pos));
    let e = q.at(pos);
    if (!e) {
      this.applyDelta(q, await q.loadFrom(pos));
      if (q !== this.query || q.count === 0) return;
      // The front may have moved past it meanwhile (old capture segments dropped).
      pos = Math.min(q.total - 1, Math.max(q.start, pos));
      e = q.at(pos);
    }
    if (!e) return;
    this.selection = { id: e.id, pos };
    if (scroll) {
      if (pos === q.total - 1) {
        this.anchor = { kind: "tail" };
        this.unseen = 0;
      } else {
        const g = this.geometry(q);
        const top = topOf(g, this.anchor, q.start);
        const next = revealTop(g, top, pos - q.start, "nearest");
        this.anchor = { kind: "row", top: q.start + (next ?? top) };
      }
      this.hooks.changed();
    }
    this.render();
    this.hooks.selected(e);
  }

  select(id: number): void {
    const pos = this.query?.positionOf(id);
    if (pos !== undefined) void this.selectPos(pos, false);
  }

  step(delta: number): void {
    const from = this.selection?.pos ?? (delta > 0 ? this.start - 1 : this.total);
    void this.selectPos(from + delta);
  }

  // ---- data arriving ----

  /** Applies how a page moved the extent to the view's state. */
  private applyDelta(q: Query, d: Delta): void {
    if (q !== this.query) return;
    if (d.grew && this.anchor.kind === "row") this.unseen += d.grew;
    if (d.dropped && this.selection && this.selection.pos < q.start) {
      this.selection = null;
      this.hooks.selected(null);
    }
    if (d.grew || d.dropped) this.hooks.changed();
    this.queueRender();
  }

  /** Something new matched: ask for what was appended (debounced). */
  private nudge(): void {
    if (this.nudgeQueued) return;
    this.nudgeQueued = true;
    setTimeout(() => {
      this.nudgeQueued = false;
      const q = this.query;
      if (q) void q.tail().then((d) => this.applyDelta(q, d));
    }, 150);
  }

  /** The live stream, as a nudge that something new matched; it ends with the query. */
  private async listen(q: Query): Promise<void> {
    while (!q.closed) {
      try {
        const it = await this.hooks.guard(async (api) => api.stream(q.filter, undefined, q.signal));
        if (!it) return;
        this.hooks.live(true);
        // Anything that matched between opening the filter and the stream starting.
        this.nudge();
        for await (const _ of it) {
          this.hooks.pulse();
          this.nudge();
        }
      } catch {
        if (q.closed) return;
      }
      this.hooks.live(false);
      await new Promise((r) => setTimeout(r, 2000));
    }
  }

  // ---- view ----

  private geometry(q: Query): Geometry {
    const head = this.wrap.querySelector("thead")?.getBoundingClientRect().height ?? 0;
    return { count: q.count, rowH: this.rowH, viewH: Math.max(this.rowH, this.wrap.clientHeight - head), maxHeight: MAX_HEIGHT };
  }

  /** The user scrolled (an offset the table did not set): the anchor follows the view. */
  private scrolled(): void {
    const q = this.query;
    if (!q || this.holding || Math.abs(this.wrap.scrollTop - this.applied) < 1) return;
    const before = this.anchor.kind;
    this.anchor = anchorFor(this.geometry(q), this.wrap.scrollTop, q.start);
    this.applied = this.wrap.scrollTop;
    if (this.anchor.kind !== before) {
      if (this.anchor.kind === "tail") this.unseen = 0;
      this.hooks.changed();
    }
    this.queueRender();
  }

  queueRender(): void {
    if (this.renderQueued) return;
    this.renderQueued = true;
    requestAnimationFrame(() => {
      this.renderQueued = false;
      this.render();
    });
  }

  private render(): void {
    const q = this.query;
    if (this.holding) return;
    if (!q) {
      this.body.replaceChildren();
      return;
    }
    const g = this.geometry(q);
    const scrollTop = scrollFor(g, this.anchor, q.start);
    const { first, last, y } = windowRows(g, scrollTop, OVERSCAN);

    const rows: HTMLTableRowElement[] = [];
    const missing = new Set<number>();
    const inView = new Set<number>();
    for (let r = first; r < last; r++) {
      const pos = q.start + r;
      inView.add(Math.floor(pos / PAGE));
      const e = q.at(pos);
      let tr: HTMLTableRowElement;
      if (e) {
        tr = this.hooks.row(e);
        if (e.id === this.selection?.id) tr.classList.add("selected");
      } else {
        tr = el("tr", "loading");
        const td = el("td", "c-loading", "…");
        td.colSpan = 8;
        tr.append(td);
        missing.add(Math.floor(pos / PAGE));
      }
      tr.classList.toggle("odd", pos % 2 === 1);
      rows.push(tr);
    }
    const spacer = (px: number) => {
      const tr = el("tr", "spacer");
      const td = el("td");
      td.colSpan = 8;
      td.style.height = `${Math.max(0, px)}px`;
      tr.append(td);
      return tr;
    };
    this.body.replaceChildren(spacer(y), ...rows, spacer(contentHeight(g) - y - rows.length * this.rowH));
    // The content is the right height now, so the offset is not clamped short.
    if (Math.abs(this.wrap.scrollTop - scrollTop) >= 1) this.wrap.scrollTop = scrollTop;
    this.applied = this.wrap.scrollTop;

    for (const page of missing) void q.load(page).then((d) => this.applyDelta(q, d));
    q.trim(inView);

    // Rows are a fixed height in CSS; measure (fonts settle late) and lay out again if it differs.
    const sample = rows.find((r) => !r.classList.contains("loading"));
    const h = sample?.getBoundingClientRect().height ?? 0;
    if (h > 0 && Math.abs(h - this.rowH) > 0.25) {
      this.rowH = h;
      this.queueRender();
    }
  }
}
