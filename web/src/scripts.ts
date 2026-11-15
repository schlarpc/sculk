// The Scripts page: installed scripts, an editor per script, and each
// script's output (its log lines, emits, errors, starts and stops) live.

import { Api, ApiError, type EvalResult, type ScriptInfo, type ScriptList, type SculkEvent } from "./api";
import { byId, el, store } from "./dom";
import { newState, newView, type EditorHooks, type EditorState, type EditorView } from "./editor";
import { showMenu, type MenuItem } from "./menu";
import * as buf from "./script-state";
import { statusView, stoppable } from "./script-state";
import { href } from "./route";

export interface Env {
  /** Runs an API call with the current token; undefined when the token was refused (a new one is being asked for). */
  guard<T>(call: (api: Api) => Promise<T>): Promise<T | undefined>;
  toast(text: string): void;
  copy(text: string): void;
  time(t: number): string;
}

const POLL_MS = 3000;
const BACKLOG = 300;
const MAX_OUT = 1000;

const TEMPLATE = (title: string) => `// ==Sculk==
// @name        ${title}
// @description What this script does
// @run         startup
// ==/Sculk==
//
// @run startup: runs at launch and whenever scripts reload, while enabled.
// @run manual: runs only when you press Run (Ctrl+Enter); good for one-shots.
// Add "// @dist client" or "// @dist server" to run in only one of them.

sculk.on('packet', e => {
  // e.dir, e.channel, e.owner, e.packet.data ... e.drop(), e.replacePacket(obj)
});
`;

/** One open script; see script-state.ts for its states. */
type Buffer = buf.Buffer<EditorState>;

const filterString = (s: string) => JSON.stringify(s);

export class ScriptsPage {
  private readonly listEl = byId<HTMLUListElement>("scripts");
  private readonly dirEl = byId("script-dir");
  private readonly host = byId("se-host");
  private readonly empty = byId("se-empty");
  private readonly nameEl = byId("se-name");
  private readonly infoEl = byId("se-info");
  private readonly banner = byId("se-banner");
  private readonly saveButton = byId<HTMLButtonElement>("se-save");
  private readonly runButton = byId<HTMLButtonElement>("se-run");
  private readonly stopButton = byId<HTMLButtonElement>("se-stop");
  private readonly moreButton = byId<HTMLButtonElement>("se-more");
  private readonly out = byId("so-out");
  private readonly outFor = byId("so-for");

  private list: ScriptList | null = null;
  private readonly buffers = new Map<string, Buffer>();
  private selected: string | null = null;
  private view: EditorView | null = null;
  private visible = false;
  private pollTimer = 0;
  private stream: AbortController | null = null;
  private readonly hooks: EditorHooks;
  private busy = false;

  constructor(private readonly env: Env) {
    this.hooks = {
      save: () => void this.save(),
      run: () => void this.run(),
      changed: (state) => this.changed(state),
      check: async (source) => (await this.env.guard((api) => api.checkScript(source)))?.problems ?? [],
    };
    byId<HTMLButtonElement>("script-new").onclick = () => void this.create();
    byId<HTMLButtonElement>("se-empty-new").onclick = () => void this.create();
    this.saveButton.onclick = () => void this.save();
    this.runButton.onclick = () => void this.run();
    this.stopButton.onclick = () => void this.stop();
    this.moreButton.onclick = () => {
      const r = this.moreButton.getBoundingClientRect();
      const info = this.selected ? this.info(this.selected) : undefined;
      if (info) showMenu(r.left, r.bottom + 2, this.menuItems(info));
    };
    byId<HTMLButtonElement>("so-clear").onclick = () => this.out.replaceChildren();
    this.listEl.addEventListener("click", (e) => {
      const target = e.target as HTMLElement;
      const item = target.closest<HTMLElement>("li[data-name]");
      if (!item?.dataset.name) return;
      if (target.closest(".sl-toggle")) {
        const info = this.info(item.dataset.name);
        if (info) void this.setEnabled(info, !info.enabled);
        return;
      }
      void this.select(item.dataset.name, "push");
    });
    this.listEl.addEventListener("keydown", (e) => {
      if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
      e.preventDefault();
      const names = this.list?.scripts.map((s) => s.name) ?? [];
      const i = this.selected ? names.indexOf(this.selected) : -1;
      const next = names[e.key === "ArrowDown" ? Math.min(names.length - 1, i + 1) : Math.max(0, i - 1)];
      if (next) {
        void this.select(next);
        this.listEl.querySelector<HTMLElement>(`li[data-name="${CSS.escape(next)}"]`)?.focus();
      }
    });
    this.listEl.addEventListener("contextmenu", (e) => {
      const item = (e.target as HTMLElement).closest<HTMLElement>("li[data-name]");
      const info = item?.dataset.name ? this.info(item.dataset.name) : undefined;
      if (!info) return;
      e.preventDefault();
      showMenu(e.clientX, e.clientY, this.menuItems(info));
    });
    window.addEventListener("beforeunload", (e) => {
      if (this.dirtyNames().length) e.preventDefault();
    });
  }

