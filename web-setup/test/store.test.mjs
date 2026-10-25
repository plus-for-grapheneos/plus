// Tests for lib/store.js: files the WebAssembly tools create live in one storage file, through browser_wasi_shim's
// Fd/Inode interface (the calls WASI makes). Node stands in for the browser's synchronous OPFS handle.
import { test } from "node:test";
import assert from "node:assert/strict";
import { closeSync, fstatSync, ftruncateSync, mkdtempSync, openSync, readSync, writeSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as wasi from "../vendor/browser_wasi_shim/wasi_defs.js";
import { ExtentStore, HandleFile, HandlePool, StoredDirectory, StoredFile, StoredPreopen, readFile, removePath, writeFile } from "../lib/store.js";

/** Same methods as the browser's FileSystemSyncAccessHandle. */
class NodeHandle {
  constructor(path) { this.fd = openSync(path, "w+"); }
  read(buf, { at }) { return readSync(this.fd, buf, 0, buf.byteLength, at); }
  write(buf, { at }) { return writeSync(this.fd, buf, 0, buf.byteLength, at); }
  getSize() { return fstatSync(this.fd).size; }
  truncate(n) { ftruncateSync(this.fd, n); }
  flush() {}
  close() { closeSync(this.fd); }
}

const ALL = 0xffffffffn;
function setup(extent = 16) {
  const store = new ExtentStore(new NodeHandle(join(mkdtempSync(join(tmpdir(), "store-")), "store.bin")), { extent });
  const root = new StoredDirectory(store);
  return { store, root, dir: new StoredPreopen("/work", root) };
}
function open(dir, path, oflags = wasi.OFLAGS_CREAT, fdflags = 0) {
  const { ret, fd_obj } = dir.path_open(0, path, oflags, ALL, ALL, fdflags);
  assert.equal(ret, wasi.ERRNO_SUCCESS, `open ${path}`);
  return fd_obj;
}
const bytes = (n, seed = 1) => Uint8Array.from({ length: n }, (_, i) => (i * 7 + seed) & 0xff);

test("data written across extents reads back, sequentially and at an offset", () => {
  const { dir } = setup();
  const f = open(dir, "a.bin");
  const data = bytes(100);
  assert.equal(f.fd_write(data).nwritten, 100);
  assert.equal(f.fd_seek(0n, wasi.WHENCE_SET).offset, 0n);
  assert.deepEqual(f.fd_read(100).data, data);
  assert.deepEqual(f.fd_pread(40, 37n).data, data.slice(37, 77));
  assert.equal(f.fd_filestat_get().filestat.size, 100n);
});

test("reads stop at the end of the file", () => {
  const { dir } = setup();
  const f = open(dir, "a.bin");
  f.fd_write(bytes(20));
  assert.equal(f.fd_pread(50, 10n).data.length, 10);
  assert.equal(f.fd_pread(5, 30n).data.length, 0);
});

test("a write past the end leaves a hole that reads as zeros", () => {
  const { dir } = setup();
  const f = open(dir, "a.bin");
  assert.equal(f.fd_pwrite(bytes(4), 50n).nwritten, 4);
  assert.equal(f.fd_filestat_get().filestat.size, 54n);
  assert.deepEqual(f.fd_pread(50, 0n).data, new Uint8Array(50));
  assert.deepEqual(f.fd_pread(4, 50n).data, bytes(4));
});

test("shrinking then growing a file gives zeros, not the old bytes", () => {
  const { dir } = setup();
  const f = open(dir, "a.bin");
  f.fd_write(bytes(40));
  assert.equal(f.fd_filestat_set_size(10n), wasi.ERRNO_SUCCESS);
  assert.equal(f.fd_filestat_set_size(40n), wasi.ERRNO_SUCCESS);
  assert.deepEqual(f.fd_pread(10, 0n).data, bytes(10));
  assert.deepEqual(f.fd_pread(30, 10n).data, new Uint8Array(30));
});

test("deleting a file frees its space for the next file", () => {
  const { store, dir } = setup();
  const f = open(dir, "a.bin");
  f.fd_write(bytes(64));
  f.fd_close();
  const used = store.extentsInUse;
  assert.equal(used, 4);
  assert.equal(dir.path_unlink_file("a.bin"), wasi.ERRNO_SUCCESS);
  assert.equal(store.extentsInUse, 0);
  const g = open(dir, "b.bin");
  g.fd_write(bytes(64, 9));
  assert.equal(store.extentsInStore, 4, "reused, the storage file did not grow");
  assert.deepEqual(g.fd_pread(64, 0n).data, bytes(64, 9));
});

test("a new file never shows a deleted file's old bytes", () => {
  const { dir } = setup();
  const f = open(dir, "old.bin");
  f.fd_write(new Uint8Array(16).fill(0xff));
  f.fd_close();
  dir.path_unlink_file("old.bin");
  const g = open(dir, "new.bin");
  g.fd_pwrite(bytes(4), 8n); // reuses old.bin's extent, writes only part of it
  assert.deepEqual(g.fd_pread(8, 0n).data, new Uint8Array(8));
});

test("a file deleted while open stays readable until it is closed", () => {
  const { store, dir } = setup();
  const f = open(dir, "a.bin");
  f.fd_write(bytes(32));
  dir.path_unlink_file("a.bin");
  assert.deepEqual(f.fd_pread(32, 0n).data, bytes(32));
  assert.equal(store.extentsInUse, 2);
  f.fd_close();
  assert.equal(store.extentsInUse, 0);
});

test("renaming over an existing file frees the replaced one", () => {
  const { store, dir } = setup();
  for (const [name, seed] of [["old.bin", 1], ["new.bin", 2]]) {
    const f = open(dir, name);
    f.fd_write(bytes(32, seed));
    f.fd_close();
  }
  // browser_wasi_shim's path_rename = path_unlink(source) + path_link(target, inode, true).
  const { inode_obj } = dir.path_unlink("new.bin");
  assert.equal(dir.path_link("old.bin", inode_obj, true), wasi.ERRNO_SUCCESS);
  assert.equal(store.extentsInUse, 2);
  assert.deepEqual(open(dir, "old.bin", 0).fd_pread(32, 0n).data, bytes(32, 2));
});

test("opening with truncate empties the file and frees its space", () => {
  const { store, dir } = setup();
  const f = open(dir, "a.bin");
  f.fd_write(bytes(48));
  f.fd_close();
  const g = open(dir, "a.bin", wasi.OFLAGS_TRUNC);
  assert.equal(g.fd_filestat_get().filestat.size, 0n);
  assert.equal(store.extentsInUse, 0);
});

test("append mode writes at the end every time", () => {
  const { dir } = setup();
  const f = open(dir, "a.bin");
  f.fd_write(bytes(10));
  const g = open(dir, "a.bin", 0, wasi.FDFLAGS_APPEND);
  g.fd_seek(0n, wasi.WHENCE_SET);
  g.fd_write(bytes(5, 3));
  assert.deepEqual(f.fd_pread(5, 10n).data, bytes(5, 3));
  assert.equal(f.fd_filestat_get().filestat.size, 15n);
});

test("file times are kept and written where WASI expects them, leaving the size intact", () => {
  const { dir } = setup();
  const f = open(dir, "a.bin");
  f.fd_write(bytes(20));
  assert.equal(f.fd_filestat_set_times(111n, 222n, wasi.FSTFLAGS_ATIM | wasi.FSTFLAGS_MTIM), wasi.ERRNO_SUCCESS);
  const view = new DataView(new ArrayBuffer(64));
  f.fd_filestat_get().filestat.write_bytes(view, 0);
  assert.equal(view.getBigUint64(32, true), 20n);
  assert.equal(view.getBigUint64(40, true), 111n);
  assert.equal(view.getBigUint64(48, true), 222n);
});

test("directories and the files in them are stored, and a later run sees them", () => {
  const { store, root, dir } = setup();
  assert.equal(dir.path_create_directory("x/"), wasi.ERRNO_SUCCESS);
  assert.equal(dir.path_create_directory("x/y"), wasi.ERRNO_SUCCESS);
  const f = open(dir, "x/y/z.bin");
  f.fd_write(bytes(20));
  f.fd_close();
  assert.equal(store.extentsInUse, 2);
  const again = new StoredPreopen("/work", root);
  const { inode_obj } = again.path_lookup("x/y/z.bin", 0);
  assert.ok(inode_obj instanceof StoredFile);
  assert.deepEqual(open(again, "x/y/z.bin", 0).fd_pread(20, 0n).data, bytes(20));
  assert.equal(again.path_remove_directory("x/y"), wasi.ERRNO_NOTEMPTY);
});

test("exclusive without create opens an existing file (mke2fs opens its output so); with create it refuses", () => {
  const { dir } = setup();
  open(dir, "img.raw").fd_close();
  open(dir, "img.raw", wasi.OFLAGS_EXCL).fd_close();
  assert.equal(dir.path_open(0, "img.raw", wasi.OFLAGS_CREAT | wasi.OFLAGS_EXCL, ALL, ALL, 0).ret, wasi.ERRNO_EXIST);
});

test("the page can write and read files directly, creating parent directories", () => {
  const { dir } = setup();
  writeFile(dir, "t/system/etc/x.xml", bytes(40));
  assert.deepEqual(readFile(dir, "t/system/etc/x.xml"), bytes(40));
  writeFile(dir, "t/system/etc/x.xml", bytes(3, 5));
  assert.deepEqual(readFile(dir, "t/system/etc/x.xml"), bytes(3, 5), "replaced, not appended");
  assert.equal(readFile(dir, "t/missing"), null);
});

test("removing a tree frees all its files", () => {
  const { store, dir } = setup();
  writeFile(dir, "tree/a/b/1.bin", bytes(40));
  writeFile(dir, "tree/a/2.bin", bytes(20));
  writeFile(dir, "keep.bin", bytes(16));
  removePath(dir, "tree");
  assert.equal(dir.path_lookup("tree", 0).inode_obj, null);
  assert.equal(store.extentsInUse, 1);
  removePath(dir, "keep.bin");
  removePath(dir, "not-there");
  assert.equal(store.extentsInUse, 0);
});

test("shrinking gives back the space after the last extent still in use", () => {
  const { store, dir } = setup();
  writeFile(dir, "low.bin", bytes(32)); // extents 0-1
  writeFile(dir, "high.bin", bytes(48)); // extents 2-4
  assert.equal(dir.path_lookup("high.bin", 0).inode_obj.lastExtent(), 4);
  removePath(dir, "high.bin");
  store.shrink();
  assert.equal(store.extentsInStore, 2);
  assert.equal(store.handle.getSize(), 2 * 16);
  writeFile(dir, "again.bin", bytes(16, 3)); // must not reuse a freed extent that is now past the end
  assert.equal(store.extentsInStore, 3);
  assert.deepEqual(readFile(dir, "low.bin"), bytes(32));
  assert.deepEqual(readFile(dir, "again.bin"), bytes(16, 3));
});

test("shrinking keeps extents that are still in use", () => {
  const { store, dir } = setup();
  writeFile(dir, "a.bin", bytes(16));
  writeFile(dir, "b.bin", bytes(16));
  removePath(dir, "a.bin");
  store.shrink();
  assert.equal(store.extentsInStore, 2, "b.bin is at extent 1");
  assert.deepEqual(readFile(dir, "b.bin"), bytes(16));
});

test("a file in a stored directory is created as a stored file", () => {
  const { dir } = setup();
  dir.path_create_directory("d");
  open(dir, "d/f.bin").fd_close();
  assert.ok(dir.path_lookup("d/f.bin", 0).inode_obj instanceof StoredFile);
  assert.ok(dir.path_lookup("d", 0).inode_obj instanceof StoredDirectory);
});

// ---- Pooled files: files created in a directory with a pool get their own storage file (the signed OTA, the images
// to load), so the page can hand them to the flasher without copying them out of the work file.

function pooled(n = 2) {
  const { store, root, dir } = setup();
  const tmp = mkdtempSync(join(tmpdir(), "pool-"));
  const pool = new HandlePool(Array.from({ length: n }, (_, i) => ({ name: `f${i}`, handle: new NodeHandle(join(tmp, `f${i}`)) })));
  dir.path_create_directory("flash");
  dir.path_lookup("flash", 0).inode_obj.pool = pool;
  return { store, root, dir, pool };
}

test("a file created in a pooled directory gets its own storage file", () => {
  const { store, dir, pool } = pooled();
  writeFile(dir, "flash/boot.img", bytes(100));
  const f = dir.path_lookup("flash/boot.img", 0).inode_obj;
  assert.ok(f instanceof HandleFile);
  assert.equal(f.poolName, "f0");
  assert.equal(f.entry.handle.getSize(), 100);
  assert.deepEqual(readFile(dir, "flash/boot.img"), bytes(100));
  assert.equal(store.extentsInUse, 0, "nothing in the work file");
  assert.equal(pool.free.length, 1);
});

test("pooled files support positional reads and writes, size changes and renames", () => {
  const { dir } = pooled();
  const f = open(dir, "flash/tmp123");
  f.fd_pwrite(bytes(4), 50n);
  assert.equal(f.fd_filestat_get().filestat.size, 54n);
  assert.deepEqual(f.fd_pread(50, 0n).data, new Uint8Array(50));
  assert.equal(f.fd_filestat_set_size(10n), wasi.ERRNO_SUCCESS);
  f.fd_close();
  const { inode_obj } = dir.path_unlink("flash/tmp123");
  assert.equal(dir.path_link("flash/out.zip", inode_obj, true), wasi.ERRNO_SUCCESS);
  assert.ok(dir.path_lookup("flash/out.zip", 0).inode_obj instanceof HandleFile);
  assert.equal(dir.path_lookup("flash/out.zip", 0).inode_obj.size, 10);
});

test("deleting a pooled file empties it and gives it back to the pool", () => {
  const { dir, pool } = pooled(1);
  writeFile(dir, "flash/a.img", bytes(64));
  const entry = dir.path_lookup("flash/a.img", 0).inode_obj.entry;
  removePath(dir, "flash/a.img");
  assert.equal(entry.handle.getSize(), 0);
  assert.equal(pool.free.length, 1);
  writeFile(dir, "flash/b.img", bytes(8));
  assert.ok(dir.path_lookup("flash/b.img", 0).inode_obj instanceof HandleFile);
});

test("when the pool is used up, new files go to the work file", () => {
  const { dir } = pooled(1);
  writeFile(dir, "flash/a.img", bytes(8));
  writeFile(dir, "flash/b.img", bytes(8));
  assert.ok(!(dir.path_lookup("flash/b.img", 0).inode_obj instanceof HandleFile));
});

test("files outside the pooled directory stay in the work file", () => {
  const { dir } = pooled();
  writeFile(dir, "other.bin", bytes(8));
  assert.ok(!(dir.path_lookup("other.bin", 0).inode_obj instanceof HandleFile));
});
