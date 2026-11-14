// The events table: a window onto every match of a filter in the whole
// capture, which may run to millions of rows. Only the rows in view (and a
// few either side) are in the page; they are fetched a page at a time by
// position (GET /events/page) and the scrollbar stands for all of them.
// New events arrive as a nudge on the live stream, after which the table
// asks the server for whatever was appended.

import { Api, type Page, type SculkEvent } from "./api";
import { el } from "./dom";

const PAGE = 200;
const OVERSCAN = 12;
/** Browsers stop scrolling an element somewhere past 17 million pixels (Firefox); stay well under. */
const MAX_HEIGHT = 8_000_000;
/** Pages kept in memory, oldest dropped first. */
const MAX_PAGES = 60;

export interface TableHooks {
  guard<T>(call: (api: Api) => Promise<T>): Promise<T | undefined>;
  /** Builds the row for an event at a position. */
  row(e: SculkEvent): HTMLTableRowElement;
  /** The selection moved (null: cleared). */
  selected(e: SculkEvent | null): void;
  /** Counts, follow state and the like changed. */
  changed(): void;
  /** New events arrived on the stream. */
  pulse(): void;
  live(on: boolean): void;
}

export class EventTable {
  /** Position of the first match still held, and one past the last. */
  start = 0;
  total = 0;
  /** Stick to the newest rows as they arrive. */
  following = true;
  /** Matches that arrived while not following. */
  unseen = 0;
  selectedId: number | null = null;
  selectedPos: number | null = null;

  private filter = "";
  private generation = 0;
  /** The generation whose filter is fully set up; loads and tails wait for it. */
  private ready = 0;
  private rowH = 21;
  private readonly pages = new Map<number, SculkEvent[]>();
  private readonly pageAge: number[] = [];
  private readonly loading = new Map<number, Promise<void>>();
  private readonly positions = new Map<number, number>();
  private renderQueued = false;
  private tailQueued = false;
  private tailRunning = false;
  private stream: AbortController | null = null;
  private holding = false;
  private lastTop = 0;

  constructor(
    private readonly wrap: HTMLDivElement,
    private readonly body: HTMLTableSectionElement,
    private readonly hooks: TableHooks,
  ) {
    wrap.addEventListener("scroll", () => {
      // Scrolling up stops following; scrolling back to the bottom resumes it. The table's own
      // scrolling only ever goes down (to the newest rows) while following, and rows arriving
      // grow the content without a scroll event, so an upward scroll is always the user's.
      const top = this.wrap.scrollTop;
      const atBottom = top + this.wrap.clientHeight >= this.wrap.scrollHeight - this.rowH;
      if (this.following && top < this.lastTop - 1 && !atBottom && !this.holding) {
        this.following = false;
        this.hooks.changed();
      } else if (!this.following && atBottom && top > this.lastTop) {
        this.following = true;
        this.unseen = 0;
        this.hooks.changed();
      }
      this.lastTop = top;
      this.queueRender();
    });
    new ResizeObserver(() => this.queueRender()).observe(wrap);
  }

  /** The filter the table shows. Throws on a bad filter (an ApiError with status 400). */
  async setFilter(filter: string, opts: { revealId?: number } = {}): Promise<void> {
    const generation = ++this.generation;
    this.stream?.abort();
    this.hooks.live(false);
    // Learn the extent first (count 0), so a bad filter fails before anything changes.
    const extent = await this.hooks.guard((api) => api.page(filter, 0, 0));
    if (!extent || generation !== this.generation) return;
    const status = await this.hooks.guard((api) => api.status()).catch(() => undefined);
    if (generation !== this.generation) return;
    this.filter = filter;
    this.pages.clear();
    this.pageAge.length = 0;
    this.loading.clear();
    this.positions.clear();
    this.start = extent.start;
    this.total = extent.total;
    this.unseen = 0;
    this.wrap.scrollTop = 0;
    this.lastTop = 0;
    this.ready = generation;
    if (opts.revealId !== undefined) {
      const pos = await this.hooks.guard((api) => api.position(filter, opts.revealId as number));
      if (!pos || generation !== this.generation) return;
      this.following = false;
      this.selectedId = opts.revealId;
      this.selectedPos = pos.index;
      this.render();
      this.scrollToPos(pos.index, "center");
    } else {
      this.following = true;
      this.render();
      this.scrollToBottom();
    }
    this.hooks.changed();
    void this.listen(filter, status?.lastEventId ?? 0, generation);
  }

