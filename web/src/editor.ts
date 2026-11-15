// The script editor: CodeMirror 6 set up for sculk scripts. Syntax errors
// come from the game's own parser (Rhino), so the editor flags exactly what
// the game would refuse; completions know the `sculk` API.

import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completeFromList,
  completionKeymap,
  snippetCompletion,
  type Completion,
  type CompletionContext,
  type CompletionResult,
} from "@codemirror/autocomplete";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { javascriptLanguage, localCompletionSource } from "@codemirror/lang-javascript";
import { javascript } from "@codemirror/lang-javascript";
import {
  bracketMatching,
  foldGutter,
  foldKeymap,
  HighlightStyle,
  indentOnInput,
  indentUnit,
  syntaxHighlighting,
} from "@codemirror/language";
import { lintGutter, linter, lintKeymap, type Diagnostic } from "@codemirror/lint";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { EditorState, Prec, type Extension } from "@codemirror/state";
import {
  crosshairCursor,
  drawSelection,
  dropCursor,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
} from "@codemirror/view";
import { tags as t } from "@lezer/highlight";

export interface Problem {
  line: number;
  column: number;
  message: string;
  severity: "error" | "warning";
}

export interface EditorHooks {
  save: () => void;
  run: () => void;
  changed: (state: EditorState) => void;
  check: (source: string) => Promise<Problem[]>;
}

// ---- look ----

const theme = EditorView.theme(
  {
    "&": {
      height: "100%",
      color: "var(--text)",
      backgroundColor: "#000",
      fontSize: "13px",
      textShadow: "none",
    },
    "&.cm-focused": { outline: "none" },
    ".cm-scroller": { fontFamily: "var(--font)", lineHeight: "1.5" },
    ".cm-content": { caretColor: "var(--glow-hot)", padding: "6px 0" },
    ".cm-cursor, .cm-dropCursor": { borderLeft: "2px solid var(--glow-hot)" },
    "&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection":
      { backgroundColor: "var(--glow-low) !important" },
    ".cm-activeLine": { backgroundColor: "rgba(17, 47, 59, 0.45)" },
    ".cm-selectionMatch": { backgroundColor: "rgba(20, 166, 178, 0.22)" },
    ".cm-matchingBracket, &.cm-focused .cm-matchingBracket": {
      backgroundColor: "rgba(43, 228, 239, 0.18)",
      outline: "1px solid var(--glow-mid)",
    },
    ".cm-nonmatchingBracket": { color: "var(--red)" },
    ".cm-gutters": {
      backgroundColor: "var(--sculk-950)",
      color: "var(--text-faint)",
      borderRight: "2px solid var(--sculk-700)",
    },
    ".cm-activeLineGutter": { backgroundColor: "rgba(17, 47, 59, 0.6)", color: "var(--glow-mid)" },
    ".cm-foldPlaceholder": { backgroundColor: "var(--sculk-700)", border: "none", color: "var(--bone)" },
    ".cm-searchMatch": { backgroundColor: "rgba(255, 170, 0, 0.25)", outline: "1px solid rgba(255, 170, 0, 0.6)" },
    ".cm-searchMatch.cm-searchMatch-selected": { backgroundColor: "rgba(255, 170, 0, 0.45)" },
    ".cm-panels": { backgroundColor: "var(--sculk-900)", color: "var(--text)" },
    ".cm-panels.cm-panels-bottom": { borderTop: "2px solid var(--sculk-700)" },
    ".cm-panel input, .cm-panel button, .cm-panel label": { fontSize: "12px" },
    ".cm-panel.cm-search [name=close]": { color: "var(--text-dim)", textShadow: "none" },
    ".cm-tooltip": {
      backgroundColor: "var(--sculk-900)",
      color: "var(--text)",
      border: "2px solid #000",
      boxShadow: "inset 2px 2px 0 var(--sculk-600), 4px 4px 0 rgba(0, 0, 0, 0.6)",
    },
    ".cm-tooltip-autocomplete > ul": { fontFamily: "var(--font)", maxHeight: "16em" },
    ".cm-tooltip-autocomplete > ul > li": { padding: "1px 8px !important" },
    ".cm-tooltip-autocomplete > ul > li[aria-selected]": { backgroundColor: "var(--glow-low)", color: "var(--glow-hot)" },
    ".cm-completionDetail": { color: "var(--text-dim)", fontStyle: "normal", marginLeft: "1.5ch" },
    ".cm-completionMatchedText": { textDecoration: "none", color: "var(--glow)" },
    ".cm-completionInfo": { padding: "6px 10px", maxWidth: "32em", whiteSpace: "pre-wrap" },
    ".cm-diagnostic": { padding: "4px 8px", borderLeftWidth: "4px" },
    ".cm-diagnostic-error": { borderLeftColor: "var(--red)" },
    ".cm-diagnostic-warning": { borderLeftColor: "var(--gold)" },
    ".cm-lintRange-error": {
      backgroundImage: "none",
      textDecoration: "underline wavy var(--red)",
      textUnderlineOffset: "3px",
    },
    ".cm-lintRange-warning": {
      backgroundImage: "none",
      textDecoration: "underline wavy var(--gold)",
      textUnderlineOffset: "3px",
    },
  },
  { dark: true },
);