  // ---- visibility ----

  show(name?: string): void {
    this.visible = true;
    // The URL is the selection: /scripts alone selects nothing (the Scripts tab links to the last script).
    void this.refresh().then(() => {
      if (name && this.info(name)) void this.select(name);
      else this.showEmpty();
    });
    clearInterval(this.pollTimer);
    this.pollTimer = window.setInterval(() => void this.refresh(), POLL_MS);
    this.view?.focus();
  }

  hide(): void {
    this.visible = false;
    clearInterval(this.pollTimer);
  }

  /** After the token changed: the output stream must reconnect with it. */
  reconnect(): void {
    if (this.visible && this.selected) void this.follow(this.selected);
  }

  // ---- the list ----

  private info(name: string): ScriptInfo | undefined {
    return this.list?.scripts.find((s) => s.name === name);
  }

  private dirtyNames(): string[] {
    return [...this.buffers].filter(([, b]) => buf.isDirty(b)).map(([n]) => n);
  }

  private dirty(name: string): boolean {
    const b = this.buffers.get(name);
    return !!b && buf.isDirty(b);
  }

  async refresh(): Promise<void> {
    let list: ScriptList | undefined;
    try {
      list = await this.env.guard((api) => api.scripts());
    } catch (e) {
      this.dirEl.textContent = `cannot list scripts: ${(e as Error).message}`;
      this.dirEl.classList.add("error");
      return;
    }
    if (!list) return;
    this.list = list;
    this.dirEl.classList.remove("error");
    // Left-to-right marks keep the slashes in place inside the right-to-left box that shows the path's end.
    this.dirEl.textContent = `\u200e${list.dir}\u200e`;
    this.dirEl.title = `${list.dir}\nThis game is a ${list.dist === "server" ? "dedicated server" : "client"}.`;
    // Files changed on disk under an open buffer.
    for (const s of list.scripts) {
      const b = this.buffers.get(s.name);
      if (!b || !s.hash) continue;
      const { buffer, reload } = buf.onDisk(b, s.hash);
      this.buffers.set(s.name, buffer);
      if (reload) await this.loadFromDisk(s.name, "reloaded: it changed on disk");
    }
    if (this.selected && !this.info(this.selected) && !this.buffers.has(this.selected)) this.showEmpty();
    this.renderList();
    this.renderHeader();
  }

  private renderList(): void {
    const scripts = this.list?.scripts ?? [];
    const items = scripts.map((s) => this.item(s));
    if (!items.length) {
      const li = el("li", "sl-none hint", "No scripts yet. New makes one in the folder above.");
      items.push(li);
    }
    const focused = document.activeElement instanceof HTMLElement ? document.activeElement.closest<HTMLElement>("li[data-name]")?.dataset.name : undefined;
    this.listEl.replaceChildren(...items);
    if (focused) this.listEl.querySelector<HTMLElement>(`li[data-name="${CSS.escape(focused)}"]`)?.focus();
  }

