import assert from "node:assert/strict";
import { test } from "node:test";
import { fromLegacyHash, href, parse } from "../src/route";

test("paths parse to pages", () => {
  assert.deepEqual(parse("/ui/"), { page: "events" });
  assert.deepEqual(parse("/ui/events"), { page: "events" });
  assert.deepEqual(parse("/ui/scripts"), { page: "scripts" });
  assert.deepEqual(parse("/ui/scripts/"), { page: "scripts" });
  assert.deepEqual(parse("/ui/scripts/my%20script.js"), { page: "scripts", name: "my script.js" });
  assert.deepEqual(parse("/ui/scripts/%E0%A4%A"), { page: "scripts" });
  assert.deepEqual(parse("/elsewhere"), { page: "events" });
});

test("hrefs round-trip, slashes in names included", () => {
  for (const name of ["a.js", "my script (2).js", "C:\\mods\\x.js", "dir/x.js"]) {
    const h = href({ page: "scripts", name });
    assert.ok(!h.slice("/ui/scripts/".length).includes("/"), h);
    assert.deepEqual(parse(h), { page: "scripts", name });
  }
  assert.equal(href({ page: "events" }), "/ui/events");
  assert.equal(href({ page: "scripts" }), "/ui/scripts");
});

test("old hash links become paths", () => {
  assert.equal(fromLegacyHash("#scripts/a%20b.js"), "/ui/scripts/a%20b.js");
  assert.equal(fromLegacyHash("#scripts"), "/ui/scripts");
  assert.equal(fromLegacyHash("#events"), "/ui/events");
  assert.equal(fromLegacyHash("#token=abc&event=4"), null);
  assert.equal(fromLegacyHash(""), null);
});