  /** Stops following, or follows again (and jumps to the newest rows). */
  setFollowing(on: boolean): void {
    this.following = on;
    if (on) {
      this.unseen = 0;
      this.scrollToBottom();
      this.queueTail();
    }
    this.hooks.changed();
  }

  /** Holds the rows still (a menu is open over them); releasing catches up. */
  hold(on: boolean): void {
    this.holding = on;
    if (!on) {
      if (this.following) this.scrollToBottom();
      this.queueRender();
    }
  }

  /** The event with this id, if it is loaded. */
  event(id: number): SculkEvent | undefined {
    const pos = this.positions.get(id);
    return pos === undefined ? undefined : this.at(pos);
  }

  rowOf(id: number): HTMLTableRowElement | null {
    return this.body.querySelector<HTMLTableRowElement>(`tr[data-id="${id}"]`);
  }

  /** Selects the row at a position, loading it if need be, and brings it into view. */
  async selectPos(pos: number, scroll = true): Promise<void> {
    let e: SculkEvent | undefined;
    for (let attempt = 0; attempt < 3 && !e; attempt++) {
      // The front of the capture may have moved on (old segments dropped) while a page loaded.
      pos = Math.min(this.total - 1, Math.max(this.start, pos));
      if (pos < this.start) return;
      e = this.at(pos);
      if (!e) await this.loadFrom(pos);
      e = this.at(pos) ?? (pos < this.start ? this.at(this.start) : undefined);
      if (e && pos < this.start) pos = this.start;
    }
    if (!e) return;
    this.selectedId = e.id;
    this.selectedPos = pos;
    if (scroll && this.following && pos < this.total - 1) {
      // Going to an older row: stop following, or the next render would pin the view to the newest.
      this.following = false;
      this.hooks.changed();
    }
    if (scroll) this.scrollToPos(pos, "nearest");
    this.render();
    this.hooks.selected(e);
  }

  select(id: number): void {
    const pos = this.positions.get(id);
    if (pos !== undefined) void this.selectPos(pos, false);
  }

  step(delta: number): void {
    const from = this.selectedPos ?? (delta > 0 ? this.start - 1 : this.total);
    void this.selectPos(Math.min(this.total - 1, Math.max(this.start, from + delta)));
  }

  /** Rows shown, and whether the filter matched anything. */
  get count(): number {
    return this.total - this.start;
  }

  // ---- data ----

  private at(pos: number): SculkEvent | undefined {
    return this.pages.get(Math.floor(pos / PAGE))?.[pos % PAGE];
  }

  /** Loads one page of positions; a page at the end may be partial and is reloaded as it fills. */
  private load(page: number): Promise<void> {
    const generation = this.generation;
    if (generation !== this.ready) return Promise.resolve();
    const pending = this.loading.get(page);
    if (pending) return pending;
    const promise = this.fetchPage(page, generation);
    this.loading.set(page, promise);
    return promise;
  }

  /** Loads rows starting exactly at {@code pos} (or the first one held after it). */
  private async loadFrom(pos: number): Promise<void> {
    const generation = this.generation;
    if (generation !== this.ready) return;
    try {
      const p = await this.hooks.guard((api) => api.page(this.filter, pos, PAGE));
      if (p && generation === this.generation) this.absorb(p);
    } catch {
      // Leave it unselected.
    }
  }

