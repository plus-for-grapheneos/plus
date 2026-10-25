// lib/progress.js counts the bytes the tools read and write, so the page can show that a long step is moving. The
// tools block the worker while they run, so reports come from inside the reads and writes, at most every interval.
import { test } from "node:test";
import assert from "node:assert/strict";
import { closeSync, fstatSync, ftruncateSync, mkdtempSync, openSync, readSync, writeSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { io, onProgress } from "../lib/progress.js";
import { ExtentStore, StoredDirectory, StoredPreopen, readFile, writeFile } from "../lib/store.js";
import { SourceFile, bytesSource } from "../lib/source.js";

class NodeHandle {
  constructor(path) { this.fd = openSync(path, "w+"); }
  read(buf, { at }) { return readSync(this.fd, buf, 0, buf.byteLength, at); }
  write(buf, { at }) { return writeSync(this.fd, buf, 0, buf.byteLength, at); }
  getSize() { return fstatSync(this.fd).size; }
  truncate(n) { ftruncateSync(this.fd, n); }
  flush() {}
  close() { closeSync(this.fd); }
}

test("reads of the picked file and reads and writes of stored files are counted", () => {
  const dir = new StoredPreopen("/work", new StoredDirectory(new ExtentStore(new NodeHandle(join(mkdtempSync(join(tmpdir(), "p-")), "s.bin")), { extent: 16 })));
  const before = { ...io };
  writeFile(dir, "a.bin", new Uint8Array(40));
  readFile(dir, "a.bin");
  new SourceFile(bytesSource(new Uint8Array(100))).read(10, 50);
  assert.equal(io.written - before.written, 40);
  assert.equal(io.read - before.read, 40 + 50);
});

test("reports are sent at most once per interval, with the totals", () => {
  const reports = [];
  let now = 0;
  onProgress((r) => reports.push(r), { interval: 500, clock: () => now });
  const src = new SourceFile(bytesSource(new Uint8Array(1000)));
  src.read(0, 10);
  now = 100;
  src.read(0, 10);
  now = 600;
  src.read(0, 10);
  assert.equal(reports.length, 2, "the first read, then one after 500 ms");
  assert.ok(reports[1].read >= reports[0].read + 20);
  onProgress(null);
});
