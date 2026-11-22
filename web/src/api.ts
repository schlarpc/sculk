// The control server's HTTP API (see ControlServer.java). Every call carries
// the bearer token; the static UI files are the only thing served without it.
// In remote access, calls go through the shell's frame instead (embedding).

export interface SculkEvent {
  id: number;
  kind: string;
  t: number;
  [field: string]: unknown;
}

export interface Connection {
  id: number;
  side: string;
  remote: string;
  open: boolean;
}

export interface Status {
  platform: string;
  pid: number;
  events: number;
  lastEventId: number;
  capture?: Capture;
  scripts: string[];
  connections: Connection[];
}

/** Matches of a filter by position over the whole capture (GET /events/page). */
export interface Page {
  /** First position still held, and one past the last. */
  start: number;
  total: number;
  /** Position of events[0]. */
  from: number;
  events: SculkEvent[];
}

export interface Position {
  /** The position of the first match at or after the id asked for. */
  index: number;
  start: number;
  total: number;
}

/** Progress of a scan on the server: events looked at so far, of how many. */
export type Progress = (done: number, total: number) => void;

export interface Capture {
  firstId: number;
  lastId: number;
  events: number;
  memoryEvents: number;
  indexBytes: number;
  dir?: string;
  diskBytes?: number;
  diskCap?: number;
  pendingWrites?: number;
}

export interface EvalResult {
  ok: boolean;
  result?: unknown;
  error?: string;
  /** Set when a script's new source was refused and its previous version kept running. */
  kept?: string;
}

export interface ReloadResult {
  ok: boolean;
  loaded: string[];
  skipped?: { script: string; reason: string }[];
  errors: { script: string; error: string }[];
}

/** One installed script, as GET /scripts lists it (ScriptManager.java). */
export interface ScriptInfo {
  name: string;
  /** dir: installed; property: from -Dsculk.scripts; memory: running with no file. */
  origin: "dir" | "property" | "memory";
  path?: string;
  enabled: boolean;
  run?: "startup" | "manual";
  title?: string;
  description?: string;
  meta?: Record<string, string>;
  /** Why it does not start in this process (its @dist), if it does not. */
  skip?: string;
  hash?: string;
  size?: number;
  modified?: number;
  status: ScriptStatus;
}

/**
 * A script's state on the game side. Running: from the source with `hash`
 * (`rejected` is a newer file that did not compile, so this older version kept
 * running). Failed: still wanted, and retried when the file changes. Stopped:
 * not wanted, with why.
 */
export type ScriptStatus =
  | { state: "running"; hash: string; since: number; listeners: number; timers: number; rejected?: { hash: string; error: string } }
  | { state: "failed"; hash: string; error: string; at: number }
  | { state: "stopped"; why: "not-started" | "user" | "disabled" | "deleted" | "wrong-dist" };

export interface ScriptList {
  dir: string;
  dist: "client" | "server";
  scripts: ScriptInfo[];
}

export interface ScriptSource extends ScriptInfo {
  source: string;
}

/** A script's info after a change, with the outcome when it started the script or refused the change. */
export interface ScriptChange extends ScriptInfo {
  created?: boolean;
  restart?: EvalResult;
}

export interface ScriptRun extends ScriptInfo, EvalResult {}

export interface CheckResult {
  ok: boolean;
  problems: { line: number; column: number; message: string; severity: "error" | "warning" }[];
}

/**
 * Set by the remote access shell's frame (sculk-ui.github.io; docs/REMOTE.md)
 * before this UI loads: requests then go through it, over WebRTC, to the one
 * game the frame was opened for. That game adds its own token.
 */
interface Embedding {
  fetch(input: string, init: RequestInit): Promise<Response>;
}

export const embedding: Embedding | undefined = (globalThis as { sculkEmbedding?: Embedding }).sculkEmbedding;