  private async fetchPage(page: number, generation: number): Promise<void> {
    try {
      const p = await this.hooks.guard((api) => api.page(this.filter, page * PAGE, PAGE));
      if (!p || generation !== this.generation) return;
      this.absorb(p);
    } catch {
      // The game went away; the rows stay blank until it is back.
    } finally {
      if (generation === this.generation) this.loading.delete(page);
    }
  }

  /** Takes a page response: its events, and the extent it reports. */
  private absorb(p: Page): void {
    this.extent(p.start, p.total);
    for (let i = 0; i < p.events.length; i++) {
      const pos = p.from + i;
      const page = Math.floor(pos / PAGE);
      let rows = this.pages.get(page);
      if (!rows) {
        rows = [];
        this.pages.set(page, rows);
        this.pageAge.push(page);
      }
      const e = p.events[i] as SculkEvent;
      rows[pos % PAGE] = e;
      this.positions.set(e.id, pos);
    }
    while (this.pageAge.length > MAX_PAGES) {
      const old = this.pageAge.shift() as number;
      // Never drop what is on screen.
      if (this.visiblePages().has(old)) {
        this.pageAge.push(old);
        break;
      }
      for (const e of this.pages.get(old) ?? []) if (e) this.positions.delete(e.id);
      this.pages.delete(old);
    }
    this.queueRender();
  }

  /** The extent moved: rows were added at the end, or dropped from the front with the oldest capture segments. */
  private extent(start: number, total: number): void {
    const grew = total - this.total;
    if (grew > 0 && !this.following) this.unseen += grew;
    if (start > this.start) {
      // Keep the same rows in view: everything moved up.
      const dropped = start - this.start;
      this.start = start;
      if (!this.following && !this.scaled()) {
        this.wrap.scrollTop = Math.max(0, this.wrap.scrollTop - dropped * this.rowH);
        this.lastTop = this.wrap.scrollTop;
      }
      if (this.selectedPos !== null && this.selectedPos < start) {
        this.selectedPos = null;
        this.selectedId = null;
        this.hooks.selected(null);
      }
    }
    this.total = Math.max(total, this.start);
    if (grew !== 0) this.hooks.changed();
  }

  /** New events: ask for what was appended past the end. */
  private queueTail(): void {
    if (this.tailQueued) return;
    this.tailQueued = true;
    setTimeout(() => {
      this.tailQueued = false;
      void this.tail();
    }, 150);
  }

  private async tail(): Promise<void> {
    if (this.tailRunning) {
      this.queueTail();
      return;
    }
    const generation = this.generation;
    if (generation !== this.ready) return;
    this.tailRunning = true;
    try {
      // The last partial page again, plus anything after it.
      const from = Math.max(this.start, Math.floor(this.total / PAGE) * PAGE);
      const p = await this.hooks.guard((api) => api.page(this.filter, from, PAGE));
      if (!p || generation !== this.generation) return;
      this.absorb(p);
      if (this.following && !this.holding) this.scrollToBottom();
    } catch {
      // Try again on the next nudge.
    } finally {
      this.tailRunning = false;
    }
  }

  /** The live stream, used only as a nudge that something new matched. */
  private async listen(filter: string, after: number, generation: number): Promise<void> {
    const controller = new AbortController();
    this.stream = controller;
    let last = after;
    while (!controller.signal.aborted && generation === this.generation) {
      try {
        const it = await this.hooks.guard(async (api) => api.stream(filter, last, controller.signal));
        if (!it) return;
        this.hooks.live(true);
        for await (const e of it) {
          last = e.id;
          this.hooks.pulse();
          this.queueTail();
        }
      } catch {
        if (controller.signal.aborted) return;
      }
      this.hooks.live(false);
      await new Promise((r) => setTimeout(r, 2000));
      // Events may have arrived while the stream was down.
      this.queueTail();
    }
  }

  // ---- view ----

  private scaled(): boolean {
    return this.count * this.rowH > MAX_HEIGHT;
  }

  private viewHeight(): number {
    const head = this.wrap.querySelector("thead")?.getBoundingClientRect().height ?? 0;
    return Math.max(this.rowH, this.wrap.clientHeight - head);
  }

