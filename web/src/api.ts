// The control server's HTTP API (see ControlServer.java). Every call carries
// the bearer token; the static UI files are the only thing served without it.

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
  scripts: string[];
  connections: Connection[];
}

export interface EvalResult {
  ok: boolean;
  result?: unknown;
  error?: string;
}

export interface ReloadResult {
  ok: boolean;
  loaded: string[];
  errors: { script: string; error: string }[];
}

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

  private async request(method: string, path: string, body?: string, signal?: AbortSignal): Promise<Response> {
    const init: RequestInit = { method, headers: { Authorization: `Bearer ${this.token}` }, cache: "no-store" };
    if (body !== undefined) init.body = body;
    if (signal) init.signal = signal;
    const r = await fetch(path, init);
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

  /** Live events after {@code after}, as they happen. Ends when {@code signal} aborts or the server goes away. */
  async *stream(filter: string, after: number, signal: AbortSignal): AsyncGenerator<SculkEvent> {
    const q = new URLSearchParams({ filter, after: String(after) });
    const r = await this.request("GET", `/events/stream?${q}`, undefined, signal);
    if (!r.body) return;
    const reader = r.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += value;
      let end: number;
      while ((end = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const data = block
          .split("\n")
          .filter((l) => l.startsWith("data: "))
          .map((l) => l.slice(6))
          .join("\n");
        if (data) yield JSON.parse(data) as SculkEvent;
      }
    }
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
}
