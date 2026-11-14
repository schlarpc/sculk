import { Api, ApiError, AuthError, type SculkEvent } from "./api";
import { byId, el, store } from "./dom";
import { installTextures } from "./textures";
import { hexDump, jsonTree } from "./views";

const MAX_ROWS = 5000;
const BACKLOG = 500;
const TOKEN_KEY = "sculk.token";

const PRESETS: [string, string][] = [
  ["everything", ""],
  ["packets", "kind == packet"],
  ["mod payloads", "kind == packet && channel"],
  ["not vanilla", "kind == packet && owner != minecraft"],
  ["injected", "injected"],
  ["dropped / replaced", "kind == packet && action != pass"],
  ["scripts", 'kind matches "^(script|eval)"'],
  ["errors", 'kind matches "error" || roundTrip matches "^(mismatch|error)"'],
];

// ---- token ----

function readToken(): string | null {
  const hash = new URLSearchParams(location.hash.slice(1));
  const fromHash = hash.get("token");
  if (fromHash) {
    try {
      sessionStorage.setItem(TOKEN_KEY, fromHash);
    } catch {
      // The token then lasts only until the page reloads.
    }
    // Keep the token out of the address bar and history.
    history.replaceState(null, "", location.pathname + location.search);
    return fromHash;
  }
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function askForToken(reason: string): Promise<string> {
  const dialog = byId<HTMLDialogElement>("login");
  const input = byId<HTMLInputElement>("login-token");
  const form = byId<HTMLFormElement>("login-form");
  byId("login-reason").textContent = reason;
  input.value = "";
  if (!dialog.open) dialog.showModal();
  return new Promise((resolve) => {
    form.onsubmit = (e) => {
      e.preventDefault();
      const token = input.value.trim();
      if (!token) return;
      try {
        sessionStorage.setItem(TOKEN_KEY, token);
      } catch {
        // See readToken.
      }
      dialog.close();
      resolve(token);
    };
  });
}

// ---- formatting ----

function time(t: number): string {
  const d = new Date(t);
  const pad = (n: number, w = 2) => String(n).padStart(w, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

function str(v: unknown): string {
  if (v === null || v === undefined) return "";
  return typeof v === "string" ? v : JSON.stringify(v);
}

function summary(e: SculkEvent): string {
  switch (e.kind) {
    case "packet":
      return str(e.channel ?? e.type);
    case "connection":
      return `${str(e.side)} ← ${str(e.remote)}`;
    case "disconnect":
      return `connection #${str(e.conn)} closed`;
    case "payload_type":
      return `${str(e.channel)}  ${str(e.flow)}${Array.isArray(e.registeredVia) && e.registeredVia.length ? "  via " + e.registeredVia.join(", ") : ""}`;
    case "script":
      return str(e.data);
    case "script_error":
      return `${str(e.script ?? e.callback)}: ${str(e.error)}`;
    case "eval":
      return str(e.source).replace(/\s+/g, " ");
    case "script_reload":
      return e.ok ? `reloaded ${str(e.loaded)}` : `reload failed: ${str(e.errors)}`;
    case "start":
      return str(e.platform);
    default: {
      const { id: _id, kind: _kind, t: _t, ...rest } = e;
      return JSON.stringify(rest);
    }
  }
}

/** Quotes a value for the filter language. */
function filterValue(v: unknown): string {
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return JSON.stringify(str(v));
}

// ---- the app ----

class App {
  private readonly rows = byId<HTMLTableSectionElement>("rows");
  private readonly wrap = byId<HTMLDivElement>("table-wrap");
  private readonly filter = byId<HTMLInputElement>("filter");
  private readonly filterError = byId<HTMLDivElement>("filter-error");
  private readonly pauseButton = byId<HTMLButtonElement>("pause");
  private readonly detail = byId<HTMLDivElement>("detail");
  private readonly sensor = byId("sensor");
  private readonly statusLine = byId("status");
  private readonly count = byId("count");

  private readonly events = new Map<number, SculkEvent>();
  private readonly rowById = new Map<number, HTMLTableRowElement>();
  private pending: SculkEvent[] = [];
  private flushQueued = false;
  private paused = false;
  private selected: number | null = null;
  private lastId = 0;
  private stream: AbortController | null = null;
  private sensorTimer = 0;

  constructor(private api: Api) {}

  start(): void {
    this.wireFilter();
    this.wireTable();
    this.wireRepl();
    byId<HTMLButtonElement>("reload").onclick = () => void this.reload();
    this.filter.value = store.get("sculk.filter") ?? "";
    void this.applyFilter();
    void this.refreshStatus();
    setInterval(() => void this.refreshStatus(), 5000);
  }

  /** Runs an API call; on a bad token asks for a new one and restarts. */
  private async guard<T>(call: () => Promise<T>): Promise<T | undefined> {
    try {
      return await call();
    } catch (e) {
      if (e instanceof AuthError) {
        this.stream?.abort();
        this.api = new Api(await askForToken("The token was refused. The game makes a new one at every launch."));
        void this.applyFilter();
        return undefined;
      }
      throw e;
    }
  }

  // ---- status ----

  private async refreshStatus(): Promise<void> {
    try {
      const s = await this.guard(() => this.api.status());
      if (!s) return;
      const open = s.connections.filter((c) => c.open);
      this.statusLine.replaceChildren(
        el("span", "st-platform", s.platform),
        el("span", "st-sep", "·"),
        el("span", open.length ? "st-conns" : "st-conns st-none", `${open.length} connection${open.length === 1 ? "" : "s"}`),
        el("span", "st-sep", "·"),
        el("span", undefined, `${s.events.toLocaleString()} events held`),
      );
      this.statusLine.title = [
        `pid ${s.pid}`,
        ...open.map((c) => `#${c.id} ${c.side} ${c.remote}`),
        ...s.scripts.map((p) => `script ${p}`),
      ].join("\n");
      this.statusLine.classList.remove("st-down");
    } catch {
      this.statusLine.replaceChildren(el("span", undefined, "game not reachable"));
      this.statusLine.classList.add("st-down");
    }
  }

  private pulse(): void {
    this.sensor.classList.add("active");
    clearTimeout(this.sensorTimer);
    this.sensorTimer = window.setTimeout(() => this.sensor.classList.remove("active"), 400);
  }

  private setLive(live: boolean): void {
    this.sensor.classList.toggle("live", live);
    this.sensor.title = live ? "live: listening for events" : "not listening";
  }

  // ---- filter and stream ----

  private wireFilter(): void {
    byId<HTMLFormElement>("filter-form").onsubmit = (e) => {
      e.preventDefault();
      void this.applyFilter();
    };
    const presets = byId("presets");
    for (const [label, filter] of PRESETS) {
      const b = el("button", "chip", label);
      b.type = "button";
      b.title = filter || "no filter";
      b.onclick = () => {
        this.filter.value = filter;
        void this.applyFilter();
      };
      presets.append(b);
    }
    this.pauseButton.onclick = () => this.setPaused(!this.paused);
    byId<HTMLButtonElement>("clear").onclick = () => this.clearRows();
  }

  private async applyFilter(): Promise<void> {
    const filter = this.filter.value.trim();
    store.set("sculk.filter", filter);
    this.stream?.abort();
    const controller = new AbortController();
    this.stream = controller;
    this.setLive(false);
    let backlog: SculkEvent[];
    try {
      const result = await this.guard(() => this.api.events(filter, BACKLOG));
      if (!result || controller.signal.aborted) return;
      backlog = result;
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        this.filterError.textContent = e.message;
        this.filterError.hidden = false;
        this.filter.classList.add("invalid");
      } else {
        this.filterError.textContent = `cannot reach the game: ${(e as Error).message}`;
        this.filterError.hidden = false;
      }
      return;
    }
    this.filterError.hidden = true;
    this.filter.classList.remove("invalid");
    this.clearRows();
    for (const e of backlog) this.add(e);
    if (backlog.length) this.lastId = (backlog[backlog.length - 1] as SculkEvent).id;
    else {
      const s = await this.guard(() => this.api.status()).catch(() => undefined);
      this.lastId = s?.lastEventId ?? this.lastId;
    }
    void this.follow(filter, controller.signal);
  }

  private async follow(filter: string, signal: AbortSignal): Promise<void> {
    while (!signal.aborted) {
      try {
        this.setLive(true);
        for await (const e of this.api.stream(filter, this.lastId, signal)) {
          this.lastId = e.id;
          this.add(e);
          this.pulse();
        }
      } catch (e) {
        if (signal.aborted) return;
        if (e instanceof AuthError) {
          void this.guard(() => Promise.reject(e));
          return;
        }
      }
      this.setLive(false);
      await new Promise((r) => setTimeout(r, 2000));
    }
  }

  // ---- table ----

  private wireTable(): void {
    this.rows.addEventListener("click", (e) => {
      const row = (e.target as HTMLElement).closest("tr");
      if (row?.dataset.id) void this.select(Number(row.dataset.id));
    });
    // Double-click selects a word, triple-click a line; neither is wanted here.
    this.rows.addEventListener("mousedown", (e) => {
      if (e.detail > 1) e.preventDefault();
    });
    // Double-click a cell to narrow the filter to its value.
    this.rows.addEventListener("dblclick", (e) => {
      const cell = (e.target as HTMLElement).closest<HTMLElement>("td[data-field]");
      const row = cell?.closest("tr");
      const event = row?.dataset.id ? this.events.get(Number(row.dataset.id)) : undefined;
      const field = cell?.dataset.field;
      if (!event || !field || event[field] === undefined || event[field] === null) return;
      const clause = `${field} == ${filterValue(event[field])}`;
      const current = this.filter.value.trim();
      this.filter.value = current ? `${current} && ${clause}` : clause;
      void this.applyFilter();
    });
    this.wrap.addEventListener("keydown", (e) => {
      if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
      e.preventDefault();
      const current = this.selected === null ? null : this.rowById.get(this.selected);
      const next = (e.key === "ArrowDown" ? current?.nextElementSibling : current?.previousElementSibling) as
        | HTMLTableRowElement
        | null
        | undefined;
      const target = next ?? (e.key === "ArrowDown" ? this.rows.firstElementChild : this.rows.lastElementChild);
      if (target instanceof HTMLTableRowElement && target.dataset.id) {
        void this.select(Number(target.dataset.id));
        target.scrollIntoView({ block: "nearest" });
      }
    });
  }

  private add(e: SculkEvent): void {
    this.pending.push(e);
    if (!this.flushQueued && !this.paused) {
      this.flushQueued = true;
      requestAnimationFrame(() => this.flush());
    }
    if (this.paused) this.pauseButton.textContent = `Resume (+${this.pending.length})`;
  }

  private flush(): void {
    this.flushQueued = false;
    if (this.paused || !this.pending.length) return;
    const atBottom = this.wrap.scrollTop + this.wrap.clientHeight >= this.wrap.scrollHeight - 40;
    const batch = this.pending.slice(-MAX_ROWS);
    this.pending = [];
    const fragment = document.createDocumentFragment();
    for (const e of batch) {
      if (this.events.has(e.id)) continue;
      this.events.set(e.id, e);
      const row = this.row(e);
      this.rowById.set(e.id, row);
      fragment.append(row);
    }
    this.rows.append(fragment);
    while (this.events.size > MAX_ROWS) {
      const oldest = this.rows.firstElementChild as HTMLTableRowElement | null;
      if (!oldest) break;
      const id = Number(oldest.dataset.id);
      oldest.remove();
      this.events.delete(id);
      this.rowById.delete(id);
    }
    this.count.textContent = `${this.events.size.toLocaleString()} shown`;
    if (atBottom) this.wrap.scrollTop = this.wrap.scrollHeight;
  }

  private row(e: SculkEvent): HTMLTableRowElement {
    const tr = el("tr", `k-${e.kind}`);
    tr.dataset.id = String(e.id);
    if (e.dir) tr.classList.add(`d-${str(e.dir)}`);
    if (e.injected) tr.classList.add("injected");
    if (e.action && e.action !== "pass") tr.classList.add(`a-${str(e.action)}`);
    if (typeof e.roundTrip === "string" && /^(mismatch|error)/.test(e.roundTrip)) tr.classList.add("rt-bad");
    if (e.id === this.selected) tr.classList.add("selected");

    const dir = e.dir === "inbound" ? "◀ in" : e.dir === "outbound" ? "▶ out" : "";
    const cells: [string, string, string?][] = [
      [String(e.id), "c-id"],
      [time(e.t), "c-time"],
      [e.kind, "c-kind", "kind"],
      [(e.injected ? "✦ " : "") + dir, "c-dir", "dir"],
      [str(e.side), "c-side", "side"],
      [e.conn === undefined ? "" : `#${str(e.conn)}`, "c-conn", "conn"],
      [str(e.owner), "c-owner", "owner"],
      [e.len === undefined || e.len === null ? "" : String(e.len), "c-len", "len"],
      [e.action && e.action !== "pass" ? str(e.action) : "", "c-action", "action"],
      [summary(e), "c-summary", e.kind === "packet" ? (e.channel ? "channel" : "type") : undefined],
    ];
    for (const [text, cls, field] of cells) {
      // The summary sits out of flow, so its length never widens the table:
      // the other columns size to their content and it takes what is left.
      const td = cls === "c-summary" ? el("td", cls) : el("td", cls, text);
      if (cls === "c-summary") {
        td.title = text;
        td.append(el("span", undefined, text));
      }
      if (field) td.dataset.field = field;
      tr.append(td);
    }
    return tr;
  }

  private clearRows(): void {
    this.rows.replaceChildren();
    this.events.clear();
    this.rowById.clear();
    this.pending = [];
    this.count.textContent = "0 shown";
  }

  private setPaused(paused: boolean): void {
    this.paused = paused;
    this.pauseButton.textContent = paused ? `Resume (+${this.pending.length})` : "Pause";
    this.pauseButton.classList.toggle("on", paused);
    if (!paused) this.flush();
  }

  // ---- detail ----

  private async select(id: number): Promise<void> {
    if (this.selected !== null) this.rowById.get(this.selected)?.classList.remove("selected");
    this.selected = id;
    this.rowById.get(id)?.classList.add("selected");
    const e = this.events.get(id);
    if (!e) return;

    const header = el("div", "detail-head");
    header.append(el("span", "detail-kind", e.kind), el("span", "detail-id", `#${e.id} · ${time(e.t)}`));
    const treeSection = el("section", "detail-section");
    treeSection.append(el("h2", undefined, "Event"), jsonTree(e));
    this.detail.replaceChildren(header, treeSection);

    if (e.kind === "packet" && typeof e.len === "number" && e.len > 0) {
      const frameSection = el("section", "detail-section");
      frameSection.append(el("h2", undefined, `Frame · ${e.len} bytes`), el("p", "hint", "loading…"));
      this.detail.append(frameSection);
      try {
        const bytes = await this.guard(() => this.api.frame(id));
        if (this.selected !== id) return;
        frameSection.lastElementChild?.remove();
        frameSection.append(bytes ? hexDump(bytes) : el("p", "hint", "the game no longer holds this frame"));
      } catch (err) {
        frameSection.lastElementChild?.replaceWith(el("p", "error", (err as Error).message));
      }
    }
  }

  // ---- repl and reload ----

  private wireRepl(): void {
    const form = byId<HTMLFormElement>("repl-form");
    const input = byId<HTMLTextAreaElement>("repl-input");
    let history: string[] = [];
    try {
      history = JSON.parse(store.get("sculk.history") ?? "[]") as string[];
    } catch {
      history = [];
    }
    let cursor = history.length;
    const fit = () => {
      input.rows = Math.min(8, Math.max(1, input.value.split("\n").length));
    };

    const run = async () => {
      const code = input.value;
      if (!code.trim()) return;
      if (history[history.length - 1] !== code) history.push(code);
      history = history.slice(-200);
      store.set("sculk.history", JSON.stringify(history));
      cursor = history.length;
      input.value = "";
      fit();
      this.replEcho(code);
      // Placed now, filled in later, so results stay under their inputs.
      const out = this.replOut("…", "r-pending");
      const show = (text: string, cls: string) => {
        out.textContent = text;
        out.className = cls;
      };
      try {
        const r = await this.guard(() => this.api.eval(code));
        if (!r) show("not run: token refused", "r-err");
        else if (r.ok) show(typeof r.result === "string" ? r.result : JSON.stringify(r.result, null, 2) ?? "undefined", "r-ok");
        else show(r.error ?? "error", "r-err");
      } catch (e) {
        show((e as Error).message, "r-err");
      }
    };

    form.onsubmit = (e) => {
      e.preventDefault();
      void run();
    };
    input.addEventListener("input", fit);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        void run();
      } else if (e.key === "ArrowUp" && input.selectionStart === 0 && cursor > 0) {
        e.preventDefault();
        input.value = history[--cursor] ?? "";
        fit();
      } else if (e.key === "ArrowDown" && input.selectionEnd === input.value.length && cursor < history.length) {
        e.preventDefault();
        input.value = history[++cursor] ?? "";
        fit();
      }
    });
    byId<HTMLButtonElement>("repl-clear").onclick = () => byId("repl-out").replaceChildren();
    this.replOut("JavaScript in the live script scope. `sculk` is the scripting API. Enter runs, Shift+Enter adds a line, ↑/↓ walk history.", "r-hint");
  }

  private replEcho(code: string): void {
    const line = el("div", "r-in");
    line.append(el("span", "prompt", "> "), el("span", undefined, code));
    this.replAppend(line);
  }

  private replOut(text: string, cls: string): HTMLElement {
    const pre = el("pre", cls, text);
    this.replAppend(pre);
    return pre;
  }

  private replAppend(node: HTMLElement): void {
    const out = byId("repl-out");
    out.append(node);
    while (out.childElementCount > 500) out.firstElementChild?.remove();
    out.scrollTop = out.scrollHeight;
  }

  private async reload(): Promise<void> {
    if (!confirm("Reload scripts? This drops all script state, listeners and timers, then loads the startup scripts again.")) return;
    try {
      const r = await this.guard(() => this.api.reload());
      if (!r) return;
      this.replOut(
        r.ok ? `reloaded ${r.loaded.length} script(s): ${r.loaded.join(", ") || "none"}` : r.errors.map((e) => `${e.script}: ${e.error}`).join("\n"),
        r.ok ? "r-ok" : "r-err",
      );
    } catch (e) {
      this.replOut((e as Error).message, "r-err");
    }
  }
}

async function main(): Promise<void> {
  installTextures();
  const token = readToken() ?? (await askForToken("Paste the token from sculk-control.json, or open this page with scripts/sculkctl.py ui."));
  new App(new Api(token)).start();
}

void main();
