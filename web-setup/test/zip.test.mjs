// lib/zip.js reads the +PLUS APK (a zip) to unpack its native libraries into the image. fixture.zip was made with
// Python's zipfile: one stored and one deflated library, an x86 library and a directory entry.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { readZip } from "../lib/zip.js";

const zip = new Uint8Array(readFileSync(new URL("./fixture.zip", import.meta.url)));

test("lists every entry", () => {
  const names = readZip(zip).map((e) => e.name);
  assert.deepEqual(names, ["AndroidManifest.xml", "lib/arm64-v8a/libavbroot.so", "lib/arm64-v8a/libafsr.so",
    "lib/x86_64/libavbroot.so", "lib/arm64-v8a/"]);
});

test("reads stored and deflated entries", async () => {
  const e = Object.fromEntries(readZip(zip).map((x) => [x.name, x]));
  const expected = new Uint8Array(256 * 40).map((_, i) => i & 0xff);
  assert.deepEqual(await e["lib/arm64-v8a/libavbroot.so"].bytes(), expected);
  assert.equal(new TextDecoder().decode(await e["lib/arm64-v8a/libafsr.so"].bytes()), "afsr".repeat(1000));
  assert.equal(new TextDecoder().decode(await e["AndroidManifest.xml"].bytes()), "manifest-bytes");
});

test("a damaged entry is refused", async () => {
  const bad = zip.slice();
  const e = readZip(bad).find((x) => x.name === "lib/arm64-v8a/libafsr.so");
  bad[e.dataOffset + 10] ^= 0xff;
  await assert.rejects(readZip(bad).find((x) => x.name === e.name).bytes(), /CRC/);
});