const highlight = HighlightStyle.define([
  { tag: [t.keyword, t.controlKeyword, t.definitionKeyword, t.moduleKeyword, t.operatorKeyword], color: "var(--glow)" },
  { tag: [t.string, t.special(t.string)], color: "#b4f3f6" },
  { tag: [t.number, t.regexp], color: "var(--gold)" },
  { tag: [t.bool, t.null, t.atom], color: "var(--green)" },
  { tag: [t.comment, t.lineComment, t.blockComment], color: "var(--text-dim)" },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: "var(--bone-hi)" },
  { tag: [t.definition(t.variableName), t.definition(t.propertyName)], color: "var(--bone)" },
  { tag: t.propertyName, color: "#9cd4d8" },
  { tag: [t.variableName, t.self], color: "var(--text)" },
  { tag: [t.operator, t.punctuation, t.separator, t.bracket], color: "var(--text-dim)" },
  { tag: t.className, color: "var(--glow-hot)" },
  { tag: t.invalid, color: "var(--red)" },
]);

// ---- completions ----

/** The `sculk` API, as ScriptApi.java offers it. */
const SCULK_API: Completion[] = [
  { label: "on", type: "method", detail: "(event, [filter], fn)", info: "sculk.on('packet', e => ...) or sculk.on('connection', c => ...).\nAn optional filter, as in the events view, picks which: sculk.on('packet', 'channel ~ \"^ae2:\" && dir == inbound', e => ...).\nListeners belong to this script and go when it stops." },
  { label: "connections", type: "method", detail: "()", info: "The open connections: {id, side, remote, inject(dir, bytes), injectPacket(dir, obj), injectPayload(dir, channel, payload), replay(event)}." },
  { label: "connection", type: "method", detail: "(id)", info: "The open connection with this id (as in e.conn.id), or null." },
  { label: "payloadTypes", type: "method", detail: "()", info: "Every registered custom payload type, as payload_type events show them." },
  { label: "emit", type: "method", detail: "(obj)", info: "Writes {kind: 'script', script, data: obj} to the event log." },
  { label: "log", type: "method", detail: "(msg)", info: "Writes msg to the game log and a script_log event (this script's output pane)." },
  { label: "chat", type: "method", detail: "(msg)", info: "Shows msg to the player only: a local chat line in a game client (never sent to the server), the console in a dedicated server. A chat event." },
  { label: "schedule", type: "method", detail: "(ms, fn)", info: "Runs fn once after ms milliseconds, unless the script stops first." },
  { label: "tree", type: "method", detail: "(obj)", info: "A plain object tree of any value: records, mod payloads, packets." },
  { label: "with", type: "method", detail: "(record, field, value)", info: "A copy of a record with one component replaced, for editing decoded payloads." },
  { label: "decoder", type: "method", detail: "(className, (obj, tree) => tree)", info: "Shows objects of a Java class (and subclasses) your way wherever sculk makes a tree: change tree in place or return a new one." },
  { label: "reader", type: "method", detail: "(bytes)", info: "Reads bytes as the game does: varInt, string, i32, i64, bool, uuid, bytes(n), rest(), nbt(), itemStack(), component(), identifier(), blockPos(); readable() says what is left." },
  { label: "hex", type: "method", detail: "(bytes)", info: "Bytes as a hex string." },
  { label: "unhex", type: "method", detail: "(str)", info: "A hex string as bytes." },
  { label: "copy", type: "method", detail: "(bytes)", info: "A copy of a byte array." },
  { label: "scope", type: "method", detail: "(name)", info: "Another running script's top-level scope, to read or call its globals; null if it is not running." },
  { label: "name", type: "property", detail: "string", info: "This script's name." },
  { label: "dist", type: "property", detail: "'client' | 'server'", info: "'client' in a game client (and its integrated server), 'server' in a dedicated server." },
];

