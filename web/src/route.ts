/**
 * The page's own URLs, as real paths under /ui/ (the control server answers
 * each with index.html): /ui/events, /ui/scripts, /ui/scripts/<name>. The
 * fragment is left for the token, which must never reach the server.
 */

export const BASE = "/ui/";

export type Route = { page: "events" } | { page: "scripts"; name?: string };

export function parse(pathname: string): Route {
  const rest = pathname.startsWith(BASE) ? pathname.slice(BASE.length) : "";
  if (rest === "scripts" || rest === "scripts/") return { page: "scripts" };
  if (rest.startsWith("scripts/")) {
    let name: string;
    try {
      name = decodeURIComponent(rest.slice("scripts/".length));
    } catch {
      return { page: "scripts" };
    }
    return { page: "scripts", name };
  }
  return { page: "events" };
}

export function href(route: Route): string {
  if (route.page === "events") return `${BASE}events`;
  return route.name === undefined ? `${BASE}scripts` : `${BASE}scripts/${encodeURIComponent(route.name)}`;
}

/** The old hash routes (#scripts/<name>, #events) as a path, or null for any other fragment. */
export function fromLegacyHash(hash: string): string | null {
  const h = hash.replace(/^#/, "");
  if (h === "events") return href({ page: "events" });
  if (h === "scripts") return href({ page: "scripts" });
  if (h.startsWith("scripts/")) {
    try {
      return href({ page: "scripts", name: decodeURIComponent(h.slice("scripts/".length)) });
    } catch {
      return href({ page: "scripts" });
    }
  }
  return null;
}
