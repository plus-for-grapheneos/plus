// lib/lp.js writes the super partition's table (Android liblp metadata) as GrapheneOS's install images carry it, so
// the page can write super from the bootloader as GrapheneOS's installer does. The vector is the table at the start of
// GrapheneOS's tokay-install-2026102100 super_1.img: its geometry block, then one metadata block (all six copies of
// the metadata are identical).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { lpLayout, readLpTable, sparseStart } from "../lib/lp.js";

const VECTOR = readFileSync(new URL("./vectors/gos-tokay-lp.bin", import.meta.url));
const TOKAY = {
  superSize: 0x1fc800000,
  partitions: [
    { name: "system", size: 0x55a17000 },
    { name: "system_dlkm", size: 0xa98000 },
    { name: "system_ext", size: 0x25392000 },
    { name: "product", size: 0x4b3d4000 },
    { name: "vendor", size: 0x3c180000 },
    { name: "vendor_dlkm", size: 0x1ed6000 },
  ],
};

test("GrapheneOS's tokay partition table is reproduced byte for byte", () => {
  const lp = lpLayout(TOKAY);
  assert.deepEqual(Buffer.from(lp.geometry), VECTOR.subarray(0, 4096));
  assert.deepEqual(Buffer.from(lp.metadata), VECTOR.subarray(4096, 8192));
});

test("partitions start at 1 MiB and each at the next 1 MiB boundary, as in GrapheneOS's image", () => {
  const { extents } = lpLayout(TOKAY);
  assert.deepEqual(extents.map((e) => [e.name, e.offset, e.size]), [
    ["system", 0x100000, 0x55a17000],
    ["system_dlkm", 0x55c00000, 0xa98000],
    ["system_ext", 0x56700000, 0x25392000],
    ["product", 0x7bb00000, 0x4b3d4000],
    ["vendor", 0xc6f00000, 0x3c180000],
    ["vendor_dlkm", 0x103100000, 0x1ed6000],
  ]);
});

test("where the table goes: geometry at 4 KiB and 8 KiB, three slots of metadata, then three backups", () => {
  const { tableBlocks } = lpLayout(TOKAY);
  assert.deepEqual(tableBlocks.map((t) => [t.offset, t.kind]), [
    [4096, "geometry"], [8192, "geometry"],
    [12288, "metadata"], [12288 + 65536, "metadata"], [12288 + 2 * 65536, "metadata"],
    [12288 + 3 * 65536, "metadata"], [12288 + 4 * 65536, "metadata"], [12288 + 5 * 65536, "metadata"],
  ]);
});

test("partitions that don't fit the dynamic partition group are refused", () => {
  assert.throws(() => lpLayout({ superSize: 0x1fc800000, partitions: [{ name: "system", size: 0x1fc400000 + 4096 }] }),
    /do not fit/);
});

test("partition sizes must be whole 4 KiB blocks", () => {
  assert.throws(() => lpLayout({ superSize: 0x1fc800000, partitions: [{ name: "system", size: 4097 }] }), /4096/);
});

/** The start of a super_N.img as GrapheneOS ships it: sparse, zeroed block 0, two geometry blocks, metadata block. */
function gosSuperStart() {
  const chunk = (type, blocks, data) => {
    const h = Buffer.alloc(12); h.writeUInt16LE(type, 0); h.writeUInt32LE(blocks, 4); h.writeUInt32LE(12 + data.length, 8);
    return Buffer.concat([h, data]);
  };
  const header = Buffer.alloc(28);
  header.writeUInt32LE(0xed26ff3a, 0); header.writeUInt16LE(1, 4); header.writeUInt16LE(28, 8); header.writeUInt16LE(12, 10);
  header.writeUInt32LE(4096, 12); header.writeUInt32LE(1069014, 16); header.writeUInt32LE(17, 20);
  return Buffer.concat([header, chunk(0xcac2, 1, Buffer.alloc(4)), chunk(0xcac1, 1, VECTOR.subarray(0, 4096)),
    chunk(0xcac1, 1, VECTOR.subarray(0, 4096)), chunk(0xcac1, 1, VECTOR.subarray(4096, 8192)), chunk(0xcac3, 15, Buffer.alloc(0))]);
}

test("GrapheneOS's table is read from the start of its super_1.img", () => {
  const raw = sparseStart(gosSuperStart(), 4 * 4096);
  const t = readLpTable(raw);
  assert.equal(t.superSize, 0x1fc800000);
  assert.deepEqual(t.partitions, TOKAY.partitions);
  assert.equal(t.matchesOurLayout, true); // our generator, given GrapheneOS's sizes, writes exactly GrapheneOS's table
});

test("a table our generator would not reproduce is reported", () => {
  const raw = Buffer.from(sparseStart(gosSuperStart(), 4 * 4096));
  raw[3 * 4096 + 128] = 0; // header flags: not a virtual A/B device
  raw.set(lpLayout(TOKAY).metadata.subarray(12, 44), 3 * 4096 + 12); // keep the header checksum plausible
  assert.throws(() => readLpTable(raw), /checksum|differ/);
});
