// lib/flash.js loads the phone the way GrapheneOS's installer does: it runs GrapheneOS's own script.txt for the release
// (vector: tokay-install-2026102100), with +PLUS's boot images, the owner's key and +PLUS's super pieces in place of
// GrapheneOS's. Everything happens in the bootloader (no fastbootd). Checked against a fake fastboot.js device that
// records what it is asked to do.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { flashPhone, lockPhone } from "../lib/flash.js";
import { parseScript } from "../lib/gosinstall.js";

const SCRIPT = parseScript(readFileSync(new URL("./vectors/gos-tokay-script.txt", import.meta.url), "utf8"));
const ANDROID_INFO = readFileSync(new URL("./vectors/gos-tokay-android-info.txt", import.meta.url), "utf8");

/** A fastboot.js FastbootDevice in the bootloader, as far as flash.js uses it. Restarts drop the connection. */
class FakeDevice {
  constructor(vars = {}) {
    this.vars = {
      product: "tokay", "slot-count": "2", "current-slot": "b", unlocked: "no", "is-userspace": "no",
      "max-download-size": "0xf900000", "partition-size:super": "0x1fc800000", "snapshot-update-status": "none",
      "version-bootloader": "ripcurrentpro-17.0-15819938", "version-baseband": "g5400c-260604-260807-B-16035863",
      "has-slot:vendor_kernel_boot": "yes", ...vars,
    };
    this.log = [];
    this.device = { opened: true };
    this.failOpens = 0;
  }
  check() { if (!this.device.opened) throw new Error("device not open"); }
  get isConnected() { return this.device.opened; }
  refuse(c) { const e = new Error(`Bootloader replied with FAIL: ${this.refusals[c]}`); e.name = "FastbootError"; e.status = "FAIL"; throw e; }
  async getVariable(v) { this.check(); return this.vars[v] ?? null; }
  async runCommand(c) {
    this.check();
    this.log.push(c);
    if (this.refusals?.[c]) this.refuse(c);
    if (c === "flashing lock" && this.declineLock) return;
    if (c === "flashing unlock" && this.restartOnUnlock) { this.vars.unlocked = "yes"; this.device = { opened: false }; return; }
    if (c === "flashing unlock") this.vars.unlocked = "yes";
    if (c === "flashing lock") this.vars.unlocked = "no";
    const m = c.match(/^set_active:(a|b)$/);
    if (m) this.vars["current-slot"] = m[1];
    if (c.startsWith("reboot")) this.device = { opened: false };
  }
  async flashBlob(p, blob, onProgress) {
    this.check();
    this.log.push(`flash ${p} ${blob.size}`);
    onProgress?.(blob.size / (blob.diskBytes ?? blob.size)); // fastboot.js measures sparse images against the whole disk
  }
  async _validateAndConnectDevice() {
    if (this.failOpens > 0) { this.failOpens--; this.log.push("open failed"); throw new Error("An operation that changes the device state is in progress."); }
    this.device.opened = true;
    this.log.push("reopened");
  }
}
const usb = (d) => ({ getDevices: async () => [d.device] });
const ui = (log) => ({
  onReconnect: async () => log.push("asked reconnect"),
  confirmUnlock: async () => log.push("asked unlock"),
  confirmLock: async () => log.push("asked lock"),
  progress: () => {},
});
const blob = (n) => new Blob([new Uint8Array(n)]);
const OURS = { boot: 11, init_boot: 12, dtbo: 13, vendor_kernel_boot: 14, pvmfw: 15, vendor_boot: 16, vbmeta: 17 };
const run = (d, extra = {}) => flashPhone({
  device: d, deviceName: "tokay", script: SCRIPT, androidInfo: ANDROID_INFO, superSize: 0x1fc800000,
  gosFile: (name) => blob(name.startsWith("bootloader") ? 1001 : 1002),
  ownImage: (p) => (OURS[p] ? blob(OURS[p]) : undefined),
  ownerPkmd: new Uint8Array(1032),
  superPieces: (max) => { d.log.push(`super pieces for ${max}`); return [blob(21), blob(22), blob(23)]; },
  ui: ui(d.log), usb: usb(d), sleep: async () => {}, ...extra,
});

test("the phone is loaded with GrapheneOS's own steps, in the bootloader, with +PLUS's images and the owner's key", async () => {
  const d = new FakeDevice();
  await run(d);
  assert.deepEqual(d.log, [
    "asked unlock", "flashing unlock",
    // GrapheneOS's steps from here (script.txt), bootloader firmware to both slots
    "flash bootloader_a 1001", "set_active:a", "reboot-bootloader", "reopened",
    "flash bootloader_b 1001", "set_active:b", "reboot-bootloader", "reopened",
    "set_active:a",
    "flash radio 1002", "reboot-bootloader", "reopened",
    "erase:avb_custom_key", "flash avb_custom_key 1032",
    "oem uart disable", "erase:fips", "erase:dpm_a", "erase:dpm_b",
    "flash boot 11", "flash init_boot 12", "flash dtbo 13", "flash vendor_kernel_boot 14", "flash pvmfw 15",
    "flash vendor_boot 16", "flash vbmeta 17",
    "erase:userdata", "erase:metadata",
    "super pieces for 261095424", "flash super 21", "flash super 22", "flash super 23",
    // then, as GrapheneOS's installer offers: lock, restart
    "asked lock", "flashing lock", "reboot",
  ]);
  assert.ok(!d.log.some((c) => c.includes("fastboot")), "never restarts into fastbootd");
});

test("a pending system update is cancelled first, as GrapheneOS's script asks", async () => {
  const d = new FakeDevice({ "snapshot-update-status": "merging" });
  await run(d);
  assert.ok(d.log.indexOf("snapshot-update:cancel") > d.log.indexOf("erase:dpm_b"));
  assert.ok(d.log.indexOf("snapshot-update:cancel") < d.log.indexOf("flash boot 11"));
});

