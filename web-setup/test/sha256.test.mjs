// lib/sha256.js hashes the GrapheneOS download in pieces (Web Crypto can only hash a whole buffer at once).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { Sha256 } from "../lib/sha256.js";

const node = (b) => createHash("sha256").update(b).digest("hex");

test("matches SHA-256 for the standard short inputs", () => {
  for (const s of ["", "abc", "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"]) {
    const b = new TextEncoder().encode(s);
    assert.equal(new Sha256().update(b).hex(), node(b));
  }
});

test("gives the same hash however the data is split", () => {
  const data = randomBytes(200_003);
  for (const step of [1, 63, 64, 65, 4096, 100_000]) {
    const h = new Sha256();
    for (let i = 0; i < data.length; i += step) h.update(data.subarray(i, i + step));
    assert.equal(h.hex(), node(data), `pieces of ${step}`);
  }
});
