/**
 * The page's own URLs, as real paths (the control server answers each with
 * index.html; its API is under /api/): /events, /scripts, /scripts/<name>. The
 * fragment is left for the token, which must never reach the server.
 */

export const BASE = "/";

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

