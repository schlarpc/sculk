import { Api, ApiError, AuthError, type SculkEvent } from "./api";
import { byId, el, store } from "./dom";
import { installTextures } from "./textures";
import { conn, field, frame, mod, roundTripBad } from "./event";
import { inspect } from "./inspector";
import { menuOpen, onMenuClose, showMenu, type MenuItem } from "./menu";
import { frameOf } from "./views";
import { ScriptsPage } from "./scripts";
import { EventTable } from "./table";

const TOKEN_KEY = "sculk.token";

const PRESETS: [string, string][] = [
  ["everything", ""],
  ["packets", "kind == packet"],
  ["mod payloads", "mod"],
  ["not vanilla", "kind == packet && owner != minecraft"],
  ["injected", "injected"],
  ["dropped / replaced", "kind == packet && action != pass"],
  ["scripts", 'kind matches "^(script|eval)"'],
  ["errors", 'kind matches "error" || mod.roundTrip matches "^(mismatch|error)"'],
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
      // Vanilla names without their namespace: the owner column says minecraft.
      return str(field(e, "channel") ?? field(e, "type")).replace(/^minecraft:/, "");
    case "connection":
      return `${str(conn(e)?.side)} ← ${str(conn(e)?.remote)}`;
    case "disconnect":
      return `connection #${str(conn(e)?.id)} closed`;
    case "payload_type": {
      const m = mod(e);
      return `${str(m?.channel)}  ${str(m?.flow)}${m?.registeredVia?.length ? "  via " + m.registeredVia.join(", ") : ""}`;
    }
    case "script":
      return `${e.script ? `${str(e.script)}: ` : ""}${str(e.data)}`;
    case "script_log":
      return `${str(e.script)}: ${str(e.message)}`;
    case "script_error":
      return `${str(e.script ?? e.callback)}: ${str(e.error)}`;
    case "script_run":
      return `started ${str(e.script)}`;
    case "script_stop":
      return `stopped ${str(e.script)}`;
    case "script_file":
      return `${str(e.action)} ${str(e.script)}${e.to ? ` → ${str(e.to)}` : ""}`;
    case "eval":
      return str(e.source).replace(/\s+/g, " ");
    case "script_reload":
      return e.ok ? `reloaded ${(e.loaded as string[] | undefined)?.join(", ") || "no scripts"}` : `reload failed: ${str(e.errors)}`;
    case "start":
      return str(e.platform);
    case "mark":
      return str(e.text);
    case "chat":
      return `${e.script ? `${str(e.script)}: ` : ""}${str(e.text)}`;
    case "mcp_client": {
      const c = e.client as { number?: number; client?: string } | undefined;
      return `${str(e.action)} MCP client #${str(c?.number)} ${str(c?.client)}`;
    }
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

/** Wraps a filter in parentheses when it has a top-level-looking "or", so "&& x" binds to all of it. */
function grouped(filter: string): string {
  return /\|\||\bor\b/.test(filter) ? `(${filter})` : filter;
}

const hexOf = (bytes: Uint8Array) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");

/** A JavaScript string literal. */
const js = (s: string) => `'${s.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;

async function copy(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    toast(text.length > 80 ? `copied ${text.length.toLocaleString()} characters` : `copied ${text}`);
  } catch {
    toast("the browser refused clipboard access");
  }
}

let toastTimer = 0;
function toast(text: string): void {
  let t = document.getElementById("toast");
  if (!t) {
    t = el("div", "toast");
    t.id = "toast";
    t.setAttribute("role", "status");
    document.body.append(t);
  }
  t.textContent = text;
  t.classList.add("on");
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => t?.classList.remove("on"), 2200);
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

  private readonly table: EventTable;
  /** "Clear" hides events up to this id. */
  private floor = 0;
  private sensorTimer = 0;
  private readonly scripts: ScriptsPage;
  private asking: Promise<void> | null = null;

  constructor(private api: Api) {
    this.table = new EventTable(this.wrap, this.rows, {
      // A refused token gives undefined (a new one is being asked for); other failures throw.
      source: {
        page: (filter, from, count, progress, signal) => this.guard(() => this.api.page(filter, from, count, progress, signal)),
        position: (filter, id, progress, signal) => this.guard(() => this.api.position(filter, id, progress, signal)),
      },
      guard: (call) => this.guard(() => call(this.api)),
      progress: (p) => this.showProgress(p),
      row: (e) => this.row(e),
      selected: (e) => this.showDetail(e),
      changed: () => this.updateFoot(),
      pulse: () => this.pulse(),
      live: (on) => this.setLive(on),
    });
    this.scripts = new ScriptsPage({
      guard: (call) => this.guard(() => call(this.api)),
      toast,
      copy: (text) => void copy(text),
      time,
    });
  }

  start(): void {
    this.wireFilter();
    this.wireTable();
    this.wireRepl();
    this.wireMenu();
    // The rows hold still while a menu is open over them.
    onMenuClose(() => this.table.hold(false));
    byId<HTMLButtonElement>("reload").onclick = () => void this.reload();
    this.filter.value = store.get("sculk.filter") ?? "";
    void this.applyFilter();
    void this.refreshStatus();
    setInterval(() => void this.refreshStatus(), 5000);
    window.addEventListener("hashchange", () => this.route());
    this.route();
  }

  // ---- pages ----

  /** #scripts or #scripts/<name> shows the script editor; anything else the events. */
  private route(): void {
    const hash = location.hash.slice(1);
    const scripts = hash === "scripts" || hash.startsWith("scripts/");
    byId("page-events").hidden = scripts;
    byId("page-scripts").hidden = !scripts;
    for (const tab of document.querySelectorAll<HTMLAnchorElement>(".tabs a")) {
      const on = tab.dataset.page === (scripts ? "scripts" : "events");
      tab.classList.toggle("on", on);
      if (on) tab.setAttribute("aria-current", "page");
      else tab.removeAttribute("aria-current");
    }
    if (scripts) {
      const name = hash.startsWith("scripts/") ? decodeURIComponent(hash.slice("scripts/".length)) : undefined;
      this.scripts.show(name || undefined);
    } else {
      this.scripts.hide();
    }
  }

  /** Runs an API call; on a bad token asks for a new one and restarts. */
  private async guard<T>(call: () => Promise<T>): Promise<T | undefined> {
    try {
      return await call();
    } catch (e) {
      if (e instanceof AuthError) {
        if (!this.asking) {
          this.asking = askForToken("The token was refused. The game makes a new one at every launch.").then((token) => {
            this.api = new Api(token);
            this.asking = null;
            void this.applyFilter();
            this.scripts.reconnect();
          });
        }
        await this.asking;
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
        ...s.scripts.map((p) => `running ${p}`),
        ...(s.capture
          ? [
              `capture: events #${s.capture.firstId}–#${s.capture.lastId}` +
                (s.capture.diskBytes !== undefined
                  ? `, ${(s.capture.diskBytes / 2 ** 20).toFixed(0)} of ${((s.capture.diskCap ?? 0) / 2 ** 20).toFixed(0)} MiB on disk`
                  : ", in memory only"),
            ]
          : []),
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
    this.pauseButton.onclick = () => this.setPaused(this.table.following);
    byId<HTMLButtonElement>("clear").onclick = () => void this.clearRows();
  }

  /** The filter the table shows: the user's, above the "Clear" floor. */
  private viewFilter(): string {
    const f = this.filter.value.trim();
    if (!this.floor) return f;
    return f ? `(${f}) && id > ${this.floor}` : `id > ${this.floor}`;
  }

  private async applyFilter(revealId?: number): Promise<void> {
    store.set("sculk.filter", this.filter.value.trim());
    try {
      await this.table.setFilter(this.viewFilter(), revealId === undefined ? {} : { revealId });
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) {
        this.filterError.textContent = e.message;
        this.filter.classList.add("invalid");
      } else {
        this.filterError.textContent = `cannot reach the game: ${(e as Error).message}`;
      }
      this.filterError.hidden = false;
      return;
    }
    this.filterError.hidden = true;
    this.filter.classList.remove("invalid");
    this.updateFoot();
  }

  private scanning: { done: number; total: number } | null = null;

  /** A filter scan on the server: "filtering… 1.2M of 3.4M" in the foot, until it is done. */
  private showProgress(p: { done: number; total: number } | null): void {
    this.scanning = p;
    this.updateFoot();
  }

  private updateFoot(): void {
    if (this.scanning) {
      const { done, total } = this.scanning;
      const short = (n: number) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${Math.round(n / 1e3)}k` : String(n));
      this.count.textContent = `filtering… ${short(done)} of ${short(total)}`;
      this.count.classList.add("scanning");
      return;
    }
    this.count.classList.remove("scanning");
    const n = this.table.count;
    const filtered = this.filter.value.trim() !== "";
    this.count.textContent = `${n.toLocaleString()} ${filtered ? "matching" : "events"}`;
    const showAll = byId<HTMLButtonElement>("show-all");
    showAll.hidden = this.floor === 0;
    showAll.textContent = `cleared at #${this.floor} · show all`;
    const following = this.table.following;
    this.pauseButton.textContent = following ? "Pause" : `Resume${this.table.unseen ? ` (+${this.table.unseen.toLocaleString()})` : ""}`;
    this.pauseButton.classList.toggle("on", !following);
    this.pauseButton.title = following ? "Stop following new events" : "Jump to the newest events and follow them";
  }

  // ---- table ----

  private wireTable(): void {
    this.rows.addEventListener("click", (e) => {
      const row = (e.target as HTMLElement).closest("tr");
      if (row?.dataset.id) this.table.select(Number(row.dataset.id));
    });
    // Double-click selects a word, triple-click a line; neither is wanted here.
    this.rows.addEventListener("mousedown", (e) => {
      if (e.detail > 1) e.preventDefault();
    });
    // Double-click a cell to narrow the filter to its value.
    this.rows.addEventListener("dblclick", (e) => {
      const cell = (e.target as HTMLElement).closest<HTMLElement>("td[data-field]");
      const row = cell?.closest("tr");
      const event = row?.dataset.id ? this.table.event(Number(row.dataset.id)) : undefined;
      const name = cell?.dataset.field;
      const value = event && name ? field(event, name) : undefined;
      if (!name || value === undefined || value === null) return;
      const clause = `${name} == ${filterValue(value)}`;
      const current = this.filter.value.trim();
      this.filter.value = current ? `${current} && ${clause}` : clause;
      void this.applyFilter();
    });
    this.wrap.addEventListener("keydown", (e) => {
      const page = Math.max(1, Math.floor(this.wrap.clientHeight / 21) - 2);
      const moves: Record<string, () => void> = {
        ArrowDown: () => this.table.step(1),
        ArrowUp: () => this.table.step(-1),
        PageDown: () => this.table.step(page),
        PageUp: () => this.table.step(-page),
        Home: () => void this.table.selectPos(this.table.start),
        End: () => void this.table.selectPos(this.table.total - 1),
      };
      const move = moves[e.key];
      if (!move) return;
      e.preventDefault();
      move();
    });
    byId<HTMLButtonElement>("show-all").onclick = () => {
      this.floor = 0;
      void this.applyFilter();
    };
  }

  private row(e: SculkEvent): HTMLTableRowElement {
    const tr = el("tr", `k-${e.kind}`);
    tr.dataset.id = String(e.id);
    const dirValue = field(e, "dir");
    if (dirValue) tr.classList.add(`d-${str(dirValue)}`);
    if (e.injected) tr.classList.add("injected");
    if (e.action && e.action !== "pass") tr.classList.add(`a-${str(e.action)}`);
    if (roundTripBad(mod(e)?.roundTrip)) tr.classList.add("rt-bad");

    // Markers in front of the direction: what sculk did to the packet. Always
    // two cells wide, so the arrows line up.
    const dropped = e.action === "drop", replaced = e.action === "replace";
    const marks = (e.injected ? "✦" : "") + (dropped ? "✕" : replaced ? "↻" : "");
    const dir = dirValue === "inbound" ? "◀ in" : dirValue === "outbound" ? "▶ out" : "";
    const dirText = dir || marks ? marks.padEnd(2, "\u00a0") + "\u00a0" + dir : "";
    const dirTitle = [e.injected ? "✦ injected by a script" : "", dropped ? "✕ dropped by a script" : "", replaced ? "↻ replaced by a script" : ""]
      .filter(Boolean)
      .join("\n");
    // [text, class, field to filter on, clipped to the cell with a tooltip]
    const cells: [string, string, string?, boolean?][] = [
      [String(e.id), "c-id"],
      [time(e.t), "c-time"],
      [e.kind, "c-kind", "kind"],
      [dirText, "c-dir", "dir"],
      [conn(e)?.id === undefined ? "" : `#${str(conn(e)?.id)}`, "c-conn", "conn.id"],
      [str(field(e, "owner")), "c-owner", "owner", true],
      [typeof frame(e)?.len === "number" ? String(frame(e)?.len) : "", "c-len", "len"],
      [summary(e), "c-summary", e.kind === "packet" ? (mod(e) ? "channel" : "type") : undefined, true],
    ];
    for (const [text, cls, field, clip] of cells) {
      // Every cell clips: columns are fixed widths, so a long value never moves them.
      const td = clip ? el("td", cls) : el("td", cls, text);
      if (clip) {
        td.append(el("span", undefined, text));
        if (text) td.title = text;
      }
      if (cls === "c-dir" && dirTitle) td.title = dirTitle;
      if (field) td.dataset.field = td.dataset.path = field;
      tr.append(td);
    }
    return tr;
  }

  /** "Clear": hide everything so far; "show all" brings it back. */
  private async clearRows(): Promise<void> {
    const s = await this.guard(() => this.api.status()).catch(() => undefined);
    if (!s) return;
    this.floor = s.lastEventId;
    await this.applyFilter();
  }

  private setPaused(paused: boolean): void {
    this.table.setFollowing(!paused);
  }

  // ---- detail ----

  private showDetail(e: SculkEvent | null): void {
    if (!e) {
      this.detail.replaceChildren(el("p", "hint", "Select an event to inspect it and its frame."));
      return;
    }
    const id = e.id;
    inspect(this.detail, e, time(e.t), (frameId) => this.guard(() => this.api.frame(frameId)), () => this.table.selectedId === id);
  }

  // ---- context menu ----

  private wireMenu(): void {
    document.addEventListener("contextmenu", (ev) => {
      const target = ev.target as HTMLElement;
      let row = target.closest<HTMLTableRowElement>("#rows tr[data-id]");
      // The Menu key or Shift+F10 on the table opens the menu for the selected row.
      const selected = this.table.selectedId;
      const keyboard = !row && target === this.wrap && selected !== null;
      if (keyboard) row = this.table.rowOf(selected as number);
      if (!row && !this.detail.contains(target)) return; // the browser's own menu elsewhere
      const id = row ? Number(row.dataset.id) : selected;
      const event = id === null ? undefined : this.table.event(id);
      if (!event) return;
      ev.preventDefault();
      if (row && event.id !== selected) this.table.select(event.id);
      this.table.hold(true);
      let x = ev.clientX, y = ev.clientY;
      let at: HTMLElement = target;
      if (keyboard && row) {
        const r = row.getBoundingClientRect();
        x = r.left + 80;
        y = r.bottom;
        at = row.querySelector<HTMLElement>(".c-summary") ?? row;
      }
      showMenu(x, y, this.menuItems(event, at), "Shift+click: edit the filter before it runs");
    });
  }

  private menuItems(e: SculkEvent, target: HTMLElement): MenuItem[] {
    const items: MenuItem[] = [];
    const fieldEl = target.closest<HTMLElement>("[data-path]");
    const path = fieldEl?.dataset.path;
    if (fieldEl && path) {
      const value = fieldEl.dataset.value !== undefined ? (JSON.parse(fieldEl.dataset.value) as unknown) : field(e, path);
      if (value !== undefined) items.push(...this.filterItems(path, value, fieldEl.dataset.inList !== undefined), "separator");
    }

    const hex = target.closest<HTMLElement>(".hexdump");
    const bytes = hex ? frameOf.get(hex) : undefined;
    const spanField = hex ? target.closest<HTMLElement>("[data-field]")?.dataset.field : undefined;
    if (bytes && spanField) {
      const span = frame(e)?.spans?.find((s) => s.field === spanField);
      if (span) {
        const part = bytes.slice(span.off, span.off + span.len);
        items.push(
          { heading: `${spanField} · ${span.len} bytes` },
          { label: "Copy as hex", run: () => void copy(hexOf(part)) },
          { label: "Copy as sculk.unhex(…)", run: () => void copy(`sculk.unhex(${js(hexOf(part))})`) },
          "separator",
        );
      }
    }

    items.push({ heading: `#${e.id} · ${e.kind}` });
    items.push({ label: "Show in context", hint: "clear the filter", run: () => void this.showInContext(e.id) });
    const f = frame(e);
    if (e.kind === "packet" && typeof f?.len === "number" && f.len > 0) {
      items.push(
        { label: "Replay…", hint: "in the console", run: () => void this.prefillReplay(e) },
        { label: "Drop packets like this…", hint: "in the console", run: () => this.prefillDrop(e) },
        { label: "Copy frame as hex", run: () => void this.withFrame(e, (b) => copy(hexOf(b))) },
        { label: "Copy frame as sculk.unhex(…)", run: () => void this.withFrame(e, (b) => copy(`sculk.unhex(${js(hexOf(b))})`)) },
      );
    }
    return items;
  }

  /** Filter actions for one field: {@code inList} when its path goes through a list (any element matches). */
  private filterItems(path: string, value: unknown, inList: boolean): MenuItem[] {
    let is: string, isNot: string;
    if (value !== null && typeof value === "object") {
      // An object or list: whether it is there at all.
      is = path;
      isNot = `!${path}`;
    } else {
      is = `${path} == ${filterValue(value)}`;
      isNot = inList ? `!(${is})` : `${path} != ${filterValue(value)}`;
    }
    const current = () => this.filter.value.trim();
    const apply = (next: string, prepare: boolean) => {
      this.filter.value = next;
      if (prepare) {
        this.filter.focus();
        this.filter.setSelectionRange(next.length, next.length);
      } else void this.applyFilter();
    };
    return [
      { heading: is.length > 70 ? is.slice(0, 69) + "…" : is },
      { label: "Filter on this", run: (ev) => apply(is, ev.shiftKey) },
      { label: "Narrow", hint: "… && this", run: (ev) => apply(current() ? `${grouped(current())} && ${is}` : is, ev.shiftKey) },
      { label: "Exclude", hint: "… && not this", run: (ev) => apply(current() ? `${grouped(current())} && ${isNot}` : isNot, ev.shiftKey) },
      { label: "Widen", hint: "… || this", run: (ev) => apply(current() ? `${current()} || ${is}` : is, ev.shiftKey) },
      "separator",
      { label: "Copy value", run: () => void copy(typeof value === "string" ? value : JSON.stringify(value)) },
      { label: "Copy path", run: () => void copy(path) },
      { label: "Copy as filter", run: () => void copy(is) },
    ];
  }

  private async withFrame(e: SculkEvent, use: (bytes: Uint8Array) => unknown): Promise<void> {
    try {
      const bytes = await this.guard(() => this.api.frame(e.id));
      if (bytes) await use(bytes);
      else if (bytes === null) toast("the game no longer holds this frame");
    } catch (err) {
      toast((err as Error).message);
    }
  }

  private async prefillReplay(e: SculkEvent): Promise<void> {
    const dir = String(field(e, "dir"));
    const id = conn(e)?.id;
    const name = String(field(e, "channel") ?? field(e, "type") ?? "?");
    await this.withFrame(e, (bytes) =>
      this.prefillRepl(
        `// Replay #${e.id} (${name}): send its frame again, ${dir}, on connection #${id}.\n` +
          `sculk.connection(${id}).inject(${js(dir)}, sculk.unhex(${js(hexOf(bytes))}))`,
      ),
    );
  }

  private prefillDrop(e: SculkEvent): void {
    const dir = String(field(e, "dir"));
    const channel = field(e, "channel");
    const test = channel ? `e.channel === ${js(String(channel))}` : `e.type === ${js(String(field(e, "type")))}`;
    this.prefillRepl(
      `// Drop ${dir} ${String(channel ?? field(e, "type"))} packets, until scripts are reloaded.\n` +
        `sculk.on('packet', e => {\n  if (e.dir === ${js(dir)} && ${test}) e.drop();\n});`,
    );
  }

  private prefillRepl(code: string): void {
    const input = byId<HTMLTextAreaElement>("repl-input");
    input.value = code;
    input.dispatchEvent(new Event("input"));
    input.focus();
    input.setSelectionRange(code.length, code.length);
    toast("in the console: check it, then press Enter to run it");
  }

  /** Clears the filter and scrolls to {@code id} among all events, without following new ones. */
  private async showInContext(id: number): Promise<void> {
    this.filter.value = "";
    this.floor = 0;
    await this.applyFilter(id);
    if (this.table.selectedId !== id) toast("the game no longer holds that event");
    else {
      const e = this.table.event(id);
      if (e) this.showDetail(e);
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
    this.replOut(
      "JavaScript in the console's own scope; sculk.scope('x.js') reaches a running script's globals. `sculk` is the scripting API. " +
        "Enter runs, Shift+Enter adds a line, ↑/↓ walk history. Scripts to keep belong on the Scripts tab.",
      "r-hint",
    );
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
    if (!confirm("Reload scripts? This stops every script and drops all script state, the console's too, then starts the enabled startup scripts again.")) return;
    try {
      const r = await this.guard(() => this.api.reload());
      if (!r) return;
      this.replOut(
        r.ok ? `reloaded ${r.loaded.length} script(s): ${r.loaded.join(", ") || "none"}` : r.errors.map((e) => `${e.script}: ${e.error}`).join("\n"),
        r.ok ? "r-ok" : "r-err",
      );
      toast(r.ok ? `reloaded ${r.loaded.length} script(s)` : `reload: ${r.errors.length} script(s) failed`);
      void this.scripts.refresh();
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
