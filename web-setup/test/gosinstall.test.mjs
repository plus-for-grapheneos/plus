// lib/gosinstall.js reads what the page needs from GrapheneOS's install package on releases.grapheneos.org with range
// requests (the server allows them from any site): the file list, small entries (script.txt, android-info.txt), the
// firmware packs, and the partition table at the start of super_1.img. Firmware packs are checked against the pieces
// of the signed update file before anything is written.
import { test } from "node:test";
import assert from "node:assert/strict";
import { deflateRawSync } from "node:zlib";
import { randomBytes } from "node:crypto";
import { crc32 } from "../lib/zip.js";
import { checkFirmware, openRemoteZip, parseScript, readEntry, readEntryStart } from "../lib/gosinstall.js";

/** A zip (deflate) built in memory, served through a fake fetch that honours Range like releases.grapheneos.org. */
function zipOf(files) {
  const locals = [], central = [];
  let offset = 0;
  for (const [name, data] of Object.entries(files)) {
    const comp = deflateRawSync(data), n = Buffer.from(name);
    const l = Buffer.alloc(30); l.writeUInt32LE(0x04034b50, 0); l.writeUInt16LE(20, 4); l.writeUInt16LE(8, 8);
    l.writeUInt32LE(crc32(data), 14); l.writeUInt32LE(comp.length, 18); l.writeUInt32LE(data.length, 22); l.writeUInt16LE(n.length, 26);
    locals.push(l, n, comp);
    const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 6); c.writeUInt16LE(8, 10);
    c.writeUInt32LE(crc32(data), 16); c.writeUInt32LE(comp.length, 20); c.writeUInt32LE(data.length, 24); c.writeUInt16LE(n.length, 28);
    c.writeUInt32LE(offset, 42);
    central.push(c, n);
    offset += 30 + n.length + comp.length;
  }
  const cd = Buffer.concat(central), e = Buffer.alloc(22);
  e.writeUInt32LE(0x06054b50, 0); e.writeUInt16LE(central.length / 2, 8); e.writeUInt16LE(central.length / 2, 10);
  e.writeUInt32LE(cd.length, 12); e.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, e]);
}
/**
 * As a browser sees releases.grapheneos.org from another site: HEAD gives Content-Length; only plain "bytes=a-b"
 * ranges go without a CORS preflight (the server answers preflights with 405, so a suffix range fails); Content-Range
 * is not exposed to the page.
 */
function server(zip) {
  const log = [];
  const fetch = async (url, { method = "GET", headers = {} } = {}) => {
    if (method === "HEAD") return { ok: true, status: 200, headers: { get: (h) => (h === "content-length" ? String(zip.length) : null) } };
    const m = (headers.Range ?? "").match(/^bytes=(\d+)-(\d+)$/);
    if (!m) throw new TypeError("Failed to fetch (CORS preflight answered 405)");
    const start = Number(m[1]), end = Math.min(Number(m[2]), zip.length - 1);
    log.push(`${start}-${end}`);
    const body = zip.subarray(start, end + 1);
    return { ok: true, status: 206, headers: { get: () => null },
      arrayBuffer: async () => body.buffer.slice(body.byteOffset, body.byteOffset + body.length) };
  };
  return { fetch, log };
}

const big = Buffer.alloc(300000);
for (let i = 0; i < big.length; i++) big[i] = (i * 7) % 256;
const RANDOM = randomBytes(1 << 20);
const ZIP = zipOf({ "tokay-install-1/script.txt": Buffer.from("flash boot boot.img\n"), "tokay-install-1/radio-x.img": big,
  "tokay-install-1/super_1.img": RANDOM }); // 1 MiB that doesn't compress

test("the file list and an entry are read with range requests, not the whole download", async () => {
  const s = server(ZIP);
  const zip = await openRemoteZip("https://example/x.zip", { fetch: s.fetch });
  assert.deepEqual(zip.entries.map((e) => e.name), ["tokay-install-1/script.txt", "tokay-install-1/radio-x.img", "tokay-install-1/super_1.img"]);
  assert.equal(new TextDecoder().decode(await readEntry(zip, zip.find("script.txt"))), "flash boot boot.img\n");
  assert.deepEqual(Buffer.from(await readEntry(zip, zip.find(/radio-.*\.img$/))), big);
  const fetched = s.log.reduce((n, r) => { const [a, b] = r.split("-").map(Number); return n + b - a + 1; }, 0);
  assert.ok(fetched < ZIP.length / 2, `fetched ${fetched} of ${ZIP.length} bytes`);
});

test("the start of an entry can be read without inflating the rest", async () => {
  const s = server(ZIP);
  const zip = await openRemoteZip("https://example/x.zip", { fetch: s.fetch });
  assert.deepEqual(Buffer.from(await readEntryStart(zip, zip.find(/radio-/), 1000)), big.subarray(0, 1000));
});

test("a damaged entry is refused (CRC)", async () => {
  const bad = Buffer.from(ZIP);
  bad[bad.indexOf(Buffer.from("radio-x.img")) + 11 + 40] ^= 0xff; // a byte inside the compressed radio data
  const zip = await openRemoteZip("https://example/x.zip", { fetch: server(bad).fetch });
  await assert.rejects(() => readEntry(zip, zip.find(/radio-/)), /CRC|invalid|damaged/i);
});

test("GrapheneOS's script.txt becomes commands", () => {
  assert.deepEqual(parseScript("check-var product tokay\n# comment\n\nflash bootloader bl.img other-slot\nrun-cmd oem uart disable\n"), [
    ["check-var", "product", "tokay"], ["flash", "bootloader", "bl.img", "other-slot"], ["run-cmd", "oem uart disable"],
  ]);
});