  private item(s: ScriptInfo): HTMLLIElement {
    const status = statusView(s);
    const li = el("li", `sl-item st-${status.cls}`);
    li.dataset.name = s.name;
    li.tabIndex = 0;
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", String(s.name === this.selected));
    if (s.name === this.selected) li.classList.add("selected");
    if (!s.enabled) li.classList.add("off");

    const dot = el("span", "sl-dot");
    dot.title = status.detail ? `${status.text}\n${status.detail}` : status.text;
    const main = el("span", "sl-main");
    const nameLine = el("span", "sl-name", s.origin === "property" ? s.name.split(/[\\/]/).pop() ?? s.name : s.name);
    if (this.dirty(s.name)) nameLine.append(el("span", "sl-dirty", " ●"));
    main.append(nameLine);
    if (s.title) main.append(el("span", "sl-title", s.title));
    li.title = [s.title, s.description, s.path].filter(Boolean).join("\n");

    const badges = el("span", "sl-badges");
    if (s.run === "manual") badges.append(el("span", "badge b-meh", "manual"));
    if (s.skip) badges.append(Object.assign(el("span", "badge", "other dist"), { title: s.skip }));
    if (s.origin === "property") badges.append(Object.assign(el("span", "badge", "-D"), { title: "from -Dsculk.scripts: " + (s.path ?? s.name) }));
    if (s.origin === "memory") badges.append(Object.assign(el("span", "badge", "no file"), { title: "running, but not from a script file" }));

    li.append(dot, main, badges);
    if (s.origin === "dir") {
      const toggle = el("button", "sl-toggle");
      toggle.type = "button";
      toggle.setAttribute("role", "switch");
      toggle.setAttribute("aria-checked", String(s.enabled));
      toggle.title = s.enabled ? "Enabled: click to disable (stops it, renames it to .disabled)" : "Disabled: click to enable";
      toggle.setAttribute("aria-label", `${s.name} enabled`);
      li.append(toggle);
    }
    return li;
  }

  private menuItems(s: ScriptInfo): MenuItem[] {
    const file = s.origin === "dir";
    const running = s.status.state === "running";
    return [
      { heading: s.name },
      { label: running ? "Restart" : "Run", hint: "Ctrl+Enter", disabled: s.origin === "memory", run: () => void this.run(s.name) },
      { label: "Stop", disabled: !stoppable(s), run: () => void this.stop(s.name) },
      "separator",
      { label: s.enabled ? "Disable" : "Enable", disabled: !file, run: () => void this.setEnabled(s, !s.enabled) },
      { label: "Rename…", disabled: !file, run: () => void this.rename(s) },
      { label: "Duplicate…", disabled: s.origin === "memory", run: () => void this.duplicate(s) },
      { label: "Delete…", disabled: !file, run: () => void this.remove(s) },
      "separator",
      { label: "Copy name", run: () => this.env.copy(s.name) },
      { label: "Copy path", disabled: !s.path, run: () => this.env.copy(s.path ?? "") },
      { label: "Copy sculk.scope(…)", disabled: !running, run: () => this.env.copy(`sculk.scope(${JSON.stringify(s.name)})`) },
    ];
  }

  // ---- the editor ----

  private showEmpty(): void {
    // Keep the outgoing editor's state (undo history, selection) for when it comes back.
    if (this.selected && this.view) {
      const b = this.buffers.get(this.selected);
      if (b) b.state = this.view.state;
    }
    this.selected = null;
    if (this.visible && location.pathname !== href({ page: "scripts" })) history.replaceState(null, "", href({ page: "scripts" }));
    this.view?.destroy();
    this.view = null;
    this.empty.hidden = false;
    this.host.hidden = true;
    this.renderHeader();
    this.stream?.abort();
    this.outFor.textContent = "";
  }

