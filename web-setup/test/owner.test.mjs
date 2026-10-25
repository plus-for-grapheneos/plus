// The owner key, its backup text, AVB blob, certificate and the build manifest must match +PLUS's Kotlin code byte
// for byte (test/vectors.json, written and re-checked by core's WebVectorsTest). The vectors' key is a throwaway test
// key, never used on a phone.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createPrivateKey, createPublicKey } from "node:crypto";
import { readFileSync } from "node:fs";
import {
  BackupError, avbPkmd, decodeBackup, encodeBackup, fingerprintHex, generateOwnerKey, keyFromPrimes, manifestJson,
  ownerCertPem, privateKeyPem, qrPayload, spki,
} from "../lib/owner.js";

const v = JSON.parse(readFileSync(new URL("./vectors.json", import.meta.url), "utf8"));
const key = keyFromPrimes(BigInt("0x" + v.p), BigInt("0x" + v.q));
const b64 = (u8) => Buffer.from(u8).toString("base64");

test("the AVB key blob and fingerprint match Kotlin", async () => {
  assert.equal(b64(avbPkmd(key)), v.avb_pkmd);
  assert.equal(await fingerprintHex(key), v.fingerprint);
});

test("the public key encoding matches Kotlin", () => {
  assert.equal(b64(spki(key)), v.spki);
});

test("the backup text and QR string match Kotlin", async () => {
  assert.equal(await encodeBackup(key), v.backup);
  assert.equal(await qrPayload(key), v.qr);
});

test("a backup decodes in either form, any case, with spaces", async () => {
  for (const text of [v.backup, v.qr, v.backup.toLowerCase().replaceAll("-", " - ")]) {
    const k = await decodeBackup(text);
    assert.equal(k.p, key.p);
    assert.equal(k.q, key.q);
  }
});

test("a mistyped backup is refused with the reason", async () => {
  const i = v.qr.length - 20;
  const typo = v.qr.slice(0, i) + (v.qr[i] === "A" ? "B" : "A") + v.qr.slice(i + 1);
  await assert.rejects(decodeBackup(typo), (e) => e instanceof BackupError && e.reason === "CRC");
  await assert.rejects(decodeBackup("PLUSKEY2" + v.qr.slice(8)), (e) => e.reason === "PREFIX");
  await assert.rejects(decodeBackup(v.qr.slice(0, -1)), (e) => e.reason === "LENGTH");
  await assert.rejects(decodeBackup(v.qr.slice(0, -1) + "1"), (e) => e.reason === "CHARSET");
});

test("the owner certificate is byte-identical to Kotlin's", async () => {
  assert.equal(await ownerCertPem(key), v.cert_pem);
});

test("the private key PEM is the same key", () => {
  const pub = createPublicKey(createPrivateKey(privateKeyPem(key))).export({ type: "spki", format: "der" });
  assert.equal(pub.toString("base64"), v.spki);
});

test("a new key is RSA-4096 with exponent 65537 and survives its own backup", async () => {
  const k = await generateOwnerKey();
  assert.equal(k.n.toString(2).length, 4096);
  assert.equal(k.p * k.q, k.n);
  assert.equal(k.e, 65537n);
  const back = await decodeBackup(await encodeBackup(k));
  assert.equal(back.n, k.n);
});

test("the build manifest JSON matches Kotlin's", () => {
  const json = manifestJson({
    device: "tokay",
    grapheneos: { build: "2026101600", otaSha256: "0f".repeat(32) },
    ownerKeyFingerprint: "sha256:" + v.fingerprint,
    installer: { version: 11, apkSha256: "ab".repeat(32) },
  });
  assert.equal(json, v.manifest_json);
});