/** Members of packet events (e in sculk.on('packet', e => ...)). */
const PACKET_EVENT: Completion[] = [
  { label: "conn", type: "property", info: "The live connection: id, side, remote, inject(dir, bytes), injectPacket(dir, obj), injectPayload(dir, channel, payload), replay(event)." },
  { label: "frame", type: "property", info: "{seq, dir, len, bytes, spans}: the bytes on the wire." },
  { label: "packet", type: "property", info: "{id, state, flow, type, javaClass, owner, object, data}: the game's packet; data is what it carries (a custom payload's payload, else the packet)." },
  { label: "mod", type: "property", info: "Custom payloads only: {channel, owner, registeredVia, javaClass, roundTrip, roundTripDetail}." },
  { label: "dir", type: "property", detail: "shorthand", info: "frame.dir: 'inbound' or 'outbound'." },
  { label: "len", type: "property", detail: "shorthand", info: "frame.len" },
  { label: "side", type: "property", detail: "shorthand", info: "conn.side: 'client' or 'server'." },
  { label: "type", type: "property", detail: "shorthand", info: "packet.type" },
  { label: "channel", type: "property", detail: "shorthand", info: "mod.channel" },
  { label: "owner", type: "property", detail: "shorthand", info: "mod.owner, else packet.owner." },
  { label: "injected", type: "property", info: "True for packets a script injected." },
  { label: "drop", type: "method", detail: "()", info: "Drop this packet." },
  { label: "replaceFrame", type: "method", detail: "(bytes)", info: "Send these bytes instead." },
  { label: "replacePacket", type: "method", detail: "(obj)", info: "Send this packet object instead." },
  { label: "replacePayload", type: "method", detail: "(payload)", info: "Send this mod payload object (e.g. sculk.with(e.packet.data, field, value)) on the same channel instead." },
];

const SNIPPETS: Completion[] = [
  snippetCompletion("sculk.on('packet', e => {\n\tif (${e.channel === 'mod:channel'}) {\n\t\t${}\n\t}\n});", {
    label: "onpacket",
    detail: "listener",
    type: "keyword",
  }),
  snippetCompletion("sculk.on('connection', c => {\n\t${}\n});", { label: "onconnection", detail: "listener", type: "keyword" }),
  snippetCompletion("sculk.schedule(${1000}, () => {\n\t${}\n});", { label: "schedule", detail: "timer", type: "keyword" }),
  snippetCompletion(
    "// ==Sculk==\n// @name        ${Name}\n// @description ${What it does}\n// @run         ${startup}\n// ==/Sculk==\n",
    { label: "==Sculk==", detail: "metadata block", type: "keyword" },
  ),
  snippetCompletion("for (const ${x} of ${list}) {\n\t${}\n}", { label: "forof", detail: "loop", type: "keyword" }),
  snippetCompletion("function ${name}(${params}) {\n\t${}\n}", { label: "function", detail: "definition", type: "keyword" }),
];

const GLOBALS: Completion[] = [
  { label: "sculk", type: "variable", detail: "the scripting API" },
  ...["JSON", "Math", "Object", "Array", "String", "Number", "Date", "RegExp", "Error", "Map", "Set", "parseInt", "parseFloat"].map(
    (label): Completion => ({ label, type: "variable" }),
  ),
  ...["const", "let", "var", "if", "else", "return", "function", "for", "while", "true", "false", "null", "undefined", "typeof", "new", "try", "catch", "throw"].map(
    (label): Completion => ({ label, type: "keyword" }),
  ),
];

