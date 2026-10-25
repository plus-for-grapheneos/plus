// lib/run.js: new descriptors take the lowest free number, as POSIX and Node's WASI do. browser_wasi_shim appends
// every new one, so a tool that opens many files reaches fd numbers above 16,000; afsr's mke2fs then crashed
// ("memory access out of bounds") when packing the system image with +PLUS added.
import { test } from "node:test";
import assert from "node:assert/strict";
import { reuseLowestFd } from "../lib/run.js";

function fakeWasi(fds) {
  const memory = new WebAssembly.Memory({ initial: 1 });
  const w = {
    fds,
    inst: { exports: { memory } },
    wasiImport: {
      // As the library: append, report the new index.
      path_open(fd, dirflags, pathPtr, pathLen, oflags, rb, ri, fdflags, openedPtr) {
        w.fds.push({ opened: true });
        new DataView(memory.buffer).setUint32(openedPtr, w.fds.length - 1, true);
        return 0;
      },
    },
  };
  return w;
}
const opened = (w) => new DataView(w.inst.exports.memory.buffer).getUint32(100, true);

test("a new descriptor takes the lowest closed slot", () => {
  const w = fakeWasi(["in", "out", "err", "pre", undefined, "x", undefined]);
  const imports = reuseLowestFd(w);
  assert.equal(imports.path_open(3, 0, 0, 0, 0, 0n, 0n, 0, 100), 0);
  assert.equal(opened(w), 4);
  assert.deepEqual(w.fds[4], { opened: true });
  assert.equal(w.fds.length, 7, "nothing appended");
});

test("with no free slot it is appended", () => {
  const w = fakeWasi(["in", "out", "err", "pre"]);
  reuseLowestFd(w).path_open(3, 0, 0, 0, 0, 0n, 0n, 0, 100);
  assert.equal(opened(w), 4);
});

test("a failed open changes nothing", () => {
  const w = fakeWasi(["in", "out", "err", "pre", undefined]);
  w.wasiImport.path_open = () => 44;
  assert.equal(reuseLowestFd(w).path_open(3, 0, 0, 0, 0, 0n, 0n, 0, 100), 44);
  assert.equal(w.fds.length, 5);
});
