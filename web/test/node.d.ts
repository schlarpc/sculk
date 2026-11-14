// Just enough of Node's test runner and assert for these tests, so they need
// no @types/node. The tests are bundled by esbuild and run with `node --test`.
declare module "node:test" {
  export function test(name: string, fn: () => void | Promise<void>): void;
}
declare module "node:assert/strict" {
  const assert: {
    (value: unknown, message?: string): asserts value;
    equal(actual: unknown, expected: unknown, message?: string): void;
    notEqual(actual: unknown, expected: unknown, message?: string): void;
    deepEqual(actual: unknown, expected: unknown, message?: string): void;
    ok(value: unknown, message?: string): asserts value;
    rejects(fn: Promise<unknown> | (() => Promise<unknown>), message?: string): Promise<void>;
  };
  export default assert;
}
