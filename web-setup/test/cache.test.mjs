// Read-ahead caches (lib/cache.js): avbroot reads in 4-16 KiB pieces, millions of times per build, and every read is a
// browser file call (about 1.6 ms each for the picked file on Windows). The caches serve those reads from large chunks.
// They must never return stale or wrong bytes: checked against a plain in-memory model under random operations.
import { test } from "node:test";
import assert from "node:assert/strict";
import { CachedHandle } from "../lib/cache.js";
import { blobSource } from "../lib/source.js";

/** An in-memory FileSystemSyncAccessHandle that counts calls. */
class MemHandle {
  constructor() { this.data = new Uint8Array(0); this.reads = 0; }
  read(buf, { at }) {
    this.reads++;
    const part = this.data.subarray(at, at + buf.byteLength);
    buf.set(part);
    return part.byteLength;
  }
  write(buf, { at }) {
    if (at + buf.byteLength > this.data.length) {
      const bigger = new Uint8Array(at + buf.byteLength);
      bigger.set(this.data);
      this.data = bigger;
    }
    this.data.set(buf, at);
    return buf.byteLength;
  }
  truncate(n) {
    const next = new Uint8Array(n);
    next.set(this.data.subarray(0, Math.min(n, this.data.length)));
    this.data = next;
  }
  getSize() { return this.data.length; }
  flush() {}
  close() {}
}

function rng(seed) {
  let s = seed;
  return (n) => { s = (s * 1103515245 + 12345) & 0x7fffffff; return s % n; };
}

test("cached reads always match the file, through writes, growth and truncation", () => {
  const mem = new MemHandle();
  const h = new CachedHandle(mem, { chunk: 64, chunks: 4, bypass: 200 });
  const r = rng(7);
  for (let i = 0; i < 5000; i++) {
    const op = r(10);
    if (op < 3) {
      const at = r(600), n = 1 + r(150);
      h.write(Uint8Array.from({ length: n }, () => r(256)), { at });
    } else if (op === 3) {
      h.truncate(r(700));
    } else {
      const at = r(700), n = 1 + r(300);
      const got = new Uint8Array(n);
      const k = h.read(got, { at });
      const want = mem.data.subarray(at, at + n);
      assert.equal(k, want.length, `read length at ${at}+${n}, step ${i}`);
      assert.deepEqual(got.subarray(0, k), want, `bytes at ${at}+${n}, step ${i}`);
    }
  }
});

test("small sequential reads cost one file call per chunk", () => {
  const mem = new MemHandle();
  mem.write(new Uint8Array(1 << 20).fill(5), { at: 0 });
  const h = new CachedHandle(mem, { chunk: 64 << 10, chunks: 4, bypass: 256 << 10 });
  for (let p = 0; p < 1 << 20; p += 4096) h.read(new Uint8Array(4096), { at: p });
  assert.equal(mem.reads, 16);
});

test("the picked file is read in large chunks, and the bytes are right", () => {
  const bytes = Uint8Array.from({ length: 300_000 }, (_, i) => (i * 31) & 0xff);
  let calls = 0;
  const blob = { size: bytes.length, slice: (a, b) => ({ a, b }) };
  const reader = { readAsArrayBuffer: ({ a, b }) => { calls++; return bytes.slice(a, b).buffer; } };
  const src = blobSource(blob, { reader, chunk: 64 << 10 });
  const out = new Uint8Array(bytes.length);
  for (let p = 0; p < bytes.length; p += 4096) src.read(out.subarray(p, p + 4096), p);
  assert.deepEqual(out, bytes);
  assert.equal(calls, 5, "300 KB in 64 KiB chunks");
  const tail = new Uint8Array(100);
  assert.equal(src.read(tail, bytes.length - 40), 40);
});
