// One filter's view of the capture: its extent, the pages of rows loaded so
// far, and the requests in flight for it. A new filter is a new Query, and
// closing the old one aborts its requests (stopping their scans on the
// server). A response can only ever change the Query that asked for it, so
// one filter's rows never land in another's table.

import type { Page, Position, Progress, SculkEvent } from "./api";

export const PAGE = 200;
/** Pages kept, besides those in view. */
const MAX_PAGES = 60;
/** Pages one tail() fetches at most, catching up after a burst. */
const TAIL_PAGES = 5;

/**
 * What a Query needs from the server. {@code undefined} means the call was
 * not made (the token was refused, and a new one is being asked for).
 */
export interface Source {
  page(filter: string, from: number, count: number, progress: Progress | undefined, signal: AbortSignal): Promise<Page | undefined>;
  position(filter: string, id: number, progress: Progress | undefined, signal: AbortSignal): Promise<Position | undefined>;
}

/** How the extent moved when a page came in: rows added at the end, rows dropped from the front. */
export interface Delta {
  grew: number;
  dropped: number;
}

const NONE: Delta = { grew: 0, dropped: 0 };

export class Query {
  /** Position of the first row held, and one past the last. */
  start = 0;
  total = 0;

  private readonly pages = new Map<number, (SculkEvent | undefined)[]>();
  /** Page numbers, least recently loaded first. */
  private readonly order: number[] = [];
  private readonly loading = new Map<number, Promise<Delta>>();
  private readonly positions = new Map<number, number>();
  private readonly controller = new AbortController();
  private tailing: Promise<Delta> | null = null;

  constructor(
    readonly filter: string,
    private readonly source: Source,
  ) {}

  get signal(): AbortSignal {
    return this.controller.signal;
  }

  get closed(): boolean {
    return this.controller.signal.aborted;
  }

  get count(): number {
    return this.total - this.start;
  }

  /** Stops everything in flight for this filter. */
  close(): void {
    this.controller.abort();
  }

  /**
   * Learns the extent: the filter's match list is built on the server here,
   * which is the slow part of a new filter. False if it was not asked (the
   * token was refused). Throws the server's ApiError for a bad filter, and an
   * AbortError if the query is closed meanwhile.
   */
  async open(progress?: Progress): Promise<boolean> {
    const p = await this.source.page(this.filter, 0, 0, progress, this.signal);
    if (!p) return false;
    this.apply(p);
    return true;
  }

  at(pos: number): SculkEvent | undefined {
    return this.pages.get(Math.floor(pos / PAGE))?.[pos % PAGE];
  }

  positionOf(id: number): number | undefined {
    return this.positions.get(id);
  }

  /** Loads page {@code page} (positions page*PAGE onwards); concurrent calls share one request. */
  load(page: number): Promise<Delta> {
    const pending = this.loading.get(page);
    if (pending) return pending;
    const p = this.fetch(page * PAGE).finally(() => this.loading.delete(page));
    this.loading.set(page, p);
    return p;
  }

  /** Loads rows starting exactly at {@code pos}, or at the first row held after it. */
  async loadFrom(pos: number): Promise<Delta> {
    const d = await this.fetch(pos);
    if (this.start <= pos || this.closed) return d;
    // The front passed it, by more than a page if nothing came back: start from the front.
    const again = await this.fetch(this.start);
    return { grew: d.grew + again.grew, dropped: d.dropped + again.dropped };
  }

  /**
   * Asks for whatever was appended past the end; one at a time, the next one
   * sharing the request. When more arrived than a page holds, keeps fetching
   * (up to TAIL_PAGES) so the newest rows are loaded, not only counted.
   */
  tail(): Promise<Delta> {
    if (this.tailing) return this.tailing;
    this.tailing = this.catchUp().finally(() => (this.tailing = null));
    return this.tailing;
  }

  private async catchUp(): Promise<Delta> {
    // The last partial page again, and anything after it.
    let from = Math.max(this.start, Math.floor(this.total / PAGE) * PAGE);
    const sum = { grew: 0, dropped: 0 };
    for (let i = 0; i < TAIL_PAGES; i++) {
      const d = await this.fetch(from);
      sum.grew += d.grew;
      sum.dropped += d.dropped;
      from = Math.max(this.start, from + PAGE);
      if (this.closed || from >= this.total) break;
    }
    return sum;
  }

  /**
   * One past the newest loaded row, looking back at most {@code within} rows
   * from the end; the end itself when none of those is loaded.
   */
  loadedEnd(within: number): number {
    let end = this.total;
    while (end > this.start && this.total - end < within && this.at(end - 1) === undefined) end--;
    return end > this.start && this.at(end - 1) !== undefined ? end : this.total;
  }

  /** The position of the first match at or after an event id, or undefined. */
  async locate(id: number, progress?: Progress): Promise<number | undefined> {
    try {
      const p = await this.source.position(this.filter, id, progress, this.signal);
      if (!p || this.closed) return undefined;
      this.extent(p.start, p.total);
      return p.index < p.total ? p.index : undefined;
    } catch {
      return undefined;
    }
  }

  private async fetch(from: number): Promise<Delta> {
    if (this.closed) return NONE;
    try {
      const p = await this.source.page(this.filter, from, PAGE, undefined, this.signal);
      return p && !this.closed ? this.apply(p) : NONE;
    } catch {
      return NONE; // Closed, or the game went away: the rows stay blank until asked again.
    }
  }

  /** Takes a page: the extent it reports, and its rows. */
  apply(p: Page): Delta {
    const delta = this.extent(p.start, p.total);
    for (let i = 0; i < p.events.length; i++) {
      const pos = p.from + i;
      if (pos < this.start) continue;
      const page = Math.floor(pos / PAGE);
      let rows = this.pages.get(page);
      if (!rows) {
        rows = [];
        this.pages.set(page, rows);
        this.order.push(page);
      }
      const e = p.events[i] as SculkEvent;
      rows[pos % PAGE] = e;
      this.positions.set(e.id, pos);
    }
    return delta;
  }

  /** Moves the extent: never backwards (positions only grow, and the front only advances). */
  private extent(start: number, total: number): Delta {
    const grew = Math.max(0, total - this.total);
    const dropped = Math.max(0, start - this.start);
    this.start = Math.max(this.start, start);
    this.total = Math.max(this.total, total, this.start);
    if (dropped > 0) {
      for (const page of [...this.pages.keys()]) {
        if ((page + 1) * PAGE <= this.start) this.forget(page);
      }
    }
    return { grew, dropped };
  }

  /** Forgets the least recently loaded pages past the cache size, except {@code keep}. */
  trim(keep: Set<number>): void {
    let excess = this.pages.size - MAX_PAGES - keep.size;
    for (let i = 0; i < this.order.length && excess > 0; ) {
      const page = this.order[i] as number;
      if (keep.has(page)) {
        i++;
        continue;
      }
      this.forget(page);
      excess--;
    }
  }

  private forget(page: number): void {
    for (const e of this.pages.get(page) ?? []) if (e) this.positions.delete(e.id);
    this.pages.delete(page);
    const i = this.order.indexOf(page);
    if (i >= 0) this.order.splice(i, 1);
  }

  /** Pages held; for tests. */
  get pageCount(): number {
    return this.pages.size;
  }
}