function memberCompletions(context: CompletionContext): CompletionResult | null {
  const sculk = context.matchBefore(/\bsculk\.\w*$/);
  if (sculk) return { from: sculk.from + "sculk.".length, options: SCULK_API, validFor: /^\w*$/ };
  // Packet events are usually called e, ev or event.
  const ev = context.matchBefore(/\b(e|ev|event)\.\w*$/);
  if (ev) return { from: ev.from + ev.text.indexOf(".") + 1, options: PACKET_EVENT, validFor: /^\w*$/ };
  return null;
}

const globalsAndSnippets = completeFromList([...GLOBALS, ...SNIPPETS]);

function topLevel(context: CompletionContext): CompletionResult | null | Promise<CompletionResult | null> {
  if (context.matchBefore(/\.\w*$/)) return null;
  return globalsAndSnippets(context);
}

// ---- the editor ----

function lintSource(check: EditorHooks["check"]) {
  return linter(
    async (view): Promise<Diagnostic[]> => {
      const doc = view.state.doc;
      let problems: Problem[];
      try {
        problems = await check(doc.toString());
      } catch {
        return []; // The game is not reachable; say nothing rather than something wrong.
      }
      if (view.state.doc !== doc) return [];
      return problems.map((p) => {
        const line = doc.line(Math.min(Math.max(1, p.line), doc.lines));
        // Rhino often points just past the end of the line ("let x = ;" ends there): mark its last character.
        let from = Math.min(line.from + Math.max(0, p.column - 1), line.to);
        if (from === line.to && line.length > 0) from = line.to - 1;
        // Rhino gives a point; mark to the end of the word there, or one character.
        const rest = doc.sliceString(from, line.to);
        const word = /^\w+/.exec(rest)?.[0].length ?? 1;
        return { from, to: Math.min(line.to, from + Math.max(1, word)), severity: p.severity, message: p.message, source: "rhino" };
      });
    },
    { delay: 500 },
  );
}

/** The nonce the page's CSP allows style elements with, from index.html (filled in by the server). */
function cspNonce(): Extension {
  const nonce = document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content ?? "";
  return nonce && !nonce.startsWith("{{") ? EditorView.cspNonce.of(nonce) : [];
}

/** The extensions of every script's editor state. */
export function extensions(hooks: EditorHooks, readOnly = false): Extension[] {
  return [
    lineNumbers(),
    highlightActiveLineGutter(),
    highlightSpecialChars(),
    history(),
    foldGutter(),
    drawSelection(),
    dropCursor(),
    EditorState.allowMultipleSelections.of(true),
    indentOnInput(),
    indentUnit.of("  "),
    EditorState.tabSize.of(2),
    syntaxHighlighting(highlight),
    bracketMatching(),
    closeBrackets(),
    autocompletion({ activateOnTyping: true }),
    rectangularSelection(),
    crosshairCursor(),
    highlightActiveLine(),
    highlightSelectionMatches(),
    javascript(),
    javascriptLanguage.data.of({ autocomplete: memberCompletions }),
    javascriptLanguage.data.of({ autocomplete: topLevel }),
    javascriptLanguage.data.of({ autocomplete: localCompletionSource }),
    lintGutter(),
    lintSource(hooks.check),
    Prec.highest(
      keymap.of([
        { key: "Mod-s", preventDefault: true, run: () => (hooks.save(), true) },
        { key: "Mod-Enter", preventDefault: true, run: () => (hooks.run(), true) },
      ]),
    ),
    keymap.of([
      ...closeBracketsKeymap,
      ...defaultKeymap,
      ...searchKeymap,
      ...historyKeymap,
      ...foldKeymap,
      ...completionKeymap,
      ...lintKeymap,
      indentWithTab,
    ]),
    EditorState.readOnly.of(readOnly),
    cspNonce(),
    EditorView.updateListener.of((u) => {
      if (u.docChanged) hooks.changed(u.state);
    }),
    theme,
  ];
}

export function newState(doc: string, hooks: EditorHooks, readOnly = false): EditorState {
  return EditorState.create({ doc, extensions: extensions(hooks, readOnly) });
}

export function newView(parent: HTMLElement, state: EditorState): EditorView {
  return new EditorView({ state, parent });
}

export type { EditorState, EditorView };
