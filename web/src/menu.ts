// A context menu: a floating list of actions, driven by mouse or keyboard
// (arrows, Home/End, Enter, Escape). One menu at a time.

import { el } from "./dom";

export type MenuItem =
  | { label: string; hint?: string; disabled?: boolean; run: (e: { shiftKey: boolean }) => void }
  | { heading: string }
  | "separator";

let open: { root: HTMLElement; restore: Element | null; cleanup: () => void } | null = null;
const closeListeners: (() => void)[] = [];

export function menuOpen(): boolean {
  return open !== null;
}

/** Calls {@code fn} whenever a menu closes. */
export function onMenuClose(fn: () => void): void {
  closeListeners.push(fn);
}

export function closeMenu(): void {
  if (!open) return;
  const { root, restore, cleanup } = open;
  open = null;
  cleanup();
  root.remove();
  if (restore instanceof HTMLElement) restore.focus({ preventScroll: true });
  for (const fn of closeListeners) fn();
}

/** Opens a menu at viewport point (x, y), kept inside the window. */
export function showMenu(x: number, y: number, items: MenuItem[], footer?: string): void {
  closeMenu();
  const root = el("div", "menu");
  root.setAttribute("role", "menu");
  const buttons: HTMLButtonElement[] = [];
  for (const item of items) {
    if (item === "separator") {
      root.append(el("div", "menu-sep"));
    } else if ("heading" in item) {
      root.append(el("div", "menu-heading", item.heading));
    } else {
      const b = el("button", "menu-item");
      b.type = "button";
      b.setAttribute("role", "menuitem");
      b.append(el("span", "menu-label", item.label));
      if (item.hint) b.append(el("span", "menu-hint", item.hint));
      b.disabled = item.disabled ?? false;
      b.addEventListener("click", (e) => {
        closeMenu();
        item.run({ shiftKey: e.shiftKey });
      });
      buttons.push(b);
      root.append(b);
    }
  }
  if (footer) root.append(el("div", "menu-foot", footer));
  document.body.append(root);

  const r = root.getBoundingClientRect();
  root.style.left = `${Math.max(4, Math.min(x, innerWidth - r.width - 4))}px`;
  root.style.top = `${Math.max(4, Math.min(y, innerHeight - r.height - 4))}px`;

  const enabled = () => buttons.filter((b) => !b.disabled);
  const onKey = (e: KeyboardEvent) => {
    const list = enabled();
    const i = list.indexOf(document.activeElement as HTMLButtonElement);
    let next: HTMLButtonElement | undefined;
    if (e.key === "Escape") {
      e.preventDefault();
      closeMenu();
      return;
    } else if (e.key === "ArrowDown") next = list[(i + 1) % list.length];
    else if (e.key === "ArrowUp") next = list[(i - 1 + list.length) % list.length];
    else if (e.key === "Home") next = list[0];
    else if (e.key === "End") next = list[list.length - 1];
    else if (e.key === "Tab") {
      e.preventDefault();
      return;
    } else return;
    e.preventDefault();
    next?.focus();
  };
  const onPointer = (e: Event) => {
    if (!root.contains(e.target as Node)) closeMenu();
  };
  const onBlur = () => closeMenu();
  // The user scrolling closes the menu; the page scrolling itself (live rows arriving) does not.
  const onWheel = (e: Event) => {
    if (!root.contains(e.target as Node)) closeMenu();
  };
  root.addEventListener("keydown", onKey);
  // Capture, so a click elsewhere closes the menu before it does anything else.
  document.addEventListener("pointerdown", onPointer, true);
  window.addEventListener("blur", onBlur);
  window.addEventListener("resize", onBlur);
  document.addEventListener("wheel", onWheel, { capture: true, passive: true });
  open = {
    root,
    restore: document.activeElement,
    cleanup: () => {
      document.removeEventListener("pointerdown", onPointer, true);
      window.removeEventListener("blur", onBlur);
      window.removeEventListener("resize", onBlur);
      document.removeEventListener("wheel", onWheel, { capture: true });
    },
  };
  enabled()[0]?.focus({ preventScroll: true });
}
