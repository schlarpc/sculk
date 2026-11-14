import assert from "node:assert/strict";
import { test } from "node:test";
import {
  anchorFor,
  contentHeight,
  lastTop,
  revealTop,
  scaled,
  scrollFor,
  scrollRange,
  scrollTopFor,
  topAt,
  topOf,
  windowRows,
  type Geometry,
} from "../src/layout";

const small: Geometry = { count: 1000, rowH: 20, viewH: 400, maxHeight: 8_000_000 }; // 20 rows in view
const huge: Geometry = { count: 10_000_000, rowH: 20, viewH: 400, maxHeight: 8_000_000 };
const short: Geometry = { count: 5, rowH: 20, viewH: 400, maxHeight: 8_000_000 };

const near = (a: number, b: number, eps = 1e-6, what = "") => assert.ok(Math.abs(a - b) <= eps, `${what} ${a} ≈ ${b}`);

test("unscaled: every row has its pixels", () => {
  assert.equal(scaled(small), false);
  assert.equal(contentHeight(small), 20_000);
  assert.equal(scrollRange(small), 19_600);
  assert.equal(lastTop(small), 980);
  assert.equal(topAt(small, 400), 20);
  assert.equal(scrollTopFor(small, 20), 400);
});

test("scaled: the scrollbar stands for all rows, ends included", () => {
  assert.equal(scaled(huge), true);
  assert.equal(contentHeight(huge), 8_000_000);
  assert.equal(topAt(huge, 0), 0);
  assert.equal(topAt(huge, scrollRange(huge)), lastTop(huge));
  near(topAt(huge, scrollRange(huge) / 2), lastTop(huge) / 2, 1e-6, "middle");
});

test("topAt and scrollTopFor invert each other, and clamp", () => {
  for (const g of [small, huge]) {
    for (const top of [0, 1, 17.5, lastTop(g) / 3, lastTop(g)]) near(topAt(g, scrollTopFor(g, top)), top, 1e-3, `${g.count}`);
    assert.equal(scrollTopFor(g, -5), 0);
    assert.equal(scrollTopFor(g, lastTop(g) + 100), scrollRange(g));
    assert.equal(topAt(g, 1e12), lastTop(g));
  }
});

test("the rendered window covers the view, plus overscan, and no more than the rows held", () => {
  for (const g of [small, huge]) {
    for (const scrollTop of [0, 1234, scrollRange(g) / 2, scrollRange(g)]) {
      const top = topAt(g, scrollTop);
      const w = windowRows(g, scrollTop, 5);
      assert.ok(w.first <= Math.floor(top), "starts at or above the top row");
      assert.ok(w.last >= Math.min(g.count, Math.ceil(top + g.viewH / g.rowH)), "reaches the bottom of the view");
      assert.ok(w.first >= 0 && w.last <= g.count);
      // The top row lands at the top of the view: its offset from the window start, from scrollTop.
      const topRowY = w.y + (top - w.first) * g.rowH;
      near(topRowY, scaled(g) ? scrollTopFor(g, top) : top * g.rowH, 1e-3, "top row y");
    }
  }
  const w = windowRows(short, 0, 5);
  assert.deepEqual([w.first, w.last, w.y], [0, 5, 0]);
});

test("anchors: the bottom is the tail; anywhere else is a row by absolute position", () => {
  assert.deepEqual(anchorFor(small, scrollRange(small), 500), { kind: "tail" });
  assert.deepEqual(anchorFor(small, 400, 500), { kind: "row", top: 520 });
  assert.deepEqual(anchorFor(short, 0, 0), { kind: "tail" }, "nothing to scroll: following");
  assert.equal(scrollFor(small, { kind: "tail" }, 500), scrollRange(small));
  assert.equal(scrollFor(small, { kind: "row", top: 520 }, 500), 400);
});

test("a row anchor keeps its rows in view when older rows are dropped from the front", () => {
  const anchor = anchorFor(small, 4000, 0); // row 200 at the top
  assert.deepEqual(anchor, { kind: "row", top: 200 });
  // 150 rows dropped: the same row is now row 50 of those held.
  const after: Geometry = { ...small, count: small.count - 150 };
  assert.equal(topAt(after, scrollFor(after, anchor, 150)), 50);
  assert.equal(topOf(after, anchor, 150), 50);
  // Dropped past it: the view clamps to the first row held.
  assert.equal(topOf(after, anchor, 300), 0);
});

test("revealing a row: nearest moves the least, or not at all; center centres", () => {
  assert.equal(revealTop(small, 100, 110, "nearest"), null, "in view");
  assert.equal(revealTop(small, 100, 90, "nearest"), 90, "above: to the top");
  assert.equal(revealTop(small, 100, 130, "nearest"), 111, "below: to the bottom");
  assert.equal(revealTop(small, 100, 500, "center"), 490.5);
  assert.equal(revealTop(small, 0, 2, "center"), 0, "clamped at the start");
  assert.equal(revealTop(small, 0, 999, "center"), lastTop(small), "clamped at the end");
});
