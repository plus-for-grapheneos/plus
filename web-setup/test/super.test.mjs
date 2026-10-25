// lib/super.js cuts the whole super partition (table + partition images at their offsets) into sparse images no bigger
// than the phone's download limit, as GrapheneOS's super_1.img ... super_N.img: each is flashed to "super" from the
// bootloader and writes only its own part (the rest is "don't care"). Checked by decoding every piece onto one disk.
import { test } from "node:test";
import assert from "node:assert/strict";
import { lpLayout } from "../lib/lp.js";
import { superPieces } from "../lib/super.js";

const BLOCK = 4096;
const MiB = 1 << 20;

function image(blocks, seed) {
  const b = new Uint8Array(blocks * BLOCK);
  for (let i = 0; i < blocks; i++) if (i % 3 !== 1) b.fill((seed + i) % 251 + 1, i * BLOCK, i * BLOCK + 64); // every 3rd block zero
  return b;
}
function runsOf(b) {
  const runs = [];
  for (let i = 0; i < b.length / BLOCK; i++) {
    const isData = b.subarray(i * BLOCK, (i + 1) * BLOCK).some((x) => x !== 0);
    if (runs.length && runs.at(-1)[0] === isData) runs.at(-1)[1]++;
    else runs.push([isData, 1]);
  }
  return runs;
}
const bytesOf = (piece, images) => {
  const parts = piece.map((p) => p.bytes ?? images[p.name].subarray(p.offset, p.offset + p.length));
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
};

/** simg2img onto an existing disk: RAW writes, FILL writes the value, DONT_CARE leaves the disk as it was. */
function apply(disk, sparse) {
  const v = new DataView(sparse.buffer, sparse.byteOffset, sparse.byteLength);
  assert.equal(v.getUint32(0, true), 0xed26ff3a);
  const blk = v.getUint32(12, true), chunks = v.getUint32(20, true);
  let p = 28, b = 0;
  for (let i = 0; i < chunks; i++) {
    const type = v.getUint16(p, true), n = v.getUint32(p + 4, true), total = v.getUint32(p + 8, true);
    if (type === 0xcac1) disk.set(sparse.subarray(p + 12, p + 12 + n * blk), b * blk);
    else if (type === 0xcac2) { const f = sparse.subarray(p + 12, p + 16); for (let k = 0; k < n * blk; k += 4) disk.set(f, b * blk + k); }
    else assert.equal(type, 0xcac3);
    p += total;
    b += n;
  }
  assert.equal(p, sparse.length, "chunk sizes add up to the file");
  return b;
}

const SUPER = 64 * MiB;
const images = { system: image(300, 7), vendor: image(2, 99), product: new Uint8Array(5 * BLOCK) };
const layout = lpLayout({ superSize: SUPER, partitions: Object.entries(images).map(([name, b]) => ({ name, size: b.length })) });
const runs = { system: runsOf(images.system), product: runsOf(images.product) }; // vendor: no map (sent as data)

test("every piece fits the download limit, and together they write the table and every image where it belongs", () => {
  const max = 200 * 1024;
  const pieces = superPieces({ layout, images: { system: { runs: runs.system }, vendor: {}, product: { runs: runs.product } }, maxBytes: max });
  assert.ok(pieces.length >= 4, `cut into ${pieces.length} pieces`);
  const disk = new Uint8Array(layout.end + MiB).fill(0xee); // whatever was there before
  const blocks = new Set();
  for (const piece of pieces) {
    const sparse = bytesOf(piece, images);
    assert.ok(sparse.length <= max, `piece of ${sparse.length} bytes`);
    blocks.add(apply(disk, sparse));
  }
  assert.equal(blocks.size, 1, "every piece describes the same disk size");
  assert.deepEqual(disk.subarray(0, BLOCK), new Uint8Array(BLOCK), "the reserved first 4 KiB are zeroed");
  for (const t of layout.tableBlocks) assert.deepEqual(disk.subarray(t.offset, t.offset + BLOCK), t.kind === "geometry" ? layout.geometry : layout.metadata);
  for (const e of layout.extents) assert.deepEqual(disk.subarray(e.offset, e.offset + e.size), images[e.name], e.name);
});

test("with a large limit everything goes in one piece", () => {
  const pieces = superPieces({ layout, images: { system: { runs: runs.system }, vendor: {}, product: { runs: runs.product } }, maxBytes: 0xf900000 });
  assert.equal(pieces.length, 1);
  const disk = new Uint8Array(layout.end);
  apply(disk, bytesOf(pieces[0], images));
  for (const e of layout.extents) assert.deepEqual(disk.subarray(e.offset, e.offset + e.size), images[e.name]);
});

test("no piece is ever over the limit, whatever the limit (gaps between chunks cost a header too)", () => {
  const imgs = { system: { runs: runs.system }, vendor: {}, product: { runs: runs.product } };
  for (let max = 20000; max < 400000; max += 997) {
    const pieces = superPieces({ layout, images: imgs, maxBytes: max });
    const disk = new Uint8Array(layout.end);
    for (const piece of pieces) {
      const sparse = bytesOf(piece, images);
      assert.ok(sparse.length <= max, `limit ${max}: piece of ${sparse.length} bytes`);
      apply(disk, sparse);
    }
    for (const e of layout.extents) assert.deepEqual(disk.subarray(e.offset, e.offset + e.size), images[e.name], `limit ${max}: ${e.name}`);
  }
});

test("each piece says how much of the disk it covers, for an honest progress bar", () => {
  const pieces = superPieces({ layout, images: { system: { runs: runs.system }, vendor: {}, product: { runs: runs.product } }, maxBytes: 200 * 1024 });
  for (const p of pieces) assert.equal(p.diskBytes, Math.ceil(layout.end / BLOCK) * BLOCK);
});

test("a limit too small for one block of data is refused", () => {
  assert.throws(() => superPieces({ layout, images: { system: {}, vendor: {}, product: {} }, maxBytes: BLOCK }), /too small/);
});
