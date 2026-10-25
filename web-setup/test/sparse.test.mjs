// lib/sparse.js writes partition images in Android's sparse format (libsparse: RAW and FILL chunks), so fastboot.js
// streams large images in pieces instead of converting a 1.4 GB image in memory. Checked by decoding the result.
import { test } from "node:test";
import assert from "node:assert/strict";
import { sparseLayout, sparseRuns, toSparse } from "../lib/sparse.js";

const BLOCK = 4096;

/** Decodes a sparse image back to raw bytes (the reference behaviour of simg2img). */
function decode(sparse) {
  const v = new DataView(sparse.buffer, sparse.byteOffset, sparse.byteLength);
  assert.equal(v.getUint32(0, true), 0xed26ff3a);
  assert.equal(v.getUint16(8, true), 28);
  assert.equal(v.getUint16(10, true), 12);
  const blk = v.getUint32(12, true), blocks = v.getUint32(16, true), chunks = v.getUint32(20, true);
  const out = new Uint8Array(blocks * blk);
  let p = 28, b = 0;
  for (let i = 0; i < chunks; i++) {
    const type = v.getUint16(p, true), n = v.getUint32(p + 4, true), total = v.getUint32(p + 8, true);
    if (type === 0xcac1) { out.set(sparse.subarray(p + 12, p + 12 + n * blk), b * blk); assert.equal(total, 12 + n * blk); }
    else if (type === 0xcac2) { const fill = v.getUint32(p + 12, true); new DataView(out.buffer).setUint32(b * blk, fill, true); assert.equal(fill, 0); assert.equal(total, 16); }
    else assert.fail(`unexpected chunk type ${type.toString(16)}`);
    p += total;
    b += n;
  }
  assert.equal(p, sparse.length);
  assert.equal(b, blocks);
  return out;
}

function sparseOf(raw, opts) {
  const parts = [];
  toSparse((pos, len) => raw.subarray(pos, pos + len), raw.length, (b) => parts.push(b.slice()), opts);
  return Uint8Array.from(parts.flatMap((p) => Array.from(p)));
}

test("an image with data and zero runs decodes back to the same bytes", () => {
  const raw = new Uint8Array(BLOCK * 40);
  for (const blk of [0, 1, 2, 7, 20, 21, 39]) raw.fill(blk + 1, blk * BLOCK, blk * BLOCK + 100);
  raw[BLOCK * 30 + 4095] = 9; // a single byte at the end of a block makes it a data block
  const sparse = sparseOf(raw);
  assert.deepEqual(decode(sparse), raw);
  assert.ok(sparse.length < raw.length / 2, "zero runs become FILL chunks");
});

test("long data runs are split into chunks of at most the limit", () => {
  const raw = new Uint8Array(BLOCK * 10).fill(7);
  const sparse = sparseOf(raw, { maxChunkBlocks: 3 });
  assert.deepEqual(decode(sparse), raw);
  assert.equal(new DataView(sparse.buffer).getUint32(20, true), 4, "3+3+3+1 blocks");
});

test("an all-zero image and a size that is not a whole number of blocks", () => {
  assert.deepEqual(decode(sparseOf(new Uint8Array(BLOCK * 5))), new Uint8Array(BLOCK * 5));
  assert.throws(() => sparseOf(new Uint8Array(BLOCK + 1)), /multiple of 4096/);
});

test("a sparse image composed from the layout over the raw file decodes to the raw bytes (no copy of the data)", async () => {
  const raw = new Uint8Array(BLOCK * 30);
  for (const blk of [0, 3, 4, 5, 17, 29]) raw.fill(blk + 7, blk * BLOCK, blk * BLOCK + 300);
  const runs = sparseRuns((p, n) => raw.subarray(p, p + n), raw.length, { maxChunkBlocks: 2 });
  const parts = sparseLayout(runs, raw.length);
  const file = new Blob([raw]);
  const blob = new Blob(parts.map((p) => p.bytes ?? file.slice(p.offset, p.offset + p.length)));
  assert.deepEqual(decode(new Uint8Array(await blob.arrayBuffer())), raw);
  assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), sparseOf(raw, { maxChunkBlocks: 2 }), "same bytes as toSparse");
});