  /** The (fractional) row offset at the top of the view. */
  private topRow(): number {
    const count = this.count;
    const viewRows = this.viewHeight() / this.rowH;
    const last = Math.max(0, count - viewRows);
    if (count * this.rowH <= MAX_HEIGHT) return Math.min(last, this.wrap.scrollTop / this.rowH);
    const room = MAX_HEIGHT - this.viewHeight();
    return room > 0 ? Math.min(last, (this.wrap.scrollTop / room) * last) : 0;
  }

  private scrollTopFor(row: number): number {
    const count = this.count;
    if (count * this.rowH <= MAX_HEIGHT) return row * this.rowH;
    const viewRows = this.viewHeight() / this.rowH;
    const room = MAX_HEIGHT - this.viewHeight();
    return count - viewRows > 0 ? (row / (count - viewRows)) * room : 0;
  }

  private scrollToBottom(): void {
    this.wrap.scrollTop = this.wrap.scrollHeight;
    this.queueRender();
  }

  private scrollToPos(pos: number, where: "center" | "nearest"): void {
    const row = pos - this.start;
    const viewRows = this.viewHeight() / this.rowH;
    const top = this.topRow();
    let target: number;
    if (where === "center") target = row - viewRows / 2 + 0.5;
    else if (row < top) target = row;
    else if (row + 1 > top + viewRows) target = row + 1 - viewRows;
    else return;
    this.wrap.scrollTop = this.scrollTopFor(Math.max(0, target));
    this.queueRender();
  }

  private visiblePages(): Set<number> {
    const first = this.start + Math.floor(this.topRow());
    const n = Math.ceil(this.viewHeight() / this.rowH) + 1;
    const out = new Set<number>();
    for (let p = Math.floor(Math.max(this.start, first - OVERSCAN) / PAGE); p <= Math.floor((first + n + OVERSCAN) / PAGE); p++) out.add(p);
    return out;
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
    if (this.holding) return;
    const count = this.count;
    const height = Math.min(count * this.rowH, MAX_HEIGHT);
    const top = this.topRow();
    const viewRows = Math.ceil(this.viewHeight() / this.rowH) + 1;
    const first = Math.max(0, Math.floor(top) - OVERSCAN);
    const last = Math.min(count, Math.floor(top) + viewRows + OVERSCAN);
    // Where row `first` sits, in the body's coordinates: its own place when every row has its
    // pixels; when scaled, where the view is, less the part of the top row scrolled past.
    const y = this.scaled()
        ? this.scrollTopFor(top) - (top - first) * this.rowH
        : first * this.rowH;

    const rows: HTMLTableRowElement[] = [];
    const missing = new Set<number>();
    for (let r = first; r < last; r++) {
      const pos = this.start + r;
      const e = this.at(pos);
      let tr: HTMLTableRowElement;
      if (e) {
        tr = this.hooks.row(e);
        if (e.id === this.selectedId) tr.classList.add("selected");
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
    const before = spacer(y);
    const after = spacer(height - Math.max(0, y) - rows.length * this.rowH);
    this.body.replaceChildren(before, ...rows, after);
    if (this.following && this.wrap.scrollTop + this.wrap.clientHeight < this.wrap.scrollHeight - 1) {
      // New rows grew the content under us: stay on the newest.
      this.wrap.scrollTop = this.wrap.scrollHeight;
      this.lastTop = this.wrap.scrollTop;
      this.queueRender();
    }
    for (const p of missing) void this.load(p);

    // Rows are a fixed height in CSS; measure once fonts have settled, and redo the layout if it differs.
    const sample = rows.find((r) => !r.classList.contains("loading"));
    if (sample) {
      const h = sample.getBoundingClientRect().height;
      if (h > 0 && Math.abs(h - this.rowH) > 0.25) {
        this.rowH = h;
        if (this.following) this.scrollToBottom();
        this.queueRender();
      }
    }
  }
}
