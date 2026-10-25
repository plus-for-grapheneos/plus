// fs_metadata.toml edits must match +PLUS's FsEdit (Kotlin) exactly: afsr packs only what the TOML lists.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { addEntries } from "../lib/edit.js";

const v = JSON.parse(readFileSync(new URL("./vectors.json", import.meta.url), "utf8"));

test("added files and their missing parent directories match Kotlin's TOML", () => {
  const out = addEntries(v.fs_toml_in, [
    { path: "/system/priv-app/Updater/Updater.apk" },
    { path: "/system/priv-app/Updater/lib/arm64/libavbroot.so", executable: true },
    { path: "/system/etc/permissions/app.seamlessupdate.client.xml" },
  ]);
  assert.equal(out, v.fs_toml_out);
});

test("an entry already listed is not added again", () => {
  const once = addEntries(v.fs_toml_in, [{ path: "/system/x.xml" }]);
  assert.equal(addEntries(once, [{ path: "/system/x.xml" }]), once);
});

test("unsafe image paths are refused", () => {
  for (const path of ["system/x", "/system/../x", '/system/a"b', "/system/a\\b", "/system/a\nb"]) {
    assert.throws(() => addEntries(v.fs_toml_in, [{ path }]), /bad image path/);
  }
});
