import assert from "node:assert/strict";
import { test } from "node:test";
import { href, parse } from "../src/route";

test("paths parse to pages", () => {
  assert.deepEqual(parse("/"), { page: "events" });
  assert.deepEqual(parse("/events"), { page: "events" });
  assert.deepEqual(parse("/scripts"), { page: "scripts" });
  assert.deepEqual(parse("/scripts/"), { page: "scripts" });
  assert.deepEqual(parse("/scripts/my%20script.js"), { page: "scripts", name: "my script.js" });
  assert.deepEqual(parse("/scripts/%E0%A4%A"), { page: "scripts" });
  assert.deepEqual(parse("/api/status"), { page: "events" });
});

test("hrefs round-trip, slashes in names included", () => {
  for (const name of ["a.js", "my script (2).js", "C:\\mods\\x.js", "dir/x.js"]) {
    const h = href({ page: "scripts", name });
    assert.ok(!h.slice("/scripts/".length).includes("/"), h);
    assert.deepEqual(parse(h), { page: "scripts", name });
  }
  assert.equal(href({ page: "events" }), "/events");
  assert.equal(href({ page: "scripts" }), "/scripts");
});