/** FBPK v2 (bootloader pack) as in GrapheneOS's packs: 112-byte header, 104-byte entries. */
function fbpk2(entries) {
  const header = 112, esz = 104;
  let off = header + entries.length * esz;
  const metas = entries.map(([name, data, slotted]) => { const m = { name, data, slotted, off }; off += data.length; return m; });
  const b = Buffer.alloc(off);
  b.write("FBPK", 0); b.writeUInt32LE(2, 4); b.writeUInt32LE(header, 8); b.writeUInt32LE(esz, 12);
  b.write("ripcurrentpro", 16); b.write("ripcurrentpro-17.0-1", 32); b.writeUInt32LE(entries.length, 104); b.writeUInt32LE(off, 108);
  metas.forEach((m, i) => {
    const o = header + i * esz;
    b.writeUInt32LE(1, o); b.write(m.name, o + 4); b.writeBigUInt64LE(BigInt(m.off), o + 80); b.writeBigUInt64LE(BigInt(m.data.length), o + 88);
    b.writeUInt32LE(m.slotted ? 1 : 0, o + 96); m.data.copy(b, m.off);
  });
  return b;
}
const abl = Buffer.from("abl-image"), bl1 = Buffer.from("bl1-image!"), modem = Buffer.from("modem-image-data");
const ota = { abl, bl1, modem };
const radio = Buffer.concat([Buffer.from("FBPK"), Buffer.from([1, 0, 0, 0]), Buffer.alloc(132), modem]);

test("firmware packs that match the signed update file's pieces pass", async () => {
  const r = await checkFirmware({ bootloader: fbpk2([["ufs", Buffer.from("u"), false], ["abl", abl, true], ["bl1", bl1, true]]), radio,
    otaImage: (n) => ota[n] });
  assert.equal(r.bootloaderVersion, "ripcurrentpro-17.0-1");
  assert.deepEqual(r.checked, ["abl", "bl1", "modem"]);
});

test("a bootloader pack whose firmware differs from the update file is refused", async () => {
  await assert.rejects(() => checkFirmware({ bootloader: fbpk2([["abl", Buffer.from("other-abl"), true]]), radio, otaImage: (n) => ota[n] }),
    /abl.*differs/);
});

test("a slot firmware entry that the update file doesn't have is refused", async () => {
  await assert.rejects(() => checkFirmware({ bootloader: fbpk2([["abl", abl, true], ["xyz", abl, true]]), radio, otaImage: (n) => ota[n] }),
    /xyz/);
});

test("a radio pack that doesn't end with the update file's modem is refused", async () => {
  const other = Buffer.concat([radio.subarray(0, radio.length - 1), Buffer.from("X")]);
  await assert.rejects(() => checkFirmware({ bootloader: fbpk2([["abl", abl, true]]), radio: other, otaImage: (n) => ota[n] }), /modem/);
});

/** The same server, but some requests fail (thrown network errors) or come back short. */
function flaky(zip, { failEvery = 0, shortOnce = false, failAfter = Infinity } = {}) {
  const s = server(zip);
  let n = 0, shortDone = false;
  const fetch = async (url, opts = {}) => {
    if (opts.method === "HEAD") return s.fetch(url, opts);
    n++;
    if (n > failAfter) throw new TypeError("Failed to fetch (network changed)");
    if (failEvery && n % failEvery === 0) throw new TypeError("Failed to fetch (connection reset)");
    const r = await s.fetch(url, opts);
    if (shortOnce && !shortDone && n > 3) {
      shortDone = true;
      const body = await r.arrayBuffer();
      return { ...r, arrayBuffer: async () => body.slice(0, body.byteLength - 1) };
    }
    return r;
  };
  return { fetch, log: s.log };
}
const noWait = { sleep: async () => {} };

test("a large entry comes in pieces with progress, and dropped or short responses are retried", async () => {
  const s = flaky(ZIP, { failEvery: 3, shortOnce: true });
  const zip = await openRemoteZip("https://example/x.zip", { fetch: s.fetch, chunk: 32 * 1024, ...noWait });
  const seen = [], before = s.log.length; // the file list (one 128 KiB read) is already done
  const data = await readEntry(zip, zip.find("super_1.img"), (f) => seen.push(f));
  assert.deepEqual(Buffer.from(data), RANDOM);
  assert.ok(s.log.slice(before).every((r) => { const [a, b] = r.split("-").map(Number); return b - a < 32 * 1024; }), "no request over 32 KiB");
  assert.ok(seen.length > 1 && seen.at(-1) === 1, "progress reaches 1");
});

test("a download that keeps failing stops with a clear message instead of hanging", async () => {
  const zip = await openRemoteZip("https://example/x.zip", { fetch: flaky(ZIP, { failAfter: 2 }).fetch, ...noWait });
  await assert.rejects(() => readEntry(zip, zip.find(/radio-/)), /GrapheneOS's server|network/i);
});

test("reading the start of an entry stops with an error, not a hang, when the network drops", async () => {
  const zip = await openRemoteZip("https://example/x.zip", { fetch: flaky(ZIP, { failAfter: 2 }).fetch, chunk: 1024, ...noWait });
  const result = await Promise.race([
    readEntryStart(zip, zip.find(/radio-/), 200000).then(() => "finished", (e) => `failed: ${e.message}`),
    new Promise((r) => setTimeout(() => r("HUNG"), 3000)),
  ]);
  assert.match(result, /^failed/);
});