export class AuthError extends Error {
  constructor() {
    super("missing or wrong token");
  }
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export class Api {
  constructor(private readonly token: string) {}

  private async request(method: string, path: string, body?: string, signal?: AbortSignal, headers: Record<string, string> = {}): Promise<Response> {
    const init: RequestInit = { method, headers: { Authorization: `Bearer ${this.token}`, ...headers }, cache: "no-store" };
    if (body !== undefined) init.body = body;
    if (signal) init.signal = signal;
    const r = await (embedding ? embedding.fetch(`/api${path}`, init) : fetch(`/api${path}`, init));
    if (r.status === 401) throw new AuthError();
    if (!r.ok) {
      let message = `${r.status} ${r.statusText}`;
      try {
        const data = (await r.json()) as { error?: string };
        if (data.error) message = data.error;
      } catch {
        // Not JSON; keep the status line.
      }
      throw new ApiError(r.status, message);
    }
    return r;
  }

  private async json<T>(method: string, path: string, body?: string): Promise<T> {
    return (await (await this.request(method, path, body)).json()) as T;
  }

  status(): Promise<Status> {
    return this.json("GET", "/status");
  }

  async events(filter: string, limit: number, after?: number): Promise<SculkEvent[]> {
    const q = new URLSearchParams({ filter, limit: String(limit) });
    if (after !== undefined) q.set("after", String(after));
    const text = await (await this.request("GET", `/events?${q}`)).text();
    return text.split("\n").filter((l) => l.trim()).map((l) => JSON.parse(l) as SculkEvent);
  }

  /**
   * The ids of new matches as they happen, and nothing else: for a view that
   * fetches the rows itself, a fraction of the bytes of {@link stream}.
   */
  async *arrivals(filter: string, signal: AbortSignal): AsyncGenerator<number> {
    const r = await this.request("GET", `/events/stream?${new URLSearchParams({ filter, ids: "true" })}`, undefined, signal);
    for await (const { id } of sse(r)) {
      if (id) yield Number(id);
    }
  }

  /** Live events as they happen (after {@code after}, backlog first, if given). Ends when {@code signal} aborts or the server goes away. */
  async *stream(filter: string, after: number | undefined, signal: AbortSignal): AsyncGenerator<SculkEvent> {
    const q = new URLSearchParams({ filter });
    if (after !== undefined) q.set("after", String(after));
    const r = await this.request("GET", `/events/stream?${q}`, undefined, signal);
    for await (const { data } of sse(r)) {
      if (data) yield JSON.parse(data) as SculkEvent;
    }
  }

  /**
   * A scan endpoint's answer, with its progress on the way when the scan is
   * long. Aborting {@code signal} also stops the scan on the server.
   */
  private async scanning<T>(path: string, progress: Progress | undefined, signal: AbortSignal | undefined): Promise<T> {
    const r = await this.request("GET", path, undefined, signal, { Accept: "text/event-stream" });
    if (!(r.headers.get("Content-Type") ?? "").startsWith("text/event-stream")) return (await r.json()) as T;
    for await (const { event, data } of sse(r)) {
      if (event === "progress" && progress) {
        const p = JSON.parse(data) as { done: number; total: number };
        progress(p.done, p.total);
      } else if (event === "result") {
        return JSON.parse(data) as T;
      }
    }
    throw new ApiError(0, "the game stopped answering");
  }

  /** Matches of a filter by position over the whole capture. */
  page(filter: string, from: number, count: number, progress?: Progress, signal?: AbortSignal): Promise<Page> {
    return this.scanning(`/events/page?${new URLSearchParams({ filter, from: String(from), count: String(count) })}`, progress, signal);
  }

  /** The position of the first match of a filter at or after an id. */
  position(filter: string, id: number, progress?: Progress, signal?: AbortSignal): Promise<Position> {
    return this.scanning(`/events/position?${new URLSearchParams({ filter, id: String(id) })}`, progress, signal);
  }

  async frame(id: number): Promise<Uint8Array | null> {
    try {
      const { hex } = await this.json<{ hex: string }>("GET", `/frame?id=${id}`);
      const bytes = new Uint8Array(hex.length / 2);
      for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
      return bytes;
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }
  }

  eval(code: string): Promise<EvalResult> {
    return this.json("POST", "/eval", code);
  }

  reload(): Promise<ReloadResult> {
    return this.json("POST", "/reload", "");
  }

  scripts(): Promise<ScriptList> {
    return this.json("GET", "/scripts");
  }

  scriptSource(name: string): Promise<ScriptSource> {
    return this.json("GET", `/scripts/source?${new URLSearchParams({ name })}`);
  }

  /** {@code base}: the hash the source was edited from, "" for a new script, undefined to overwrite. */
  saveScript(name: string, source: string, base?: string): Promise<ScriptChange> {
    const q = new URLSearchParams({ name });
    if (base !== undefined) q.set("base", base);
    return this.json("POST", `/scripts/save?${q}`, source);
  }

  checkScript(source: string): Promise<CheckResult> {
    return this.json("POST", "/scripts/check", source);
  }

  runScript(name: string): Promise<ScriptRun> {
    return this.json("POST", `/scripts/run?${new URLSearchParams({ name })}`, "");
  }

  stopScript(name: string): Promise<{ ok: boolean }> {
    return this.json("POST", `/scripts/stop?${new URLSearchParams({ name })}`, "");
  }

  deleteScript(name: string): Promise<{ ok: boolean }> {
    return this.json("POST", `/scripts/delete?${new URLSearchParams({ name })}`, "");
  }

  renameScript(name: string, to: string): Promise<ScriptChange> {
    return this.json("POST", `/scripts/rename?${new URLSearchParams({ name, to })}`, "");
  }

  enableScript(name: string, enabled: boolean): Promise<ScriptChange> {
    return this.json("POST", `/scripts/enable?${new URLSearchParams({ name, enabled: String(enabled) })}`, "");
  }
}

/** Server-sent events from a response, as (event name, data) pairs. */
async function* sse(r: Response): AsyncGenerator<{ event: string; data: string; id: string }> {
  if (!r.body) return;
  const reader = r.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += value;
    // Blocks by offset, and the rest kept once per read: a big read (a burst,
    // over SSH or gzip) costs its length, not its length times its events.
    let at = 0;
    let end: number;
    while ((end = buffer.indexOf("\n\n", at)) >= 0) {
      const block = buffer.slice(at, end);
      at = end + 2;
      let event = "message";
      let id = "";
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith("event: ")) event = line.slice(7);
        else if (line.startsWith("data: ")) data.push(line.slice(6));
        else if (line.startsWith("id: ")) id = line.slice(4);
      }
      if (data.length || id || event !== "message") yield { event, data: data.join("\n"), id };
    }
    buffer = buffer.slice(at);
  }
}