  /** Opens a script in the editor. "push" makes it a new history entry (a click); otherwise the URL is only corrected. */
  async select(name: string, nav: "push" | "replace" = "replace"): Promise<void> {
    const url = href({ page: "scripts", name });
    if (location.pathname !== url) {
      if (nav === "push") history.pushState(null, "", url);
      else history.replaceState(null, "", url);
    }
    if (this.selected !== name || !this.view) {
      // Keep the outgoing editor's state (undo history, selection) for when it comes back.
      if (this.selected && this.view) {
        const b = this.buffers.get(this.selected);
        if (b) b.state = this.view.state;
      }
      this.selected = name;
      store.set("sculk.script", name);
      byId<HTMLAnchorElement>("tab-scripts").href = url;
      if (!this.buffers.has(name) && !(await this.loadFromDisk(name))) {
        this.renderList();
        this.renderHeader();
        return;
      }
      if (this.selected !== name) return; // another click won
      const b = this.buffers.get(name) as Buffer;
      this.empty.hidden = true;
      this.host.hidden = false;
      if (this.view) this.view.setState(b.state);
      else this.view = newView(this.host, b.state);
      void this.follow(name);
    }
    this.renderList();
    this.renderHeader();
    this.view?.focus();
  }

  /** Loads (or reloads) a script's source into its buffer. False if it has none. */
  private async loadFromDisk(name: string, note?: string): Promise<boolean> {
    const info = this.info(name);
    if (info?.origin === "memory") {
      this.buffers.set(name, buf.loaded(newState("// This script is running, but not from a file: there is no source to show.\n", this.hooks, true), "", ""));
      return true;
    }
    let src;
    try {
      src = await this.env.guard((api) => api.scriptSource(name));
    } catch (e) {
      this.env.toast((e as Error).message);
      return false;
    }
    if (!src) return false;
    const b = buf.loaded(newState(src.source, this.hooks), src.source, src.hash ?? "");
    this.buffers.set(name, b);
    if (this.selected === name && this.view) {
      // Keep the cursor where it was, as far as the new text allows.
      const head = Math.min(this.view.state.selection.main.head, src.source.length);
      this.view.setState(b.state);
      this.view.dispatch({ selection: { anchor: head }, scrollIntoView: true });
      b.state = this.view.state;
      if (note) this.line(note, "o-dim");
    }
    return true;
  }

  private changed(state: EditorState): void {
    if (!this.selected) return;
    const before = this.buffers.get(this.selected);
    if (!before) return;
    const b = buf.edited(before, state, state.doc.toString());
    this.buffers.set(this.selected, b);
    const dirty = buf.isDirty(b);
    const item = this.listEl.querySelector<HTMLElement>(`li[data-name="${CSS.escape(this.selected)}"] .sl-name`);
    if (item && !!item.querySelector(".sl-dirty") !== dirty) this.renderList();
    this.renderHeader();
  }

  private renderHeader(): void {
    const name = this.selected;
    const info = name ? this.info(name) : undefined;
    const b = name ? this.buffers.get(name) : undefined;
    const editable = !!info && info.origin !== "memory";
    this.nameEl.textContent = name ? (info?.origin === "property" ? info.path ?? name : name) : "";
    this.nameEl.classList.toggle("dirty", !!name && this.dirty(name));
    this.saveButton.disabled = !editable || !this.dirty(name as string);
    this.runButton.disabled = !editable;
    this.runButton.textContent = info?.status.state === "running" ? "Restart" : "Run";
    this.stopButton.disabled = !info || !stoppable(info);
    this.moreButton.disabled = !info;

    const parts: HTMLElement[] = [];
    if (info) {
      const status = statusView(info);
      parts.push(el("span", `si-state st-${status.cls}`, status.text));
      parts.push(el("span", "si-sep", "·"));
      parts.push(el("span", info.enabled ? "" : "si-off", info.enabled ? (info.run === "manual" ? "manual" : "runs at startup") : "disabled"));
      if (info.skip) parts.push(el("span", "si-sep", "·"), el("span", "si-warn", info.skip));
      if (status.detail) parts.push(el("span", "si-sep", "·"), Object.assign(el("span", "si-err", status.detail), { title: status.detail }));
      else if (info.description) parts.push(el("span", "si-sep", "·"), el("span", "si-desc", info.description));
    }
    this.infoEl.replaceChildren(...parts);

    if (b?.kind === "conflict") {
      this.banner.hidden = false;
      const reload = el("button", undefined, "Load theirs");
      reload.type = "button";
      reload.onclick = () => void this.loadFromDisk(name as string, "loaded the version on disk; your edits are gone").then(() => this.renderHeader());
      const keep = el("button", undefined, "Keep mine");
      keep.type = "button";
      keep.title = "Saving will then overwrite the file";
      keep.onclick = () => {
        const now = this.buffers.get(name as string);
        if (now) this.buffers.set(name as string, buf.keepMine(now));
        this.renderHeader();
      };
      this.banner.replaceChildren(el("span", undefined, "This script changed on disk while you were editing it."), reload, keep);
    } else {
      this.banner.hidden = true;
    }
  }