test("an already unlocked phone is not asked to unlock again", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  await run(d);
  assert.ok(!d.log.includes("flashing unlock"));
});

test("a phone of another model is refused before anything is written", async () => {
  const d = new FakeDevice({ product: "caiman" });
  await assert.rejects(() => run(d), /caiman/);
  assert.deepEqual(d.log, []);
});

test("a phone whose super partition is another size is refused before anything is written", async () => {
  const d = new FakeDevice({ "partition-size:super": "0x200000000", unlocked: "yes" });
  await assert.rejects(() => run(d), /super/);
  assert.ok(!d.log.some((c) => c.startsWith("flash")));
});

test("a failed requirement (firmware not taken) stops before the boot images are written", async () => {
  const d = new FakeDevice({ "version-bootloader": "ripcurrentpro-16.0-1" });
  await assert.rejects(() => run(d), /version-bootloader/);
  assert.ok(!d.log.includes("flash boot 11"));
});

test("a script step the page doesn't know stops the load", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  await assert.rejects(() => run(d, { script: [["check-var", "product", "tokay"], ["format", "userdata"]] }), /format/);
});

test("an image GrapheneOS flashes that +PLUS didn't build stops the load", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  await assert.rejects(() => run(d, { script: [["flash", "odm", "odm.img"]] }), /odm/);
});

test("after a restart the page keeps trying to reopen the phone while Windows still holds it", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  d.failOpens = 3;
  await run(d, { script: [["reboot-bootloader"], ["check-var", "product", "tokay"]] });
  assert.equal(d.log.filter((l) => l === "open failed").length, 3);
  assert.ok(d.log.includes("reopened"));
  assert.ok(!d.log.includes("asked reconnect"));
});

test("only if the phone never comes back is the person asked to choose it again", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  d.failOpens = 1000;
  const extra = { script: [["reboot-bootloader"]], reconnectTries: 5 };
  extra.ui = { ...ui(d.log), onReconnect: async () => { d.log.push("asked reconnect"); d.failOpens = 0; d.device.opened = true; } };
  await run(d, extra);
  assert.ok(d.log.includes("asked reconnect"));
});

test("the cheap checks of GrapheneOS's steps run before the unlock, which erases the phone", async () => {
  const d = new FakeDevice({ "max-download-size": "0x10000000" });
  await assert.rejects(() => run(d), /max-download-size/);
  assert.ok(!d.log.includes("flashing unlock"));
});

test("a phone that refuses to unlock (OEM unlocking off) is reported as such", async () => {
  const d = new FakeDevice();
  d.refusals = { "flashing unlock": "Flashing Unlock is not allowed" };
  await assert.rejects(() => run(d), /OEM unlocking/);
});

test("a phone that restarts right after the unlock is opened again and the load goes on", async () => {
  const d = new FakeDevice();
  d.restartOnUnlock = true;
  await run(d);
  assert.ok(d.log.includes("flash super 23"));
});

test("a refused restart stops the load with the phone's message instead of carrying on", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  d.refusals = { "reboot-bootloader": "reboot not allowed" };
  await assert.rejects(() => run(d), /reboot not allowed/);
  assert.ok(!d.log.includes("flash bootloader_b 1001"), "the second bootloader write never happens without the restart");
});

test("a phone that doesn't restart when asked stops the load", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  const real = d.runCommand.bind(d);
  d.runCommand = async (c) => { if (c === "reboot-bootloader") { d.log.push(c); return; } return real(c); }; // OKAY, but stays
  await assert.rejects(() => run(d, { script: [["reboot-bootloader"]] }), /didn't restart/);
});

test("a declined lock is reported, and locking alone can be repeated without loading again", async () => {
  const d = new FakeDevice();
  d.declineLock = true;
  const written = [];
  await assert.rejects(() => run(d, { ui: { ...ui(d.log), onWritten: () => written.push(true) } }), /still unlocked/);
  assert.equal(written.length, 1, "the page is told the phone is fully written");
  d.declineLock = false;
  d.log.length = 0;
  await lockPhone({ device: d, ui: ui(d.log), usb: usb(d), sleep: async () => {} });
  assert.deepEqual(d.log, ["asked lock", "flashing lock", "reboot"]);
});

test("requirement lines: reject and other products' lines are honoured, unknown lines stop the load", async () => {
  const base = { unlocked: "yes" };
  await run(new FakeDevice(base), { script: [["check-requirements", "android-info.txt"]],
    androidInfo: "require-for-product:caiman version-bootloader=x\nrequire version-bootloader=ripcurrentpro-17.0-*\n" });
  await assert.rejects(() => run(new FakeDevice(base), { script: [["check-requirements", "android-info.txt"]],
    androidInfo: "reject version-baseband=g5400c-260604-260807-B-16035863\n" }), /reject/);
  await assert.rejects(() => run(new FakeDevice(base), { script: [["check-requirements", "android-info.txt"]],
    androidInfo: "require-something-new x=y\n" }), /require-something-new/);
});

test("progress during super moves by piece, not by the whole disk", async () => {
  const d = new FakeDevice({ unlocked: "yes" });
  const seen = [];
  const piece = (n) => Object.assign(blob(n), { diskBytes: n * 100 });
  await run(d, { script: [["flash", "super", "super_1.img"]], superPieces: () => [piece(10), piece(10)],
    ui: { ...ui(d.log), progress: (label, f) => seen.push(f) } });
  assert.ok(seen.some((f) => Math.abs(f - 0.5) < 1e-9), `after the first of two pieces: ${seen.join(", ")}`);
});