  // ---- actions ----

  /** Runs an action with errors shown in the output and the list refreshed afterwards. */
  private async act<T>(what: string, call: (api: Api) => Promise<T>): Promise<T | undefined> {
    if (this.busy) return undefined;
    this.busy = true;
    try {
      return await this.env.guard(call);
    } catch (e) {
      const message = (e as Error).message;
      this.line(`${what}: ${message}`, "o-err");
      this.env.toast(`${what}: ${message}`);
      return undefined;
    } finally {
      this.busy = false;
      void this.refresh();
    }
  }

  /** Saves the open script. False if it was not saved; else whether saving restarted it (a running script restarts). */
  async save(): Promise<false | { restarted: boolean }> {
    const name = this.selected;
    const b = name ? this.buffers.get(name) : undefined;
    if (!name || !b || !this.view) return false;
    const source = this.view.state.doc.toString();
    if (b.kind === "conflict") {
      this.env.toast("it changed on disk: choose Load theirs or Keep mine first");
      return false;
    }
    const saving = buf.startSave(b, source);
    if (!saving) return { restarted: false };
    this.buffers.set(name, saving);
    const settle = (next: (b: Buffer) => Buffer) => {
      const now = this.buffers.get(name);
      if (now) this.buffers.set(name, next(now));
    };
    let r;
    try {
      this.busy = true;
      r = await this.env.guard((api) => api.saveScript(name, source, saving.base));
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        await this.refresh();
        // The hash on disk now, or "" if it was deleted: Keep mine then saves over it, or makes it again.
        settle((now) => buf.saveConflict(now, this.info(name)?.hash ?? ""));
        this.renderHeader();
        this.line("not saved: someone else changed it; choose Load theirs or Keep mine", "o-err");
        return false;
      }
      settle(buf.saveFailed);
      this.renderHeader();
      this.line(`save: ${(e as Error).message}`, "o-err");
      return false;
    } finally {
      this.busy = false;
    }
    if (!r) {
      settle(buf.saveFailed);
      return false;
    }
    const hash = r.hash ?? "";
    settle((now) => buf.saveDone(now, hash, (this.selected === name && this.view ? this.view.state : now.state).doc.toString()));
    this.env.toast(`saved ${name}`);
    if (r.restart) this.result(r.created ? "saved and started" : "saved and restarted", r.restart);
    await this.refresh();
    return { restarted: !!r.restart };
  }

  async run(name = this.selected): Promise<void> {
    if (!name) return;
    if (name === this.selected && this.dirty(name)) {
      const saved = await this.save();
      // Saving a running script restarted it already.
      if (!saved || saved.restarted) return;
    }
    const r = await this.act("run", (api) => api.runScript(name));
    if (r) this.result(name === this.selected ? "ran" : `ran ${name}`, r);
  }

  private async stop(name = this.selected): Promise<void> {
    if (!name) return;
    await this.act("stop", (api) => api.stopScript(name));
  }

  private async setEnabled(s: ScriptInfo, enabled: boolean): Promise<void> {
    const r = await this.act(enabled ? "enable" : "disable", (api) => api.enableScript(s.name, enabled));
    if (r?.restart) this.result(`started ${s.name}`, r.restart);
  }

  private async create(): Promise<void> {
    const raw = await ask("New script", "File name, in the scripts folder", "my-script.js", "Create");
    if (!raw) return;
    const name = jsName(raw);
    const title = name.replace(/\.js$/, "").replace(/[-_]+/g, " ").replace(/^./, (c) => c.toUpperCase());
    const r = await this.act("create", (api) => api.saveScript(name, TEMPLATE(title), ""));
    if (!r) return;
    await this.refresh();
    await this.select(name, "push");
    // Put the cursor inside the listener.
    const doc = this.view?.state.doc.toString() ?? "";
    const at = doc.indexOf("  // e.dir");
    if (this.view && at >= 0) this.view.dispatch({ selection: { anchor: at + 2, head: doc.indexOf("\n", at) }, scrollIntoView: true });
  }

  private async duplicate(s: ScriptInfo): Promise<void> {
    const base = (s.origin === "property" ? s.name.split(/[\\/]/).pop() ?? s.name : s.name).replace(/\.js$/, "");
    const raw = await ask("Duplicate", `Copy ${s.name} as`, `${base}-copy.js`, "Duplicate");
    if (!raw) return;
    const name = jsName(raw);
    const src = s.name === this.selected && this.view ? this.view.state.doc.toString() : (await this.env.guard((api) => api.scriptSource(s.name)))?.source;
    if (src === undefined) return;
    if (await this.act("duplicate", (api) => api.saveScript(name, src, ""))) {
      await this.refresh();
      await this.select(name, "push");
    }
  }

  private async rename(s: ScriptInfo): Promise<void> {
    const raw = await ask("Rename", `New name for ${s.name}`, s.name, "Rename");
    if (!raw) return;
    const to = jsName(raw);
    if (to === s.name) return;
    const r = await this.act("rename", (api) => api.renameScript(s.name, to));
    if (!r) return;
    const b = this.buffers.get(s.name);
    if (b) {
      if (this.selected === s.name && this.view) b.state = this.view.state;
      this.buffers.delete(s.name);
      this.buffers.set(to, b);
    }
    if (this.selected === s.name) {
      this.selected = null;
      await this.refresh();
      await this.select(to);
    }
    if (r.restart) this.result(`restarted as ${to}`, r.restart);
  }

  private async remove(s: ScriptInfo): Promise<void> {
    const unsaved = this.dirty(s.name) ? " It has unsaved changes." : "";
    if (!confirm(`Delete ${s.name}? It stops now, and the file is deleted.${unsaved}`)) return;
    if (!(await this.act("delete", (api) => api.deleteScript(s.name)))) return;
    this.buffers.delete(s.name);
    if (this.selected === s.name) this.showEmpty();
    this.env.toast(`deleted ${s.name}`);
  }

  // ---- output ----

  private line(text: string, cls: string, t?: number, tag?: string): void {
    const row = el("div", `o-line ${cls}`);
    row.append(el("span", "o-time", this.env.time(t ?? Date.now())));
    if (tag) row.append(el("span", "o-tag", tag));
    row.append(el("span", "o-text", text));
    const atBottom = this.out.scrollTop + this.out.clientHeight >= this.out.scrollHeight - 30;
    this.out.append(row);
    while (this.out.childElementCount > MAX_OUT) this.out.firstElementChild?.remove();
    if (atBottom) this.out.scrollTop = this.out.scrollHeight;
  }

  private result(what: string, r: EvalResult): void {
    if (r.ok) {
      const v = r.result;
      this.line(v === undefined || v === null ? `${what}` : `${what} → ${typeof v === "string" ? v : JSON.stringify(v, null, 2)}`, "o-ok");
    } else {
      this.line(`${what}: ${r.error ?? "failed"}${r.kept ? ` (${r.kept})` : ""}`, "o-err");
    }
  }

  private event(e: SculkEvent): void {
    const s = (v: unknown) => (typeof v === "string" ? v : JSON.stringify(v));
    switch (e.kind) {
      case "script_log":
        return this.line(s(e.message), "o-log", e.t);
      case "script":
        return this.line(s(e.data), "o-emit", e.t, "emit");
      case "chat":
        return this.line(s(e.text), "o-log", e.t, "chat");
      case "script_error":
        return this.line(
          `${e.callback ? `${s(e.callback)}: ` : ""}${s(e.error)}${e.kept ? "\n  not applied: the previous version is still running" : ""}${e.packet ? `\n  on ${s(e.packet)}` : ""}`,
          "o-err", e.t, "error");
      case "script_run":
        return this.line(`started${causeText(e.cause)}`, "o-run", e.t, "run");
      case "script_stop":
        return this.line(`stopped${causeText(e.cause)}`, "o-dim", e.t, "stop");
      case "script_file":
        return this.line(`${s(e.action)}${e.to ? ` → ${s(e.to)}` : ""}${e.by === "disk" ? " (on disk)" : ""}`, "o-dim", e.t, "file");
      default:
        return this.line(JSON.stringify(e), "o-dim", e.t, e.kind);
    }
  }

  /** Shows the selected script's recent events, then follows new ones. */
  private async follow(name: string): Promise<void> {
    this.stream?.abort();
    const controller = new AbortController();
    this.stream = controller;
    this.out.replaceChildren();
    this.outFor.textContent = name;
    const filter = `script == ${filterString(name)}`;
    let last = 0;
    try {
      const backlog = await this.env.guard((api) => api.events(filter, BACKLOG));
      if (!backlog || controller.signal.aborted) return;
      for (const e of backlog) this.event(e);
      if (backlog.length) last = (backlog[backlog.length - 1] as SculkEvent).id;
      else last = (await this.env.guard((api) => api.status()))?.lastEventId ?? 0;
      if (!backlog.length) this.line("No output yet. sculk.log(…), sculk.chat(…) and sculk.emit(…) show here, with errors, starts and stops.", "o-dim");
    } catch (e) {
      this.line(`cannot load output: ${(e as Error).message}`, "o-err");
      return;
    }
    while (!controller.signal.aborted) {
      try {
        const it = await this.env.guard(async (api) => api.stream(filter, last, controller.signal));
        if (!it) return;
        for await (const e of it) {
          last = e.id;
          this.event(e);
        }
      } catch {
        if (controller.signal.aborted) return;
      }
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
}

/** Why a script started or stopped, as a suffix for its output. */
function causeText(cause: unknown): string {
  switch (cause) {
    case "file":
      return ": its file changed";
    case "launch":
      return ": launch or reload";
    default:
      return "";
  }
}

/** Adds .js unless it is there, and drops path separators. */
function jsName(raw: string): string {
  const name = raw.trim().replace(/[\\/]/g, "-");
  return name.endsWith(".js") ? name : `${name}.js`;
}

/** Asks for one line of text in the #ask dialog; null if cancelled. */
function ask(title: string, label: string, value: string, ok: string): Promise<string | null> {
  const dialog = byId<HTMLDialogElement>("ask");
  const form = byId<HTMLFormElement>("ask-form");
  const input = byId<HTMLInputElement>("ask-input");
  byId("ask-title").textContent = title;
  byId("ask-label").textContent = label;
  byId<HTMLButtonElement>("ask-ok").textContent = ok;
  input.value = value;
  dialog.showModal();
  // Select the name without its extension, ready to type over.
  const dot = value.lastIndexOf(".js");
  input.setSelectionRange(0, dot > 0 ? dot : value.length);
  return new Promise((resolve) => {
    form.onsubmit = (e) => {
      e.preventDefault();
      const v = input.value.trim();
      if (!v) return;
      dialog.close();
      resolve(v);
    };
    byId<HTMLButtonElement>("ask-cancel").onclick = () => dialog.close();
    dialog.onclose = () => resolve(null);
  });
}
